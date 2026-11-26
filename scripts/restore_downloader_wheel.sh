#!/usr/bin/env bash
# Only the wheel from the exact approved Downloader commit is accepted.
set -euo pipefail
donor="https://github.com/Azrazieliz/AsterionDownloader.git"
branch="repair/downloader-global-search-20261121"
sha="71f38b91af637bbb0aed74906eea2a92fbb17d03"
wheel="yt_dlp-2026.10.26-py3-none-any.whl"
expected="a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone --quiet --filter=blob:none --no-checkout --single-branch --branch "$branch" "$donor" "$tmp/downloader"
test "$(git -C "$tmp/downloader" rev-parse HEAD)" = "$sha"
mkdir -p app/src/main/assets
git -C "$tmp/downloader" show "$sha:app/src/main/assets/$wheel" > "app/src/main/assets/$wheel"
echo "$expected  app/src/main/assets/$wheel" | sha256sum -c -
