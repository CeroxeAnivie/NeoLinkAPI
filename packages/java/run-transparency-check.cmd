@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%.") do set "PROJECT_DIR=%%~fI"
cd /d "%PROJECT_DIR%" || (
  echo 无法进入项目目录: %PROJECT_DIR%
  set "RUN_EXIT=1"
  goto finish
)

if not "%~7"=="" goto usage

set "REMOTE_DOMAIN=%~1"
set "HOOK_PORT=%~2"
set "CONNECT_PORT=%~3"
set "ACCESS_KEY=%~4"
set "LOCAL_PORT=%~5"
set "LOCAL_HOST=%~6"
set "INTERACTIVE_MODE="
set "RUN_EXIT=0"

if "%~1"=="" if "%~2"=="" if "%~3"=="" if "%~4"=="" if "%~5"=="" if "%~6"=="" set "INTERACTIVE_MODE=1"

if defined INTERACTIVE_MODE (
  rem Double-click launches without arguments, so prompt here instead of failing fast and closing the window.
  echo [交互模式] 直接回车将使用方括号中的默认值。
  call :promptValue "remote domain" REMOTE_DOMAIN "p.ceroxe.top"
  call :promptValue "hook port" HOOK_PORT "44801"
  call :promptValue "connect port" CONNECT_PORT "44802"
  call :promptValue "access key" ACCESS_KEY ""
  call :promptValue "local port" LOCAL_PORT "7777"
  call :promptValue "local host" LOCAL_HOST "127.0.0.1"
)

if not defined REMOTE_DOMAIN set "REMOTE_DOMAIN=p.ceroxe.top"
if not defined HOOK_PORT set "HOOK_PORT=44801"
if not defined CONNECT_PORT set "CONNECT_PORT=44802"
if not defined LOCAL_PORT set "LOCAL_PORT=7777"
if not defined LOCAL_HOST set "LOCAL_HOST=127.0.0.1"
if not defined ACCESS_KEY (
  echo 参数错误: ACCESS_KEY 必填。
  set "RUN_EXIT=2"
  goto finish
)

call :validatePort "%HOOK_PORT%" HOOK_PORT
if errorlevel 1 (
  set "RUN_EXIT=2"
  goto finish
)
call :validatePort "%CONNECT_PORT%" CONNECT_PORT
if errorlevel 1 (
  set "RUN_EXIT=2"
  goto finish
)
call :validatePort "%LOCAL_PORT%" LOCAL_PORT
if errorlevel 1 (
  set "RUN_EXIT=2"
  goto finish
)

if not exist "%PROJECT_DIR%\gradlew.bat" (
  echo 缺少 Gradle Wrapper: "%PROJECT_DIR%\gradlew.bat"
  set "RUN_EXIT=1"
  goto finish
)

set "GRADLE_USER_HOME=%PROJECT_DIR%\.gradle-home"
for %%I in ("%TEMP%") do set "TEMP_DIR=%%~sI"
set "CP_FILE=%TEMP_DIR%\neolink-transparency-cp-%RANDOM%-%RANDOM%.txt"
set "JAVA_ARGS_FILE=%TEMP_DIR%\neolink-transparency-javaargs-%RANDOM%-%RANDOM%.txt"
echo [构建] 正在通过 Gradle Wrapper 准备运行时依赖...
call "%PROJECT_DIR%\gradlew.bat" -q --console=plain printTransparencyRuntimeClasspath > "%CP_FILE%" 2>&1
if errorlevel 1 (
  echo 获取运行时 classpath 失败。
  if exist "%CP_FILE%" type "%CP_FILE%"
  if exist "%CP_FILE%" del /q "%CP_FILE%" >nul 2>nul
  if exist "%JAVA_ARGS_FILE%" del /q "%JAVA_ARGS_FILE%" >nul 2>nul
  set "RUN_EXIT=1"
  goto finish
)

set "CP="
set /p "CP="<"%CP_FILE%"
if exist "%CP_FILE%" del /q "%CP_FILE%" >nul 2>nul
if not defined CP (
  echo 获取运行时 classpath 失败: 输出为空。
  if exist "%JAVA_ARGS_FILE%" del /q "%JAVA_ARGS_FILE%" >nul 2>nul
  set "RUN_EXIT=1"
  goto finish
)

echo [参数] remote domain = %REMOTE_DOMAIN%
echo [参数] hook port = %HOOK_PORT%
echo [参数] connect port = %CONNECT_PORT%
echo [参数] access key = %ACCESS_KEY%
echo [参数] local port = %LOCAL_PORT%
echo [参数] local host = %LOCAL_HOST%
echo [测试] 将直接在当前控制台打印 server/client 实时输出，请稍候...

rem Only kill a real listening owner on the configured local port.
rem TIME_WAIT rows often show the remote port with PID 0, which must never be treated as a leaked server process.
for /f "tokens=5" %%A in ('netstat -ano -p tcp ^| findstr /r /c:"^[ ]*TCP[ ]*[^ ]*:%LOCAL_PORT%[ ]*[^ ]*[ ]*LISTENING[ ]*[1-9][0-9]*$"') do (
  echo [清理] 发现残留进程占用本地监听端口 %LOCAL_PORT%，PID=%%A，正在终止...
  taskkill /pid %%A /f >nul 2>nul
  >nul ping 127.0.0.1 -n 2
)

rem Java argfile avoids Windows command-line length limits. Keep the classpath unquoted:
rem the Java launcher treats quotes in argfiles as literal classpath characters on this JDK.
> "%JAVA_ARGS_FILE%" echo -Dfile.encoding=UTF-8
>> "%JAVA_ARGS_FILE%" echo -Dsun.stdout.encoding=UTF-8
>> "%JAVA_ARGS_FILE%" echo -Dsun.stderr.encoding=UTF-8
>> "%JAVA_ARGS_FILE%" echo -cp
>> "%JAVA_ARGS_FILE%" echo %CP%
>> "%JAVA_ARGS_FILE%" echo top.ceroxe.api.neolink.transparency.APITransparencyRunner
>> "%JAVA_ARGS_FILE%" echo %REMOTE_DOMAIN%
>> "%JAVA_ARGS_FILE%" echo %HOOK_PORT%
>> "%JAVA_ARGS_FILE%" echo %CONNECT_PORT%
>> "%JAVA_ARGS_FILE%" echo %ACCESS_KEY%
>> "%JAVA_ARGS_FILE%" echo %LOCAL_PORT%
>> "%JAVA_ARGS_FILE%" echo %LOCAL_HOST%

java @%JAVA_ARGS_FILE%
set "RUN_EXIT=%ERRORLEVEL%"
if exist "%JAVA_ARGS_FILE%" del /q "%JAVA_ARGS_FILE%" >nul 2>nul

goto finish

:usage
echo 用法: run-transparency-check.cmd [remoteDomain] [hookPort] [connectPort] [accessKey] [localPort] [localHost]
echo 示例: run-transparency-check.cmd p.ceroxe.top 44801 44802 YOUR_KEY 7777 127.0.0.1
echo 说明: 不带参数双击运行时，将进入交互输入模式。
set "RUN_EXIT=2"
goto finish

:promptValue
set "%~2=%~3"
set /p "USER_INPUT=%~1 [%~3]: "
if defined USER_INPUT set "%~2=%USER_INPUT%"
set "USER_INPUT="
exit /b 0

:validatePort
set "PORT_VALUE=%~1"
set "PORT_NAME=%~2"
for /f "delims=0123456789" %%A in ("%PORT_VALUE%") do (
  echo 参数错误: %PORT_NAME% 非法，当前值=%PORT_VALUE%
  exit /b 1
)
if "%PORT_VALUE%"=="" (
  echo 参数错误: %PORT_NAME% 非法，当前值=%PORT_VALUE%
  exit /b 1
)
set /a PORT_NUMBER=%PORT_VALUE% 2>nul
if %PORT_NUMBER% LSS 1 (
  echo 参数错误: %PORT_NAME% 非法，当前值=%PORT_VALUE%
  exit /b 1
)
if %PORT_NUMBER% GTR 65535 (
  echo 参数错误: %PORT_NAME% 非法，当前值=%PORT_VALUE%
  exit /b 1
)
exit /b 0

:pauseIfNeeded
if defined INTERACTIVE_MODE pause
exit /b 0

:finish
call :pauseIfNeeded
exit /b %RUN_EXIT%
