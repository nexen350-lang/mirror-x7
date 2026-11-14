param(
    [string]$Bundle = (Join-Path $PSScriptRoot '..\app\build\outputs\bundle\release\app-release.aab'),
    # bundletool's classpath: a bundletool-all jar, or ';'-separated jars. Found in the Gradle cache when empty.
    [string]$Bundletool = '',
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
    # A connected device's serial, to install what Google Play would deliver to it.
    [string]$Device = ''
)
# Checks an App Bundle before it is uploaded to Google Play: bundletool accepts it, it is not
# debuggable and not signed with a debug key, its version and target API are what you expect, it
# carries the privacy policy and open-source notices App info shows, the sole scrcpy 5.0.1 server
# exactly as released, and not the LGPL library the app
# replaced, and every APK Play could make from it keeps native code aligned for 16 KB memory pages.
# With -Device, it also installs the APKs Play would deliver to that device. Needs Java 17 on the
# PATH or JAVA_HOME.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Bundle = [IO.Path]::GetFullPath($Bundle)
if (-not (Test-Path $Bundle)) { throw "No bundle at $Bundle. Build it with .\gradlew.bat :app:bundleRelease" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool' }
$aapt2 = (Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Recurse -Filter 'aapt2*' | Where-Object { $_.Name -match '^aapt2(\.exe)?$' } |
    Sort-Object FullName -Descending | Select-Object -First 1).FullName
$zipalign = (Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Recurse -Filter 'zipalign*' | Where-Object { $_.Name -match '^zipalign(\.exe)?$' } |
    Sort-Object FullName -Descending | Select-Object -First 1).FullName
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
if (-not $Bundletool) {
    # bundletool, its dependencies and the Kotlin library it needs, as the Android Gradle plugin caches them.
    $cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
    $jars = foreach ($artifact in 'com.android.tools.build\bundletool', 'com.android.tools.build\aapt2-proto',
            'com.google.protobuf\protobuf-java', 'com.google.protobuf\protobuf-java-util', 'com.google.guava\guava',
            'com.google.guava\failureaccess', 'com.google.dagger\dagger', 'javax.inject\javax.inject',
            'com.google.auto.value\auto-value-annotations', 'org.bitbucket.b_c\jose4j', 'com.google.errorprone\error_prone_annotations',
            'org.slf4j\slf4j-api', 'com.google.code.gson\gson', 'org.jetbrains.kotlin\kotlin-stdlib') {
        # Guava's JRE build, not its Android one; otherwise the newest cached version.
        Get-ChildItem (Join-Path $cache $artifact) -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' -and $_.Name -notmatch '-android\.jar$' } |
            Sort-Object { [regex]::Replace($_.Directory.Parent.Name, '\d+', { param($m) $m.Value.PadLeft(8, '0') }) } -Descending |
            Select-Object -First 1 | ForEach-Object FullName
    }
    if (@($jars | Where-Object { $_ -match 'bundletool' }).Count -eq 0) { throw 'bundletool is not in the Gradle cache; pass -Bundletool <bundletool-all.jar>.' }
    $Bundletool = $jars -join ';'
}
function Bundletool([string[]]$Arguments) {
    $output = & $java -cp $Bundletool com.android.tools.build.bundletool.BundleToolMain @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "bundletool $($Arguments[0]) failed: $output" }
    $output
}
function MinLoadAlignment([byte[]]$Bytes) {
    $is64 = $Bytes[4] -eq 2
    $phoff = if ($is64) { [BitConverter]::ToUInt64($Bytes, 0x20) } else { [BitConverter]::ToUInt32($Bytes, 0x1C) }
    $size = [BitConverter]::ToUInt16($Bytes, $(if ($is64) { 0x36 } else { 0x2A }))
    $count = [BitConverter]::ToUInt16($Bytes, $(if ($is64) { 0x38 } else { 0x2C }))
    $min = [UInt64]::MaxValue
    for ($i = 0; $i -lt $count; $i++) {
        $at = [int]($phoff + $i * $size)
        if ([BitConverter]::ToUInt32($Bytes, $at) -ne 1) { continue }
        $align = if ($is64) { [BitConverter]::ToUInt64($Bytes, $at + 0x30) } else { [BitConverter]::ToUInt32($Bytes, $at + 0x1C) }
        if ($align -lt $min) { $min = $align }
    }
    $min
}

$problems = [Collections.Generic.List[string]]::new()
$archive = [IO.Compression.ZipFile]::OpenRead($Bundle)
try { $entries = @($archive.Entries | ForEach-Object FullName) } finally { $archive.Dispose() }
# App info shows these two; Google Play requires the policy inside the app as well as online.
foreach ($asset in 'base/assets/legal/privacy-policy.md', 'base/assets/open-source/notices.json') {
    if ($entries -notcontains $asset) { $problems.Add("It has no $asset, which App info needs.") }
}
# Only official scrcpy 5.0.1: reject extra server assets as well as a wrong digest.
$registry = Join-Path $PSScriptRoot '..\app\src\main\java\com\scrcpy\remote\scrcpy\ScrcpyServerVersion.kt'
$servers = if (Test-Path -LiteralPath $registry) {
    @([regex]::Matches([IO.File]::ReadAllText($registry), 'V\d+_\d+\("([0-9.]+)", "([0-9a-f]{64})"'))
} else { @() }
if ($servers.Count -ne 1 -or $servers[0].Groups[1].Value -ne '5.0.1') { $problems.Add("The registry must contain only scrcpy 5.0.1.") }
$serverAssets = @($entries | Where-Object { $_ -match '^base/assets/scrcpy-server' })
if ($serverAssets.Count -ne 1 -or $serverAssets[0] -ne 'base/assets/scrcpy-server-v5.0.1') {
    $problems.Add('The bundle must contain exactly the scrcpy 5.0.1 server asset.')
}
$archive = [IO.Compression.ZipFile]::OpenRead($Bundle)
try {
    foreach ($server in $servers) {
        $version = $server.Groups[1].Value
        $entry = $archive.GetEntry("base/assets/scrcpy-server-v$version")
        if (-not $entry) { $problems.Add("It has no scrcpy $version server (base/assets/scrcpy-server-v$version)."); continue }
        $stream = $entry.Open()
        try { $digest = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose() }
        if ($digest -ne $server.Groups[2].Value) { $problems.Add("Its scrcpy $version server is not the release's: SHA-256 $digest.") }
    }
} finally { $archive.Dispose() }
# The LGPL SPAKE2 library is only a test dependency now; shipping it would bring back its source obligations.
if (@($entries | Where-Object { $_ -match '/libspake2\.so$' }).Count) { $problems.Add('It contains libspake2.so, the LGPL library the app no longer ships.') }
$mapping = $entries -contains 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map'
# Whatever else is wrong is reported too when bundletool refuses the bundle outright.
try { Bundletool @('validate', "--bundle=$Bundle") | Out-Null }
catch { $problems | ForEach-Object { Write-Warning $_ }; throw }
$manifest = (Bundletool @('dump', 'manifest', "--bundle=$Bundle")) -join "`n"
$version = [regex]::Match($manifest, 'android:versionName="([^"]+)"').Groups[1].Value
$code = [regex]::Match($manifest, 'android:versionCode="(\d+)"').Groups[1].Value
$target = [regex]::Match($manifest, 'android:targetSdkVersion="(\d+)"').Groups[1].Value
$package = [regex]::Match($manifest, 'package="([^"]+)"').Groups[1].Value
if ($manifest -match 'android:debuggable="true"') { $problems.Add('The bundle is debuggable.') }
if ([int]$target -lt 36) { $problems.Add("It targets API $target; Google Play requires 36 for new apps and updates.") }
$signer = (& $keytool -printcert -jarfile $Bundle 2>&1) -join "`n"
if ($signer -match 'CN=Android Debug') { $problems.Add('It is signed with a debug key, not your upload key.') }
if ($signer -notmatch 'Owner:') { $problems.Add('It is not signed.') }

$work = Join-Path ([IO.Path]::GetTempPath()) "verify-play-bundle-$PID"
New-Item -ItemType Directory -Force $work | Out-Null
try {
    # Every split Play could make, signed with the local debug key just to build them.
    Bundletool @('build-apks', "--bundle=$Bundle", "--output=$work\all.apks", "--aapt2=$aapt2") | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory("$work\all.apks", "$work\all")
    $apks = @(Get-ChildItem "$work\all" -Recurse -Filter *.apk)
    $libraries = 0
    foreach ($apk in $apks) {
        & $zipalign -c -P 16 -v 4 $apk.FullName *> $null
        if ($LASTEXITCODE -ne 0) { $problems.Add("$($apk.Name) is not zip-aligned for 16 KB pages.") }
        $zip = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            foreach ($entry in $zip.Entries | Where-Object { $_.FullName -like 'lib/*.so' }) {
                $stream = $entry.Open(); $memory = [IO.MemoryStream]::new(); $stream.CopyTo($memory); $stream.Dispose()
                $libraries++
                $alignment = try { MinLoadAlignment $memory.ToArray() } catch { -1 }
                if ($alignment -lt 0) { $problems.Add("$($apk.Name)!$($entry.FullName) is not a readable ELF library.") }
                elseif ($alignment -lt 16384) { $problems.Add("$($apk.Name)!$($entry.FullName) has $alignment-byte segment alignment; 16 KB pages need 16384.") }
            }
        } finally { $zip.Dispose() }
    }
    if ($Device) {
        Bundletool @('build-apks', "--bundle=$Bundle", "--output=$work\device.apks", "--aapt2=$aapt2", '--connected-device',
            "--device-id=$Device", "--adb=$adb") | Out-Null
        Bundletool @('install-apks', "--apks=$work\device.apks", "--device-id=$Device", "--adb=$adb") | Out-Null
        "Installed on ${Device}: " + ((& $adb -s $Device shell pm path $package) -replace '^package:.*/', '' -join ', ')
    }
} finally { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }

"$package $version (versionCode $code), target API $target, $($apks.Count) possible APKs, $libraries native libraries checked"
if (-not $mapping) { Write-Warning 'The bundle carries no R8 mapping, so Google Play cannot make crash reports readable.' }
if ($problems.Count) { $problems | ForEach-Object { Write-Warning $_ }; throw "The bundle is not ready for Google Play ($($problems.Count) problem(s))." }
'Ready to upload: validated, release-signed, not debuggable, and 16 KB-aligned in every APK.'
