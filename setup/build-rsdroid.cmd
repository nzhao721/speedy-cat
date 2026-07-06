@echo off
setlocal EnableDelayedExpansion

set "ROOT=C:\alpha_ai\speedrun"

echo [%DATE% %TIME%] build-rsdroid.cmd starting

set "PATH=%PATH:C:\msys64\usr\bin;=%"
set "PATH=%PATH:;C:\msys64\usr\bin=%"

call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if errorlevel 1 (
  echo ERROR: vcvars64 failed
  exit /b 1
)

set "PATH=%PATH:C:\msys64\usr\bin;=%"
set "PATH=%PATH:;C:\msys64\usr\bin=%"

if defined WindowsSdkDir if not "!WindowsSdkDir!"=="" goto sdk_done
set "_sdk=%ROOT%\anki\out\winsdk\splat\Windows Kits\10"
set "_hdr_store=%ROOT%\anki\out\winsdk\cache\unpack\Win11SDK_10.0.22621_store_headers.msi\include"
set "_hdr_splat=%ROOT%\anki\out\winsdk\splat\Windows Kits\10\Include\10.0.22621"

if exist "!_sdk!\Lib" (
  echo Using bundled SDK libs at "!_sdk!"
  for /d %%v in ("!_sdk!\Lib\*") do (
    set "LIB=%%~fv\um\x64;%%~fv\ucrt\x64;!LIB!"
  )
)
if exist "!_hdr_store!\um" (
  echo Using bundled SDK headers from store cache
  set "INCLUDE=!_hdr_store!\um;!_hdr_store!\shared;!_hdr_store!\winrt;!_hdr_store!\cppwinrt;!_hdr_splat!\ucrt;!INCLUDE!"
) else if exist "!_hdr_splat!\um" (
  echo Using bundled SDK headers from splat
  for /d %%v in ("!_sdk!\Include\*") do (
    set "INCLUDE=!_sdk!\Include\%%~nxv\um;!_sdk!\Include\%%~nxv\ucrt;!_sdk!\Include\%%~nxv\shared;!_sdk!\Include\%%~nxv\winrt;!_sdk!\Include\%%~nxv\cppwinrt;!INCLUDE!"
  )
) else (
  echo WARNING: bundled SDK headers not found
)
:sdk_done

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_NDK_HOME=C:\Users\natha\AppData\Local\Android\Sdk\ndk\29.0.14206865"
set "PATH=%USERPROFILE%\.cargo\bin;!PATH!"
set "PATH=!PATH!;C:\msys64\usr\bin"
set "CARGO_TARGET_DIR=%ROOT%\rsdroid-build\target-host"

cd /d "%ROOT%\rsdroid-build"
if errorlevel 1 (
  echo ERROR: cd to rsdroid-build failed
  exit /b 1
)

echo JAVA_HOME=!JAVA_HOME!
echo ANDROID_HOME=!ANDROID_HOME!
echo ANDROID_NDK_HOME=!ANDROID_NDK_HOME!
echo CWD=!CD!

rem Physical devices need arm64-v8a; Windows default rsdroid build is x86_64-only.
set "ALL_ARCHS=1"
echo ALL_ARCHS=!ALL_ARCHS!

cargo run -p build_rust
set "BUILD_ERR=!ERRORLEVEL!"
echo [%DATE% %TIME%] cargo exit code !BUILD_ERR!
exit /b !BUILD_ERR!
