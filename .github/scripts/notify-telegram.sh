#!/usr/bin/env bash
# 把构建结果发到 Telegram。
#
# 用法：notify-telegram.sh "<HTML 文本>" [附件路径...]
# 需要环境变量 TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID；缺任意一个就静默跳过，
# 不让通知失败拖垮发布流水线。附件超过 Bot API 的 50MB 上限会被跳过并提示。
set -euo pipefail

token="${TELEGRAM_BOT_TOKEN:-}"
chat="${TELEGRAM_CHAT_ID:-}"

if [ -z "$token" ] || [ -z "$chat" ]; then
  echo "未配置 TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID，跳过 Telegram 通知。"
  exit 0
fi

if [ "$#" -lt 1 ]; then
  echo "用法：notify-telegram.sh <文本> [附件...]" >&2
  exit 2
fi

message="$1"
shift

api="https://api.telegram.org/bot${token}"

call() {
  local method="$1"
  shift
  if ! curl --fail-with-body --silent --show-error --retry 3 --retry-delay 2 \
    "${api}/${method}" "$@" > /dev/null; then
    echo "Telegram ${method} 调用失败（已忽略，不影响构建结果）" >&2
  fi
}

call sendMessage \
  --data-urlencode "chat_id=${chat}" \
  --data-urlencode "text=${message}" \
  --data-urlencode "parse_mode=HTML" \
  --data-urlencode "disable_web_page_preview=true"

for file in "$@"; do
  if [ ! -f "$file" ]; then
    continue
  fi
  size=$(wc -c < "$file")
  if [ "$size" -gt 49000000 ]; then
    echo "跳过 $(basename "$file")：${size} 字节超过 Bot API 的 50MB 上传上限" >&2
    continue
  fi
  call sendDocument \
    -F "chat_id=${chat}" \
    -F "document=@${file}" \
    -F "caption=$(basename "$file")"
done
