# S4 phone experience verification

Date: 2026-10-02. Scope: phone emulator checks, as requested. Physical phone/TV testing and release performance measurements remain deferred. S5 TV navigation is unstarted.

## Implemented behavior

- Phone Home/Library navigation, FamilyTube app bar, local search/category chips, fixed 16:9 thumbnail cards, and continue watching sorted by saved-progress timestamp. Unknown durations omit the badge. Missing/failed artwork keeps a placeholder.
- Inline watch with title/category and up to five cached related videos. One retained playback coordinator owns the player; changing presentation does not set another media item. The surface stays in the same composition slot as its bounds change.
- Landscape fullscreen hides system bars. Back first returns to portrait inline view; another Back saves/stops playback and restores browsing scroll. Home/Library and continue-row scroll states live above screen branching.
- Custom play/pause, bounded ten-second seeks, double-tap sides, and a red timeline that previews while dragging and commits on release. Controls hide after three seconds of playing inactivity and stay visible when paused, scrubbing or failed. Controls expose labels/state and reserve 48 dp touch targets. Physical TalkBack and gesture checks remain pending.
- Selection shows artwork until the first frame. Progress ticks update only the controls; related items and the video surface do not collect every tick. Screen-awake flags follow active viewing. Background saves/releases playback; Play is explicit on return.
- Retry prepares a failed player again; Replay seeks a finished item to the beginning. Leaving watch clears the media item and does not leave audio playing. Playback has no automatic next-item selection.

Artwork uses Coil 3.6.3 with one application-owned loader, card-sized decoding, the shared HTTP pool, disabled redirects, and the existing backend's versioned poster URLs. No backend API migration was required. See [Coil Compose sizing](https://coil-kt.github.io/coil/compose/) and [Coil network configuration](https://coil-kt.github.io/coil/network/).

## Test target and isolated media

`emulator-5554`: A36 Android 17 / API 37.2 x86_64 phone emulator. Screenshots are 1080×2400 portrait and 2400×1080 landscape. The Google TV emulator was not running during S4; its APK was rebuilt/linted, with its S3 device evidence retained separately.

The fixture runs on host `127.0.0.1:8765`, reached as `http://10.0.2.2:8765` from Android. Its media/catalog/watch database are under ignored `client/build/s4-fixture/`. Twelve entries share immutable copies/hard links of the existing H.264/AAC ExoPlayer Big Buck Bunny sample. Deterministic synthetic PNG posters exercise artwork fetching; they are test assets, not supplied family posters. Real family-media compatibility remains an S0/S2 verification gap. [S3 report and sample source](S3_VERIFICATION.md).

From `client/`, with the documented JBR/SDK environment:

```powershell
python tools/emulator_fixture.py --sample build/s3-sample.mp4 --output build/s4-fixture --items 12 --posters
```

In another shell:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app-mobile:assembleDebug :app-tv:assembleDebug
.\gradlew.bat :app-mobile:lintDebug :app-tv:lintDebug
.\gradlew.bat :core:data:testDebugUnitTest :core:playback:testDebugUnitTest
.\gradlew.bat :core:playback:connectedDebugAndroidTest
.\gradlew.bat :app-mobile:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.fixtureServer=http://10.0.2.2:8765'
```

All listed tasks passed. These tasks were issued in combined Gradle invocations where appropriate; subsequent phone changes were rebuilt/linted and verified again. Logs: `build/s4-build.log`, `build/s4-verification.log`, `build/s4-phone-final.log`, and `build/s4-replay-test.log`. Gradle requires access to installed SDK/caches outside the workspace; the sandbox-only attempt failed before compilation and the authorized build succeeded.

| Check | Result |
| --- | --- |
| Phone and TV assembly | Passed; debug APKs produced |
| Phone and TV lint | Passed, with warnings; no lint errors |
| Shared JVM tests | 8 passed |
| Shared playback selection/background instrumentation | 1 passed on phone |
| Focused phone UI tests | 6 passed: timeout/reveal, paused/error visibility, seek bounds, release-only scrubbing, scroll/continue watching, reopening/editing search |
| Real-player phone Activity journey | Passed: browse, pause/seek, playing fullscreen, inline, Back/stop, saved resume |
| Real-player Replay extension | Passed separately with the focused class runner argument; ended media restarts below ten seconds |

The Activity journey compares the actual player object and media ID before/after landscape rotation and portrait return; it verifies playback remains playing and its position is retained. After leaving, it asserts `isPlaying == false` and `mediaItemCount == 0`, then reselects and verifies local resume. The six UI tests plus that journey passed together as seven phone tests. The later Replay extension passed with:

```powershell
.\gradlew.bat :app-mobile:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.fixtureServer=http://10.0.2.2:8765' '-Pandroid.testInstrumentationRunnerArguments.class=org.familytube.mobile.PhonePlaybackTest'
```

Without `fixtureServer`, the real-player test is skipped. Gradle's connected runner can reinstall/remove the target APK and its data; use a disposable emulator installation. After verification, the current debug phone APK is installed and its original server address restored.

## Visual and failure evidence

ADB-driven checks produced the following ignored local artifacts:

| Artifact | Evidence |
| --- | --- |
| [Home](../build/s4-phone-home.png) | Branding, category chips and fetched artwork |
| [Inline watch](../build/s4-phone-inline.png) | Decoded video above title and related items; readable dark theme |
| [Seek](../build/s4-phone-seek.png) | Ten-second seek from approximately 0:01 to 0:11 |
| [Fullscreen](../build/s4-phone-fullscreen.png) | Landscape video and overlay controls with hidden system bars |
| [Return inline](../build/s4-phone-return-inline.png) | Same saved position after fullscreen exit |
| [Continue watching](../build/s4-phone-continue.png) | Immediate local resume row and progress indicators |
| [Search](../build/s4-phone-search.png) | Local title filtering to one video |
| [Empty search](../build/s4-phone-empty.png) | Stable no-match layout |
| [Offline catalog](../build/s4-phone-offline.png) | HTTP 503 refresh retains the cached catalog and offers Retry |
| [Playback error](../build/s4-phone-error.png) | Failed stream shows a usable retry control |
| [Recovered playback](../build/s4-phone-recovered.png) | Retry after restoring the fixture renders video again |

`build/s4-visual-evidence.json` records visible text/actions and MediaSession dumps. The Back-to-browse dump reports `NONE(0)`, position zero and an empty queue. This supplements the direct player assertions; audio output was not measured acoustically.

Visual review corrected text contrast, the timeline appearance, and a populated-search reopening bug. Search now stays open while editing and closes with its explicit clear action or Back. Its regression test reproduces clearing and replacing a populated query.

## Observed interruptions and remaining checks

- The initial UI suite failed before executing app assertions because the transitive Espresso input handler reflected `InputManager.getInstance`, removed on this emulator. Pinning Espresso 3.7.0 fixed initialization; its official release notes record the switch to `getSystemService`. [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test).
- Android's first-use immersive hint covered the first manual fullscreen screenshot. It was dismissed through **Got it** before recapturing.
- One manual reinstall/run hit an input-focus ANR. Android reported “Application does not have a focused window” for FamilyTube and subsequently for the system UI. Captures: `build/s4-last-anr.txt`, `build/s4-window.txt`, `build/s4-recent-logcat.txt`. Force-stopping the app did not clear the system interruption; rebooting the emulator restored input. The subsequent full flow and phone test runs passed. The root cause was not established; this is not evidence of physical-device stability.
- Lint retains target-SDK, dependency-version, deliberate LAN HTTP, and requested-phone-orientation warnings. Target SDK 36/LAN permission decisions remain as documented in S0; upgrading them is outside S4.
- Physical playback, rotation/surface behavior, TalkBack, full family codec coverage, battery/memory pressure and release-like performance remain unverified. No performance acceptance targets are claimed. The single-sample emulator evidence does not replace those checks.

The next incomplete implementation stage is S5, the Android TV browsing and remote-focus experience. S6 caching/preloading, S7 PIN/autoplay controls, and S8 release validation remain later work.
