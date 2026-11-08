#!/bin/sh
set -eu

export PATH=/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export LC_ALL=C
export DEBIAN_FRONTEND=noninteractive

distro=$1
abi=$2
profile=$3
revision=$4
case "$distro:$profile" in
    alpine:general|ubuntu:general|debian:general|debian:napcat) ;;
    *) printf '%s\n' 'Unsupported distro/profile' >&2; exit 1 ;;
esac
case "$abi" in arm64|x86_64) ;; *) exit 1 ;; esac
[ "$revision" = 1 ]
[ -r /etc/os-release ]
. /etc/os-release
[ "$ID" = "$distro" ]
case "$abi:$(uname -m)" in
    arm64:aarch64|x86_64:x86_64) ;;
    *) printf '%s\n' 'Unexpected build architecture; configure CI QEMU first' >&2; exit 1 ;;
esac

base_packages='bash curl wget jq rsync git ripgrep file less net-tools netcat-openbsd ca-certificates'
napcat_packages='ca-certificates xvfb xauth dpkg unzip libnss3 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 libgtk-3-0t64 libgssapi-krb5-2 libnotify4 libsecret-1-0 libxss1 libxtst6 libatomic1 fonts-noto-cjk'

policy_saved=0
policy_created=0
restore_policy() {
    if [ "$policy_created" = 1 ]; then
        rm -f /usr/sbin/policy-rc.d
        if [ "$policy_saved" = 1 ]; then
            mv /tmp/aharou-policy-rc.d /usr/sbin/policy-rc.d
        fi
    fi
}
trap restore_policy EXIT
if [ "$distro" = alpine ]; then
    packages="$base_packages bind-tools nodejs npm python3 py3-pip py3-virtualenv"
    apk add --no-cache $packages
    for package in $packages; do apk info -e "$package" >/dev/null; done
else
    if [ -e /usr/sbin/policy-rc.d ] || [ -L /usr/sbin/policy-rc.d ]; then
        mv /usr/sbin/policy-rc.d /tmp/aharou-policy-rc.d
        policy_saved=1
    fi
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
    chmod 755 /usr/sbin/policy-rc.d
    policy_created=1
    if [ "$profile" = napcat ]; then
        [ "${VERSION_CODENAME:-}" = trixie ]
        packages=$napcat_packages
    else
        packages="$base_packages dnsutils nodejs npm python3 python3-pip python3-venv"
    fi
    apt-get -o Acquire::ForceIPv4=true --error-on=any update
    apt-get -o Acquire::ForceIPv4=true install -y --no-install-recommends $packages
    for package in $packages; do
        [ "$(dpkg-query -W -f='${Status}' "$package")" = 'install ok installed' ]
    done
    dpkg --audit > /tmp/aharou-dpkg-audit
    [ ! -s /tmp/aharou-dpkg-audit ]
    apt-get check
fi

if [ "$profile" = general ]; then
    bash --version
    curl --version
    wget --version
    jq --version
    rsync --version
    git --version
    rg --version
    file --version
    less --version
    netstat --version
    # OpenBSD nc 的帮助在部分发行版返回 1，以帮助正文核验而不是忽略执行失败。
    nc_status=0
    nc -h > /tmp/aharou-nc-check 2>&1 || nc_status=$?
    [ "$nc_status" -le 1 ]
    grep -qi 'OpenBSD' /tmp/aharou-nc-check
    dig -v
    command -v nslookup
    node --version
    npm --version
    python3 --version
    python3 -m pip --version
    python3 -m venv /tmp/aharou-venv
    /tmp/aharou-venv/bin/python -c 'import ssl, sys; assert sys.prefix != sys.base_prefix'
    /tmp/aharou-venv/bin/python -m pip --version
else
    bash --version
    dpkg --version
    unzip -v
    xauth -V
    Xvfb -help > /tmp/aharou-xvfb-check 2>&1
    # 检查每个运行库的动态链接依赖，避免“包已登记但库缺失”被误判成可用。
    for package in $napcat_packages; do
        case "$package" in
            lib*)
                dpkg-query -L "$package" > /tmp/aharou-library-list
                grep -E '\.so(\.[0-9]+)*$' /tmp/aharou-library-list > /tmp/aharou-shared-libraries
                [ -s /tmp/aharou-shared-libraries ]
                while IFS= read -r library; do
                    [ -f "$library" ]
                    ldd "$library" > /tmp/aharou-ldd-check 2>&1
                    if grep -q 'not found' /tmp/aharou-ldd-check; then
                        printf 'Missing shared dependency: %s\n' "$library" >&2
                        exit 1
                    fi
                done < /tmp/aharou-shared-libraries
                ;;
        esac
    done
fi

restore_policy
policy_created=0
trap - EXIT
if [ "$distro" = alpine ]; then
    find /var/cache/apk -mindepth 1 -delete
else
    apt-get clean
    find /var/lib/apt/lists -mindepth 1 -delete
    find /var/cache/apt -type f -delete
fi
# 工具安装在系统路径；保留包数据库与许可证，只删除构建缓存及用户状态。
for directory in /tmp /var/tmp /var/log /var/cache/fontconfig /root /home; do
    if [ -d "$directory" ]; then find "$directory" -mindepth 1 -delete; fi
done
rm -f /etc/machine-id /var/lib/dbus/machine-id /var/lib/systemd/random-seed
if [ -d /etc/ssh ]; then
    find /etc/ssh -maxdepth 1 \( -type f -o -type l \) -name 'ssh_host_*' -delete
fi
rm -f /.installed /.installed_custom /.provisioned
mkdir -p /etc/aharou
printf '{"schemaVersion":1,"profile":"%s","distro":"%s","abi":"%s","recipeRevision":"%s"}\n' \
    "$profile" "$distro" "$abi" "$revision" > /etc/aharou/preinstalled.json
