# Real-time session timeout probe, November 2, 2026

All requested real-time cases passed on the local API 35 arm64 emulator.
This is evidence for the unreleased, test-only session integration. It does not
validate the supported Grafana Android package or close workbench #57.

## Environment

- Probe base: `92c5038c4bc3311cc2866196212809569e9ffe8a`, with the local
  real-time observation and verifier additions. No SDK clock or timeout changes.
- OTel source: `e13e9d8967e02accb3d47a43c8e0ea636876f4a1`, using the isolated
  local artifacts described in the parent README. No upstream source edits.
- AVD: `replay_probe_api35`, serial `emulator-5580`, API 35, `arm64-v8a`.
- Fingerprint: `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
- R8 Release APK, non-debuggable, signed with the local debug key. Root was used
  only to access the sandbox as the app UID; the app ran under its normal UID.
- APK SHA-256: `c48d0e6a7e66552dece32718302a1da75f6b9e5c0c2160c13e6d253bfeb3af91`.
- Loopback OTLP receiver only. No Grafana Cloud traffic or hosted checks.
- Checkpoint window: 13:03:13 to 17:34:28 UTC, about 4h31m15s.

## Observations

| Check | Device elapsed time | Expected and observed |
| --- | --- | --- |
| Return before the background timeout | 14m0.213s in background | Original session retained |
| Return after the background timeout | 16m0.179s in background | New session linked to the original |
| Foreground-idle control | 16m0.100s | Same session retained |
| Before maximum lifetime | 3h59m0.123s | Same session retained |
| After maximum lifetime | 4h1m0.113s | New session linked to the previous one |

The last three durations start at the checkpoint following the 16-minute
background return. Independent host-monotonic measurements were 960.053s,
14340.076s and 14460.069s. Background host measurements were 840.717s and
960.700s. No clock was advanced or timeout shortened.

PID `3416` and launch identity `ca859a12-d691-4819-9a7a-e430f18e8064` stayed
unchanged throughout. The recorded activity history was foreground, background,
foreground, background, foreground, with no further transitions during the
lifetime check.

The receiver recorded exactly three session starts and two ends in the expected
order, with correct previous-session links. Every checkpoint produced one log
and one span attributed to the expected session. All six checkpoints were checked
again against the final export set. Collector errors were empty; no metric
exports were accepted. All 36 recorded source/APK hashes matched at close-out.

## Reproduction and evidence

From the repository root, with the existing virtual environment and rebuilt
Release probe installed:

```sh
/usr/bin/caffeinate -i session-integration/build/probe-python/bin/python -u \
  session-integration/probe/verify.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5580 \
  --apk session-integration/probe/build/outputs/apk/release/probe-release.apk \
  --root-files --real-time \
  --evidence session-integration/build/evidence/realtime-api35-20261102-01
```

Use a new evidence directory for another run. The local, ignored directory above
contains `environment.json`, `inputs-sha256.json`, `installed.apk`,
`realtime-checkpoints.json`, `results.json`, `otlp-records.json`,
`collector-errors.json` and `logcat.txt`. Do not upload those automatically.

The fresh JVM run passed 81 integration tests and 14 probe tests. All 11 Python
verifier tests passed, including mutations for missing/duplicate records, wrong
session attribution, transition order, broken links, changed process identity,
shortened waits and clock disagreement. Formatting, lint, coverage checks and
Debug/Release builds also passed.

The same APK then passed all eight existing probe scenarios without
`--real-time`: restart links, manual reset and in-flight attribution, process
isolation, corrupt/future records, write failures, last-durable restart links,
buffered replay and native `abort()` replay. That run's evidence is in
`session-integration/build/evidence/realtime-api35-20261102-regression-01`;
collector errors were empty. Both drivers exited successfully.

## Limits

- Activity timestamps approximate SDK process-lifecycle callbacks. These checks
  bracket the timeout with one-minute margins; they do not establish the exact
  millisecond when expiry takes effect or when a session-end event is emitted.
- Expiry is observed at the next checkpoint. The lifetime run alone cannot
  distinguish creation time from the last foreground return, since those occur
  together here. Creation-time semantics were checked in the pinned source.
- Foreground means an awake, visible test activity. This is not a Doze,
  low-memory process-death or physical-device battery test.
- API 23 and other ABIs did not receive a real-time run today. JVM coverage of
  API 23 is not equivalent to that run.
- Supported-package, physical-device and hosted-ingestion validation still need
  their own evidence. Earlier upstream `forceFlush` flakiness was not investigated
  or claimed resolved by this run.
