#!/usr/bin/env bash
# 服务器状态：服务、版本、健康、资源、最近日志（不输出配置内容）
# shellcheck source=common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
echo "主机 $(hostname)  时间 $(date '+%F %T %Z')  $(uptime -p)"
echo "版本 $(cut -c1-7 "$VERSION_FILE" 2>/dev/null)  配置$([ -f "$CONFIG" ] && echo 已到位 || echo 未到位)$([ -f "$BIN/disabled.flag" ] && echo '  已停用')"
echo "服务 $(systemctl is-active $SERVICE)  自动更新 $(systemctl is-active $TIMER)  （TrendPilot $(systemctl is-active trendpilot.service)）"
echo "健康 $(health_json | head -c 600)"
free -m | awk 'NR==2{print "内存已用 "$3"M / "$2"M"}'
ps -o rss=,args= -C java 2>/dev/null | awk '{printf "  java %dM %s\n", $1/1024, ($0 ~ /phoenixscout/ ? "PhoenixScout" : ($0 ~ /trendpilot/ ? "TrendPilot" : "其他"))}'
df -h / | awk 'NR==2{print "硬盘已用 "$3" / "$2}'
echo "--- auto-update.log"
tail -n 8 "$LOG_FILE" 2>/dev/null
echo "--- 最近的扫描（scan_run）"
sqlite3 -readonly "$APP_DIR/data/phoenixscout.db" "SELECT scan_day, started_at, finished_at, status FROM scan_run ORDER BY scan_day DESC LIMIT 5;" 2>&1 | head -n 5
echo "--- 快照 / 历史归档"
ls -la "$APP_DIR/data/snapshot" 2>&1 | tail -n 4
ls "$APP_DIR/data/raw/p0-archive/history" 2>/dev/null | wc -l | awk '{print "已归档币种文件 "$1}'
grep -E '快照|P0|归档|启动检查' "$APP_DIR/logs/phoenixscout.log" 2>/dev/null | tail -n 6 | cut -c1-220
echo "--- phoenixscout.log 最近警告"
grep -E 'WARN|ERROR' "$APP_DIR/logs/phoenixscout.log" 2>/dev/null | tail -n 8 | cut -c1-220
