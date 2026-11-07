#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac --release 17 -d out $(find src/main/java src/test/java -name '*.java')
cp -R src/main/resources/. out/
java -cp out io.github.leocb.ls9.SelfTest
