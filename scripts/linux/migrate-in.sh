#!/usr/bin/env bash
# 迁移接收：电脑已平滑停机并上传配置（和可选的数据）到 /opt/phoenixscout 后执行。
# 修正权限、去掉停用标记、启动服务，等到一次完整的每日扫描成功（ok 或 partial）才算迁移成功（最多 45 分钟）。
# 全新数据库时，启动后会自动补跑当天的扫描（UTC 00:40 之前则立即请求一次）。
# 没等到就停掉服务器上的服务、写停用标记并退出 1（电脑端会恢复本机运行）。
set -uo pipefail
# shellcheck source=common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
[ -f "$CONFIG" ] || { echo "配置没有到位"; exit 1; }
chmod 600 "$CONFIG"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR"
DB="$APP_DIR/data/phoenixscout.db"
if [ -f "$DB" ]; then
    check=$(sudo -u "$RUN_USER" sqlite3 "$DB" 'PRAGMA integrity_check;' 2>&1)
    [ "$check" = ok ] || { echo "数据库校验没通过：$check"; exit 1; }
    echo "数据库校验通过"
else
    echo "全新数据库（从零开始）"
fi
rm -f "$BIN/disabled.flag"
since=$(date +%s)
systemctl restart $SERVICE
# UTC 00:40 之前启动不会自动补跑，主动请求一次
if [ "$(date -u +%H%M)" -lt 0040 ]; then
    install -d -o "$RUN_USER" -g "$RUN_USER" "$APP_DIR/data/commands"
    date -u > "$APP_DIR/data/commands/scan.request" && chown "$RUN_USER:$RUN_USER" "$APP_DIR/data/commands/scan.request"
fi
for i in $(seq 1 540); do
    sleep 5
    if [ "$(last_scan_ok)" -ge "$since" ]; then
        echo "服务器上的每日扫描已成功完成（状态 $(health_field lastScanStatus)），迁移成功"
        exit 0
    fi
    [ $((i % 60)) -eq 0 ] && echo "等待扫描完成… 已 $((i * 5 / 60)) 分钟，扫描运行中=$(health_field scanRunning)"
done
echo "45 分钟内没有完成一次成功的扫描（最后状态 $(health_field lastScanStatus)，错误 $(health_field lastScanError)）"
systemctl stop $SERVICE
touch "$BIN/disabled.flag"
exit 1
