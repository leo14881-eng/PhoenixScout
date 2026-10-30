#!/usr/bin/env bash
# 研究层中转（2026-10-30 用户要求“以后不要依赖我的电脑”）：服务器和 Claude 研究任务通过公开仓库的 research-data 分支交换文件，
# 不再经过用户电脑。由 auto-update.sh 每 3 分钟调用一次（root 运行，git 以运行用户执行）。
#   上行：最新 7 份 data/reports/candidates-<日期>.json → 分支 candidates/；服务器已有研究文件名清单 → 分支 server-research.txt
#         （需要可写的 deploy key：首次运行自动生成，并用 Telegram 发一次公钥，用户加到 GitHub 后生效）
#   下行：分支 research/research-<日期>-<coin_id>.json → data/research/（只新增，不覆盖已有文件；匿名 HTTPS 读取，不需要密钥）
# 研究文件的格式校验、评级、推送仍由服务完成（ResearchService），这里只搬运文件。
# 从不读取或上传 config/application-local.yml、数据库、日志。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

RELAY_BRANCH=${RELAY_BRANCH:-research-data}
RELAY_DIR=${RELAY_DIR:-/opt/phoenixscout-relay}
RELAY_KEY=${RELAY_KEY:-$RUN_HOME/.ssh/relay_key}
RELAY_PULL_URL=${RELAY_PULL_URL:-$REPO_HTTPS}
RELAY_PUSH_URL=${RELAY_PUSH_URL:-$REPO_SSH}
RELAY_KEEP=${RELAY_KEEP:-7}
RESEARCH_DIR="$APP_DIR/data/research"
REPORTS_DIR="$APP_DIR/data/reports"
NOTIFIED="$BIN/relay-key-notified.txt"
MAX_BYTES=1048576

# 运行用户就是当前用户时（测试）直接执行
as_user() {
    if [ "$(id -un)" = "$RUN_USER" ]; then "$@"; else sudo -u "$RUN_USER" -H "$@"; fi
}

git_relay() {
    as_user env GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND="ssh -i $RELAY_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$RUN_HOME/.ssh/known_hosts -o ConnectTimeout=20" \
        git -C "$RELAY_DIR" "$@"
}

ensure_clone() {
    [ -d "$RELAY_DIR/.git" ] && return 0
    install -d -o "$RUN_USER" -g "$RUN_USER" "$RELAY_DIR" 2>/dev/null || mkdir -p "$RELAY_DIR"
    if ! as_user env GIT_TERMINAL_PROMPT=0 git clone --quiet --single-branch --branch "$RELAY_BRANCH" "$RELAY_PULL_URL" "$RELAY_DIR" 2>>"$LOG_FILE"; then
        log "中转：克隆 $RELAY_BRANCH 分支失败"
        return 1
    fi
    git_relay config user.name "PhoenixScout server"
    git_relay config user.email "phoenixscout-server@users.noreply.github.com"
    log "中转：已克隆 $RELAY_BRANCH 分支"
}

# 拉取最新分支内容（本地未推送的提交只含候选文件，下一轮会重新生成，丢弃无妨）
sync_down() {
    git_relay fetch --quiet origin "$RELAY_BRANCH" 2>>"$LOG_FILE" || { log "中转：fetch 失败"; return 1; }
    git_relay reset --quiet --hard "origin/$RELAY_BRANCH"
}

# 研究结果 → data/research（只新增；文件名必须合规；单个文件不超过 1MB）
ingest_research() {
    [ -d "$RELAY_DIR/research" ] || return 0
    install -d -o "$RUN_USER" -g "$RUN_USER" "$RESEARCH_DIR" 2>/dev/null || mkdir -p "$RESEARCH_DIR"
    local f name n=0
    for f in "$RELAY_DIR"/research/*.json; do
        [ -f "$f" ] || continue
        name=$(basename "$f")
        [[ "$name" =~ ^research-[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9][a-z0-9._-]*\.json$ ]] || { log "中转：跳过不合规的文件名 $name"; continue; }
        [ -e "$RESEARCH_DIR/$name" ] && continue
        if [ "$(stat -c %s "$f")" -gt "$MAX_BYTES" ]; then log "中转：$name 超过 1MB，跳过"; continue; fi
        # 先写临时名再改名，避免服务读到写了一半的文件（服务只处理 .json）
        install -o "$RUN_USER" -g "$RUN_USER" -m 644 "$f" "$RESEARCH_DIR/.$name.part" 2>/dev/null || cp "$f" "$RESEARCH_DIR/.$name.part"
        mv -f "$RESEARCH_DIR/.$name.part" "$RESEARCH_DIR/$name"
        n=$((n + 1))
    done
    [ "$n" -gt 0 ] && log "中转：收到 $n 个研究文件"
    return 0
}

# 没有可写密钥就生成，并用 Telegram 发一次公钥（公钥不是秘密）
ensure_key() {
    if [ ! -f "$RELAY_KEY" ]; then
        install -d -o "$RUN_USER" -g "$RUN_USER" -m 700 "$RUN_HOME/.ssh" 2>/dev/null || mkdir -p "$RUN_HOME/.ssh"
        as_user ssh-keygen -q -t ed25519 -N '' -C "phoenixscout-relay-$(hostname)" -f "$RELAY_KEY" || { log "中转：生成密钥失败"; return 1; }
        log "中转：已生成上传密钥 $RELAY_KEY.pub"
    fi
    if [ ! -f "$NOTIFIED" ]; then
        send_telegram "研究层不再经过电脑中转，需要你在 GitHub 加一把可写的部署密钥（只需一次）：
PhoenixScout 仓库 → Settings → Deploy keys → Add deploy key，标题填 phoenixscout-relay，勾选 Allow write access，内容粘贴下面一行：
$(cat "$RELAY_KEY.pub")"
        date -u '+%Y-%m-%dT%H:%M:%SZ' > "$NOTIFIED"
    fi
}

# 候选和研究清单 → 分支（内容有变化才提交和推送）
publish() {
    ensure_key || return 1
    mkdir -p "$RELAY_DIR/candidates"
    local keep f
    keep=$(ls -1 "$REPORTS_DIR"/candidates-*.json 2>/dev/null | sort | tail -n "$RELAY_KEEP")
    for f in $keep; do
        [ "$(stat -c %s "$f")" -le $((MAX_BYTES * 8)) ] || continue
        cmp -s "$f" "$RELAY_DIR/candidates/$(basename "$f")" || cp -f "$f" "$RELAY_DIR/candidates/"
    done
    # 分支只保留最新 RELAY_KEEP 份候选
    for f in "$RELAY_DIR"/candidates/candidates-*.json; do
        [ -f "$f" ] || continue
        echo "$keep" | grep -qx "$REPORTS_DIR/$(basename "$f")" || rm -f "$f"
    done
    ls -1 "$RESEARCH_DIR" 2>/dev/null | grep -E '^research-.*\.json$' | sort > "$RELAY_DIR/server-research.txt"
    chown -R "$RUN_USER:$RUN_USER" "$RELAY_DIR" 2>/dev/null
    git_relay add -A candidates server-research.txt
    git_relay diff --cached --quiet && return 0
    git_relay commit --quiet -m "服务器同步候选与研究清单 $(date -u '+%Y-%m-%d %H:%M')"
    if git_relay push --quiet "$RELAY_PUSH_URL" "HEAD:refs/heads/$RELAY_BRANCH" 2>>"$LOG_FILE"; then
        log "中转：已上传候选（$(echo "$keep" | tail -1 | xargs -r basename)）"
    else
        log "中转：上传失败（部署密钥还没加到 GitHub，或网络问题），下一轮重试"
        return 1
    fi
}

ensure_clone || exit 0
sync_down || exit 0
ingest_research
publish || true
exit 0
