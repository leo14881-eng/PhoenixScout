#!/usr/bin/env bash
# PhoenixScout 服务器自动更新与守护（systemd 定时器每 3 分钟以 root 运行一次），对应电脑上的 auto-update.ps1 + watchdog.ps1：
#   1. 守护：配置到位、没有停用标记、服务却没在跑 → 拉起；健康接口报告卡住（扫描超过 4 小时）→ 重启并通知。
#   2. 更新：GitHub main 有新提交时（main 只接收 Windows 和 Linux CI 都通过的提交，见 scripts/ship.sh）：
#      只改文档 → 复制文件，不构建、不重启；否则构建（含全部测试），成功后等扫描不在运行时换 jar 重启；
#      启动后 10 分钟内启动检查完成才算成功，否则回滚到上一版（2 小时后再试这个版本）。构建失败继续跑旧版，同一提交只提醒一次。
# 从不改动：config/application-local.yml、data、logs；也不碰同机的 TrendPilot。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

exec 9>/run/phoenixscout-update.lock
flock -n 9 || exit 0

service_ready() { [ -f "$CONFIG" ] && [ ! -f "$BIN/disabled.flag" ]; }

start_service() {
    systemctl start $SERVICE
    for _ in $(seq 1 36); do
        sleep 5
        healthy && return 0
    done
    return 1
}

# 等到扫描不在运行（最多约 30 分钟）
wait_quiet() {
    for _ in $(seq 1 180); do
        [ "$(health_field scanRunning)" = true ] || return 0
        sleep 10
    done
    return 1
}

# 新版本启动后，等启动检查完成（最多约 10 分钟）
wait_startup_ok() {
    for _ in $(seq 1 120); do
        sleep 5
        [ "$(health_field startupDone)" = true ] && return 0
    done
    return 1
}

# ---------- 1. 守护 ----------
watchdog() {
    service_ready || return 0
    if ! systemctl is-active --quiet $SERVICE; then
        log "服务没在运行，拉起"
        if start_service; then log "已拉起"; else send_telegram "⚠️ 服务器上的服务没能启动，稍后自动重试"; fi
        return
    fi
    local ok
    ok=$(health_field ok)
    if [ "$ok" = false ]; then
        log "健康检查报告卡住，重启服务"
        systemctl restart $SERVICE
        send_telegram "⚠️ 服务器上扫描卡住超过 4 小时，已重启服务"
    fi
}

# ---------- 2. 更新 ----------
update() {
    [ -d "$SRC_DIR/.git" ] || return
    git_src fetch --quiet origin "$BRANCH" 2>>"$LOG_FILE" || { log "git fetch 失败"; return; }
    local latest current short subject
    latest=$(git_src rev-parse "origin/$BRANCH")
    current=$(read_text "$VERSION_FILE")
    [ "$latest" = "$current" ] && return
    [ "$latest" = "$(read_text "$FAILED_FILE")" ] && return
    if [ "$latest" = "$(read_text "$START_FAILED_FILE")" ] && [ -n "$(find "$START_FAILED_FILE" -mmin -120 2>/dev/null)" ]; then return; fi
    short=${latest:0:7}
    subject=$(git_src log -1 --format=%s "$latest")
    log "发现新提交 ${short}：$subject"

    local changed docs_only=0
    if [ -n "$current" ]; then
        changed=$(git_src diff --name-only "$current" "$latest" 2>/dev/null || true)
        if [ -n "$changed" ] && ! echo "$changed" | grep -qvE '^docs/|\.md$'; then docs_only=1; fi
    fi
    git_src checkout --quiet --force "$latest"
    if [ "$docs_only" -eq 1 ]; then
        cp -r "$SRC_DIR/docs" "$APP_DIR/" 2>/dev/null
        echo "$latest" > "$VERSION_FILE"
        log "只改了文档，已更新到 $short（不重启）"
        return
    fi

    if service_ready && systemctl is-active --quiet $SERVICE && ! wait_quiet; then log "扫描一直在运行，$short 稍后再装"; return; fi
    if ! build_src; then
        log "$short 构建失败，继续运行旧版本"
        echo "$latest" > "$FAILED_FILE"
        send_telegram "⚠️ 服务器上 $short 构建或测试没通过，继续运行旧版本（$subject）"
        return
    fi

    local scripts_changed=0
    if ! diff -rq "$SRC_DIR/scripts/linux" "$APP_DIR/scripts/linux" >/dev/null 2>&1; then scripts_changed=1; fi

    if service_ready && systemctl is-active --quiet $SERVICE && ! wait_quiet; then log "扫描一直在运行，$short 稍后再装"; return; fi
    systemctl stop $SERVICE
    [ -f "$JAR" ] && cp -f "$JAR" "$PREV_JAR"
    install -o "$RUN_USER" -g "$RUN_USER" -m 644 "$SRC_DIR/target/phoenixscout.jar" "$JAR"
    cp -r "$SRC_DIR/scripts/." "$APP_DIR/scripts/"
    cp -r "$SRC_DIR/docs" "$APP_DIR/" 2>/dev/null
    chown -R "$RUN_USER:$RUN_USER" "$BIN" "$APP_DIR/scripts" "$APP_DIR/docs" 2>/dev/null
    [ "$scripts_changed" -eq 1 ] && bash "$APP_DIR/scripts/linux/install.sh" --units

    if ! service_ready; then
        echo "$latest" > "$VERSION_FILE"
        log "已更新到 $short（服务未启用，没有启动）"
        return
    fi
    if start_service && wait_startup_ok; then
        echo "$latest" > "$VERSION_FILE"
        rm -f "$FAILED_FILE" "$START_FAILED_FILE"
        log "已更新到 $short"
        local body
        body=$(git_src log -1 --format=%B "$latest" | grep -vE '^(Co-Authored-By|Claude-Session):' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')
        send_telegram "✅ 服务器已更新到 $short，运行正常
本次改善：$body"
    else
        log "$short 启动后启动检查没有完成，回滚"
        systemctl stop $SERVICE
        [ -f "$PREV_JAR" ] && cp -f "$PREV_JAR" "$JAR"
        echo "$latest" > "$START_FAILED_FILE"
        start_service || true
        send_telegram "⚠️ 服务器上 $short 启动后没有正常完成启动检查，已回滚到上一版，2 小时后再试"
    fi
}

watchdog
# 研究层中转（research-data 分支，不经过用户电脑）；放在更新前面，更新可能要等很久
if [ -f "$HERE/relay.sh" ]; then timeout 150 bash "$HERE/relay.sh" || log "中转超时或失败"; fi
update
