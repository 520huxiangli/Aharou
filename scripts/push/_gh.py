"""GitHub REST 推送脚本的公共逻辑：取 token、发请求、读本地 git。

只用标准库。本机 api.github.com 常被劫持成 301 跳 github.com，或直连 443 不通，
所以请求失败时按以下顺序兜底（依据全局记忆 github_access 第一节的实测）：
  1. 直连 api.github.com；
  2. 把 api.github.com 强解析到实测可用的 API IP（SNI 与证书校验仍按 api.github.com）；
  3. 仅 GET：走 gh-proxy.com 代理前缀，且不带 Authorization（代理会剥掉认证，
     写操作走代理必然 403；token 也不该交给第三方）。
"""

import base64
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API_HOST = "api.github.com"
API_BASE = "https://" + API_HOST
PINNED_IPS = ("20.205.243.168", "140.82.121.6", "140.82.112.6")
READ_PROXY_PREFIX = "https://gh-proxy.com/"
USER_AGENT = "aharou-push-script"
TIMEOUT = 30
RETRY_DELAYS = (1, 3, 6)

_orig_getaddrinfo = socket.getaddrinfo
_pinned_ip = None


def _getaddrinfo(host, *args, **kwargs):
    if _pinned_ip and host == API_HOST:
        return _orig_getaddrinfo(_pinned_ip, *args, **kwargs)
    return _orig_getaddrinfo(host, *args, **kwargs)


socket.getaddrinfo = _getaddrinfo


class GhError(Exception):
    def __init__(self, message, status=None, body=None):
        super().__init__(message)
        self.status = status
        self.body = body


class _Hijacked(Exception):
    pass


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_opener = urllib.request.build_opener(_NoRedirect)


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def die(msg, code=1):
    log("错误：" + msg)
    sys.exit(code)


# ---------- token ----------

_TOKEN_RE = re.compile(r"(github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9]+)")


def _token_from_credential_helper(repo_dir):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_ASKPASS="true", SSH_ASKPASS="true")
    try:
        out = subprocess.run(
            ["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True, text=True, timeout=15, cwd=repo_dir, env=env,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if out.returncode != 0:
        return None
    for line in out.stdout.splitlines():
        if line.startswith("password="):
            value = line[len("password="):].strip()
            return value or None
    return None


def _token_from_secrets():
    path = os.path.expanduser("~/.aharou/memory/SECRETS.md")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            m = _TOKEN_RE.search(f.read())
    except OSError:
        return None
    return m.group(1) if m else None


def get_token(repo_dir=".", required=True):
    """返回 (token, 来源)。来源只用于提示，token 本身任何时候都不打印。"""
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        return token, "环境变量 GITHUB_TOKEN"
    token = _token_from_credential_helper(repo_dir)
    if token:
        return token, "git credential"
    token = _token_from_secrets()
    if token:
        return token, "SECRETS.md"
    if required:
        die("找不到 GitHub token：依次查过 GITHUB_TOKEN、git credential fill（host=github.com）、"
            "~/.aharou/memory/SECRETS.md，均无。请设置 GITHUB_TOKEN 后重试。")
    return None, "无（匿名读取，受 60 次/小时限流）"


# ---------- HTTP ----------

def _once(method, url, body, headers):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with _opener.open(req, timeout=TIMEOUT) as resp:
            raw = resp.read()
            return resp.status, raw
    except urllib.error.HTTPError as e:
        if 300 <= e.code < 400:
            raise _Hijacked("HTTP %d -> %s" % (e.code, e.headers.get("Location", "?")))
        return e.code, e.read()


def _with_retry(method, url, body, headers):
    """5xx 与超时/断连退避重试 3 次；3xx 视为劫持直接上抛，交给外层换通道。"""
    last_exc = None
    for attempt in range(len(RETRY_DELAYS) + 1):
        if attempt:
            time.sleep(RETRY_DELAYS[attempt - 1])
        try:
            status, raw = _once(method, url, body, headers)
        except _Hijacked:
            raise
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as e:
            last_exc = e
            log("  %s %s 连接失败（%s），第 %d 次" % (method, _short(url), _reason(e), attempt + 1))
            continue
        if status >= 500:
            last_exc = GhError("HTTP %d" % status, status, raw)
            log("  %s %s 返回 %d，第 %d 次" % (method, _short(url), status, attempt + 1))
            continue
        return status, raw
    raise last_exc


def _reason(e):
    return str(getattr(e, "reason", e))[:120]


def _short(url):
    return url.split("api.github.com", 1)[-1] or url


def _decode(status, raw, method, path):
    text = raw.decode("utf-8", errors="replace") if raw else ""
    payload = None
    if text:
        try:
            payload = json.loads(text)
        except ValueError:
            payload = None
    if status >= 400:
        msg = payload.get("message") if isinstance(payload, dict) else text[:200]
        raise GhError("%s %s -> HTTP %d：%s" % (method, path, status, msg), status, payload)
    return payload


class GitHub:
    def __init__(self, owner, repo, token):
        self.owner = owner
        self.repo = repo
        self.token = token
        self._mode = "direct"

    def repo_path(self, suffix):
        return "/repos/%s/%s%s" % (self.owner, self.repo, suffix)

    def _headers(self, with_auth):
        h = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": USER_AGENT,
        }
        if with_auth and self.token:
            h["Authorization"] = "Bearer " + self.token
        if with_auth:
            h["Content-Type"] = "application/json"
        return h

    def request(self, method, path, body=None):
        global _pinned_ip
        url = API_BASE + path
        errors = []
        if self._mode == "direct":
            try:
                return _decode(*_with_retry(method, url, body, self._headers(True)), method, path)
            except GhError as e:
                if e.status is not None and e.status < 500:
                    raise
                errors.append("直连：%s" % e)
            except _Hijacked as e:
                errors.append("直连被劫持（%s）" % e)
            except Exception as e:
                errors.append("直连：%s" % _reason(e))
            log("  直连 %s 不可用，改用固定 API IP" % API_HOST)
            self._mode = "pinned"
        if self._mode == "pinned":
            ips = ([_pinned_ip] if _pinned_ip else []) + [ip for ip in PINNED_IPS if ip != _pinned_ip]
            for ip in ips:
                _pinned_ip = ip
                try:
                    return _decode(*_with_retry(method, url, body, self._headers(True)), method, path)
                except GhError as e:
                    if e.status is not None and e.status < 500:
                        raise
                    errors.append("%s：%s" % (ip, e))
                except _Hijacked as e:
                    errors.append("%s 被劫持（%s）" % (ip, e))
                except Exception as e:
                    errors.append("%s：%s" % (ip, _reason(e)))
            _pinned_ip = None
        if method == "GET":
            proxied = READ_PROXY_PREFIX + url
            log("  固定 IP 均不可用，GET 改走 %s（不带认证）" % READ_PROXY_PREFIX)
            try:
                return _decode(*_with_retry(method, proxied, None, self._headers(False)), method, path)
            except Exception as e:
                errors.append("代理：%s" % e)
        raise GhError("%s %s 所有通道都失败：\n    " % (method, path) + "\n    ".join(errors))

    def get(self, path):
        return self.request("GET", path)

    def get_ref(self, ref):
        """ref 形如 heads/master、tags/v1.0；不存在返回 None。路径必须用复数 /git/refs/。"""
        try:
            data = self.get(self.repo_path("/git/refs/" + quote_ref(ref)))
        except GhError as e:
            if e.status == 404:
                return None
            raise
        # 前缀匹配时 GitHub 会返回列表，只认完全相同的那条。
        if isinstance(data, list):
            data = next((d for d in data if d.get("ref") == "refs/" + ref), None)
            if data is None:
                return None
        if data.get("ref") != "refs/" + ref:
            return None
        return data["object"]["sha"]

    def get_commit(self, sha):
        return self.get(self.repo_path("/git/commits/" + sha))


def quote_ref(ref):
    return urllib.parse.quote(ref, safe="/")


# ---------- 本地 git ----------

def git(repo_dir, *args, binary=False, check=True):
    out = subprocess.run(["git", "-C", repo_dir] + list(args), capture_output=True)
    if check and out.returncode != 0:
        die("git %s 失败：%s" % (" ".join(args), out.stderr.decode("utf-8", "replace").strip()))
    return out.stdout if binary else out.stdout.decode("utf-8", "replace").strip()


def git_ok(repo_dir, *args):
    return subprocess.run(["git", "-C", repo_dir] + list(args), capture_output=True).returncode == 0


def repo_root(repo_dir):
    return git(repo_dir, "rev-parse", "--show-toplevel")


_REMOTE_RE = re.compile(r"github\.com[:/]+([^/]+)/([^/]+?)(?:\.git)?/?$")


def resolve_repo(repo_dir, override=None):
    if override:
        if override.count("/") != 1:
            die("--repo 应为 owner/name 形式")
        owner, name = override.split("/")
        return owner, name
    url = git(repo_dir, "remote", "get-url", "origin", check=False)
    m = _REMOTE_RE.search(url or "")
    if not m:
        die("无法从 origin 解析 GitHub 仓库，请用 --repo owner/name 指定")
    return m.group(1), m.group(2)


def blob_payload(content):
    """文本按 utf-8 原样上传，二进制或含 NUL 的走 base64。"""
    if b"\0" not in content:
        try:
            return {"content": content.decode("utf-8"), "encoding": "utf-8"}
        except UnicodeDecodeError:
            pass
    return {"content": base64.b64encode(content).decode("ascii"), "encoding": "base64"}


def git_blob_sha(content):
    import hashlib
    return hashlib.sha1(b"blob %d\0" % len(content) + content).hexdigest()


DRY_SHA = "0" * 40


def write(gh, method, path, body, dry_run):
    if dry_run:
        print("[dry-run] " + describe_write(method, path, body))
        return None
    return gh.request(method, path, body)


def upload_blobs(gh, files, dry_run):
    """files: [(path, mode, content_bytes)]，返回 GitHub tree 条目列表。"""
    entries = []
    total = len(files)
    for i, (path, mode, content) in enumerate(files, 1):
        payload = blob_payload(content)
        log("  [%d/%d] blob %s（%d 字节，%s，mode %s）" % (i, total, path, len(content), payload["encoding"], mode))
        res = write(gh, "POST", gh.repo_path("/git/blobs"), payload, dry_run)
        sha = git_blob_sha(content) if res is None else res["sha"]
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": sha})
    return entries


def create_commit(gh, parent_sha, base_tree, entries, message, author, dry_run):
    """以远端 tree 为 base_tree 叠加 entries，建出 commit，返回新 sha（dry-run 返回全 0）。"""
    tree = write(gh, "POST", gh.repo_path("/git/trees"), {"base_tree": base_tree, "tree": entries}, dry_run)
    body = {"message": message, "tree": tree["sha"] if tree else DRY_SHA, "parents": [parent_sha]}
    if author:
        body["author"] = author
    commit = write(gh, "POST", gh.repo_path("/git/commits"), body, dry_run)
    return commit["sha"] if commit else DRY_SHA


def update_branch(gh, branch, sha, dry_run):
    # 不 force：新 commit 的父就是刚读到的远端 sha，期间远端若被别人推进，GitHub 会以 422 拒绝。
    write(gh, "PATCH", gh.repo_path("/git/refs/heads/" + quote_ref(branch)), {"sha": sha, "force": False}, dry_run)


def describe_write(method, path, body):
    """dry-run 打印写请求时不带内容正文，只给出字段摘要。"""
    summary = {}
    for k, v in (body or {}).items():
        if k == "content":
            summary[k] = "<%d 字符>" % len(v)
        elif k == "tree" and isinstance(v, list):
            summary[k] = "<%d 条目>" % len(v)
        elif k == "message" and isinstance(v, str):
            summary[k] = v.splitlines()[0][:72] if v else ""
        else:
            summary[k] = v
    return "%s %s %s" % (method, path, json.dumps(summary, ensure_ascii=False))
