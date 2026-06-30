@echo off
set CARGO_TARGET_DIR=%~dp0..\out\rust

REM === MSVC environment bootstrap ==========================================
REM This machine keeps C:\msys64\usr\bin on the system PATH so the web/aqt
REM build steps can find rsync. That same dir ships GNU coreutils' link.exe,
REM which shadows MSVC's link.exe in a normal shell and breaks Rust's MSVC
REM linking (errors like: link: extra operand '...rcgu.o' / Try 'link --help').
REM Calling vcvars64.bat prepends MSVC's bin dirs to PATH so the real
REM link.exe/cl.exe win, while rsync stays resolvable from msys later in PATH.
REM Guarded on VSCMD_VER so it only runs once per ninja.bat invocation.
if not defined VSCMD_VER (
    call :init_msvc || exit /b 1
)
REM ========================================================================

REM separate build+run steps so build env doesn't leak into subprocesses
cargo build -p runner --release || exit /b 1
out\rust\release\runner build %* || exit /b 1
exit /b 0

:init_msvc
REM Prefer vswhere; fall back to scanning well-known install dirs because
REM vswhere returns nothing on some installs (e.g. unregistered instances).
set "_vswhere=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%_vswhere%" (
    for /f "usebackq tokens=*" %%i in (`"%_vswhere%" -products * -latest -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2^>nul`) do (
        if exist "%%i\VC\Auxiliary\Build\vcvars64.bat" (
            call "%%i\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
            goto :init_msvc_done
        )
    )
)
for %%p in ("%ProgramFiles%\Microsoft Visual Studio\2022" "%ProgramFiles(x86)%\Microsoft Visual Studio\2022") do (
    for %%e in (Community Professional Enterprise BuildTools Preview) do (
        if exist "%%~p\%%e\VC\Auxiliary\Build\vcvars64.bat" (
            call "%%~p\%%e\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
            goto :init_msvc_done
        )
    )
)
echo ERROR: could not locate vcvars64.bat ^(MSVC C++ build tools^). 1>&2
exit /b 1

:init_msvc_done
if not defined VSCMD_VER (
    echo ERROR: vcvars64.bat ran but VSCMD_VER is not set. 1>&2
    exit /b 1
)
REM If no Windows SDK is installed, vcvars leaves WindowsSdkDir empty and the
REM MSVC linker can't find kernel32.lib / ucrt (LNK1181). Fall back to the
REM no-admin SDK fetched via xwin into out\winsdk\splat. Self-deactivating:
REM when a real SDK is present, WindowsSdkDir is set and this block is skipped.
if defined WindowsSdkDir if not "%WindowsSdkDir%"=="" exit /b 0
call :init_bundled_sdk
exit /b 0

:init_bundled_sdk
set "_sdk=%~dp0..\out\winsdk\splat\Windows Kits\10"
if not exist "%_sdk%\Lib" (
    echo WARNING: no Windows SDK from vcvars and no bundled SDK at "%_sdk%". 1>&2
    echo          MSVC linking will fail; see speedrun-architecture.mdc ^(xwin SDK^). 1>&2
    exit /b 0
)
REM Single version dir under Lib\ (e.g. 10.0.26100); prepend its um/ucrt to
REM LIB and the matching headers to INCLUDE so cl.exe/link.exe find them.
for /d %%v in ("%_sdk%\Lib\*") do (
    set "INCLUDE=%_sdk%\Include\%%~nxv\um;%_sdk%\Include\%%~nxv\ucrt;%_sdk%\Include\%%~nxv\shared;%_sdk%\Include\%%~nxv\winrt;%_sdk%\Include\%%~nxv\cppwinrt;%INCLUDE%"
    set "LIB=%%~fv\um\x64;%%~fv\ucrt\x64;%LIB%"
)
exit /b 0
