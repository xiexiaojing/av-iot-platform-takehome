#!/usr/bin/env bash
# 编译：只用 JDK 自带 javac，无需 Maven/Gradle/外网。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
rm -rf build/classes
mkdir -p build/classes
javac -encoding UTF-8 -Xlint:all -d build/classes $(find src -name '*.java')
echo "compiled -> build/classes"
