@echo off

REM Default port if not provided
set PORT=%1
if "%PORT%"=="" set PORT=8080

echo Starting Proxy Server on port %PORT%...

REM Run using Gradle Wrapper
call gradlew.bat run --args="%PORT%"