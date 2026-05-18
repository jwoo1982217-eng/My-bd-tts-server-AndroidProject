#!/data/data/com.termux/files/usr/bin/bash

set -e

unset JAVA_HOME
export TERM=dumb

JAVA_BIN="$(readlink -f "$(command -v java)")"
export JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"

echo "java      = $JAVA_BIN"
echo "JAVA_HOME = $JAVA_HOME"
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version

rm -rf "$HOME/.gradle-tts"
mkdir -p "$HOME/.gradle-tts"

cat > "$HOME/.gradle-tts/gradle.properties" <<'EOG'
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.workers.max=1
org.gradle.console=plain
org.gradle.native=true
org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8 -Dorg.gradle.native=true -Djansi.mode=strip
kotlin.compiler.execution.strategy=in-process
EOG

sed -i '/org\.gradle\.native/d' gradle.properties 2>/dev/null || true

export GRADLE_USER_HOME="$HOME/.gradle-tts"
export GRADLE_OPTS="-Dorg.gradle.native=true -Djansi.mode=strip -Dorg.gradle.console=plain"

bash ./gradlew --no-daemon --console=plain :app:assembleRelease --max-workers=1 -Dorg.gradle.native=true -Djansi.mode=strip
