#!/usr/bin/env python3
"""PhoenixScout Telegram 推送。

配置读取 config/application-local.yml（已 gitignore）：
phoenixscout:
  telegram:
    bot-token: "..."
    chat-id: "..."

用法：python3 scripts/notify.py "消息内容"   或   echo 内容 | python3 scripts/notify.py
token 不打印、不写日志。
"""
import json, pathlib, re, sys, urllib.request, urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
CFG = ROOT / "config" / "application-local.yml"
LIMIT = 4000  # Telegram 单条上限 4096，留余量


def _cfg():
    text = CFG.read_text(encoding="utf-8")
    tok = re.search(r'bot-token:\s*"?([^"\s]+)"?', text)
    cid = re.search(r'chat-id:\s*"?([^"\s]+)"?', text)
    if not tok or not cid:
        raise SystemExit("config/application-local.yml 缺少 bot-token 或 chat-id")
    return tok.group(1), cid.group(1)


def chunks(msg):
    out, cur = [], ""
    for line in msg.splitlines(keepends=True):
        while len(line) > LIMIT:
            out.append(cur) if cur else None
            cur = ""
            out.append(line[:LIMIT]); line = line[LIMIT:]
        if len(cur) + len(line) > LIMIT:
            out.append(cur); cur = ""
        cur += line
    if cur:
        out.append(cur)
    return out


def send(msg):
    tok, cid = _cfg()
    for part in chunks(msg):
        data = urllib.parse.urlencode({"chat_id": cid, "text": part,
                                       "disable_web_page_preview": "true"}).encode()
        req = urllib.request.Request(f"https://api.telegram.org/bot{tok}/sendMessage", data=data)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                ok = json.load(r).get("ok")
        except Exception as e:  # 不把含 token 的 URL 打出来
            raise SystemExit(f"Telegram 推送失败：{type(e).__name__}")
        if not ok:
            raise SystemExit("Telegram 推送失败：接口返回 ok=false")


if __name__ == "__main__":
    send(" ".join(sys.argv[1:]) if len(sys.argv) > 1 else sys.stdin.read())
    print("sent")
