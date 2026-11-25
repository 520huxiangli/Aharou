#!/usr/bin/env python3
"""评测任务集格式校验脚本。

只校验 eval/tasks.json 本身的形态，不执行任务、不联网、不修改任何文件：

1. JSON 可解析，顶层是数组；
2. 每项字段齐全且类型为字符串，不允许多余字段；
3. id 唯一、形如「分类-三位数」，且前缀与该条 category 一致；
4. category / risk 取值在允许集合内；
5. prompt / expect 非空且长度合理（prompt >= 8 字符、expect >= 10 字符）；
6. 提示完全重复的 prompt（忽略首尾与连续空白差异）。

只报告不阻断：本脚本不会被构建或 CI 调用，发现问题时打印清单并以退出码 1 结束。
用法：python3 scripts/check_eval_tasks.py   （退出码 0 = 通过，1 = 有问题）
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS_REL = "eval/tasks.json"
TASKS_FILE = os.path.join(ROOT, TASKS_REL)

CATEGORIES = ["files", "browser", "device", "mcp", "automation", "memory", "ui"]
RISKS = ["low", "medium", "high"]
FIELDS = ["id", "category", "title", "prompt", "expect", "risk"]
ID_RE = re.compile(r"^(%s)-\d{3}$" % "|".join(CATEGORIES))
MIN_PROMPT_LEN = 8
MIN_EXPECT_LEN = 10
MIN_TITLE_LEN = 4
SUGGESTED_COUNT = (20, 25)

errors = []
notes = []


def label(index, item):
    """问题定位用：第几项 + 能拿到的 id。"""
    item_id = item.get("id") if isinstance(item, dict) else None
    if isinstance(item_id, str) and item_id.strip():
        return "第 %d 项（%s）" % (index, item_id.strip())
    return "第 %d 项" % index


def norm(text):
    """去首尾空白并把连续空白压成一个空格，用于判断「实质重复」。"""
    return re.sub(r"\s+", " ", text.strip())


# ---------- 1. 读取并解析 ----------
if not os.path.isfile(TASKS_FILE):
    print("[FAIL] 找不到评测任务文件：%s" % TASKS_REL)
    sys.exit(1)

try:
    with open(TASKS_FILE, encoding="utf-8") as f:
        data = json.load(f)
except json.JSONDecodeError as e:
    print("[FAIL] %s 不是合法 JSON：第 %d 行第 %d 列 %s" % (TASKS_REL, e.lineno, e.colno, e.msg))
    sys.exit(1)
except OSError as e:
    print("[FAIL] 读取 %s 失败：%s" % (TASKS_REL, e))
    sys.exit(1)

if not isinstance(data, list):
    print("[FAIL] %s 顶层必须是数组，实际是 %s" % (TASKS_REL, type(data).__name__))
    sys.exit(1)

if not data:
    print("[FAIL] %s 是空数组，至少要有 %d 条任务" % (TASKS_REL, SUGGESTED_COUNT[0]))
    sys.exit(1)

# ---------- 2. 逐项校验 ----------
seen_ids = {}  # id -> 首次出现的序号
seen_prompts = {}  # 归一化 prompt -> (序号, id)
category_count = {}
risk_count = {}

for index, item in enumerate(data, start=1):
    where = label(index, item)

    if not isinstance(item, dict):
        errors.append("%s：条目必须是 JSON 对象，实际是 %s" % (where, type(item).__name__))
        continue

    # 字段齐全、类型正确
    for field in FIELDS:
        if field not in item:
            errors.append("%s：缺少字段 %s" % (where, field))
        elif not isinstance(item[field], str):
            errors.append("%s：字段 %s 必须是字符串，实际是 %s" % (where, field, type(item[field]).__name__))

    extra = [k for k in item.keys() if k not in FIELDS]
    if extra:
        errors.append("%s：出现未定义字段 %s（字段固定为 %s）" % (where, "、".join(sorted(extra)), " / ".join(FIELDS)))

    # 取字符串值做后续检查；缺字段或类型错时跳过，避免重复报错
    def text_of(field):
        value = item.get(field)
        return value if isinstance(value, str) else None

    item_id = text_of("id")
    category = text_of("category")
    title = text_of("title")
    prompt = text_of("prompt")
    expect = text_of("expect")
    risk = text_of("risk")

    # id 形态与唯一性
    if item_id is not None:
        stripped = item_id.strip()
        if not stripped:
            errors.append("%s：id 不能为空" % where)
        elif not ID_RE.match(stripped):
            errors.append("%s：id 必须形如「分类-三位数」（例 files-001），实际是 %r" % (where, item_id))
        elif category is not None and stripped.rsplit("-", 1)[0] != category.strip():
            errors.append(
                "%s：id 前缀与 category 不一致（id=%r，category=%r）" % (where, stripped, category)
            )
        if stripped in seen_ids:
            errors.append(
                "%s：id 重复，第 %d 项已用过 %r" % (where, seen_ids[stripped], stripped)
            )
        else:
            seen_ids[stripped] = index

    # category 枚举
    if category is not None:
        c = category.strip()
        if c not in CATEGORIES:
            errors.append("%s：category 取值非法 %r，可用 %s" % (where, category, " / ".join(CATEGORIES)))
        else:
            category_count[c] = category_count.get(c, 0) + 1

    # risk 枚举
    if risk is not None:
        r = risk.strip()
        if r not in RISKS:
            errors.append("%s：risk 取值非法 %r，可用 %s" % (where, risk, " / ".join(RISKS)))
        else:
            risk_count[r] = risk_count.get(r, 0) + 1

    # title 非空且能读
    if title is not None:
        if len(title.strip()) < MIN_TITLE_LEN:
            errors.append("%s：title 太短（至少 %d 个字符）" % (where, MIN_TITLE_LEN))

    # prompt 非空且长度合理
    if prompt is not None:
        p = prompt.strip()
        if not p:
            errors.append("%s：prompt 不能为空，要写成可直接复制执行的指令" % where)
        elif len(p) < MIN_PROMPT_LEN:
            errors.append("%s：prompt 太短（%d 字符，至少 %d 字符）" % (where, len(p), MIN_PROMPT_LEN))
        else:
            key = norm(p)
            if key in seen_prompts:
                first_index, first_id = seen_prompts[key]
                errors.append(
                    "%s：prompt 与第 %d 项（%s）完全重复，两条会测同一个场景" % (where, first_index, first_id)
                )
            else:
                seen_prompts[key] = (index, item_id or "无 id")

    # expect 非空且能判过没过
    if expect is not None:
        e = expect.strip()
        if not e:
            errors.append("%s：expect 不能为空，要写清「看到什么算过」" % where)
        elif len(e) < MIN_EXPECT_LEN:
            errors.append("%s：expect 太短（%d 字符，至少 %d 字符），写不出可观察的通过标准"
                          % (where, len(e), MIN_EXPECT_LEN))

# ---------- 3. 规模检查（只提示，不判失败） ----------
low, high = SUGGESTED_COUNT
if len(data) < low or len(data) > high:
    notes.append("当前共 %d 条，建议区间是 %d~%d 条（这条只是提示，不算失败）" % (len(data), low, high))
else:
    notes.append("共 %d 条任务（建议区间 %d~%d）" % (len(data), low, high))

# ---------- 输出 ----------
if errors:
    print("[FAIL] 评测任务集校验未通过，共 %d 个问题：" % len(errors))
    for e in errors:
        print("  - %s" % e)
    sys.exit(1)

print("[OK] 评测任务集校验通过")
for n in notes:
    print("  - %s" % n)
print("  - 分类分布：%s" % "、".join("%s %d" % (c, category_count[c]) for c in CATEGORIES if c in category_count))
print("  - 风险分布：%s" % "、".join("%s %d" % (r, risk_count[r]) for r in RISKS if r in risk_count))
