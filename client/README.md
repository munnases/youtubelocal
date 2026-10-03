# FamilyTube Android client

Native Kotlin phone and Android TV apps for streaming a family video library from a home server over Wi-Fi. S0/S1 scaffolding, S2 playback, S3 cached catalog/durable progress, S4 phone browsing/watch UI, and S5 TV browsing/remote controls are implemented. The phone has thumbnail browsing, Home/Library navigation, continue watching, related items, gestures, and fullscreen controls. TV has category/poster rows, remote search, continue watching, fullscreen overlays, confirmed timeline seeking and focus restoration. Phone and Google TV emulator checks are recorded separately; physical-device verification is deferred at the user's request.

Read [the architecture design](ARCHITECTURE.md) for the module structure, phone and TV experience, playback lifecycle, caching and preloading strategy, and existing backend integration.

Follow [the staged execution plan](EXECUTION_PLAN.md) for implementation order, deliverables, completion checks, and progress tracking. Agents should also read the repository [agent instructions](../AGENTS.md).

Scope: no in-app mini-player. Shorts for small, short-form videos is planned after the first release.

The [backend thumbnail worker](../backend/README.md#missing-thumbnail-worker) generates missing artwork in the background after successful library scans, preserves supplied posters, and stores generated images beside the backend database so the media mount remains read-only. Existing apps load these through `thumbnailUrl` on the next catalog refresh. Deployment remains a separate action.

The [Compose download and share setup](../backend/README.md#metube-downloads-and-automatic-discovery) adds parent-managed MeTube downloads and a password-protected read-only Samba share for the same media folder (`backend/media` by default). A backend folder watcher automatically scans settled changes without altering saved scan settings; refresh the app library to see new videos. These are server-side tools; Android UI and the remaining S6-S8 work are unchanged.

See [S0 implementation notes](docs/IMPLEMENTATION_NOTES.md) for the observed toolchain, backend contract, device matrix, and media audit; [S3 verification](docs/S3_VERIFICATION.md) for persistence/sync evidence; [S4 verification](docs/S4_VERIFICATION.md) for the phone acceptance flow and screenshots; [S5 verification](docs/S5_VERIFICATION.md) for TV remote, focus and playback evidence; and [media preparation](docs/MEDIA_PREPARATION.md) for compatible-copy commands. See [Next-video verification](docs/NEXT_VIDEO_VERIFICATION.md) for immediate Next, five-second advance, cancellation/lifecycle checks and the TV media limitation. The six modules in the architecture exist. The phone and TV use separate Compose Material themes, with shared models, Room repositories, playback coordinator, and design colors.

## Build and verification

For signed phone/TV APKs, follow [the production build guide](docs/PRODUCTION_BUILD.md). It documents release build/lint commands, external keystores, Android Studio and command-line signing, signature checks and safe update behavior. Unsigned `assembleRelease` output requires signing before installation.

Open this directory as the Android Studio project, or run the checked-in wrapper from `client/`. The checked-in daemon configuration requests Java 25; the verified local build used Android Studio's JBR 25 and an Android SDK with API 37 and build tools 36. JVM source compatibility remains Java 17. On this machine, set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr` and `ANDROID_HOME` to `C:\Users\Munna-Saudico\AppData\Local\Android\Sdk` in the shell before running:

```powershell
.\gradlew.bat :app-mobile:assembleDebug :app-tv:assembleDebug
.\gradlew.bat :app-mobile:lintDebug :app-tv:lintDebug
.\gradlew.bat :core:data:testDebugUnitTest :core:playback:testDebugUnitTest
.\gradlew.bat :core:data:connectedDebugAndroidTest :core:playback:connectedDebugAndroidTest
```

Debug APKs are written to `app-mobile/build/outputs/apk/debug/` and `app-tv/build/outputs/apk/debug/`. Build output, local SDK paths, and signing material are ignored by Git. The commands above ran successfully on 2026-10-01; shared-module instrumentation passed 5 tests on each emulator then. On 2026-10-02, both apps assembled/linted, the 8 shared unit tests passed, and the playback selection/background instrumentation passed on the phone. S4 added 6 focused phone UI tests and one real-player Activity journey. The Next-video increment subsequently passed 12 shared JVM tests, 9 phone instrumentation tests, and 7 TV instrumentation tests with prepared faststart media; both apps assembled and lint passed. The original EOF-index MP4 intermittently failed TV range requests; see the linked Next-video report. This does not verify physical devices or performance targets.

For the S4 real-player test, start the isolated fixture in another shell (the sample MP4 is untracked; see the S3 report for its source):

```powershell
python tools/emulator_fixture.py --sample build/s3-sample.mp4 --output build/s4-fixture --items 12 --posters
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app-mobile:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.fixtureServer=http://10.0.2.2:8765'
```

This command passed all 7 phone tests. Without `fixtureServer`, the real-player journey is skipped; the focused UI tests require no backend. The current suite has 7 UI tests and 2 real-player journeys; both real-player journeys restore the prior server address. Use a disposable emulator installation: Gradle's connected-test runner can reinstall/remove the target APK and its data. The fixture server uses its own media/catalog/watch database under ignored `build/`. Stop it with Ctrl+C after testing.

For S5, the fixture needs at least 12 items; the verified run used 24. To preserve an existing TV installation, build test APKs and run instrumentation directly with `adb` instead of the connected-test runner:

```powershell
python tools/emulator_fixture.py --sample build/s3-sample.mp4 --output build/s5-fixture --items 24 --posters
# In another shell, with the installed SDK's adb on PATH:
.\gradlew.bat :app-tv:assembleDebug :app-tv:assembleDebugAndroidTest :app-tv:lintDebug
adb -s emulator-5556 install -r app-tv/build/outputs/apk/debug/app-tv-debug.apk
adb -s emulator-5556 install -r app-tv/build/outputs/apk/androidTest/debug/app-tv-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -e fixtureServer http://10.0.2.2:8765 org.familytube.tv.test/androidx.test.runner.AndroidJUnitRunner
```

This passed all six TV tests on 2026-10-02. Without `fixtureServer`, the real-player test is skipped and six focused UI tests run in the current suite. The playback test restores its prior nonblank origin in `finally`. The S5 checks preserved installation identity and restored the original TV server address. Stop the isolated fixture after testing.

The server address accepts an `http://` or `https://` origin and is stored with DataStore. Open the **Server** icon to edit it. Phone Home includes continue watching; Library shows all saved videos. Search titles and category chips filter locally. Thumbnail loading uses a single Coil loader with the shared server HTTP client and versioned artwork URLs; missing/failed artwork keeps a fixed 16:9 placeholder. Refresh preserves cached items on failure.

On the phone, watch shows video above its title and cached related items. Double-tap either side for a bounded ten-second seek; dragging the timeline previews the position and seeks on release. Controls hide after three seconds while playing and remain visible while paused, scrubbing or showing an error. Fullscreen rotates to landscape and hides system bars while retaining the player/media item. Back first returns to portrait inline view, then saves/stops playback and restores browsing scroll. Backgrounding saves/releases playback and requires explicit Play on return. TV uses Home/Library category rows and a remote navigation rail. Search runs locally; Down dismisses the keyboard and focuses View results, then Down/OK enters the matching row. Right from View results reaches Clear. During fullscreen watch, Up from Play/Pause focuses the timeline; Left/Right previews ten-second changes, OK commits and Back cancels. Related opens on request. Back closes related items, then hides controls, then leaves watch and restores the selected card/row scroll. Media Play/Pause, fast-forward/rewind and Stop work immediately. Next advances immediately on phone and TV. After completion, the first related family-library video plays automatically after a visible five-second countdown unless you select another video or press Cancel. TV Back cancels the countdown and Media Next advances immediately. Leaving watch or backgrounding cancels it; fullscreen changes retain it. This enabled policy follows the latest user request; a persistent parent preference remains S7 work.

Progress is saved every five seconds while playing and on playback transitions. Room stores progress and the coalesced watch outbox atomically before asynchronous sync. Installation IDs persist in DataStore; retries reuse persisted session IDs/sequences. WorkManager retries use capped exponential backoff with periodic recovery and do not require validated internet. Resume restarts items within their final 10 seconds or at 95% of known duration. Backgrounding saves/releases the player, and Play is explicit on return. Each normalized server origin has a separate local library ID; returning to that origin restores its catalog/progress. Changing a server's address currently creates a separate library; an explicit same-library migration flow is deferred.

The health check does not require Android's validated-internet signal. Target SDK 36 intentionally keeps LAN access under `INTERNET`; moving to target 37 requires the `ACCESS_LOCAL_NETWORK` permission and runtime flow. The setup screen is a pre-release shell; parent PIN gating belongs to S7. S5 emulator acceptance passed six tests; physical TV verification remains pending. S6 caching/preloading/performance is the next unstarted implementation stage. Hardware/media compatibility and release checks remain pending.
