$ErrorActionPreference = "Continue"
$base = "C:\alpha_ai\speedrun\setup"
$log = Join-Path $base "android-setup.log"
$status = Join-Path $base "android-setup.status"
Remove-Item $status -ErrorAction SilentlyContinue
"=== START $(Get-Date) ===" | Out-File $log
function Log($m){ $t = Get-Date -Format "HH:mm:ss"; "$t  $m" | Out-File -FilePath $log -Append; Write-Host "$t  $m" }

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$jbr = "C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_HOME = $jbr
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:Path = "$jbr\bin;$env:Path"
Log "SDK=$sdk"
Log "JAVA_HOME=$jbr"

# ---- 1) Install cmdline-tools if missing ----
$cltLatest = Join-Path $sdk "cmdline-tools\latest"
$sdkmanager = Join-Path $cltLatest "bin\sdkmanager.bat"
if (Test-Path $sdkmanager) {
    Log "cmdline-tools already present."
} else {
    Log "cmdline-tools missing; resolving download URL..."
    $file = $null
    try {
        $content = (Invoke-WebRequest "https://dl.google.com/android/repository/repository2-3.xml" -UseBasicParsing).Content
        $file = ([regex]::Matches($content, "commandlinetools-win-\d+_latest\.zip") | Select-Object -First 1).Value
    } catch { Log "XML fetch failed: $($_.Exception.Message)" }
    if (-not $file) { $file = "commandlinetools-win-13114758_latest.zip"; Log "Falling back to $file" }
    $url = "https://dl.google.com/android/repository/$file"
    $zip = Join-Path $env:TEMP $file
    Log "Downloading $url"
    Invoke-WebRequest $url -OutFile $zip
    $tmp = Join-Path $env:TEMP "clt_extract"
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path $cltLatest | Out-Null
    # the zip contains a top-level "cmdline-tools" folder; move its contents into latest\
    Get-ChildItem (Join-Path $tmp "cmdline-tools") | ForEach-Object { Move-Item $_.FullName -Destination $cltLatest -Force }
    if (Test-Path $sdkmanager) { Log "cmdline-tools installed OK." } else { Log "ERROR: sdkmanager not found after extract." }
}

# ---- 2) Accept licenses ----
Log "Accepting SDK licenses..."
$yes = ("y`r`n" * 60)
$yes | & $sdkmanager --sdk_root=$sdk --licenses 2>&1 | Tee-Object -FilePath $log -Append
Log "licenses exit: $LASTEXITCODE"

# ---- 3) Ensure required packages ----
Log "Installing/validating required SDK packages..."
& $sdkmanager --sdk_root=$sdk "platform-tools" "emulator" "platforms;android-36" "build-tools;36.0.0" "system-images;android-36;google_apis;x86_64" 2>&1 | Tee-Object -FilePath $log -Append
Log "packages exit: $LASTEXITCODE"

# ---- 4) Create an AVD if none exists ----
$avdmanager = Join-Path $cltLatest "bin\avdmanager.bat"
$avdName = "speedrun_api36"
$existing = & $avdmanager list avd 2>&1 | Out-String
if ($existing -match $avdName) {
    Log "AVD $avdName already exists."
} else {
    Log "Creating AVD $avdName from system-images;android-36;google_apis;x86_64 ..."
    "no`r`n" | & $avdmanager create avd -n $avdName -k "system-images;android-36;google_apis;x86_64" -d "pixel_6" 2>&1 | Tee-Object -FilePath $log -Append
    Log "avd create exit: $LASTEXITCODE"
    # Give the AVD more RAM + a writable data partition for smoother runs
    $cfg = "$env:USERPROFILE\.android\avd\$avdName.avd\config.ini"
    if (Test-Path $cfg) {
        Add-Content $cfg "hw.ramSize=3072"
        Add-Content $cfg "disk.dataPartition.size=4096M"
        Log "Tuned AVD config.ini (ram=3072, data=4096M)."
    }
}

Log "--- final AVD list ---"
& "$sdk\emulator\emulator.exe" -list-avds 2>&1 | Tee-Object -FilePath $log -Append

Log "=== DONE ==="
"DONE" | Out-File $status
