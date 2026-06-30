$ErrorActionPreference = "Continue"
$base = "C:\alpha_ai\speedrun\setup"
$log = Join-Path $base "install-admin.log"
$status = Join-Path $base "install-admin.status"
Remove-Item $status -ErrorAction SilentlyContinue
function Log($m){ $t = Get-Date -Format "HH:mm:ss"; "$t  $m" | Tee-Object -FilePath $log -Append | Out-Null; Write-Host "$t  $m" }
"=== START $(Get-Date) ===" | Out-File $log

# 1) Visual Studio 2022 Build Tools with C++ workload (MSVC + Windows SDK)
Log "Installing Visual Studio 2022 Build Tools (C++ workload + recommended)... this is the big one."
winget install --id Microsoft.VisualStudio.2022.BuildTools -e --silent --accept-package-agreements --accept-source-agreements --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended" 2>&1 | Tee-Object -FilePath $log -Append
Log "VS Build Tools winget exit code: $LASTEXITCODE"

# 2) MSYS2 (provides rsync that Anki's build runner invokes)
Log "Installing MSYS2..."
winget install --id MSYS2.MSYS2 -e --silent --accept-package-agreements --accept-source-agreements 2>&1 | Tee-Object -FilePath $log -Append
Log "MSYS2 winget exit code: $LASTEXITCODE"

# 3) rsync (+git) inside MSYS2
if (Test-Path "C:\msys64\usr\bin\bash.exe") {
    Log "Installing rsync via pacman..."
    & "C:\msys64\usr\bin\bash.exe" -lc "pacman -Sy --noconfirm --needed rsync" 2>&1 | Tee-Object -FilePath $log -Append
    Log "pacman exit code: $LASTEXITCODE"
    if (Test-Path "C:\msys64\usr\bin\rsync.exe") { Log "rsync.exe present: OK" } else { Log "WARNING: rsync.exe NOT found after pacman" }
} else {
    Log "WARNING: C:\msys64\usr\bin\bash.exe not found; MSYS2 install may have failed."
}

# Verify VS / MSVC
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $vswhere) {
    $vc = & $vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>$null
    Log "VC tools install path: $vc"
} else {
    Log "vswhere not found"
}

Log "=== DONE ==="
"DONE" | Out-File $status
