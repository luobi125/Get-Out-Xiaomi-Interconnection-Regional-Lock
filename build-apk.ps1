<#
.SYNOPSIS
    Build MirrorFix. No Gradle needed - only JDK + Android SDK build-tools.

.DESCRIPTION
    Pipeline: javac(stubs + module) -> d8(module classes only) -> aapt2
              -> inject classes.dex -> zipalign -> apksigner

    Most parameters are auto-detected. NOTE: this file is intentionally
    ASCII-only, because Windows PowerShell 5.1 reads .ps1 files without a
    BOM as ANSI, which corrupts non-ASCII comments and can break parsing.

.EXAMPLE
    .\build-apk.ps1

.EXAMPLE
    .\build-apk.ps1 -Ver 5.0 -Out build

.EXAMPLE
    .\build-apk.ps1 -AndroidJar "D:\Android\Sdk\platforms\android-34\android.jar" -FrameworkRes ".\framework-res.apk" -Keystore ".\debug.jks"
#>
[CmdletBinding()]
param(
    [string]$Ver = "5.0",
    [string]$Out = "build",

    # Leave empty to auto-detect.
    [string]$BuildTools,
    [string]$AndroidJar,
    [string]$FrameworkRes,
    [string]$Keystore,

    # Signing. If -Keystore does not exist, a debug key is generated with these.
    [string]$KsPass = "android",
    [string]$KeyAlias = "mirrorfix",
    [string]$KeyPass = "android"
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Info($msg) { Write-Host $msg }

# ------------------------------------------------------------------ 1. JDK
$javaHome = $env:JAVA_HOME
if ($javaHome -and (Test-Path (Join-Path $javaHome "bin\javac.exe"))) {
    $javac   = Join-Path $javaHome "bin\javac.exe"
    $keytool = Join-Path $javaHome "bin\keytool.exe"
} else {
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javacCmd) { Fail "javac not found. Set JAVA_HOME or add the JDK bin directory to PATH." }
    $javac   = $javacCmd.Source
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
}
Info "[1/8] JDK         : $javac"

# -------------------------------------------------------- 2. Android SDK
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }

if (-not $BuildTools) {
    if (-not $sdk) { Fail "Android SDK not found. Set ANDROID_HOME, or pass -BuildTools." }
    $btDir = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
             Sort-Object { try { [version]$_.Name } catch { [version]"0.0.0" } } -Descending |
             Select-Object -First 1
    if (-not $btDir) { Fail "No build-tools found under $sdk\build-tools" }
    $BuildTools = $btDir.FullName
}
foreach ($tool in @("aapt2.exe", "zipalign.exe")) {
    if (-not (Test-Path (Join-Path $BuildTools $tool))) { Fail "Missing $BuildTools\$tool" }
}
Info "[2/8] build-tools : $BuildTools"

if (-not $AndroidJar) {
    if (-not $sdk) { Fail "android.jar not found. Set ANDROID_HOME, or pass -AndroidJar." }
    $plat = Get-ChildItem (Join-Path $sdk "platforms") -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "android.jar") } |
            Sort-Object { try { [version]($_.Name -replace '^android-','') } catch { [version]"0" } } -Descending |
            Select-Object -First 1
    if (-not $plat) { Fail "No platform with android.jar found under $sdk\platforms" }
    $AndroidJar = Join-Path $plat.FullName "android.jar"
}
if (-not (Test-Path $AndroidJar)) { Fail "android.jar not found: $AndroidJar" }
Info "      android.jar : $AndroidJar"

# ---------------------------------------------------- 3. framework-res.apk
if (-not $FrameworkRes) {
    $cached = Join-Path $Root (Join-Path $Out "framework-res.apk")
    if (Test-Path $cached) {
        $FrameworkRes = $cached
    } else {
        $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
        if (-not $adb) { Fail "framework-res.apk is required. Pull it from a device (adb pull /system/framework/framework-res.apk .) and pass -FrameworkRes." }
        $dev = (& $adb devices) -split "`n" | Where-Object { $_ -match "\sdevice$" }
        if ($dev.Count -ne 1) { Fail "framework-res.apk is required and cannot be pulled automatically (need exactly one authorized device). Pass -FrameworkRes." }
        New-Item -ItemType Directory -Force -Path (Join-Path $Root $Out) | Out-Null
        Info "      pulling framework-res.apk from device ..."
        & $adb pull /system/framework/framework-res.apk $cached | Out-Null
        $FrameworkRes = $cached
    }
}
if (-not (Test-Path $FrameworkRes)) { Fail "framework-res.apk not found: $FrameworkRes" }
Info "[3/8] framework-res: $FrameworkRes"

# ------------------------------------------------------------- 4. keystore
if (-not $Keystore) { $Keystore = Join-Path $Root (Join-Path $Out "debug.jks") }
if (-not (Test-Path $Keystore)) {
    if (-not $keytool) { Fail "Keystore missing and keytool not found. Pass -Keystore with an existing keystore." }
    New-Item -ItemType Directory -Force -Path (Split-Path $Keystore -Parent) | Out-Null
    Info "      generating debug keystore: $Keystore"
    & $keytool -genkeypair -v -keystore $Keystore -alias $KeyAlias `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $KsPass -keypass $KeyPass `
        -dname "CN=MirrorFix, OU=Dev, O=MirrorFix, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "keytool failed." }
}
Info "[4/8] keystore    : $Keystore"

# ------------------------------------------------------------ 5. compile
$work = Join-Path $Root $Out
$cls  = Join-Path $work "classes"
$dex  = Join-Path $work "dex"
Remove-Item $cls, $dex -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $cls, $dex | Out-Null

$files = @()
$files += Get-ChildItem (Join-Path $Root "stubs") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$files += Get-ChildItem (Join-Path $Root "src")   -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($files.Count -eq 0) { Fail "No .java files under src/." }

Info "[5/8] javac       : $($files.Count) files"
# -parameters must stay: d8 throws an internal NullPointerException with -g:none / -g:source
& $javac --release 8 -nowarn -parameters -encoding UTF-8 -d $cls $files
if ($LASTEXITCODE -ne 0) { Fail "javac failed" }

# ----------------------------------------------------------------- 6. d8
# Only the module's own classes go into the dex. stubs/ is compile-time only.
$moduleCls = Join-Path $cls "com\dsh\mirrorfix"
if (-not (Test-Path $moduleCls)) { Fail "No com\dsh\mirrorfix output. Check the package name." }
$classFiles = Get-ChildItem $moduleCls -Filter *.class | ForEach-Object { $_.FullName }

Info "[6/8] d8          : $($classFiles.Count) classes"
$d8bat = Join-Path $BuildTools "d8.bat"
if (Test-Path $d8bat) {
    & $d8bat --min-api 29 --lib $AndroidJar --output $dex $classFiles
} else {
    & (Join-Path $BuildTools "d8") --min-api 29 --lib $AndroidJar --output $dex $classFiles
}
if ($LASTEXITCODE -ne 0) { Fail "d8 failed" }

# --------------------------------------------------------------- 7. aapt2
Info "[7/8] aapt2 compile + link"
$resZip  = Join-Path $work "res.zip"
$baseApk = Join-Path $work "base.apk"
& (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $Root "res") -o $resZip
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile failed" }

& (Join-Path $BuildTools "aapt2.exe") link -o $baseApk `
    -I $FrameworkRes `
    --manifest (Join-Path $Root "AndroidManifest.xml") `
    -A (Join-Path $Root "assets") `
    $resZip
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link failed" }

# ------------------------------------------------------ 8. package + sign
Info "[8/8] inject classes.dex + zipalign + apksigner"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$unsigned = Join-Path $work "unsigned.apk"
Copy-Item $baseApk $unsigned -Force
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
$zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' } | ForEach-Object { $_.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $dex "classes.dex"), "classes.dex") | Out-Null
$zip.Dispose()

$aligned = Join-Path $work "aligned.apk"
& (Join-Path $BuildTools "zipalign.exe") -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Fail "zipalign failed" }

$final = Join-Path $work "MirrorFix-$Ver.apk"
$signArgs = @("sign", "--ks", $Keystore, "--ks-pass", "pass:$KsPass", "--key-pass", "pass:$KeyPass")
if ($KeyAlias) { $signArgs += @("--ks-key-alias", $KeyAlias) }
$signArgs += @("--out", $final, $aligned)

& (Join-Path $BuildTools "apksigner.bat") @signArgs
if ($LASTEXITCODE -ne 0) { Fail "apksigner failed" }

Write-Host ""
Write-Host "OK -> $final  ($((Get-Item $final).Length) bytes)" -ForegroundColor Green
