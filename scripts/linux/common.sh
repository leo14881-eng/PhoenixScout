#!/usr/bin/env bash
# PhoenixScout 服务器（Ubuntu）共用设置和函数。被 install.sh / auto-update.sh / migrate-in.sh / status.sh 引用。
# 同一台服务器上还跑着 TrendPilot（/opt/trendpilot、端口 8080、trendpilot.service）：这里的名字全部不同，从不改动它。
# 从不输出 config/application-local.yml 的内容。

APP_DIR=/opt/phoenixscout
SRC_DIR=/opt/phoenixscout-src
# 仓库公开时和 TrendPilot 一样匿名 HTTPS 拉取；私有时用只读 deploy key 走 SSH 的 443 端口（有些机房封 22 端口）
REPO_HTTPS=https://github.com/leo14881-eng/PhoenixScout.git
REPO_SSH=ssh://git@ssh.github.com:443/leo14881-eng/PhoenixScout.git
BRANCH=main
RUN_USER=phoenixscout
RUN_HOME=/home/$RUN_USER
DEPLOY_KEY=$RUN_HOME/.ssh/deploy_key
PORT=8100
BASE="http://127.0.0.1:$PORT"
JAVA_XMX=700m          # 三个项目共用 4G 内存（TrendPilot 约 0.9G），本项目上限 700M
BIN="$APP_DIR/app"
JAR="$BIN/phoenixscout.jar"
PREV_JAR="$BIN/phoenixscout.prev.jar"
VERSION_FILE="$BIN/version.txt"
FAILED_FILE="$BIN/failed-commit.txt"
START_FAILED_FILE="$BIN/startup-failed-commit.txt"
CONFIG="$APP_DIR/config/application-local.yml"
LOG_FILE="$APP_DIR/logs/auto-update.log"
SERVICE=phoenixscout.service
TIMER=phoenixscout-update.timer

log() {
    mkdir -p "$(dirname "$LOG_FILE")"
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG_FILE"
}

read_text() {
    [ -f "$1" ] && tr -d '\r\n ' < "$1" || true
}

# 以运行用户执行 git（带 deploy key；公开仓库时 deploy key 用不到，不影响）
git_src() {
    sudo -u "$RUN_USER" -H env GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND="ssh -i $DEPLOY_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$RUN_HOME/.ssh/known_hosts" \
        git -C "$SRC_DIR" "$@"
}

# 构建（含全部测试）。内存紧：Maven 本身限 512M，测试 JVM 限 512M
build_src() {
    sudo -u "$RUN_USER" -H bash -c "cd '$SRC_DIR' && MAVEN_OPTS='-Xmx512m' sh ./mvnw -B -q clean package -DargLine='-Xmx512m'" \
        > "$APP_DIR/logs/build.log" 2>&1
}

# Telegram 通知：从本机配置读取机器人 token 和 chat id（不输出）。读不到就不发。只发消息，不收消息。
send_telegram() {
    [ -f "$CONFIG" ] || return 0
    local token chat text
    token=$(sed -n 's/^[[:space:]]*bot-token:[[:space:]]*["'\'']\{0,1\}\([^"'\''[:space:]#]*\).*/\1/p' "$CONFIG" | head -1)
    chat=$(sed -n 's/^[[:space:]]*chat-id:[[:space:]]*["'\'']\{0,1\}\([^"'\''[:space:]#]*\).*/\1/p' "$CONFIG" | head -1)
    [ -n "$token" ] && [ -n "$chat" ] || return 0
    text="【PhoenixScout】$1"
    curl -fsS -m 20 -o /dev/null "https://api.telegram.org/bot${token}/sendMessage" \
        --data-urlencode "chat_id=${chat}" --data-urlencode "text=${text}" 2>/dev/null \
        || log "Telegram 发送失败"
}

health_json() {
    curl -fsS -m 15 "$BASE/api/health" 2>/dev/null
}

healthy() {
    health_json >/dev/null
}

# 健康接口里的字段（取不到输出空）
health_field() {
    health_json | jq -r ".$1 // empty" 2>/dev/null
}

# 最近一次扫描完成时间（epoch 秒），以及状态；取不到输出 0
last_scan_ok() {
    local fin st
    fin=$(health_field lastScanFinish)
    st=$(health_field lastScanStatus)
    if [ -n "$fin" ] && { [ "$st" = ok ] || [ "$st" = partial ]; }; then date -d "$fin" +%s 2>/dev/null || echo 0; else echo 0; fi
}
