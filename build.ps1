# Build kakao-adblock APK without Gradle (javac -> d8 -> aapt2 -> zipalign -> apksigner).
# Requires: JDK, Android SDK (platforms;android-35, build-tools;35.0.0).
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$bt = Join-Path $sdk "build-tools\35.0.0"
$jar = Join-Path $sdk "platforms\android-35\android.jar"
$out = Join-Path $root "build"

function Run($exe) {
    $ErrorActionPreference = "Continue" # tools print info to stderr
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes" | Out-Null

# 1) Compile Java
$src = (Get-ChildItem "$root\src" -Recurse -Filter *.java).FullName
Run javac --release 11 -encoding UTF-8 "-Xlint:-options" -classpath $jar -d "$out\classes" @src

# 2) Dex
$classes = (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
Run "$bt\d8.bat" --release --min-api 26 --lib $jar --output $out @classes

# 3) Link manifest/resources
Run "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $jar --manifest "$root\AndroidManifest.xml" `
    --min-sdk-version 26 --target-sdk-version 35

# 4) Add classes.dex
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", "Update")
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\classes.dex", "classes.dex")
$zip.Dispose()

# 5) Align
Run "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"

# 6) Sign (keystore is created on first build and must be kept for future updates)
$ksDir = Join-Path $root "keystore"
$ks = Join-Path $ksDir "release.jks"
$pwFile = Join-Path $ksDir "password.txt"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force $ksDir | Out-Null
    $pw = -join ((48..57 + 65..90 + 97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    [IO.File]::WriteAllText($pwFile, $pw)
    Run keytool -genkeypair -keystore $ks -storepass $pw -keypass $pw -alias key -keyalg RSA -keysize 2048 `
        -validity 10000 -dname "CN=kakao-adblock-android"
}
$pw = [IO.File]::ReadAllText($pwFile).Trim()
Run "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$pw" --out "$out\kakao-adblock.apk" "$out\aligned.apk"
Run "$bt\apksigner.bat" verify "$out\kakao-adblock.apk"

$apk = Get-Item "$out\kakao-adblock.apk"
"OK: $($apk.FullName) ($($apk.Length) bytes)"
