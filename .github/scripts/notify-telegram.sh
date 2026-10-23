#!/usr/bin/env bash
# 把构建结果发到 Telegram。
#
# 用法：notify-telegram.sh "<HTML 文本>" [附件路径...]
# 需要环境变量 TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID；缺任意一个就静默跳过，
# 不让通知失败拖垮发布流水线。TELEGRAM_CHAT_ID 支持用逗号或空格分隔多个会话
# （例如群组 + 频道），每个会话都会收到消息与附件。
# 附件超过 Bot API 的 50MB 上限会被跳过并提示。
set -euo pipefail

token="${TELEGRAM_BOT_TOKEN:-}"
chats_raw="${TELEGRAM_CHAT_ID:-}"

if [ -z "$token" ] || [ -z "$chats_raw" ]; then
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

IFS=', ' read -r -a chats <<< "$chats_raw"

# 正文先落到临时文件再交给 curl 读，避免命令行参数长度与编码（Windows 上非 UTF-8 argv）问题。
message_file="$(mktemp)"
trap 'rm -f "$message_file"' EXIT
printf '%s' "$message" > "$message_file"
# 在 Git Bash 里本地调试时，curl.exe 认不出 /tmp/... 这类 MSYS 路径，换成 Windows 路径。
message_path="$message_file"
if command -v cygpath > /dev/null 2>&1; then
  message_path="$(cygpath -w "$message_file")"
fi

for chat in "${chats[@]}"; do
  [ -n "$chat" ] || continue
  call sendMessage \
    --data-urlencode "chat_id=${chat}" \
    --data-urlencode "text@${message_path}" \
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
done
