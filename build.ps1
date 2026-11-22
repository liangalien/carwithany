param(
    [switch]$Install,
    [string]$Device = '192.168.2.50:5555'
)

$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot

# SDK / JDK：可用环境变量覆盖，默认取本机常见路径
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { 'D:\soft\Android\SDK' }
$BuildTools = if ($env:BUILD_TOOLS_DIR) { $env:BUILD_TOOLS_DIR } else { Join-Path $Sdk 'build-tools\36.0.0' }
$AndroidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$Jbr = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\soft\AndroidStudio\jbr' }
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'

if (-not (Test-Path $AndroidJar)) {
    $plat = Get-ChildItem (Join-Path $Sdk 'platforms') -Directory | Sort-Object Name | Select-Object -Last 1
    $AndroidJar = Join-Path $plat.FullName 'android.jar'
}

$env:JAVA_HOME = $Jbr
$env:PATH = "$Jbr\bin;$env:PATH"

# 版本号：version.properties 是唯一来源，可用环境变量临时覆盖
$props = @{}
Get-Content (Join-Path $Root 'version.properties') | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$VersionName = if ($env:VERSION_NAME) { $env:VERSION_NAME } else { $props['VERSION_NAME'] }
$VersionCode = if ($env:VERSION_CODE) { $env:VERSION_CODE } else { $props['VERSION_CODE'] }
if (-not $VersionName) { $VersionName = '1.0' }
if (-not $VersionCode) { $VersionCode = '1' }

$Build = Join-Path $Root 'build'
$Out = Join-Path $Root 'out'
$Lib = Join-Path $Root 'libs\api-82.jar'
$Ks = if ($env:KEYSTORE) { $env:KEYSTORE } else { Join-Path $Root 'keystore\debug.keystore' }
$KsPass = if ($env:KS_PASS) { $env:KS_PASS } else { 'android' }
$KeyPass = if ($env:KEY_PASS) { $env:KEY_PASS } else { 'android' }
$KeyAlias = if ($env:KEY_ALIAS) { $env:KEY_ALIAS } else { 'androiddebugkey' }

if (Test-Path $Build) { Remove-Item -LiteralPath $Build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Build, $Out | Out-Null

if (-not (Test-Path $Lib)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Lib) | Out-Null
    Write-Host '[*] downloading Xposed api-82.jar'
    Invoke-WebRequest -Uri 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar' `
        -OutFile $Lib -UseBasicParsing
}

# 合并 classes.dex 到 aapt2 打出来的 base apk（不做 full rebuild，避免要 android.jar 全量资源）
$mergeScript = Join-Path $Build 'merge_dex.py'
@'
import sys, zipfile
src, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for ins in zin.infolist():
    if ins.filename == 'classes.dex':
        continue
    zout.writestr(ins, zin.read(ins.filename))
zin.close()
zout.write(dex, 'classes.dex')
zout.close()
'@ | Set-Content -LiteralPath $mergeScript -Encoding UTF8

function Build-Apk {
    param(
        [string]$Name,
        [string]$Manifest,
        [string]$SrcDir,
        [string]$ResDir,
        [string]$AssetsDir,
        [string]$ExtraClasspath,
        [string]$OutFile
    )

    $work = Join-Path $Build $Name
    $classes = Join-Path $work 'classes'
    $dexDir = Join-Path $work 'dex'
    New-Item -ItemType Directory -Force -Path $classes, $dexDir | Out-Null

    Write-Host "[$Name] javac"
    $sources = @(Get-ChildItem -LiteralPath $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) { throw "[$Name] no java sources" }
    $cp = $AndroidJar
    if ($ExtraClasspath -and (Test-Path $ExtraClasspath)) { $cp = "$AndroidJar;$ExtraClasspath" }
    & "$Jbr\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
        -classpath $cp -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw "[$Name] javac failed" }

    Write-Host "[$Name] d8"
    $classJar = Join-Path $work 'classes.jar'
    & "$Jbr\bin\jar.exe" cf $classJar -C $classes .
    & "$BuildTools\d8.bat" --release --min-api 29 --lib $AndroidJar `
        --output $dexDir $classJar
    if ($LASTEXITCODE -ne 0) { throw "[$Name] d8 failed" }

    $linkArgs = @(
        'link', '-o', (Join-Path $work 'base.apk'),
        '-I', $AndroidJar,
        '--manifest', $Manifest,
        '--min-sdk-version', '29',
        '--target-sdk-version', '33',
        '--version-code', "$VersionCode",
        '--version-name', $VersionName,
        '--auto-add-overlay'
    )
    if ((Test-Path $ResDir) -and (Get-ChildItem -LiteralPath $ResDir -Recurse -File)) {
        Write-Host "[$Name] aapt2 compile"
        $resZip = Join-Path $work 'res.zip'
        & "$BuildTools\aapt2.exe" compile --dir $ResDir -o $resZip
        if ($LASTEXITCODE -ne 0) { throw "[$Name] aapt2 compile failed" }
        $linkArgs += @('-R', $resZip)
    }
    if ((Test-Path $AssetsDir) -and (Get-ChildItem -LiteralPath $AssetsDir -Recurse -File)) {
        $linkArgs += @('-A', $AssetsDir)
    }

    Write-Host "[$Name] aapt2 link"
    & "$BuildTools\aapt2.exe" @linkArgs
    if ($LASTEXITCODE -ne 0) { throw "[$Name] aapt2 link failed" }

    Write-Host "[$Name] merge dex"
    python $mergeScript (Join-Path $work 'base.apk') (Join-Path $dexDir 'classes.dex') (Join-Path $work 'merged.apk')
    if ($LASTEXITCODE -ne 0) { throw "[$Name] dex merge failed" }

    Write-Host "[$Name] zipalign + sign"
    $aligned = Join-Path $work 'aligned.apk'
    & "$BuildTools\zipalign.exe" -f -p 4 (Join-Path $work 'merged.apk') $aligned
    if ($LASTEXITCODE -ne 0) { throw "[$Name] zipalign failed" }

    & "$BuildTools\apksigner.bat" sign `
        --ks $Ks --ks-pass "pass:$KsPass" --key-pass "pass:$KeyPass" --ks-key-alias $KeyAlias `
        --out $OutFile $aligned
    if ($LASTEXITCODE -ne 0) { throw "[$Name] apksigner failed" }

    $size = [math]::Round((Get-Item $OutFile).Length / 1KB, 1)
    Write-Host "[+] $Name -> $OutFile ($size KB)"
    return $OutFile
}

$moduleApk = Build-Apk -Name 'CarWithAny' `
    -Manifest (Join-Path $Root 'AndroidManifest.xml') `
    -SrcDir (Join-Path $Root 'src') `
    -ResDir (Join-Path $Root 'res') `
    -AssetsDir (Join-Path $Root 'assets') `
    -ExtraClasspath $Lib `
    -OutFile (Join-Path $Out "CarWithAny-$VersionName.apk")

$drawerApk = Build-Apk -Name 'AllAppsDrawer' `
    -Manifest (Join-Path $Root 'drawer\AndroidManifest.xml') `
    -SrcDir (Join-Path $Root 'drawer\src') `
    -ResDir (Join-Path $Root 'drawer\res') `
    -AssetsDir (Join-Path $Root 'drawer\assets') `
    -ExtraClasspath '' `
    -OutFile (Join-Path $Out "AllAppsDrawer-$VersionName.apk")

if ($Install) {
    Write-Host '[*] adb install'
    & $Adb -s $Device install -r $moduleApk
    & $Adb -s $Device install -r $drawerApk
}

Write-Host "完成：$Out（版本 $VersionName / $VersionCode）"
