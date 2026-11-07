#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac --release 17 -d out $(find src/main/java -name '*.java')
cp -R src/main/resources/. out/
jar --create --file ls9-java.jar -C out .
echo "Built $(pwd)/ls9-java.jar and classes in $(pwd)/out"
