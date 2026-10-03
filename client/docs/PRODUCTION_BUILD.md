# Build and sign FamilyTube release APKs

This guide creates installable release APKs for your family's phone and Android TV. The current Gradle project has no release signing configuration: `assembleRelease` produces unsigned APKs. You can sign through Android Studio or use the command-line procedure below. [Android's signing guide](https://developer.android.com/studio/publish/app-signing) explains how signing certificates control installation and updates.

## Prerequisites

Open `client/` as the Android Studio project. Install Android SDK Platform 37, SDK Build-Tools 36.0.0 and Platform-Tools through SDK Manager, and accept the SDK licenses. Use the checked-in Gradle wrapper; it pins Gradle 9.6.0 and the daemon configuration requests Java 25. The locally verified setup is Android Studio's bundled JBR 25. JVM source/target compatibility remains Java 17. The first build needs internet to obtain dependencies; app viewing only needs the home network.

For this Windows machine, from `client/` in PowerShell:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME = 'C:/Users/Munna-Saudico/AppData/Local/Android/Sdk'
```

Adjust these paths on other machines. In Android Studio, choose a compatible Java 25 Gradle JDK. On Linux/macOS, set the corresponding `JAVA_HOME`/SDK path and replace `.\gradlew.bat` with `./gradlew` (run `chmod +x gradlew` if needed). Installed SDK tooling paths are local configuration and must stay out of Git.

| App | Application ID | Minimum / target SDK | Current version |
| --- | --- | --- | --- |
| Phone (`app-mobile`) | `org.familytube.mobile` | 26 / 36 | `0.1.0`, version code 1 |
| TV (`app-tv`) | `org.familytube.tv` | 26 / 36 | `0.1.0`, version code 1 |

For each update, increase `versionCode` and set the desired `versionName` in the relevant app's `build.gradle.kts` before building. Keep the application ID and release signing key stable to preserve upgrade compatibility. The backend address is configured in the app; it is not embedded in the release APK.

## Option 1: Android Studio

1. Open **Build > Generate Signed Bundle / APK**, select **APK**, and select `app-mobile`.
2. Choose your existing release keystore. For the first release, create a new keystore outside this repository, for example `C:/FamilyTubeSigning/familytube-release.keystore`, and choose an alias such as `familytube`. Back up the keystore and its passwords privately; future updates need the same key.
3. Select the `release` build variant and a destination directory. Build, then use Android Studio's completion notification to locate the signed APK.
4. Repeat for `app-tv`, choosing the same private keystore/alias if that is your signing policy. Each app still has its own package ID.
5. Verify the resulting APKs using the commands below, replacing paths with the APK locations chosen in the wizard.

Use the wizard's password fields. Do not commit signing configuration containing passwords or copy private keys into the source tree. This guide does not provision a production key or distribute APKs.

## Option 2: Command line

### Build unsigned release APKs

From `client/`, after setting the JDK/SDK variables:

```powershell
.\gradlew.bat --no-daemon :app-mobile:assembleRelease :app-tv:assembleRelease :app-mobile:lintRelease :app-tv:lintRelease
if ($LASTEXITCODE -ne 0) { throw 'Release build or lint failed.' }
```

Expected output paths:

- Phone: `app-mobile/build/outputs/apk/release/app-mobile-release-unsigned.apk`
- TV: `app-tv/build/outputs/apk/release/app-tv-release-unsigned.apk`
- Lint reports: `app-mobile/build/reports/lint-results-release.html` and `app-tv/build/reports/lint-results-release.html`

Unsigned APKs require signing before installation. Release builds are not debuggable. The current release variant does not enable R8 shrinking/obfuscation. Dependency-provided profiles are packaged, while FamilyTube benchmark/profile generation and performance tuning remain S6 work.

### Create a private key once

This creates a password-protected **keystore file** containing your app's signing key. Android uses its signature to recognize your app and accept future updates. Create it for your first production release, then reuse the same file for every update. If you already have the production key for these apps, continue to the signing section with that key.

On your Windows machine:

1. Open **PowerShell**. You can run this step from any folder.
2. Paste the following commands. They create `C:/FamilyTubeSigning` and a file called `familytube-release.keystore`. The certificate name is set to `FamilyTube`; the format is PKCS12, which the signing command below supports.

```powershell
New-Item -ItemType Directory -Force -Path 'C:/FamilyTubeSigning' | Out-Null
if (Test-Path -LiteralPath 'C:/FamilyTubeSigning/familytube-release.keystore') {
    throw 'A keystore already exists at this path. Use your existing key for signing.'
}
& 'C:/Program Files/Android/Android Studio/jbr/bin/keytool.exe' -genkeypair -v -storetype PKCS12 -keystore 'C:/FamilyTubeSigning/familytube-release.keystore' -alias familytube -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=FamilyTube'
if ($LASTEXITCODE -ne 0) { throw 'Key creation failed.' }
```

3. At **Enter keystore password**, type a strong password you choose, then press Enter. Password characters are hidden while typing. At **Re-enter new password**, type the same password again and press Enter. Keep it in your private password manager.
4. Wait for the command to finish, then check that the key exists:

```powershell
& 'C:/Program Files/Android/Android Studio/jbr/bin/keytool.exe' -list -keystore 'C:/FamilyTubeSigning/familytube-release.keystore' -alias familytube
if ($LASTEXITCODE -ne 0) { throw 'Keystore verification failed.' }
```

Enter the same password when asked. Successful output includes the alias `familytube` and the entry type `PrivateKeyEntry`.

5. Back up the keystore file privately and retain its password. Continue to **Align, sign, and verify both APKs** below, using these values:

| Value | What to use |
| --- | --- |
| Keystore path | `C:/FamilyTubeSigning/familytube-release.keystore` |
| Key alias | `familytube` (the name of the key inside the file) |
| Signing password | The password you entered above |

The same key can sign both FamilyTube apps. Keep this file for later releases and outside Git. The [keytool reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/keytool.html) documents key generation and listing. If Android Studio is installed elsewhere, replace the executable path in both commands with your installation's `jbr/bin/keytool.exe` path.

### Align, sign, and verify both APKs

The [zipalign documentation](https://developer.android.com/tools/zipalign) specifies alignment before signing and the 16 KiB shared-library alignment option. The [apksigner documentation](https://developer.android.com/tools/apksigner) describes signing and signature verification. These commands prompt for the keystore password and leave the original unsigned artifacts in place:

```powershell
$familytubeBuildTools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$familytubeKeystore = 'C:/FamilyTubeSigning/familytube-release.keystore'
$familytubeReleaseDir = 'build/production'
New-Item -ItemType Directory -Force -Path $familytubeReleaseDir | Out-Null

foreach ($app in @('app-mobile', 'app-tv')) {
    $unsignedApk = "$app/build/outputs/apk/release/$app-release-unsigned.apk"
    $alignedApk = "$familytubeReleaseDir/$app-aligned.apk"
    $signedApk = "$familytubeReleaseDir/$app-release.apk"
    & "$familytubeBuildTools/zipalign.exe" -P 16 -f 4 $unsignedApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "Alignment failed: $app" }
    & "$familytubeBuildTools/apksigner.bat" sign --ks $familytubeKeystore --ks-key-alias familytube --out $signedApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "Signing failed: $app" }
    & "$familytubeBuildTools/apksigner.bat" verify --verbose --print-certs $signedApk
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $app" }
    & "$familytubeBuildTools/zipalign.exe" -c -P 16 4 $signedApk
    if ($LASTEXITCODE -ne 0) { throw "Signed APK alignment check failed: $app" }
    Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk
}
```

Your signed files are `client/build/production/app-mobile-release.apk` and `client/build/production/app-tv-release.apk`. Record their SHA-256 hashes, certificate fingerprint and app versions with each release. Do not change an APK after signing. On Linux/macOS, SDK tools are named `zipalign`, `apksigner` and `keytool`, without Windows extensions; use the same option ordering and APK paths.

## Install and update

Enable developer options/USB debugging or supported TV network debugging, connect the device, and choose its serial from `adb devices -l`. Use explicit serials when more than one target is connected:

```powershell
$familytubeAdb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $familytubeAdb devices -l
$phoneSerial = 'YOUR_PHONE_SERIAL'
$tvSerial = 'YOUR_TV_SERIAL'
& $familytubeAdb -s $phoneSerial install -r 'build/production/app-mobile-release.apk'
& $familytubeAdb -s $tvSerial install -r 'build/production/app-tv-release.apk'
```

Replacing an existing install requires its signing certificate to match. A production-key APK cannot directly replace the currently installed debug-key APK; Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Keep the debug installation until you are ready for that transition. Uninstalling it erases local installation identity, progress, preferences and cached library; backend history remains under the old device ID. This increment does not provide a data migration between those signing identities. Updates signed with the same production key use `install -r` to retain app data.

After installation, set the backend's LAN URL in Server settings and check browse, Play, seek, Next, rotation/fullscreen, Back and resume. On TV, also check D-pad focus and media keys. Release packaging does not replace the pending physical-device, full-library, accessibility and performance validation in the [execution plan](../EXECUTION_PLAN.md).

## Verification evidence

On 2026-10-03, the release build/lint command above passed using JBR 25 and the installed SDK. Both unsigned APKs were produced at the documented paths. Release lint reported zero errors, with 14 phone warnings and three TV warnings. Manifest inspection confirmed the listed package IDs, version 0.1.0/code 1, min SDK 26, target SDK 36 and absence of the debuggable flag. The documented `zipalign -P 16` and alignment verification commands passed for both release APKs, with aligned copies under ignored `client/build/production/`.

Signing needs your private production key. No production keystore is created or committed by this documentation task, and no production-signed APK is claimed. The Android Studio wizard and installation steps are instructions for the operator.
