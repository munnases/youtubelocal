# S3 implementation and emulator verification

Verified 2026-10-01. The user authorized emulator-only testing and S3 implementation. Physical hardware and real-library compatibility checks remain deferred.

## Implemented behavior

- Room database version 1 stores libraries, catalog, progress, and a coalesced outbox. Its generated schema is checked in under `core/data/schemas/`. This is the first database, so no prior Room schema migration is needed. Existing DataStore server preferences are preserved; installation identity is added lazily.
- Each normalized origin maps to a persisted local UUID. Catalog/progress/video IDs are scoped to it. Returning to an origin restores its data; changing the address creates another library. An explicit same-library address migration is deferred.
- Home observes saved catalog/progress independently of network refresh. Search matches local titles case-insensitively; category filtering also runs locally. Refresh failure retains saved rows. Foreground return refreshes when the last successful catalog snapshot is older than one minute.
- Playback reads local resume progress without waiting for network. An ordered writer saves progress and outbox in one Room transaction before sync, every five seconds while playing and on transitions. Monotonic watched time excludes pause/buffering/seek distance. Late resume lookups cannot replace a newer selection. Backgrounding releases the player and returning requires Play.
- Near-end items restart within their final ten seconds or at 95% of known duration. Unknown duration preserves a nonnegative saved position.
- Watch/history/recommendation requests use a persisted installation UUID in `X-Device-ID`. Client playback values are milliseconds; API values are seconds; timestamps stay epoch milliseconds. Pending local snapshots take precedence over remote history; other history updates require a strictly newer timestamp.
- Retry retains the outbox session ID, sequence and cumulative watched time. One latest event per session is stored. A response for an older event cannot remove a newer event. Foreground sync requests are conflated; WorkManager uses one unique retry task, capped at eight attempts with exponential backoff from 30 seconds, plus periodic recovery with a 15-minute minimum interval subject to OS scheduling. It does not require validated internet. Permanent watch HTTP 400/404/410 removes the rejected outbox record but retains local progress.
- Cached recommendation order and category/recent fallback are implemented in the repository. Related-video presentation remains S4/S5 work. Streaming media bytes are not cached in S3.

New pinned dependencies: [Room 2.8.5](https://developer.android.com/jetpack/androidx/releases/room) and [WorkManager 2.11.2](https://developer.android.com/jetpack/androidx/releases/work). Room uses the project's existing KAPT pipeline, which built successfully; the exported v1 schema is retained for future migrations.

## Commands and results

From `client/`, with Android Studio JBR and SDK configured as described in the README:

```powershell
.\gradlew.bat :app-mobile:assembleDebug :app-tv:assembleDebug
.\gradlew.bat :app-mobile:lintDebug :app-tv:lintDebug
.\gradlew.bat :core:data:testDebugUnitTest :core:playback:testDebugUnitTest
.\gradlew.bat :core:data:connectedDebugAndroidTest :core:playback:connectedDebugAndroidTest
```

All passed. The combined run is recorded in ignored `client/build/s3-verification.log`. There are 7 data JVM tests and 1 playback JVM test; 4 storage and 1 playback selection/lifecycle instrumentation test passed on **each** emulator. Storage tests exercise database reopen, origin isolation, atomic catalog rollback, progress/outbox coalescing, safe acknowledgement, and cached related-video fallback. Playback instrumentation deliberately blocks A's resume lookup, selects B, then releases A's result; B remains selected. It also verifies background player release. JVM tests cover conversion, resume policy, reconciliation, local filtering, and actual playing-time accumulation.

From the repository root, Python 3.13.15 ran `python -m unittest discover -s backend -p 'test_*.py'`: 23 tests passed. Backend files were not changed by this increment.

## Emulator journeys

| Target | Observed |
| --- | --- |
| `emulator-5554`, `A36` phone | Android 17/API 37, x86_64, 1080x2400 portrait; landscape fullscreen screenshot 2400x1080 |
| `emulator-5556`, `FamilyTube_TV_API34` | Android 14/API 34 Google TV, x86; 4K configured profile but observed app/screenshot output 1920x1080 |

The previously saved phone server origin was unreachable. Testing therefore used an isolated instance of the existing backend at host `127.0.0.1:8765` (`http://10.0.2.2:8765` in the emulator), with all media copies/catalog/watch databases under ignored `client/build/s3-fixture/`. Another isolated origin at port 8766 tested separation using overlapping video IDs. The public sample MP4 was copied into the temporary library; no home-server media, watch database, or settings were changed.

Observed journeys:

- Both APKs installed and streamed H.264 320x180 (`avc1.42C00D`) with AAC (`mp4a.40.2`). Media3 discovered the 9:56 duration where the fixture catalog reported zero. This single low-resolution sample does not establish 1080p/AV1/WebM or physical-decoder compatibility.
- A byte-range request returned HTTP 206, `Content-Range: bytes 0-63/64657027`, and 64 bytes.
- Phone fullscreen rotated, hid system bars, and preserved playback position. Back first returned inline; another Back returned to catalog with MediaSession state `NONE`. TV uses the entire screen for video behind translucent controls. Control timeout remains later UI work.
- TV D-pad selected video and forward seek; the dedicated media Pause key changed playback to `PAUSED`. D-pad Down exits the search field; Right/OK selects categories. Home released the TV MediaSession after the lifecycle transition settled. Google TV's own setup prompt interrupted one run; cancelling that system prompt restored the app.
- TV text fields intercept vertical keys before the software keyboard and use explicit focus destinations (Search Up to Server, Search Down to All, address Down to Connect). This corrected the Google TV IME consuming arrows before Compose's ordinary preview handler. The final TV build/lint rerun passed and its APK was installed. ADB UI dumps occasionally exited 137 on the TV; helper interruption/retry is not counted as an app test pass.
- During simulated outage (fixture HTTP 503), both apps launched with two saved items and resume labels; local title search reduced the list to one without contacting a working catalog endpoint. Combining a mismatched title/category yielded zero on phone; TV title/category selection was exercised separately through D-pad.
- Phone was force-stopped while playing and relaunched to the catalog, showing saved `Resume at 4:22`; selecting resumed at that position. The observed pre-kill label was 4:20, with time elapsing before termination. This confirms persistence in that run, not a precision performance measurement or a universal durability guarantee.
- Switching the phone to port 8766 showed no progress for the overlapping `song` ID; returning to port 8765 restored its saved progress.
- The fixture stored watch events and deliberately dropped responses. Retrying produced duplicate acknowledgements (six observed) while each installation's view count stayed one. Local progress continued to advance and retained its session/sequence identity.

Screenshots exist in ignored `client/build/`: `s3-phone-fullscreen.png`, `s3-tv-fullscreen.png`, `s3-phone-offline.png`, and `s3-tv-offline.png`. App-module UI instrumentation and release benchmarks have not run.

After verification, the temporary servers were stopped. The phone's original `http://192.168.10.140:8001` origin was restored and the new TV app was configured to the same origin. It is currently unreachable from both emulators; use Server to enter the actual running home-server address. Fixture catalog/progress remains isolated under its separate origins.

## Reproduce the isolated fixture

Provide a compatible sample file and use an unused port. From the repository root:

```powershell
python client/tools/emulator_fixture.py --sample <sample.mp4> --output client/build/s3-fixture --port 8765
```

The fixture preserves existing output, copies the sample into two categories, and uses the existing backend contract. Its `/__test/control` and `/__test/state` endpoints are fixture-only and do not exist in the production backend. `client/tools/emulator_smoke.py` supplies ADB setup, text-entry, D-pad focus/key, screenshot and visible-text helpers; see its `--help`. Test provisioning changes the emulator's selected server through its setup screen; record/restore the original origin after tests. Never direct fixture control requests at the live home server.

Next work is S4 phone UI and S5 TV focus/overlay experience. Physical family-device/media checks and performance acceptance remain incomplete.
