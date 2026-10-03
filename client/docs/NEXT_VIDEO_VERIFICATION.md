# Next-video verification

Date: 2026-10-02. Implementation and emulator checks are finished; physical-device and full-library compatibility remain pending.

## Behavior

Phone and TV expose an immediate Next button. After normal completion, the retained shared coordinator counts down five seconds and selects the first cached related video from the same family library. The title and remaining seconds appear with Cancel. With no candidate, Next is disabled and no timer runs. Existing saved-position selection behavior applies to Next as well as library cards.

Manual selection, Cancel, Replay/seek, leaving watch, and backgrounding cancel pending advance. Replaying allows a new countdown on the next completion. Configuration/fullscreen changes retain the countdown and player. Errors do not start a countdown. TV Back first cancels a pending countdown, and the Media Next key advances immediately. Automatic selections update the watch title and related list without changing the original browsing return position.

The user explicitly enabled this behavior, replacing the earlier autoplay-off default. A persistent PIN-gated autoplay preference remains S7 work. No backend contract or deployment changed.

## Commands and results

From `client/`, with the JDK/SDK environment in the README:

```powershell
.\gradlew.bat --no-daemon :app-mobile:assembleDebug :app-tv:assembleDebug :app-mobile:assembleDebugAndroidTest :app-tv:assembleDebugAndroidTest :app-mobile:lintDebug :app-tv:lintDebug :core:data:testDebugUnitTest :core:playback:testDebugUnitTest
python tools/emulator_fixture.py --sample build/s3-sample.mp4 --output build/next-fixture --port 8767 --items 24 --posters
adb -s emulator-5554 install -r app-mobile/build/outputs/apk/debug/app-mobile-debug.apk
adb -s emulator-5554 install -r app-mobile/build/outputs/apk/androidTest/debug/app-mobile-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e fixtureServer http://10.0.2.2:8767 org.familytube.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

Builds passed; lint reported zero errors (14 phone warnings, 3 TV warnings). Shared JVM suites passed 12 tests, including four new countdown/selection tests using controlled one-second ticks. These cover the five-tick boundary, repeated completion callbacks, metadata refresh, stale/cross-library/self candidates, explicit Next, cancellation, selection, stop/background and replay.

Phone `A36`, Android 17/API 37 emulator: all **9** instrumentation tests passed (7 focused UI checks and 2 real-player journeys). The new real-player journey checks inline countdown visibility/Cancel, waiting beyond five seconds without advance, immediate Next with the same player, automatic advance during fullscreen rotation, correct watch title, manual selection winning, and background release with no later advance. The original journey still checks seek, fullscreen retention, save/stop, resume and Replay. Phone fixture tests now restore the prior server address in `finally`.

Google TV API 34 emulator: all **7** instrumentation tests passed (6 focused UI checks and 1 expanded real-player journey) with a prepared faststart copy of the same H.264 320x180/AAC sample:

```powershell
adb -s emulator-5556 install -r app-tv/build/outputs/apk/debug/app-tv-debug.apk
adb -s emulator-5556 install -r app-tv/build/outputs/apk/androidTest/debug/app-tv-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -e fixtureServer http://10.0.2.2:8770 org.familytube.tv.test/androidx.test.runner.AndroidJUnitRunner
```

The TV journey checks the existing remote/seek/resume/related flow, Back cancellation after completion, no advance after waiting, Replay followed by automatic advance, updated title and retained player, Media Next, Stop, and restored original browsing focus. Both suites use direct `adb install -r`, preserving app data rather than uninstalling. The TV test restores its prior nonblank server address.

## Fixture investigation and limits

The original sample has its MP4 `moov` index at EOF. Initial TV runs passed the six UI tests but intermittently failed playback at initial selection, saved-position resume, or after advance with HTTP 416. An isolated diagnostic fixture logged an invalid `bytes=3360445739-` request against a 64,657,027-byte file. This was reproduced at fresh fixture origins, so it cannot be attributed solely to saved app data. The exact player/extractor cause is unresolved.

An offline temporary script, `python build/next_prepare_fixture.py`, copied the sample, moved `moov` ahead of `mdat`, and adjusted all 42,264 chunk offsets after validating their original bounds. The copy retains the same file size and encoded media; originals are unchanged. A temporary logging wrapper then ran the existing fixture at port 8770 using `build/next-faststart.mp4`. This prepared-media run passed all seven TV tests. [Media preparation](MEDIA_PREPARATION.md) already calls for faststart MP4; the observation does not prove all EOF-index MP4 or WebM files work on TV.

Build/test logs, diagnostic wrappers, prepared sample, fixtures and APKs remain ignored artifacts under `build/` and app build directories. Logs include `next-final-build.log`, `next-phone-tests.log`, `next-tv-initial-failure.log`, `next-tv-repeat-failure.log`, `next-tv-source-diagnostics.log`, and `next-tv-faststart-tests.log`. Both fixture servers and additional diagnostic fixtures were stopped after verification. Existing backend watch-database changes were preserved.

Physical phone/TV, OEM remotes, accessibility and full family media remain unverified. S6 caching/preloading/performance is the next unstarted implementation stage; PIN-gated settings remain S7. Track the TV EOF-index/range issue during media compatibility and recovery work.
