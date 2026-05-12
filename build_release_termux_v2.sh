#!/data/data/com.termux/files/usr/bin/bash

set -e

unset JAVA_HOME
export TERM=dumb

JAVA_BIN="$(readlink -f "$(command -v java)")"
JAVA_HOME_REAL="$(dirname "$(dirname "$JAVA_BIN")")"

echo "java      = $JAVA_BIN"
echo "JAVA_HOME = $JAVA_HOME_REAL"
"$JAVA_HOME_REAL/bin/java" -version
"$JAVA_HOME_REAL/bin/javac" -version

rm -rf "$HOME/.gradle-tts"
mkdir -p "$HOME/.gradle-tts"

cat > "$HOME/.gradle-tts/gradle.properties" <<EOG
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.workers.max=1
org.gradle.console=plain
org.gradle.native=true
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$JAVA_HOME_REAL
org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8 -Dorg.gradle.native=true -Djansi.mode=strip
kotlin.compiler.execution.strategy=in-process
EOG

# 项目级也写一份，避免 Gradle 忽略自定义用户目录时继续自动探测
grep -v -E 'org\.gradle\.native|org\.gradle\.java\.installations|kotlin\.compiler\.execution\.strategy|org\.gradle\.workers\.max|org\.gradle\.parallel|org\.gradle\.daemon|org\.gradle\.console' gradle.properties > gradle.properties.tmp 2>/dev/null || true
mv gradle.properties.tmp gradle.properties

cat >> gradle.properties <<EOG

# Termux build fix
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.workers.max=1
org.gradle.console=plain
org.gradle.native=true
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$JAVA_HOME_REAL
kotlin.compiler.execution.strategy=in-process
EOG

export JAVA_HOME="$JAVA_HOME_REAL"
export GRADLE_USER_HOME="$HOME/.gradle-tts"
export GRADLE_OPTS="-Dorg.gradle.native=true -Djansi.mode=strip -Dorg.gradle.console=plain"

bash ./gradlew --no-daemon --no-watch-fs --console=plain :app:assembleRelease --max-workers=1 -Dorg.gradle.native=true -Djansi.mode=strip
