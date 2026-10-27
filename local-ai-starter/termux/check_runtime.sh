#!/data/data/com.termux/files/usr/bin/bash
set -eu
echo "== Architecture =="
uname -m
echo
echo "== Android version =="
getprop ro.build.version.release 2>/dev/null || true
echo
echo "== llama.cpp package availability =="
pkg show llama-cpp 2>&1 | head -n 18 || true
echo
echo "If no package is available, use the official guide:"
echo "https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md"
