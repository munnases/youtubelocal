# S5 TV verification

Date: 2026-10-02. TV implementation and emulator acceptance are complete. Stage status remains **Verification pending** because the execution plan also requires a real TV check; physical hardware is deferred at the user's request.

## Implemented behavior

- TV Material navigation rail: Home, Library, Search, Server and Refresh. Home includes continue watching; library videos appear in category rows. Fixed-size poster cards have a white focus border and 4% focus scale, with reserved spacing and an artwork placeholder.
- Search filters the saved library locally. Up leaves the input for navigation; Down dismisses the keyboard and enters View results, then Down/OK enters the results. Clear is reachable with Right from View results. Left/Right remain available for text editing. Both ordinary and pre-IME key dispatch are handled.
- Browsing retains selected row/video IDs and both scroll axes above the watch route. Saveable state supports Activity recreation. Initial focus waits for the current library rows/window; later progress updates do not take focus. Removed selections fall back to the first available card or navigation. Row edges have explicit remote behavior.
- One shared coordinator/player renders fullscreen video. Controls hide after three seconds during playback, remain visible while paused/previewing/failed, and reveal on remote input before activating a button. Retry and Replay use the shared coordinator's existing behavior.
- Focus the timeline with Up from Play/Pause. Left/Right previews bounded ten-second changes without seeking; OK applies the preview; Back cancels. Back then closes related items, hides the overlay, and finally leaves watch, saves progress and stops playback. An explicit Leave video action also stops it.
- Related family videos open only on request. Selecting one reuses the same player; autoplay remains off. Foreground media Play, Pause, Play/Pause, Fast-forward, Rewind and Stop route through the coordinator callbacks. Backgrounding preserves progress and releases playback; returning requires explicit Play.
- TV artwork uses one application Coil loader with the shared redirect-restricted HTTP client. No backend contract, media cache, preload or parent-setting change was needed.

## Target and fixture

`FamilyTube_TV_API34`, Google TV x86, Android 14/API 34, configured with 2 GiB RAM and a 4K profile. Observed app/screenshots are 1920x1080. Tests used the existing local Android SDK and JBR 25. These are emulator results, not physical TV measurements.

The isolated server ran on host `127.0.0.1:8765`, reached as `http://10.0.2.2:8765` from the emulator. It used 24 fixture entries, synthetic posters and the S3 H.264 320x180/AAC sample. Media, catalog, watch database and screenshots are under ignored `client/build/`. Backend source was not edited, and the existing live-database working-tree change was preserved. Fixture viewing used a separate watch database; no deployment or server configuration change was performed.

## Commands and results

From `client/`, with `JAVA_HOME` and `ANDROID_HOME` configured as in the README:

```powershell
.\gradlew.bat :app-tv:assembleDebug :app-tv:assembleDebugAndroidTest :app-tv:lintDebug --console=plain
.\gradlew.bat :app-mobile:assembleDebug --console=plain
```

Both debug APKs assembled. TV lint passed with 0 errors and 3 existing packaging/cleartext warnings. Final TV build/lint output is `build/s5-final-build.log`. The phone build passed in the earlier combined S5 build; phone source and shared modules were unchanged.

The direct ADB test run preserves the target installation, unlike the connected-test runner's potential uninstall/reinstall behavior:

```powershell
python tools/emulator_fixture.py --sample build/s3-sample.mp4 --output build/s5-fixture --items 24 --posters
# In another shell; use the installed SDK's adb executable:
adb -s emulator-5556 install -r app-tv/build/outputs/apk/debug/app-tv-debug.apk
adb -s emulator-5556 install -r app-tv/build/outputs/apk/androidTest/debug/app-tv-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -e fixtureServer http://10.0.2.2:8765 org.familytube.tv.test/androidx.test.runner.AndroidJUnitRunner
```

The final run passed **6 tests** in 18.798 seconds (`build/s5-ui-tests-final.log`):

1. Seek preview does not seek early; OK commits once, Back cancels, and both ends clamp.
2. Playing controls time out; the first OK reveals without pausing; Back hides before leaving.
3. Paused/failed controls stay visible; related updates do not steal focus.
4. Remote browsing scrolls a long row, returns to the exact selected card and preserves its scroll index; continue watching renders.
5. Local search retains field focus, recovers from no match, reaches Clear/results and returns Home with Back.
6. Real Activity/player journey: remote selection, media Pause/Play, seek cancellation/commit, overlay-first Back, player item cleared on leaving, restored card focus, saved-position resume, related selection with the same player, and media Stop.

Without `fixtureServer`, the real-player test is skipped; five focused UI tests require no backend. The real-player test temporarily changes the origin and restores its prior nonblank origin in `finally`. S5 testing also backed up the existing emulator app data before installing APKs; no target uninstall or app-data clear was performed. The original TV server origin was restored and persisted installation identity compared successfully with the pre-test archive (`build/s5-restore-server.log`).

Native ADB D-pad/OK/Back/media input separately exercised server configuration, paused fullscreen video, preview/cancel/commit, related items, overlay-first Back, restored card focus, search/Clear/results, and cached catalog during fixture HTTP 503. XML focus checks confirmed the same selected title on return while the resume label advanced. Native media Retry also recovered an observed fixture source error; fast-forward/rewind moved 1 -> 11 -> 1 seconds, then Play/Stop worked (`build/s5-native-retry.log`). Native search checks waited for each focus transition and used keyboard text injection into the already-focused field; no touch selection was required. Logs are `build/s5-native-remote.log` and `build/s5-native-search-safe.log`.

## Review evidence and fixes

- `build/s5-tv-home.png`: category rows and an outlined focused poster.
- `build/s5-tv-paused.png`, `s5-tv-seek-preview.png`, `s5-tv-seek-committed.png`: fullscreen video and timeline interaction.
- `build/s5-tv-related.png`, `s5-tv-overlay-hidden.png`, `s5-tv-returned.png`: related overlay, clean video, restored browsing focus.
- `build/s5-tv-no-match.png`, `s5-tv-search.png`, `s5-tv-search-result-focus.png`, `s5-tv-cached-offline.png`: search recovery, remote result focus, and saved catalog during outage.
- `build/s5-native-media-playing.txt` / `s5-native-media-stopped.txt`: FamilyTube MediaSession PLAYING(3), then NONE(0) with an empty queue after Stop. Other emulator system sessions are unrelated.

Visual review corrected TV Material's default circular button clipping on posters and bounded timeline focus styling. Repeated tests exposed library initialization clearing already-attached row states; initialization and first-focus restoration now run sequentially with observable row-state storage. Native input checks added explicit keyboard dismissal on leaving text fields. Early test failures also required waiting for the watch route before dispatching Back; the final suite passed after these corrections.

One native selection after the outage encountered an EOF/source error. Explicit Retry recovered, followed by successful media seeks and Stop. The exact cause is not established; `build/s5-playback-diagnostics.log` retains the error and subsequent decoded-track/first-frame evidence. No automatic infinite retry is used.

## Remaining verification

Real-TV readability, remote/OEM keyboard behavior, accessibility, the complete family codec library and release-like performance remain unverified. Seek preview displays time, not frame thumbnails; preview sprites belong to a later phase. The current emulator sample and debug builds do not certify S6 performance targets. S0/S1/S2 physical/media gaps remain visible.

Next implementation stage: **S6**, starting with an additive backend content-version contract before media caching/preloading. S6 has not been started.
