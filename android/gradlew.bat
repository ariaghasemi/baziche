@rem Baziche Gradle launcher for Windows (self-bootstrapping).
@rem Downloads Gradle 9.7.1 on first run via PowerShell. Requires Java 17+.
@echo off
setlocal
set GRADLE_VERSION=9.7.1
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST_DIR=%GRADLE_USER_HOME%\wrapper\dists\gradle-%GRADLE_VERSION%-bin
set GRADLE_BAT=%DIST_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if exist "%GRADLE_BAT%" goto execute
echo Downloading Gradle %GRADLE_VERSION% ...
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%DIST_DIR%\gradle-%GRADLE_VERSION%-bin.zip'; Expand-Archive -Path '%DIST_DIR%\gradle-%GRADLE_VERSION%-bin.zip' -DestinationPath '%DIST_DIR%' -Force"
if errorlevel 1 goto fail
:execute
"%GRADLE_BAT%" %*
goto end
:fail
echo Gradle bootstrap failed. 1>&2
exit /b 1
:end
endlocal
