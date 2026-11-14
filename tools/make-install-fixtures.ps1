<#
.SYNOPSIS
Builds the tiny signed app packages that the app-install tests install on a real device.

.DESCRIPTION
The instrumented install tests need genuine, signed APKs: Android's own package manager
decides whether an install succeeds, so a stand-in file cannot exercise it. This script builds
them from the source below with the Android SDK's build tools (javac, d8, aapt2, zipalign and
apksigner) and writes them to app/src/androidTest/assets/install-fixtures. They are stored with
a .bin extension: they are test data, and .apk files are deliberately never committed here.

Each run makes two new signing keys and deletes them afterwards. Nothing secret is kept, and
the tests only depend on the packages agreeing with each other: version 1 and version 2 share
one key, and version 3 uses the other.

Every fixture is a test-only package (android:testOnly), which Android refuses to install
unless the installer explicitly allows test packages. The app always does, and the tests rely on
these packages to prove it.

.EXAMPLE
.\tools\make-install-fixtures.ps1 -Sdk $env:ANDROID_HOME -Jdk $env:JAVA_HOME
#>
param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$BuildTools = '36.0.0',
    [string]$Platform = 'android-36'
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk -or -not (Test-Path -LiteralPath $Sdk)) { throw 'Pass -Sdk (the Android SDK folder) or set ANDROID_HOME.' }
if (-not $Jdk -or -not (Test-Path -LiteralPath $Jdk)) { throw 'Pass -Jdk (a JDK 17 folder) or set JAVA_HOME.' }

$tools = Join-Path $Sdk "build-tools\$BuildTools"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
foreach ($required in @($tools, $androidJar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing SDK component: $required" }
}
$javac = Join-Path $Jdk 'bin\javac.exe'
$keytool = Join-Path $Jdk 'bin\keytool.exe'
$aapt2 = Join-Path $tools 'aapt2.exe'
$d8 = Join-Path $tools 'd8.bat'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$env:JAVA_HOME = $Jdk

$project = Split-Path -Parent $PSScriptRoot
$output = Join-Path $project 'app\src\androidTest\assets\install-fixtures'
$work = Join-Path ([IO.Path]::GetTempPath()) ("install-fixtures-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work, $output | Out-Null

function Invoke-Tool([string]$Tool, [string[]]$Arguments) {
    $text = & $Tool @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Tool)) failed ($LASTEXITCODE): $($text -join "`n")" }
}

try {
    $package = 'com.scrcpy.remote.installfixture'
    $sources = Join-Path $work 'src\com\scrcpy\remote\installfixture'
    New-Item -ItemType Directory -Force -Path $sources | Out-Null
    # An empty activity is enough: the tests only need something the launcher can open.
    Set-Content -LiteralPath (Join-Path $sources 'MainActivity.java') -Encoding ASCII -Value @"
package $package;

public final class MainActivity extends android.app.Activity {}
"@
    Set-Content -LiteralPath (Join-Path $work 'AndroidManifest.xml') -Encoding UTF8 -Value @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package">
    <application android:label="Install fixture" android:testOnly="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"@
    $classes = Join-Path $work 'classes'
    $dex = Join-Path $work 'dex'
    New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
    Invoke-Tool $javac @('-source', '8', '-target', '8', '-nowarn', '-Xlint:-options', '-cp', $androidJar,
        '-d', $classes, (Join-Path $sources 'MainActivity.java'))
    Invoke-Tool $d8 @('--release', '--min-api', '21', '--lib', $androidJar, '--output', $dex,
        (Join-Path $classes 'com\scrcpy\remote\installfixture\MainActivity.class'))

    $keys = @{}
    foreach ($key in 'a', 'b') {
        $store = Join-Path $work "key-$key.jks"
        Invoke-Tool $keytool @('-genkeypair', '-keystore', $store, '-storepass', 'fixture', '-keypass', 'fixture',
            '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-dname', "CN=Scrcpy Remote install fixture $key")
        $keys[$key] = $store
    }

    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $fixtures = @(
        @{ Name = 'install-fixture-v1.bin'; Code = 1; Version = '1.0'; Key = 'a' },
        @{ Name = 'install-fixture-v2.bin'; Code = 2; Version = '2.0'; Key = 'a' },
        @{ Name = 'install-fixture-v3-other-key.bin'; Code = 3; Version = '3.0'; Key = 'b' }
    )
    foreach ($fixture in $fixtures) {
        $base = Join-Path $work "base-$($fixture.Code).apk"
        Invoke-Tool $aapt2 @('link', '-I', $androidJar, '--manifest', (Join-Path $work 'AndroidManifest.xml'),
            '--min-sdk-version', '21', '--target-sdk-version', '35',
            '--version-code', "$($fixture.Code)", '--version-name', $fixture.Version, '-o', $base)
        $zip = [IO.Compression.ZipFile]::Open($base, [IO.Compression.ZipArchiveMode]::Update)
        try {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $dex 'classes.dex'), 'classes.dex') | Out-Null
        } finally { $zip.Dispose() }
        $aligned = Join-Path $work "aligned-$($fixture.Code).apk"
        Invoke-Tool $zipalign @('-p', '-f', '4', $base, $aligned)
        $signedApk = Join-Path $work "signed-$($fixture.Code).apk"
        Invoke-Tool $apksigner @('sign', '--ks', $keys[$fixture.Key], '--ks-pass', 'pass:fixture',
            '--key-pass', 'pass:fixture', '--out', $signedApk, $aligned)
        Invoke-Tool $apksigner @('verify', $signedApk)
        $signed = Join-Path $output $fixture.Name
        Copy-Item -LiteralPath $signedApk -Destination $signed -Force
        $hash = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Output ("{0}  {1} bytes  {2}" -f $fixture.Name, (Get-Item -LiteralPath $signed).Length, $hash)
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
