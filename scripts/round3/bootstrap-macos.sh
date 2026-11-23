#!/bin/sh
# Downloaded tools live outside the workspace; no brew services or existing DB endpoints are used.
set -eu
[ "$(uname -m)" = arm64 ] && [ "$(uname -s)" = Darwin ] || { echo 'macOS arm64 only; other hosts supply exact MySQL 8.0.46 binaries to accept.py'; exit 1; }
p3_tools="${PILATES_ROUND3_TOOL_DIR:-/tmp/pilates-round3-tools}"
mkdir -p "$p3_tools"
if [ ! -x "$p3_tools/mysql-8.0.46-macos15-arm64/bin/mysqld" ]; then
 curl -fL --retry 2 -o "$p3_tools/mysql-8.0.46-macos15-arm64.tar.gz" https://cdn.mysql.com/Downloads/MySQL-8.0/mysql-8.0.46-macos15-arm64.tar.gz
 [ "$(shasum -a 256 "$p3_tools/mysql-8.0.46-macos15-arm64.tar.gz" | cut -d ' ' -f 1)" = 6ca4a361a45f5019fbf6d500997986b4aaf6f0e30ff9c41179d1e8ecd635f3db ] || exit 2
 tar -xzf "$p3_tools/mysql-8.0.46-macos15-arm64.tar.gz" -C "$p3_tools"
fi
if [ ! -x "$p3_tools/nginx/sbin/nginx" ]; then
 curl -fL --retry 2 -o "$p3_tools/nginx.tar.gz" https://nginx.org/download/nginx-1.28.0.tar.gz
 [ "$(shasum -a 256 "$p3_tools/nginx.tar.gz" | cut -d ' ' -f 1)" = c6b5c6b086c0df9d3ca3ff5e084c1d0ef909e6038279c71c1c3e985f576ff76a ] || exit 2
 tar -xzf "$p3_tools/nginx.tar.gz" -C "$p3_tools"
 (cd "$p3_tools/nginx-1.28.0" && ./configure --prefix="$p3_tools/nginx" --with-http_ssl_module --with-cc-opt=-I/opt/homebrew/include --with-ld-opt=-L/opt/homebrew/lib && make -j4 && make install)
fi
if [ ! -x "$p3_tools/redis-7.4.10/src/redis-server" ]; then
 curl -fL --retry 2 -o "$p3_tools/redis-7.4.10.tar.gz" https://download.redis.io/releases/redis-7.4.10.tar.gz
 [ "$(shasum -a 256 "$p3_tools/redis-7.4.10.tar.gz" | cut -d ' ' -f 1)" = 669ab6689b5e7d0c479e8d526ccbadae36b69a11370742ffe23822b9df8d85ba ] || exit 2
 tar -xzf "$p3_tools/redis-7.4.10.tar.gz" -C "$p3_tools"
 make -C "$p3_tools/redis-7.4.10" -j4 MALLOC=libc BUILD_TLS=no
fi
printf 'Tool preparation complete. No existing database or Redis was connected.\n'
