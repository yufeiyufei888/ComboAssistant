# Project instructions

Before changing or validating this Android project, read [docs/testing.md](docs/testing.md). That document is the source of truth for local, CI, and Redmi K80 Pro verification.

- Preserve the no-network, no-Root, no-`SYSTEM_ALERT_WINDOW` privacy boundary.
- Keep playback target-package and orientation gates, serialized gesture callbacks, and emergency stop behavior intact.
- Keep recording offline and continuous: never reintroduce per-gesture mirror injection into the capture path.
- Keep normal overlays locked; position/size/opacity changes belong to the transactional layout mode.
- Preserve v0.1 Room timeline compatibility and the existing DataStore file/key names.
- Run the smallest relevant tests after each change and distinguish static/automated verification from Redmi K80 Pro acceptance.
- Do not claim real-game compatibility without device evidence.
- Keep API 35 instrumented tests in every PR/main CI run and API 26 compatibility tests in the scheduled/manual job.
- Never publish the temporary CI-signed APK as the Beta. The pre-release files are `ComboAssistant-v0.2.0-beta.1-debug.apk` and `SHA256SUMS.txt`, rebuilt from merged `main` and checked against the signer recorded in [docs/testing.md](docs/testing.md).
