#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/frontend"
npm ci
npm run build
rm -rf "$ROOT/app/src/main/assets/web-build"
mkdir -p "$ROOT/app/src/main/assets/web-build"
cp -a dist/. "$ROOT/app/src/main/assets/web-build/"
# Put the entry point and Vite output at the asset root expected by WebViewAssetLoader.
cp -a "$ROOT/app/src/main/assets/web-build/." "$ROOT/app/src/main/assets/"
rm -rf "$ROOT/app/src/main/assets/web-build"
mkdir -p "$ROOT/app/src/main/assets/assets"
cp -a "$ROOT/app/src/main/assets/catalog" "$ROOT/app/src/main/assets/assets/"
cp -a "$ROOT/app/src/main/assets-source/icons" "$ROOT/app/src/main/assets/assets/"
cp -a "$ROOT/app/src/main/assets-source/fonts" "$ROOT/app/src/main/assets/assets/"
echo "Web frontend staged in app/src/main/assets. Open the project in Android Studio and build the APK."
