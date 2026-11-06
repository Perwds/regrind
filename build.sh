#!/bin/sh
# Builds regrind.jar. Needs JDK 25 or newer on PATH; there are no other dependencies.
set -e
cd "$(dirname "$0")"
mkdir -p out
javac --release 25 -Xlint:none -d out $(find src -name '*.java')
jar --create --file regrind.jar --main-class regrind.Main -C out .
echo "Built regrind.jar"
