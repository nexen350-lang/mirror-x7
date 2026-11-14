<#
.SYNOPSIS
Builds the app bundles that the bundle-install tests read and install on a real device.

.DESCRIPTION
An app bundle is several signed APKs that only install together, and which of them a device
needs depends on its processor, screen density and languages. The tests need genuine bundles,
built the way real ones are, so this script makes a tiny app with native libraries for three
processors, pictures for two densities and a French translation, and has bundletool split it.

It writes four files to app/src/androidTest/assets/install-fixtures, with a .bin extension
because they are test data:

- bundle-fixture.apks.bin: bundletool's own APK set (build-apks), with its table of contents.
- bundle-fixture.xapk.bin: the same splits as an XAPK, with the manifest.json those carry.
- bundle-fixture.apkm.bin: the same splits as an APKM, with the info.json those carry.
- bundle-fixture-riscv.apks.bin: an APK set whose native code is only for RISC-V, which no
  test device runs, so a correct installer must refuse it rather than install a partial app.

bundletool is taken from the Gradle cache, where the Android Gradle plugin keeps it with its
dependencies once the project has been built; pass -BundletoolClasspath to use another copy.
Each run makes a new signing key and deletes it afterwards. Every fixture is a test-only
package (android:testOnly), as the single-APK fixtures are.

.EXAMPLE
.\tools\make-bundle-fixtures.ps1 -Sdk $env:ANDROID_HOME -Jdk $env:JAVA_HOME
#>
param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$BuildTools = '36.0.0',
    [string]$Platform = 'android-36',
    [string]$BundletoolClasspath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk -or -not (Test-Path -LiteralPath $Sdk)) { throw 'Pass -Sdk (the Android SDK folder) or set ANDROID_HOME.' }
if (-not $Jdk -or -not (Test-Path -LiteralPath $Jdk)) { throw 'Pass -Jdk (a JDK 17 folder) or set JAVA_HOME.' }

$tools = Join-Path $Sdk "build-tools\$BuildTools"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
foreach ($required in @($tools, $androidJar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing SDK component: $required" }
}
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
$keytool = Join-Path $Jdk 'bin\keytool.exe'
$aapt2 = Join-Path $tools 'aapt2.exe'
$d8 = Join-Path $tools 'd8.bat'
$env:JAVA_HOME = $Jdk

if (-not $BundletoolClasspath) {
    # bundletool and the libraries it needs, as the Android Gradle plugin caches them.
    $cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
    $artifacts = @(
        'com.android.tools.build\bundletool', 'com.android.tools.build\aapt2-proto', 'com.google.auto.value\auto-value-annotations',
        'com.google.errorprone\error_prone_annotations', 'com.google.guava\guava', 'com.google.guava\failureaccess',
        'com.google.protobuf\protobuf-java', 'com.google.protobuf\protobuf-java-util', 'com.google.code.gson\gson',
        'com.google.dagger\dagger', 'javax.inject\javax.inject', 'org.bitbucket.b_c\jose4j', 'org.slf4j\slf4j-api')
    $jars = foreach ($artifact in $artifacts) {
        $name = Split-Path $artifact -Leaf
        $found = Get-ChildItem (Join-Path $cache $artifact) -Recurse -Filter "$name-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Sort-Object {
                $number = $_.Directory.Parent.Name -replace '[^0-9.].*$', ''
                [version]$(if ($number -match '\.') { $number } else { "$number.0" })
            } | Select-Object -Last 1
        if (-not $found) { throw "bundletool needs $artifact, which is not in the Gradle cache. Build the project once, or pass -BundletoolClasspath." }
        $found.FullName
    }
    $BundletoolClasspath = $jars -join ';'
}

$project = Split-Path -Parent $PSScriptRoot
$output = Join-Path $project 'app\src\androidTest\assets\install-fixtures'
$work = Join-Path ([IO.Path]::GetTempPath()) ("bundle-fixtures-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work, $output | Out-Null

function Invoke-Tool([string]$Tool, [string[]]$Arguments) {
    $text = & $Tool @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Tool)) failed ($LASTEXITCODE): $($text -join "`n")" }
}
function Invoke-Bundletool([string[]]$Arguments) {
    Invoke-Tool $java (@('-cp', $BundletoolClasspath, 'com.android.tools.build.bundletool.BundleToolMain') + $Arguments)
}

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem, System.Drawing
try {
    $package = 'com.scrcpy.remote.bundlefixture'
    $sources = Join-Path $work 'src\com\scrcpy\remote\bundlefixture'
    New-Item -ItemType Directory -Force -Path $sources | Out-Null
    Set-Content -LiteralPath (Join-Path $sources 'MainActivity.java') -Encoding ASCII -Value @"
package $package;

public final class MainActivity extends android.app.Activity {}
"@
    $classes = Join-Path $work 'classes'
    $dex = Join-Path $work 'dex'
    New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
    Invoke-Tool $javac @('-source', '8', '-target', '8', '-nowarn', '-Xlint:-options', '-cp', $androidJar,
        '-d', $classes, (Join-Path $sources 'MainActivity.java'))
    Invoke-Tool $d8 @('--release', '--min-api', '21', '--lib', $androidJar, '--output', $dex,
        (Join-Path $classes 'com\scrcpy\remote\bundlefixture\MainActivity.class'))

    # A translation and a picture in two densities, so bundletool makes language and density splits.
    $res = Join-Path $work 'res'
    foreach ($folder in 'values', 'values-fr', 'drawable-mdpi', 'drawable-xxhdpi') {
        New-Item -ItemType Directory -Force -Path (Join-Path $res $folder) | Out-Null
    }
    Set-Content -LiteralPath (Join-Path $res 'values\strings.xml') -Encoding UTF8 -Value @'
<?xml version="1.0" encoding="utf-8"?>
<resources><string name="app_name">Bundle fixture</string></resources>
'@
    Set-Content -LiteralPath (Join-Path $res 'values-fr\strings.xml') -Encoding UTF8 -Value @'
<?xml version="1.0" encoding="utf-8"?>
<resources><string name="app_name">Appli en plusieurs parties</string></resources>
'@
    foreach ($density in @(@{ Folder = 'drawable-mdpi'; Size = 48 }, @{ Folder = 'drawable-xxhdpi'; Size = 144 })) {
        $bitmap = [Drawing.Bitmap]::new($density.Size, $density.Size)
        try {
            $graphics = [Drawing.Graphics]::FromImage($bitmap)
            try { $graphics.Clear([Drawing.Color]::FromArgb(255, 0, 120, 212)) } finally { $graphics.Dispose() }
            $bitmap.Save((Join-Path $res "$($density.Folder)\marker.png"), [Drawing.Imaging.ImageFormat]::Png)
        } finally { $bitmap.Dispose() }
    }
    Set-Content -LiteralPath (Join-Path $work 'AndroidManifest.xml') -Encoding UTF8 -Value @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package">
    <application android:label="@string/app_name" android:testOnly="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"@
    $compiled = Join-Path $work 'compiled.zip'
    Invoke-Tool $aapt2 @('compile', '--dir', $res, '-o', $compiled)
    $linked = Join-Path $work 'linked.zip'
    Invoke-Tool $aapt2 @('link', '--proto-format', '-I', $androidJar, '--manifest', (Join-Path $work 'AndroidManifest.xml'),
        '--min-sdk-version', '21', '--target-sdk-version', '35', '--version-code', '1', '--version-name', '1.0',
        '-o', $linked, $compiled)

    $keystore = Join-Path $work 'bundle-key.jks'
    Invoke-Tool $keytool @('-genkeypair', '-keystore', $keystore, '-storepass', 'fixture', '-keypass', 'fixture',
        '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-dname', 'CN=Scrcpy Remote bundle fixture')

    # One base module per bundle: the linked manifest and resources in bundletool's module layout,
    # the code, and a placeholder native library for each processor named. The package manager
    # copies a library without loading it, which is all an install test needs.
    function New-ApkSet([string]$Name, [string[]]$Abis) {
        $module = Join-Path $work "$Name-base.zip"
        $source = [IO.Compression.ZipFile]::OpenRead($linked)
        $target = [IO.Compression.ZipFile]::Open($module, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($entry in $source.Entries) {
                $path = if ($entry.FullName -eq 'AndroidManifest.xml') { 'manifest/AndroidManifest.xml' } else { $entry.FullName }
                $copy = $target.CreateEntry($path)
                $in = $entry.Open(); $out = $copy.Open()
                try { $in.CopyTo($out) } finally { $out.Dispose(); $in.Dispose() }
            }
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($target, (Join-Path $dex 'classes.dex'), 'dex/classes.dex') | Out-Null
            foreach ($abi in $Abis) {
                $library = $target.CreateEntry("lib/$abi/libbundlefixture.so")
                $out = $library.Open()
                try { $bytes = [Text.Encoding]::ASCII.GetBytes("placeholder library for $abi"); $out.Write($bytes, 0, $bytes.Length) }
                finally { $out.Dispose() }
            }
        } finally { $target.Dispose(); $source.Dispose() }
        $bundle = Join-Path $work "$Name.aab"
        Invoke-Bundletool @('build-bundle', "--modules=$module", "--output=$bundle")
        $apks = Join-Path $work "$Name.apks"
        Invoke-Bundletool @('build-apks', "--bundle=$bundle", "--output=$apks", "--aapt2=$aapt2",
            "--ks=$keystore", '--ks-pass=pass:fixture', '--ks-key-alias=fixture', '--key-pass=pass:fixture')
        $apks
    }

    $apkSet = New-ApkSet 'bundle-fixture' @('x86_64', 'arm64-v8a', 'armeabi-v7a')
    $riscv = New-ApkSet 'bundle-fixture-riscv' @('riscv64')

    # The fields of bundletool's table of contents (toc.pb) that say which splits make up each
    # variant: BuildApksResult.variant (1) > Variant.targeting (1) > sdk_version_targeting (1) >
    # value (1) > min (1) > value (1); Variant.apk_set (2) > apk_description (2) > path (2) and
    # split_apk_metadata (3) > split_id (1).
    function Read-Fields([byte[]]$Bytes) {
        $fields = @(); $i = 0
        while ($i -lt $Bytes.Length) {
            $key = 0L; $shift = 0
            do { $b = $Bytes[$i++]; $key = $key -bor ([long]($b -band 0x7F) -shl $shift); $shift += 7 } while ($b -band 0x80)
            $number = [int]($key -shr 3); $type = [int]($key -band 7)
            switch ($type) {
                0 { $v = 0L; $shift = 0; do { $b = $Bytes[$i++]; $v = $v -bor ([long]($b -band 0x7F) -shl $shift); $shift += 7 } while ($b -band 0x80)
                    $fields += [pscustomobject]@{ Number = $number; Value = $v; Bytes = $null } }
                2 { $len = 0L; $shift = 0; do { $b = $Bytes[$i++]; $len = $len -bor ([long]($b -band 0x7F) -shl $shift); $shift += 7 } while ($b -band 0x80)
                    $chunk = [byte[]]::new($len); [Array]::Copy($Bytes, $i, $chunk, 0, $len); $i += $len
                    $fields += [pscustomobject]@{ Number = $number; Value = $null; Bytes = $chunk } }
                5 { $i += 4 }
                1 { $i += 8 }
                default { throw "Unexpected protobuf wire type $type in toc.pb" }
            }
        }
        $fields
    }
    function Field($Fields, [int]$Number) { @($Fields | Where-Object { $_.Number -eq $Number }) }
    function Read-Variants([string]$ApkSetPath) {
        $zip = [IO.Compression.ZipFile]::OpenRead($ApkSetPath)
        try {
            $stream = $zip.GetEntry('toc.pb').Open(); $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            foreach ($variant in (Field (Read-Fields $memory.ToArray()) 1)) {
                $parts = Read-Fields $variant.Bytes
                $minimum = 0
                foreach ($targeting in (Field $parts 1)) {
                    foreach ($sdk in (Field (Read-Fields $targeting.Bytes) 1)) {
                        foreach ($value in (Field (Read-Fields $sdk.Bytes) 1)) {
                            foreach ($min in (Field (Read-Fields $value.Bytes) 1)) {
                                $minimum = [int](Field (Read-Fields $min.Bytes) 1)[0].Value
                            }
                        }
                    }
                }
                $splits = @()
                foreach ($set in (Field $parts 2)) {
                    foreach ($description in (Field (Read-Fields $set.Bytes) 2)) {
                        $fields = Read-Fields $description.Bytes
                        $path = [Text.Encoding]::UTF8.GetString((Field $fields 2)[0].Bytes)
                        $metadata = Field $fields 3
                        $id = 'base'
                        if ($metadata.Count -gt 0) {
                            $splitId = Field (Read-Fields $metadata[0].Bytes) 1
                            if ($splitId.Count -gt 0) { $id = [Text.Encoding]::UTF8.GetString($splitId[0].Bytes) }
                        }
                        $splits += [pscustomobject]@{ Path = $path; Id = $id }
                    }
                }
                [pscustomobject]@{ MinSdk = $minimum; Splits = $splits }
            }
        } finally { $zip.Dispose() }
    }

    # XAPK and APKM files carry one device-independent set of splits from a store. Both are made
    # here from the APK set's newest variant, renamed the way each format names its files.
    $newest = @(Read-Variants $apkSet | Sort-Object MinSdk)[-1]
    $set = [IO.Compression.ZipFile]::OpenRead($apkSet)
    try {        foreach ($format in 'xapk', 'apkm') {
            $file = Join-Path $work "bundle-fixture.$format"
            $zip = [IO.Compression.ZipFile]::Open($file, [IO.Compression.ZipArchiveMode]::Create)
            try {
                $entries = @()
                foreach ($split in $newest.Splits) {
                    $id = $split.Id
                    $name = if ($format -eq 'xapk') {
                        if ($id -eq 'base') { "$package.apk" } else { "$id.apk" }
                    } else {
                        if ($id -eq 'base') { 'base.apk' } else { "split_$id.apk" }
                    }
                    $entries += [ordered]@{ file = $name; id = $id }
                    $copy = $zip.CreateEntry($name)
                    $in = $set.GetEntry($split.Path).Open(); $out = $copy.Open()
                    try { $in.CopyTo($out) } finally { $out.Dispose(); $in.Dispose() }
                }
                $metadata = if ($format -eq 'xapk') {
                    [ordered]@{ xapk_version = 2; package_name = $package; name = 'Bundle fixture'; version_code = '1'
                        version_name = '1.0'; min_sdk_version = '21'; target_sdk_version = '35'
                        split_configs = @($entries | Where-Object { $_.id -ne 'base' } | ForEach-Object { $_.id })
                        split_apks = $entries }
                } else {
                    [ordered]@{ apkm_version = 5; app_name = 'Bundle fixture'; pname = $package; versioncode = '1'
                        release_version = '1.0'; min_api = '21'; arches = @('x86_64', 'arm64-v8a', 'armeabi-v7a') }
                }
                $info = $zip.CreateEntry($(if ($format -eq 'xapk') { 'manifest.json' } else { 'info.json' }))
                $out = $info.Open()
                try { $bytes = [Text.Encoding]::UTF8.GetBytes(($metadata | ConvertTo-Json -Depth 4)); $out.Write($bytes, 0, $bytes.Length) }
                finally { $out.Dispose() }
            } finally { $zip.Dispose() }
        }
    } finally { $set.Dispose() }

    foreach ($pair in @(
        @{ From = $apkSet; Name = 'bundle-fixture.apks.bin' },
        @{ From = (Join-Path $work 'bundle-fixture.xapk'); Name = 'bundle-fixture.xapk.bin' },
        @{ From = (Join-Path $work 'bundle-fixture.apkm'); Name = 'bundle-fixture.apkm.bin' },
        @{ From = $riscv; Name = 'bundle-fixture-riscv.apks.bin' }
    )) {
        $target = Join-Path $output $pair.Name
        Copy-Item -LiteralPath $pair.From -Destination $target -Force
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Output ("{0}  {1} bytes  {2}" -f $pair.Name, (Get-Item -LiteralPath $target).Length, $hash)
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
