#!/usr/bin/env bash
# PhoenixScout 服务器安装（Ubuntu 24.04，root 运行，可重复运行）。不改动同机的 TrendPilot。
#   1. 网络检查：CoinGecko、DefiLlama、Telegram、GitHub（SSH 443）。
#   2. 缺什么装什么（Java 21、git、jq、sqlite3、curl）；时区和交换文件已由 TrendPilot 设好，不重复设置。
#   3. 建普通用户 phoenixscout；源码 /opt/phoenixscout-src，运行目录 /opt/phoenixscout。
#   4. 仓库读取：公开仓库匿名 HTTPS（和 TrendPilot 一样）；私有仓库用只读 deploy key（没有就生成并打印公钥）。
#   5. 克隆、构建（含全部测试）、注册 systemd：
#        phoenixscout.service         服务本身（只监听 127.0.0.1:8100，崩溃 30 秒后拉起）。配置到位、且没有停用标记才启动。
#        phoenixscout-update.timer    每 3 分钟 auto-update.sh：自动更新 + 守护。
# 用法：bash install.sh               完整安装
#       bash install.sh --check       只做网络检查
#       bash install.sh --repo        测试能否读取仓库（公开 → HTTPS；私有 → 生成并显示 deploy key 公钥）
#       bash install.sh --units       只重新写 systemd 单元
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行"; exit 1; }

check_network() {
    local fail=0 code
    for item in \
        "CoinGecko|https://api.coingecko.com/api/v3/ping" \
        "DefiLlama|https://api.llama.fi/overview/fees?excludeTotalDataChart=true&excludeTotalDataChartBreakdown=true&dataType=dailyRevenue" \
        "Telegram|https://api.telegram.org/" \
        "GitHub|https://api.github.com/"; do
        local name=${item%%|*} url=${item#*|}
        code=$(curl -s -m 30 -o /dev/null -w '%{http_code}' "$url" || echo 000)
        case "$name" in
            Telegram) [ "$code" != 000 ] && echo "OK   $name（HTTP $code）" || { echo "FAIL $name 连不上"; fail=1; } ;;
            *) [ "$code" = 200 ] && echo "OK   $name" || { echo "FAIL $name（HTTP $code）"; fail=1; } ;;
        esac
    done
    if timeout 20 bash -c 'exec 3<>/dev/tcp/ssh.github.com/443' 2>/dev/null; then echo "OK   GitHub SSH（443 端口）"
    else echo "FAIL GitHub SSH（443 端口）连不上"; fail=1; fi
    return $fail
}

ensure_user() {
    id "$RUN_USER" >/dev/null 2>&1 || useradd --system --create-home --home-dir "$RUN_HOME" --shell /usr/sbin/nologin "$RUN_USER"
    install -d -o "$RUN_USER" -g "$RUN_USER" -m 700 "$RUN_HOME/.ssh"
}

git_as_user() {
    sudo -u "$RUN_USER" -H env GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND="ssh -i $DEPLOY_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$RUN_HOME/.ssh/known_hosts" git "$@"
}

# 选择能读取的仓库地址：公开仓库用 HTTPS（和 TrendPilot 一样）；否则用 deploy key（没有就生成并打印公钥）。
# 能读取时输出地址并返回 0；不能读取返回 4
repo_url() {
    ensure_user
    if git_as_user ls-remote "$REPO_HTTPS" "$BRANCH" >/dev/null 2>&1; then echo "$REPO_HTTPS"; return 0; fi
    if [ ! -f "$DEPLOY_KEY" ]; then
        sudo -u "$RUN_USER" ssh-keygen -q -t ed25519 -N '' -C "phoenixscout-server-$(hostname)" -f "$DEPLOY_KEY"
        echo "仓库是私有的，已生成新的只读 deploy key" >&2
    fi
    if git_as_user ls-remote "$REPO_SSH" "$BRANCH" >/dev/null 2>&1; then echo "$REPO_SSH"; return 0; fi
    echo "仓库是私有的，服务器还不能读取。二选一：" >&2
    echo "  A. 把仓库改为公开（和 TrendPilot 一样）；" >&2
    echo "  B. 把下面这行公钥加到 GitHub：PhoenixScout → Settings → Deploy keys → Add deploy key（不要勾选 Allow write access）：" >&2
    echo "DEPLOY_KEY_PUB $(cat "$DEPLOY_KEY.pub")" >&2
    return 4
}

write_units() {
    cat > /etc/systemd/system/$SERVICE <<EOF2
[Unit]
Description=PhoenixScout research scanner (find coins, reasons, notifications; no trading)
After=network-online.target
Wants=network-online.target
ConditionPathExists=$CONFIG
ConditionPathExists=!$BIN/disabled.flag

[Service]
User=$RUN_USER
WorkingDirectory=$APP_DIR
# 内存：堆上限 $JAVA_XMX；扫描完把空闲堆还给系统（MaxHeapFreeRatio=30）；整个进程硬上限 950M，超了只重启本服务，不影响同机 TrendPilot
ExecStart=/usr/bin/java -Xms64m -Xmx$JAVA_XMX -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:-ShrinkHeapInSteps -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError -jar $JAR
MemoryMax=950M
Restart=always
RestartSec=30
TimeoutStopSec=120
StandardOutput=append:$APP_DIR/logs/console.log
StandardError=append:$APP_DIR/logs/console.log

[Install]
WantedBy=multi-user.target
EOF2
    cat > /etc/systemd/system/phoenixscout-update.service <<EOF2
[Unit]
Description=PhoenixScout auto update and watchdog

[Service]
Type=oneshot
ExecStart=/bin/bash $APP_DIR/scripts/linux/auto-update.sh
TimeoutStartSec=3600
EOF2
    cat > /etc/systemd/system/$TIMER <<EOF2
[Unit]
Description=Run PhoenixScout auto update every 3 minutes

[Timer]
OnBootSec=3min
OnUnitInactiveSec=3min

[Install]
WantedBy=timers.target
EOF2
    systemctl daemon-reload
    systemctl enable $SERVICE $TIMER >/dev/null 2>&1
    systemctl start $TIMER
}

case "${1:-}" in
    --check) check_network; exit $? ;;
    --repo) repo_url; exit $? ;;
    --units) write_units; exit 0 ;;
esac

echo "== 1/6 网络检查"
check_network || { echo "网络检查没通过，停止安装"; exit 2; }

echo "== 2/6 工具"
need=()
for c in java git jq sqlite3 curl; do command -v "$c" >/dev/null || need+=("$c"); done
if [ ${#need[@]} -gt 0 ]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq openjdk-21-jdk-headless git jq sqlite3 curl ca-certificates >/dev/null
fi
java -version 2>&1 | head -1

echo "== 3/6 用户和目录"
ensure_user
mkdir -p "$BIN" "$APP_DIR/config" "$APP_DIR/data" "$APP_DIR/logs" "$APP_DIR/scripts"
chmod 700 "$APP_DIR/config"

echo "== 4/6 仓库读取权限"
url=$(repo_url) || exit 4
echo "OK   仓库地址 $url"

echo "== 5/6 克隆并构建（含全部测试，约 5～10 分钟）"
install -d -o "$RUN_USER" -g "$RUN_USER" "$SRC_DIR"
if [ ! -d "$SRC_DIR/.git" ]; then
    # 目录已由 root 建好并交给运行用户（运行用户不能在 /opt 下新建目录）；git 可以克隆进空目录
    git_as_user clone --quiet --branch "$BRANCH" "$url" "$SRC_DIR"
else
    git_src remote set-url origin "$url"
    git_src fetch --quiet origin "$BRANCH"
    git_src checkout --quiet --force "origin/$BRANCH"
fi
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR"
build_src || { echo "构建失败，见 $APP_DIR/logs/build.log 最后几行："; tail -n 30 "$APP_DIR/logs/build.log"; exit 3; }
install -o "$RUN_USER" -g "$RUN_USER" -m 644 "$SRC_DIR/target/phoenixscout.jar" "$JAR"
cp -r "$SRC_DIR/scripts/." "$APP_DIR/scripts/"
cp -r "$SRC_DIR/docs" "$APP_DIR/" 2>/dev/null || true
git_src rev-parse HEAD > "$VERSION_FILE"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR"

echo "== 6/6 注册 systemd"
write_units
log "安装完成：$(cut -c1-7 "$VERSION_FILE")"
if [ -f "$CONFIG" ] && [ ! -f "$BIN/disabled.flag" ]; then
    systemctl restart $SERVICE
    echo "安装完成，服务已启动。"
else
    echo "安装完成。服务会在迁移时（配置上传后）启动。"
fi
