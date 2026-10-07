@echo off
echo Starting SwarmCoder...

set JAVA_OPTS=--add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.time=ALL-UNNAMED
java %JAVA_OPTS% -jar sc-app\target\sc-app-0.1.0.jar
pause
