#!/usr/bin/env bash
# 配置已由电脑上传到 /opt/phoenixscout/config 后执行：修正权限，等扫描空档重启服务，确认启动检查完成。不碰数据。
set -uo pipefail
# shellcheck source=common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
[ -f "$CONFIG" ] || { echo "配置没有到位"; exit 1; }
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR/config"
chmod 700 "$APP_DIR/config"
chmod 600 "$APP_DIR"/config/*
if [ -f "$BIN/disabled.flag" ]; then echo "服务器上的服务已停用，配置已更新，未启动"; exit 0; fi
for _ in $(seq 1 180); do
    [ "$(health_field scanRunning)" = true ] || break
    sleep 10
done
systemctl restart $SERVICE
for _ in $(seq 1 120); do
    sleep 5
    if [ "$(health_field startupDone)" = true ]; then
        echo "配置已更新，服务已重启，运行正常"
        log "配置已更新并重启"
        exit 0
    fi
done
echo "重启后 10 分钟内启动检查没有完成"
exit 1
