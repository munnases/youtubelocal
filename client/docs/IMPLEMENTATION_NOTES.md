# S0 implementation notes

Checked 2026-09-29. This is a local workspace and temporary-fixture audit, not a live-server or physical-device validation.

## Toolchain and build decision

| Item | Observed / selected |
| --- | --- |
| Host | Windows PowerShell in `E:\project\youtubelocal` |
| Java on PATH | Eclipse Adoptium 11.0.29; too old for selected AGP |
| Build JDK | Android Studio bundled JBR 25.0.3; used successfully for the debug build |
| Android Studio / SDK | Installed; platform SDKs 36 and 37, build tools 35.0.0 and 36.0.0, platform-tools/ADB present |
| Standalone `sdkmanager` | Not found under `cmdline-tools/latest` |
| Gradle | Checked-in wrapper 9.6.0, generated from a locally cached distribution |
| Python | 3.13.15; existing backend suite runs |
| Media inspection | `ffprobe` and `ffmpeg` not found on PATH; no local representative media in the workspace |

Pinned project versions: Android Gradle Plugin 9.4.0, Kotlin/Compose compiler plugin 2.4.10, Gradle 9.6.0, Compose BOM 2026.09.00, TV Material 1.1.0, Media3 1.11.0 (all artifacts share this version), DataStore 1.2.1, and Hilt 2.60.1. The [AGP 9.4 compatibility table](https://developer.android.com/build/releases/agp-9-4-0-release-notes) lists Gradle 9.6, build tools 36, and JDK 17 as the minimum. The [Compose setup guidance](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) pairs the Compose compiler plugin with Kotlin and uses the BOM. [TV Material](https://developer.android.com/jetpack/androidx/releases/tv), [Media3](https://developer.android.com/jetpack/androidx/releases/media3), and [Hilt](https://dagger.dev/hilt/gradle-setup.html) versions were checked against their release/setup pages.

`compileSdk = 37` supports the selected Compose libraries; `targetSdk = 36` is deliberate for the first home-network slice. [Android's local-network guidance](https://developer.android.com/privacy-and-security/local-network-permission) says apps targeting 36 or lower use `INTERNET` for LAN access and should not request `ACCESS_LOCAL_NETWORK`; target 37 would require a runtime request. Minimum SDK 26 remains provisional until the real devices' Android versions are known. The apps enable cleartext HTTP because a parent may enter an arbitrary LAN server address. The S1 health request uses only that origin and disables redirects.

## Device matrix

| Device supplied by user | Android version | RAM / storage | ADB / install access | Evidence |
| --- | --- | --- | --- | --- |
| Samsung A36 phone | Unknown | Unknown | Unknown | Physical model supplied by user; not attached to ADB |
| Redmi 15 phone | Unknown | Unknown | Unknown | Physical model supplied by user; not attached to ADB |
| 43-inch 4K Android TV, Cortex-A55 | Unknown; exact model unknown | Unknown | Unknown | Physical description supplied by user; not attached to ADB |
| `A36` Android Studio virtual phone | Android 17 / API 37 (AVD image 37.2) | Configured 2 GiB RAM; 1080 × 2400 | ADB `emulator-5554` | Generic `medium_phone`, x86_64; mobile APK installed and launched |

At the initial S0 check, `adb devices -l` returned an empty list. The user then created and booted the `A36` virtual phone; `adb devices -l` showed `emulator-5554`. `emulator -list-avds` did not list it in this shell, although the AVD configuration exists and the running emulator was reachable through ADB. Both S1 APKs installed and opened on this phone emulator; the TV APK's smoke launch does not validate TV layout or remote input. No physical-device launch, TV-target launch, decoder, performance, or live home-server check has been performed.

## Existing API and fixture evidence

The existing Python server was inspected. `GET /api/health` returns `{"ok": true, "videos": count}`. `GET /api/videos` returns a full catalog with `id`, `title`, `category`, `duration` in seconds, `addedAt` in epoch milliseconds, and absolute `streamUrl` and optional `thumbnailUrl` values. The server builds those URLs from the request host. `GET /media/{id}` supports byte ranges (`206`, `Content-Range`, `Accept-Ranges`) and `HEAD`; invalid ranges return `416`. Watch/history/recommendation routes use `X-Device-ID`, which is a per-installation identifier and not authentication. Watch positions, duration, and watched time are in seconds; `updatedAt` is in epoch milliseconds. No `contentVersion` exists yet, so persistent media byte caching must wait for S6's backend contract change.

The existing test suite uses temporary directories and a temporary local HTTP server. On 2026-09-29, `python -m unittest discover -s backend -p 'test_*.py'` passed 23 tests, including range handling, catalog responses, scan behavior, and duplicate watch delivery. The local `backend/catalog.json` contains `[]`; deployment documentation lists `http://192.168.10.151:8000` as a setup hint, but that address was not contacted. There is no backend API migration in S0/S1.

## Media findings and remaining prerequisites

The backend discovers `.mp4`, `.m4v`, `.mov`, and `.webm` files, but the workspace has no representative video files. Its tests create 10-byte fixtures named `.mp4`; these verify HTTP behavior, not codec compatibility. The media's video/audio codecs, profile, resolution, frame rate, duration, and MP4 index placement remain unknown. Before S2 physical playback checks, inspect representative files with `ffprobe` (or equivalent) and prepare H.264 8-bit SDR/AAC MP4 copies with the MP4 index at the front when needed. Preserve originals; do preparation outside the Play request path. Final profile, level, bitrate, and any 720p fallback must follow device inspection.

S0 remains **verification pending** for physical-device inventory and representative media audit. Independent Android scaffolding proceeded because these unknowns do not change the S1 project structure.
