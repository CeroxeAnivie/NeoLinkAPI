@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" (
  set "DESKTOP_DIR=%SCRIPT_DIR:~0,-1%"
) else (
  set "DESKTOP_DIR=%SCRIPT_DIR%"
)

pushd "%DESKTOP_DIR%\.." >nul 2>nul
if errorlevel 1 (
  echo 无法进入 desktop 父目录: %DESKTOP_DIR%\..
  set "RUN_EXIT=1"
  goto finish
)
set "PROJECT_DIR=%CD%"
popd >nul 2>nul

cd /d "%PROJECT_DIR%"
if errorlevel 1 (
  echo 无法进入项目目录: %PROJECT_DIR%
  set "RUN_EXIT=1"
  goto finish
)

rem Resolve API version from shared/version.json for local builds.
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "(Get-Content '%PROJECT_DIR%\..\..\shared\version.json' | ConvertFrom-Json).apiVersion"`) do set "API_VERSION=%%A"

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
  rem Double-click launches without arguments, so prompt here instead of failing fast.
  echo [交互模式] 按 Enter 接受括号内的默认值。
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
  echo 参数无效: ACCESS_KEY 为必填项。
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
set "TEMP_DIR=%TEMP%"
set "TRANSPARENCY_BUILD_DIR=%DESKTOP_DIR%\build\transparency-check"
set "TRANSPARENCY_DEP_DIR=%TRANSPARENCY_BUILD_DIR%\deps"
set "TRANSPARENCY_MAIN_CLASSES=%TRANSPARENCY_BUILD_DIR%\classes\main"
set "TRANSPARENCY_TEST_CLASSES=%TRANSPARENCY_BUILD_DIR%\classes\test"
set "SHARED_SRC_DIR=%PROJECT_DIR%\shared\src\main\java"
set "DESKTOP_SRC_DIR=%DESKTOP_DIR%\src\main\java"
set "TRANSPARENCY_SRC_DIR=%DESKTOP_DIR%\src\test\java\top\ceroxe\api\neolink\transparency"
set "SHARED_RESOURCES_DIR=%PROJECT_DIR%\shared\src\main\resources"
set "DESKTOP_RESOURCES_DIR=%DESKTOP_DIR%\src\main\resources"
set "CP_FILE=%TEMP_DIR%\neolink-transparency-cp-%RANDOM%-%RANDOM%.txt"
set "JAVA_ARGS_FILE=%TEMP_DIR%\neolink-transparency-javaargs-%RANDOM%-%RANDOM%.txt"

call :resolveTransparencyClasspath
if errorlevel 1 (
  if exist "%CP_FILE%" del /q "%CP_FILE%" >nul 2>nul
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
echo [运行] 在当前控制台实时输出 server/client 日志...

rem Only kill a real listening owner on the configured local port.
rem TIME_WAIT rows often show the remote port with PID 0, which must never be treated as a leaked server process.
for /f "tokens=5" %%A in ('netstat -ano -p tcp ^| findstr /r /c:"^[ ]*TCP[ ]*[^ ]*:%LOCAL_PORT%[ ]*[^ ]*[ ]*LISTENING[ ]*[1-9][0-9]*$"') do (
  echo [清理] 发现 local port %LOCAL_PORT% 上存在残留进程，PID=%%A，正在终止...
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

java @"%JAVA_ARGS_FILE%"
set "RUN_EXIT=%ERRORLEVEL%"
if exist "%JAVA_ARGS_FILE%" del /q "%JAVA_ARGS_FILE%" >nul 2>nul

goto finish

:usage
echo 用法: run-transparency-check.cmd [remoteDomain] [hookPort] [connectPort] [accessKey] [localPort] [localHost]
echo 示例: run-transparency-check.cmd p.ceroxe.top 44801 44802 YOUR_KEY 7777 127.0.0.1
echo 提示: 不带参数双击即可进入交互模式。
set "RUN_EXIT=2"
goto finish

:promptValue
set "%~2=%~3"
set /p "USER_INPUT=%~1 [%~3]: "
if defined USER_INPUT set "%~2=%USER_INPUT%"
set "USER_INPUT="
exit /b 0

:resolveTransparencyClasspath
set "CP="
call :prepareTransparencyClasspathLocally
if errorlevel 1 (
  echo [构建] 本地 transparency runtime 准备失败，回退到 Gradle Wrapper...
  call :prepareTransparencyClasspathWithGradle
  exit /b %ERRORLEVEL%
)
if not defined CP (
  echo 解析 runtime classpath 失败: 本地结果为空。
  exit /b 1
)
exit /b 0

:prepareTransparencyClasspathLocally
where javac >nul 2>nul
if errorlevel 1 (
  echo 缺少 javac，无法编译本地 transparency runtime。
  exit /b 1
)
if not exist "%SHARED_SRC_DIR%" (
  echo 缺少 source directory: "%SHARED_SRC_DIR%"
  exit /b 1
)
if not exist "%DESKTOP_SRC_DIR%" (
  echo 缺少 source directory: "%DESKTOP_SRC_DIR%"
  exit /b 1
)
if not exist "%TRANSPARENCY_SRC_DIR%" (
  echo 缺少 transparency source directory: "%TRANSPARENCY_SRC_DIR%"
  exit /b 1
)

if exist "%TRANSPARENCY_BUILD_DIR%" (
    rmdir /s /q "%TRANSPARENCY_BUILD_DIR%" >nul 2>nul
    rem Windows 文件系统可能需要短暂时间释放句柄。
    >nul ping 127.0.0.1 -n 2
    if exist "%TRANSPARENCY_BUILD_DIR%" (
        echo [Build] 警告: 无法完全删除旧的 build 目录，可能某些文件仍被占用。尝试继续...
    )
)

mkdir "%TRANSPARENCY_DEP_DIR%" >nul 2>nul
if errorlevel 1 (
    >nul ping 127.0.0.1 -n 2
    mkdir "%TRANSPARENCY_DEP_DIR%" >nul 2>nul
)
if errorlevel 1 (
  echo 创建 directory 失败: "%TRANSPARENCY_DEP_DIR%"
  exit /b 1
)
mkdir "%TRANSPARENCY_MAIN_CLASSES%" >nul 2>nul
if errorlevel 1 (
    >nul ping 127.0.0.1 -n 2
    mkdir "%TRANSPARENCY_MAIN_CLASSES%" >nul 2>nul
)
if errorlevel 1 (
  echo 创建 directory 失败: "%TRANSPARENCY_MAIN_CLASSES%"
  exit /b 1
)
mkdir "%TRANSPARENCY_TEST_CLASSES%" >nul 2>nul
if errorlevel 1 (
    >nul ping 127.0.0.1 -n 2
    mkdir "%TRANSPARENCY_TEST_CLASSES%" >nul 2>nul
)
if errorlevel 1 (
  echo 创建 directory 失败: "%TRANSPARENCY_TEST_CLASSES%"
  exit /b 1
)

rem Mirror cached jars into a flat directory so cmd can use a simple wildcard classpath.
rem This avoids rebuilding a huge semicolon-joined classpath string on every run.
for /r "%GRADLE_USER_HOME%\caches\modules-2\files-2.1" %%I in (*.jar) do copy /y "%%I" "%TRANSPARENCY_DEP_DIR%\" >nul
if not exist "%TRANSPARENCY_DEP_DIR%\*.jar" (
  echo 在 "%GRADLE_USER_HOME%\caches\modules-2\files-2.1" 下未找到缓存的 dependency jars
  exit /b 1
)

set "MAIN_ARGS_FILE=%TEMP_DIR%\neolink-transparency-javac-main-%RANDOM%-%RANDOM%.txt"
set "TEST_ARGS_FILE=%TEMP_DIR%\neolink-transparency-javac-test-%RANDOM%-%RANDOM%.txt"
set "MAIN_LOG_FILE=%TEMP_DIR%\neolink-transparency-javac-main-%RANDOM%-%RANDOM%.log"
set "TEST_LOG_FILE=%TEMP_DIR%\neolink-transparency-javac-test-%RANDOM%-%RANDOM%.log"
set "DEPENDENCY_CP_FILE=%TEMP_DIR%\neolink-transparency-deps-%RANDOM%-%RANDOM%.txt"

call :writeDependencyClasspath "%TRANSPARENCY_DEP_DIR%" "%DEPENDENCY_CP_FILE%"
set "DEPENDENCY_CP="
set /p "DEPENDENCY_CP="<"%DEPENDENCY_CP_FILE%"
if exist "%DEPENDENCY_CP_FILE%" del /q "%DEPENDENCY_CP_FILE%" >nul 2>nul
if not defined DEPENDENCY_CP (
  echo 从 "%TRANSPARENCY_DEP_DIR%" 构建 dependency classpath 失败
  exit /b 1
)

rem The runner only needs desktop/shared main sources plus the dedicated transparency test sources.
rem Avoid compiling the whole test suite and pulling unrelated test runtime dependencies.
> "%MAIN_ARGS_FILE%" echo -encoding
>> "%MAIN_ARGS_FILE%" echo UTF-8
>> "%MAIN_ARGS_FILE%" echo -d
>> "%MAIN_ARGS_FILE%" echo %TRANSPARENCY_MAIN_CLASSES%
>> "%MAIN_ARGS_FILE%" echo -cp
>> "%MAIN_ARGS_FILE%" echo %DEPENDENCY_CP%
for /r "%SHARED_SRC_DIR%" %%I in (*.java) do >> "%MAIN_ARGS_FILE%" echo %%I
for /r "%DESKTOP_SRC_DIR%" %%I in (*.java) do >> "%MAIN_ARGS_FILE%" echo %%I

echo [构建] 使用本地 javac 编译 desktop/shared main sources...
javac @"%MAIN_ARGS_FILE%" > "%MAIN_LOG_FILE%" 2>&1
if errorlevel 1 (
  echo Local main-source 编译失败。
  if exist "%MAIN_LOG_FILE%" type "%MAIN_LOG_FILE%"
  exit /b 1
)

> "%TEST_ARGS_FILE%" echo -encoding
>> "%TEST_ARGS_FILE%" echo UTF-8
>> "%TEST_ARGS_FILE%" echo -d
>> "%TEST_ARGS_FILE%" echo %TRANSPARENCY_TEST_CLASSES%
>> "%TEST_ARGS_FILE%" echo -cp
>> "%TEST_ARGS_FILE%" echo %TRANSPARENCY_MAIN_CLASSES%;%DEPENDENCY_CP%
for /r "%TRANSPARENCY_SRC_DIR%" %%I in (*.java) do >> "%TEST_ARGS_FILE%" echo %%I

echo [构建] 使用本地 javac 编译 transparency runner sources...
javac @"%TEST_ARGS_FILE%" > "%TEST_LOG_FILE%" 2>&1
if errorlevel 1 (
  echo Local transparency-source 编译失败。
  if exist "%TEST_LOG_FILE%" type "%TEST_LOG_FILE%"
  exit /b 1
)

call :cleanupLocalCompileTemps

rem When compiling locally we bypass Gradle's processResources, so api.properties still
rem contains the raw ${version} placeholder. Write a substituted copy into the main
rem classes directory so VersionInfo reports the real version.
if exist "%SHARED_RESOURCES_DIR%\api.properties" (
    if defined API_VERSION (
        >nul 2>&1 powershell -NoProfile -Command "(Get-Content '%SHARED_RESOURCES_DIR%\api.properties') -replace '\$\{version\}', '%API_VERSION%' | Set-Content '%TRANSPARENCY_MAIN_CLASSES%\api.properties'"
        if errorlevel 1 (
            echo [构建] 警告: 替换 api.properties 中的 version 失败。
        )
    )
)

set "CP=%TRANSPARENCY_TEST_CLASSES%;%TRANSPARENCY_MAIN_CLASSES%"
if exist "%SHARED_RESOURCES_DIR%" set "CP=%CP%;%SHARED_RESOURCES_DIR%"
if exist "%DESKTOP_RESOURCES_DIR%" set "CP=%CP%;%DESKTOP_RESOURCES_DIR%"
set "CP=%CP%;%DEPENDENCY_CP%"
exit /b 0

:cleanupLocalCompileTemps
if exist "%MAIN_ARGS_FILE%" del /q "%MAIN_ARGS_FILE%" >nul 2>nul
if exist "%TEST_ARGS_FILE%" del /q "%TEST_ARGS_FILE%" >nul 2>nul
if exist "%MAIN_LOG_FILE%" del /q "%MAIN_LOG_FILE%" >nul 2>nul
if exist "%TEST_LOG_FILE%" del /q "%TEST_LOG_FILE%" >nul 2>nul
if exist "%DEPENDENCY_CP_FILE%" del /q "%DEPENDENCY_CP_FILE%" >nul 2>nul
exit /b 0

:writeDependencyClasspath
setlocal EnableDelayedExpansion
set "SOURCE_DIR=%~1"
set "OUTPUT_FILE=%~2"
set "WROTE_ONE="
> "%OUTPUT_FILE%" break
for %%I in ("%SOURCE_DIR%\*.jar") do (
  if defined WROTE_ONE (
    >> "%OUTPUT_FILE%" <nul set /p "=;%%~fI"
  ) else (
    > "%OUTPUT_FILE%" <nul set /p "=%%~fI"
    set "WROTE_ONE=1"
  )
)
endlocal
exit /b 0

:prepareTransparencyClasspathWithGradle
echo [构建] 通过 Gradle Wrapper 准备 runtime dependencies...
call "%PROJECT_DIR%\gradlew.bat" -p "%PROJECT_DIR%" -q --console=plain --daemon --build-cache :desktop:printTransparencyRuntimeClasspath > "%CP_FILE%" 2>&1
if errorlevel 1 (
  echo 解析 runtime classpath 失败。
  if exist "%CP_FILE%" type "%CP_FILE%"
  exit /b 1
)

set "CP="
set /p "CP="<"%CP_FILE%"
if exist "%CP_FILE%" del /q "%CP_FILE%" >nul 2>nul
if not defined CP (
  echo 解析 runtime classpath 失败: 输出为空。
  exit /b 1
)
exit /b 0

:validatePort
set "PORT_VALUE=%~1"
set "PORT_NAME=%~2"
for /f "delims=0123456789" %%A in ("%PORT_VALUE%") do (
  echo 无效的 %PORT_NAME% 值: %PORT_VALUE%
  exit /b 1
)
if "%PORT_VALUE%"=="" (
  echo 无效的 %PORT_NAME% 值: %PORT_VALUE%
  exit /b 1
)
set /a PORT_NUMBER=%PORT_VALUE% 2>nul
if %PORT_NUMBER% LSS 1 (
  echo 无效的 %PORT_NAME% 值: %PORT_VALUE%
  exit /b 1
)
if %PORT_NUMBER% GTR 65535 (
  echo 无效的 %PORT_NAME% 值: %PORT_VALUE%
  exit /b 1
)
exit /b 0

:pauseIfNeeded
if defined INTERACTIVE_MODE pause
exit /b 0

:finish
call :pauseIfNeeded
exit /b %RUN_EXIT%
