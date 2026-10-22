#!/usr/bin/env bash
# 用 FFmpeg 生成一路测试画面并推到 RTMP，验证媒体链路（推流/拉流/重连）。
set -euo pipefail
PUSH_URL="${PUSH_URL:-rtmp://localhost:1935/live/test}"
echo "推流到 $PUSH_URL（Ctrl-C 停止）"
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=25 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p \
  -f flv "$PUSH_URL"
