# FamilyTube agent instructions

These instructions apply to the repository. Follow the user's latest request when it changes the product scope or implementation plan.

## Read before working

1. Read `client/README.md` for project status and document links.
2. Read `client/ARCHITECTURE.md` for architecture and product behavior.
3. Read `client/EXECUTION_PLAN.md` for stage dependencies, acceptance criteria, and progress.
4. Inspect the relevant source files and working-tree changes before editing. Preserve unrelated work.

The current Android client is a design, not an implemented application. Verify repository state on each task; do not assume planned modules, tools, tests, or APKs already exist.

## Product requirements

- Build native Kotlin Android phone and Android TV apps under `client/` with a shared core.
- Stream the family library from a home server/NAS over Wi-Fi. Normal viewing must work without internet access.
- Use the existing Python backend under `backend/`. Extend its API compatibly when needed.
- Aim for familiar YouTube-style browsing and controls with FamilyTube branding, fast feedback, and smooth playback.
- Do not implement an in-app mini-player. Leaving watch saves progress and stops playback; switching inline/fullscreen preserves playback.
- Shorts is a later phase for small, short-form videos. Do not add Shorts UI, feeds, or content restrictions to the first release.
- Per the latest request, advance to the next family-library video five seconds after completion, with cancellation and immediate Next controls. Keep future parent-controlled settings behind the local PIN gate.
- Recommendations and search only use the supplied family library. Do not add public video discovery, accounts, ads, or tracking services.
- Offline downloads, shared child profiles, PiP, casting, and adaptive streaming are outside the first release.

## Execution workflow

- Implement the stage or scope requested by the user. A request for planning or documentation does not authorize starting app implementation.
- For an implementation request, start with the earliest unmet prerequisite in the authorized scope. Continue through authorized work without requesting routine stage-by-stage approval.
- Keep each increment buildable and reviewable. Prefer a working phone/TV playback slice before broad UI polish.
- Update the stage tracker in `client/EXECUTION_PLAN.md` when starting or finishing a stage. Record changed areas, commands/results, device evidence, unresolved issues, and the next action.
- Mark a stage complete only when its exit criteria are satisfied. If hardware is unavailable, distinguish implemented code from pending device verification; continue independent authorized work.
- Resolve routine implementation choices and document material deviations. Ask only for missing information that blocks dependent work; never invent device specifications or benchmark results.
- Introduce backend contract changes before client behavior that depends on them. Describe migration and compatibility behavior.
- Keep architecture, execution plan, and README consistent when scope or behavior changes.

## Client implementation rules

- Use Compose for phone, Compose with TV Material for TV, and Media3 ExoPlayer for playback. Use one compatible pinned version across Media3 artifacts.
- Share models, repositories, playback logic, storage, and design tokens. Keep touch navigation and TV focus/navigation in their respective app modules.
- Keep dependency direction as specified in the architecture. Avoid creating modules or abstraction layers with no concrete need.
- Route playback commands through one coordinator. Never create a player per card or within a composable body.
- Preserve the player through fullscreen/configuration changes; stop on leaving watch and handle background lifecycle explicitly. Prevent stale asynchronous selections from replacing newer ones.
- Keep file I/O, Room operations, media cache initialization, and network work off the main thread. Update only the UI that needs each progress tick.
- Bound preloading by candidate count and memory; playback takes priority. Build the preload manager and player with shared compatible configuration.
- Use a single cache owner per cache directory. Version media cache keys; never serve replaced content indefinitely under an unchanged URL.
- Keep API time units explicit: playback values in seconds at the existing API boundary, milliseconds in the client, and epoch milliseconds for timestamps.
- Persist progress before network sync. Preserve session IDs and sequence numbers on retries. Treat the existing history as per-device, not cross-device profiles.
- Keep the server configurable. Allow home-network operation without validated internet. Apply the network permission behavior required by the chosen target SDK.
- Make TV navigation fully usable with D-pad, OK, Back, and media keys. Restore focus/scroll on return; do not require touch input.

## Backend and data rules

- Preserve existing endpoints and device-ID semantics unless an explicitly documented migration changes them.
- Keep video delivery seekable through byte-range requests. Prepare incompatible media before viewing, never in the Play request path.
- Preserve original videos, metadata, watch databases, and user settings. Use temporary fixtures/output directories for tests and media experiments.
- Do not treat `X-Device-ID` or the client PIN as backend authentication.
- Keep generated APKs, build output, media files, local SDK paths, keystores, and credentials out of source control. Store signing secrets outside tracked files.
- Do not deploy, publish, or change the live home server unless that action is within the user's authorized task.

## Verification and reporting

- Run checks relevant to the change. For documentation-only edits, check links, stage dependencies, and consistency; app tests are unnecessary.
- Existing backend tests can be run from the repository root with `python -m unittest discover -s backend -p "test_*.py"`. Confirm the available Python executable first.
- After scaffolding, use the checked-in Gradle wrapper. Document actual build, lint, unit-test, and instrumentation commands in the client README; do not claim proposed commands have run.
- Test meaningful risks: API conversion, retry idempotency, cache invalidation, selection races, lifecycle behavior, TV focus, and failure recovery.
- Measure release-like builds on physical phone and TV hardware for performance acceptance. Distinguish emulator checks from device evidence.
- Report changed behavior, verification performed, remaining limitations, and the next incomplete stage. Never claim a target, screenshot, APK, or test result that has not been produced.
