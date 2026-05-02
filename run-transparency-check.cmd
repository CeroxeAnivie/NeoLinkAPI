@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d D:\Engineering\code\APIs\NeoLinkAPI

set "REMOTE_DOMAIN=%~1"
set "HOOK_PORT=%~2"
set "CONNECT_PORT=%~3"
set "LOCAL_HOST=%~4"

if "%~5" neq "" goto usage
if not defined REMOTE_DOMAIN set "REMOTE_DOMAIN=p.ceroxe.top"
if not defined HOOK_PORT set "HOOK_PORT=55801"
if not defined CONNECT_PORT set "CONNECT_PORT=55802"
if not defined LOCAL_HOST set "LOCAL_HOST=127.0.0.1"

call :validatePort "%HOOK_PORT%" HOOK_PORT || exit /b 2
call :validatePort "%CONNECT_PORT%" CONNECT_PORT || exit /b 2

set "CP=D:\Engineering\code\APIs\NeoLinkAPI\build\classes\java\main;D:\Engineering\code\APIs\NeoLinkAPI\build\resources\main;D:\Engineering\code\APIs\NeoLinkAPI\build\classes\java\test;C:\Users\Administrator\.gradle\caches\modules-2\files-2.1\top.ceroxe.api\ceroxe-core\2.0.0\46011869793613ee1901b4c45ac03807126c3d6e\ceroxe-core-2.0.0.jar;C:\Users\Administrator\.gradle\caches\modules-2\files-2.1\top.ceroxe.api\ceroxe-detector\2.0.0\da124ab3dfb9eeb39d7493cf645c4d0365c3d2ca\ceroxe-detector-2.0.0.jar;C:\Users\Administrator\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.11.0\527175ca6d81050b53bdd4c457a6d6e017626b0e\gson-2.11.0.jar"
set "RUN_DIR=%TEMP%\neolink-local-%DATE:~0,4%%DATE:~5,2%%DATE:~8,2%-%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%"
set "RUN_DIR=%RUN_DIR: =0%"
mkdir "%RUN_DIR%" 2>nul

set "SOUT=%RUN_DIR%\server.out.log"
set "SERR=%RUN_DIR%\server.err.log"
set "COUT=%RUN_DIR%\client.out.log"
set "CERR=%RUN_DIR%\client.err.log"
set "SERVER_TAG=neolink.transparency.server"
set "SERVER_JAVA_OPTS=-Dcodex.run.tag=%SERVER_TAG%"

echo [参数] remote domain = %REMOTE_DOMAIN%
echo [参数] hook port = %HOOK_PORT%
echo [参数] connect port = %CONNECT_PORT%
echo [参数] local host = %LOCAL_HOST%

start "neolink-transparency-server" /b java %SERVER_JAVA_OPTS% -cp "%CP%" top.ceroxe.api.neolink.transparency.NeoLinkTransparencyServer "%REMOTE_DOMAIN%" "%HOOK_PORT%" "%CONNECT_PORT%" "%LOCAL_HOST%" 1>"%SOUT%" 2>"%SERR%"

set "TUNADDR="
for /l %%i in (1,1,30) do (
  if exist "%SOUT%" (
    for /f "tokens=2 delims==" %%A in ('findstr /c:"[server] tunnel address = " "%SOUT%"') do set "TUNADDR=%%A"
  )
  if defined TUNADDR goto gotTun
  ping -n 2 127.0.0.1 >nul
)

:gotTun
echo [运行目录] %RUN_DIR%
if not defined TUNADDR (
  echo ==== server 标准输出 ====
  if exist "%SOUT%" type "%SOUT%"
  echo ==== server 错误输出 ====
  if exist "%SERR%" type "%SERR%"
  exit /b 1
)

echo [结果] TUNAddr = %TUNADDR%
java -cp "%CP%" top.ceroxe.api.neolink.transparency.NeoLinkTransparencyClient %TUNADDR% 1>"%COUT%" 2>"%CERR%"
set "CLIENT_EXIT=%ERRORLEVEL%"
for /f "tokens=2 delims==; " %%P in ('wmic process where "name='java.exe' and commandline like '%%-Dcodex.run.tag=%SERVER_TAG%%%'" get ProcessId /value 2^>nul ^| find "="') do taskkill /pid %%P /f >nul 2>nul
echo [退出码] client exit = %CLIENT_EXIT%
echo ==== client 标准输出 ====
if exist "%COUT%" type "%COUT%"
echo ==== client 错误输出 ====
if exist "%CERR%" type "%CERR%"
echo ==== server 标准输出 ====
if exist "%SOUT%" type "%SOUT%"
echo ==== server 错误输出 ====
if exist "%SERR%" type "%SERR%"
exit /b %CLIENT_EXIT%

:usage
echo 用法: run-transparency-check.cmd [remoteDomain] [hookPort] [connectPort] [localHost]
echo 示例: run-transparency-check.cmd p.ceroxe.top 55801 55802 127.0.0.1
exit /b 2

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
