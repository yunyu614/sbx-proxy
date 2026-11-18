#!/bin/sh
# 下载 sing-box Android ARM64 二进制到 module/sing-box
set -e
cd "$(dirname "$0")/.."
VER=${SINGBOX_VER:-1.14.4}
URL="https://github.com/SagerNet/sing-box/releases/download/v${VER}/sing-box-${VER}-android-arm64.tar.gz"
curl -fL -o /tmp/singbox.tgz "$URL"
tar xzf /tmp/singbox.tgz -C /tmp
cp /tmp/sing-box-${VER}-android-arm64/sing-box module/sing-box
chmod 755 module/sing-box
echo "sing-box ${VER} ready"
