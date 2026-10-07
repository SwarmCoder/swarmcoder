@echo off
echo Building SwarmCoder...
if exist dist rmdir /s /q dist
call mvn clean install -DskipTests
if %ERRORLEVEL% neq 0 (
    echo Build failed!
    pause
    exit /b %ERRORLEVEL%
)
echo Build complete.
pause
