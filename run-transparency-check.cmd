@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%.") do set "PROJECT_DIR=%%~fI"
cd /d "%PROJECT_DIR%" || (
  echo 无法进入项目目录: %PROJECT_DIR%
  call :pauseIfNeeded
  exit /b 1
)

if not "%~6"=="" goto usage

set "REMOTE_DOMAIN=%~1"
set "HOOK_PORT=%~2"
set "CONNECT_PORT=%~3"
set "LOCAL_HOST=%~4"
set "ACCESS_KEY=%~5"
set "INTERACTIVE_MODE="

if "%~1"=="" if "%~2"=="" if "%~3"=="" if "%~4"=="" if "%~5"=="" set "INTERACTIVE_MODE=1"

if defined INTERACTIVE_MODE (
  rem Double-click launches without arguments, so prompt here instead of failing fast and closing the window.
  echo [交互模式] 直接回车将使用方括号中的默认值。
  call :promptValue "remote domain" REMOTE_DOMAIN "p.ceroxe.top"
  call :promptValue "hook port" HOOK_PORT "55801"
  call :promptValue "connect port" CONNECT_PORT "55802"
  call :promptValue "local host" LOCAL_HOST "127.0.0.1"
  call :promptValue "access key" ACCESS_KEY ""
)

if not defined REMOTE_DOMAIN set "REMOTE_DOMAIN=p.ceroxe.top"
if not defined HOOK_PORT set "HOOK_PORT=55801"
if not defined CONNECT_PORT set "CONNECT_PORT=55802"
if not defined LOCAL_HOST set "LOCAL_HOST=127.0.0.1"

call :validatePort "%HOOK_PORT%" HOOK_PORT || (
  call :pauseIfNeeded
  exit /b 2
)
call :validatePort "%CONNECT_PORT%" CONNECT_PORT || (
  call :pauseIfNeeded
  exit /b 2
)

if not exist "%PROJECT_DIR%\gradlew.bat" (
  echo 缺少 Gradle Wrapper: "%PROJECT_DIR%\gradlew.bat"
  call :pauseIfNeeded
  exit /b 1
)

set "CP_FILE=%TEMP%\neolink-transparency-cp-%RANDOM%-%RANDOM%.txt"
call "%PROJECT_DIR%\gradlew.bat" -q --console=plain printTransparencyRuntimeClasspath > "%CP_FILE%" 2>&1
if errorlevel 1 (
  echo 获取运行时 classpath 失败。
  if exist "%CP_FILE%" type "%CP_FILE%"
  if exist "%CP_FILE%" del /q "%CP_FILE%" >nul 2>nul
  call :pauseIfNeeded
  exit /b 1
)

set /p "CP="<"%CP_FILE%"
if exist "%CP_FILE%" del /q "%CP_FILE%" >nul 2>nul
if not defined CP (
  echo 获取运行时 classpath 失败: 输出为空。
  call :pauseIfNeeded
  exit /b 1
)

set "RUN_DIR=%TEMP%\neolink-local-%RANDOM%-%RANDOM%-%RANDOM%"
mkdir "%RUN_DIR%" >nul 2>nul
if not exist "%RUN_DIR%" (
  echo 无法创建运行目录: %RUN_DIR%
  call :pauseIfNeeded
  exit /b 1
)

set "SOUT=%RUN_DIR%\server.out.log"
set "SERR=%RUN_DIR%\server.err.log"
set "COUT=%RUN_DIR%\client.out.log"
set "CERR=%RUN_DIR%\client.err.log"
set "SERVER_TAG=neolink.transparency.server.%RANDOM%%RANDOM%"
rem Tag the background JVM so cleanup only targets the server started by this script run.
set "SERVER_JAVA_OPTS=-Dcodex.run.tag=%SERVER_TAG%"

echo [参数] remote domain = %REMOTE_DOMAIN%
echo [参数] hook port = %HOOK_PORT%
echo [参数] connect port = %CONNECT_PORT%
echo [参数] local host = %LOCAL_HOST%
echo [参数] access key = %ACCESS_KEY%

rem Kill any leaked transparency server from a previous run still holding the fixed local port.
for /f "tokens=5" %%A in ('netstat -ano ^| findstr ":7777"') do (
  echo [清理] 发现残留进程占用端口 7777，PID=%%A，正在终止...
  taskkill /pid %%A /f >nul 2>nul
  >nul ping 127.0.0.1 -n 2
)

echo [构建] 正在通过 Gradle Wrapper 准备运行时依赖...

start "neolink-transparency-server" /b java %SERVER_JAVA_OPTS% -cp "%CP%" top.ceroxe.api.neolink.transparency.NeoLinkTransparencyServer "%REMOTE_DOMAIN%" "%HOOK_PORT%" "%CONNECT_PORT%" "%LOCAL_HOST%" "%ACCESS_KEY%" 1>"%SOUT%" 2>"%SERR%"

set "TUNADDR="
for /l %%I in (1,1,30) do (
  if exist "%SOUT%" (
    for /f "tokens=1,* delims==" %%A in ('findstr /c:"[server] tunnel address = " "%SOUT%"') do (
      set "TUNADDR=%%B"
    )
  )
  if defined TUNADDR goto gotTun

  rem If the server has already crashed, do not wait the full 30 seconds.
  if exist "%SERR%" for %%Z in ("%SERR%") do if %%~zZ GTR 0 (
    echo [错误] server 启动失败，错误输出：
    type "%SERR%"
    call :stopServer >nul 2>nul
    call :pauseIfNeeded
    exit /b 1
  )

  >nul ping 127.0.0.1 -n 2
)

:gotTun
echo [运行目录] %RUN_DIR%
if not defined TUNADDR (
  echo ==== server 标准输出 ====
  if exist "%SOUT%" type "%SOUT%"
  echo ==== server 错误输出 ====
  if exist "%SERR%" type "%SERR%"
  call :stopServer >nul 2>nul
  call :pauseIfNeeded
  exit /b 1
)

for /f "tokens=* delims= " %%A in ("%TUNADDR%") do set "TUNADDR=%%A"
echo [结果] TUNAddr = %TUNADDR%

java -cp "%CP%" top.ceroxe.api.neolink.transparency.NeoLinkTransparencyClient "%TUNADDR%" 1>"%COUT%" 2>"%CERR%"
set "CLIENT_EXIT=%ERRORLEVEL%"

call :stopServer >nul 2>nul

echo [退出码] client exit = %CLIENT_EXIT%
echo ==== client 标准输出 ====
if exist "%COUT%" type "%COUT%"
echo ==== client 错误输出 ====
if exist "%CERR%" type "%CERR%"
echo ==== server 标准输出 ====
if exist "%SOUT%" type "%SOUT%"
echo ==== server 错误输出 ====
if exist "%SERR%" type "%SERR%"

call :pauseIfNeeded
exit /b %CLIENT_EXIT%

:usage
echo 用法: run-transparency-check.cmd [remoteDomain] [hookPort] [connectPort] [localHost] [accessKey]
echo 示例: run-transparency-check.cmd p.ceroxe.top 55801 55802 127.0.0.1 YOUR_KEY
echo 说明: 不带参数双击运行时，将进入交互输入模式。
call :pauseIfNeeded
exit /b 2

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

:stopServer
rem Use netstat on the fixed local port instead of wmic commandline queries,
rem because wmic is deprecated on newer Windows and often fails silently.
for /f "tokens=5" %%A in ('netstat -ano ^| findstr ":7777"') do (
  taskkill /pid %%A /f >nul 2>nul
)
exit /b 0

:pauseIfNeeded
if defined INTERACTIVE_MODE pause
exit /b 0
