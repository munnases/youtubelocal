# FamilyTube Android client

Native Kotlin phone and Android TV apps for streaming a family video library from a home server over Wi-Fi. S0/S1 scaffolding, S2 playback, and S3 cached catalog/durable progress are implemented. Both apps show saved libraries, search/filter locally, and resume through Media3. Phone and Google TV emulator checks passed; physical-device verification is deferred at the user's request.

Read [the architecture design](ARCHITECTURE.md) for the module structure, phone and TV experience, playback lifecycle, caching and preloading strategy, and existing backend integration.

Follow [the staged execution plan](EXECUTION_PLAN.md) for implementation order, deliverables, completion checks, and progress tracking. Agents should also read the repository [agent instructions](../AGENTS.md).

Scope: no in-app mini-player. Shorts for small, short-form videos is planned after the first release.

See [S0 implementation notes](docs/IMPLEMENTATION_NOTES.md) for the observed toolchain, backend contract, device matrix, and media audit; [S3 verification](docs/S3_VERIFICATION.md) for emulator evidence and persistence/sync behavior; and [media preparation](docs/MEDIA_PREPARATION.md) for compatible-copy commands. The six modules in the architecture exist. The phone and TV use separate Compose Material themes, with shared models, Room repositories, playback coordinator, and design colors.

## Build and verification

Open this directory as the Android Studio project, or run the checked-in wrapper from `client/`. Use JDK 17 or newer; the verified local build used Android Studio's JBR 25 and an Android SDK with API 37 and build tools 36. On this machine, set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr` and `ANDROID_HOME` to `C:\Users\Munna-Saudico\AppData\Local\Android\Sdk` in the shell before running:

```powershell
.\gradlew.bat :app-mobile:assembleDebug :app-tv:assembleDebug
.\gradlew.bat :app-mobile:lintDebug :app-tv:lintDebug
.\gradlew.bat :core:data:testDebugUnitTest :core:playback:testDebugUnitTest
.\gradlew.bat :core:data:connectedDebugAndroidTest :core:playback:connectedDebugAndroidTest
```

Debug APKs are written to `app-mobile/build/outputs/apk/debug/` and `app-tv/build/outputs/apk/debug/`. Build output, local SDK paths, and signing material are ignored by Git. All four commands above ran successfully on 2026-10-01. Unit suites passed 8 tests; shared-module instrumentation passed 5 tests on each of the Android 17 phone and Android 14 Google TV emulators. App-module UI instrumentation has not been added/run; the viewing flows were driven through ADB. This does not verify physical devices or performance targets.

The server address accepts an `http://` or `https://` origin and is stored with DataStore. Open **Server** to edit it, or select a video from the library to resume locally saved progress. Search titles and category buttons filter the saved catalog without network requests. Refresh preserves cached items on failure. Back leaves watch, queues a local progress save, and stops playback; phone Fullscreen rotates to landscape and hides system bars without restarting the media item. TV video fills the screen behind its controls. Controls are still proof screens; timeout, polished browsing, and complete focus restoration belong to S4/S5.

Progress is saved every five seconds while playing and on playback transitions. Room stores progress and the coalesced watch outbox atomically before asynchronous sync. Installation IDs persist in DataStore; retries reuse persisted session IDs/sequences. WorkManager retries use capped exponential backoff with periodic recovery and do not require validated internet. Resume restarts items within their final 10 seconds or at 95% of known duration. Backgrounding saves/releases the player, and Play is explicit on return. Each normalized server origin has a separate local library ID; returning to that origin restores its catalog/progress. Changing a server's address currently creates a separate library; an explicit same-library migration flow is deferred.

The health check does not require Android's validated-internet signal. Target SDK 36 intentionally keeps LAN access under `INTERNET`; moving to target 37 requires the `ACCESS_LOCAL_NETWORK` permission and runtime flow. The setup screen is a pre-release shell; parent PIN gating belongs to S7. S4 phone UI is the next unstarted stage; S5 TV UI can follow independently. Hardware/media compatibility and release checks remain pending.
