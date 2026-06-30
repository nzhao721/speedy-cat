$ErrorActionPreference = "Continue"
$base = "C:\alpha_ai\speedrun\setup"
$log = Join-Path $base "install-admin2.log"
$status = Join-Path $base "install-admin2.status"
Remove-Item $status -ErrorAction SilentlyContinue
"=== START $(Get-Date) ===" | Out-File $log
function Log($m){ $t = Get-Date -Format "HH:mm:ss"; $line = "$t  $m"; $line | Out-File -FilePath $log -Append; Write-Host $line }

function Invoke-WingetWithRetry($id, $extra) {
    for ($i = 1; $i -le 3; $i++) {
        Log "winget install $id (attempt $i)..."
        $args = @("install","--id",$id,"-e","--silent","--accept-package-agreements","--accept-source-agreements")
        if ($extra) { $args += $extra }
        & winget @args 2>&1 | Tee-Object -FilePath $log -Append
        Log "$id winget exit code: $LASTEXITCODE"
        if ($LASTEXITCODE -eq 0 -or $LASTEXITCODE -eq -1978335189) { return $true }  # 0 ok, or 'already installed'
        Start-Sleep -Seconds 5
    }
    return $false
}

# ---- 1) MSYS2 (provides rsync that Anki's build runner invokes) ----
if (Test-Path "C:\msys64\usr\bin\bash.exe") {
    Log "MSYS2 already present at C:\msys64."
} else {
    $ok = Invoke-WingetWithRetry "MSYS2.MSYS2" $null
    if (-not (Test-Path "C:\msys64\usr\bin\bash.exe")) {
        Log "winget MSYS2 failed; trying direct installer download from GitHub..."
        try {
            $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/msys2/msys2-installer/releases/latest" -Headers @{ "User-Agent" = "ps" }
            $asset = $rel.assets | Where-Object { $_.name -match "^msys2-x86_64-\d+\.exe$" } | Select-Object -First 1
            if ($asset) {
                $exe = Join-Path $env:TEMP $asset.name
                Log "Downloading $($asset.browser_download_url)"
                Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $exe
                Log "Running MSYS2 installer headless..."
                & $exe in --confirm-command --accept-messages --root C:/msys64 2>&1 | Tee-Object -FilePath $log -Append
            } else { Log "Could not find MSYS2 installer asset." }
        } catch { Log "Direct MSYS2 download failed: $($_.Exception.Message)" }
    }
}

# ---- 2) rsync (+git) inside MSYS2 ----
if (Test-Path "C:\msys64\usr\bin\bash.exe") {
    Log "Installing rsync via pacman..."
    & "C:\msys64\usr\bin\bash.exe" -lc "pacman -Sy --noconfirm --needed rsync" 2>&1 | Tee-Object -FilePath $log -Append
    Log "pacman exit code: $LASTEXITCODE"
    if (Test-Path "C:\msys64\usr\bin\rsync.exe") { Log "rsync.exe present: OK" } else { Log "WARNING: rsync.exe NOT found after pacman" }

    # ---- 3) Add C:\msys64\usr\bin to MACHINE PATH (appended, so Windows git keeps precedence) ----
    $mp = [Environment]::GetEnvironmentVariable("Path","Machine")
    if ($mp -notlike "*C:\msys64\usr\bin*") {
        [Environment]::SetEnvironmentVariable("Path", ($mp.TrimEnd(';') + ";C:\msys64\usr\bin"), "Machine")
        Log "Appended C:\msys64\usr\bin to Machine PATH."
    } else { Log "C:\msys64\usr\bin already on Machine PATH." }
} else {
    Log "ERROR: MSYS2 bash not found; rsync NOT installed."
}

# ---- 4) PowerShell 7 (pwsh) - needed by Anki's justfile windows-shell ----
if (Get-Command pwsh -ErrorAction SilentlyContinue) {
    Log "pwsh already present."
} else {
    Invoke-WingetWithRetry "Microsoft.PowerShell" $null | Out-Null
}

# ---- Summary ----
$rsyncOk = Test-Path "C:\msys64\usr\bin\rsync.exe"
Log "SUMMARY: rsync=$rsyncOk"
Log "=== DONE ==="
if ($rsyncOk) { "DONE" | Out-File $status } else { "FAILED" | Out-File $status }
