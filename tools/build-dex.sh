#!/bin/sh
# 构建 tool.dex：需要 JDK 17+ 与 d8（r8.jar）
# 用法: D8_JAR=/path/to/r8.jar ./build-dex.sh
set -e
cd "$(dirname "$0")/.."
D8_JAR=${D8_JAR:?请设置 D8_JAR 指向 r8.jar}
HAPB_JAR=${HAPB_JAR:?请设置 HAPB_JAR 指向 hiddenapibypass 的 classes.jar}
mkdir -p build/stubs build/classes build/dex
find app/stub -name "*.java" > /tmp/stub-list.txt
javac -source 8 -target 8 -nowarn -d build/stubs @/tmp/stub-list.txt
( cd build/stubs && jar cf ../stubs.jar . )
find app/src -name "*.java" > /tmp/src-list.txt
javac -source 8 -target 8 -nowarn -cp build/stubs.jar:$HAPB_JAR -d build/classes @/tmp/src-list.txt
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 29 --lib build/stubs.jar \
  --output build/dex build/classes/com/dsh/sbxtool/Main.class "$HAPB_JAR"
cp build/dex/classes.dex module/tool.dex
echo "tool.dex ready"
