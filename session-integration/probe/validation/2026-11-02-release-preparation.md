# Session release preparation, November 2, 2026

The existing probe now has a persistence-on/off startup comparison. The
[release checklist](release-checklist.md) maps the remaining package, device,
hosted and documentation checks to #57. This prepares those checks; it does not
validate a supported Grafana release or close the issue.

## Inputs

- Probe base: `47394d43c7e4134e1b76657433038260c8a6a76e`, with the local
  comparison, verifier and documentation changes described here.
- Upstream: `e13e9d8967e02accb3d47a43c8e0ea636876f4a1`, through the existing
  isolated local Maven repository. Normal package and SDK implementations are unchanged.
- API 23 and API 35 arm64 emulators, disposable read-only AVD instances.
  R8 Release uses rooted sandbox access; API 23 was checked with SELinux enforcing.
- Only the probe app and a host-loopback OTLP receiver are used. No hosted traffic.
- Debug APK SHA-256:
  `5417e32289411856178b46249ad49f38461c7eb15496747a72a7d1b6c1d089f4`.
- R8 Release APK SHA-256:
  `98e1202cebd2371bb3c3abe5f5d479143ebdd220ac3ddf7e2065452d898b10c8`.
- Probe application SHA-256:
  `77eb57dc4114078e7aafe60e37c0fd90d0e7185eb2e59897ed780e09b2405efa`.
- Final verifier SHA-256:
  `d5dba974f039e60ad403d881af6d20ed9a8df2bfd2dd608072b8923c9766b9eb`.

The pre-push rebuild passed the same build and unit checks. Its Debug APK hash was unchanged;
its Release APK hash was
`e6222cd5a1009500f599fc0e9d20fbbfbecdefd66858322eb2ca9622e99fe49c`.
SHA-256 comparison of all 143 unique ZIP entries confirmed identical payloads,
and both Release APKs verified with the same debug signing certificate. The
runtime evidence below still identifies the original tested APK, not a new run
of the rebuilt container.

## Checks

The fresh build passed formatting, lint, coverage checks, 99 JVM tests and
Debug/Release builds. Both native libraries are present for all four ABIs;
runtime checks here cover arm64 only. All 22 Python verifier tests passed,
including invalid options, wrong attribution, changed history, missing timing
and remote file-read errors.

| Run | Result | Local evidence directory |
| --- | --- | --- |
| API 23 R8 Release regression | All eight scenarios passed | `preparation-regression-api23-release-01` |
| API 35 R8 Release regression | All eight scenarios passed | `preparation-regression-api35-release-02` |
| API 23 R8 Release startup comparison | Two alternating pairs plus two warmups passed | `preparation-startup-api23-release-05` |
| API 35 R8 Release startup comparison | Ten alternating pairs plus two warmups passed | `preparation-startup-api35-release-03` |
| API 35 Debug startup comparison | Two alternating pairs plus two warmups passed through `run-as`, without root-file mode | `preparation-startup-api35-debug-01` |

The regression covers restart/reset links, existing-span attribution, independent
processes, rejected competing writers, corrupt/future records, denied writes,
last-durable links, buffered replay and one-time native-crash replay. The comparison
requires fresh processes and sessions, correct restart links, unchanged durable
bytes in the control and all five sampling cases. Both modes keep metrics disabled.

The API 23 regression preceded the final comparison-only JSON validation guard;
its regression path and APK were unchanged. All three final comparison runs and
the API 35 regression have matching current input hashes. Collector error lists
were empty in all five runs. Earlier attempts remain local rather than being
overwritten by passing runs.

For the ten-pair API 35 Release run, synchronous SDK setup had these observations:

| Measurement | Minimum | Median | Maximum |
| --- | --- | --- | --- |
| In-memory control | 9.41 ms | 15.16 ms | 21.00 ms |
| Persistence enabled | 10.45 ms | 17.30 ms | 29.09 ms |
| Paired persistence-minus-control delta | -5.47 ms | 1.29 ms | 15.60 ms |

Every measured record was 90 bytes. Negative deltas and the spread illustrate
measurement noise; there is no performance verdict. Both emulators were running
on the host, though their test drivers ran sequentially.

## Issues found while testing

- API 23 root-created fixture files lacked the app's SELinux category. The driver
  now restores labels on this test app before rooted-emulator launches. It does
  not change enforcement or use this operation on phones.
- The API 23 test image lacked `base64`, and its shell returns CRLF timing output. Record
  comparison now reads raw bytes through `adb exec-out`; timing parsing accepts
  either line ending.
- A failed remote `cat` can return exit code zero with an error message. The
  comparison now requires valid JSON matching the last durable session, so two
  identical error messages cannot pass as an unchanged record.
- Activity Manager timing includes a persistence-only duplicate-owner check in
  the probe. That timing is retained for diagnostics, not presented as a clean
  comparison. The SDK timer excludes the check and configuration construction.

## Reproduction

From the repository root, with the upstream checkout and local artifacts prepared
as described in the parent README:

```sh
"$UPSTREAM/gradlew" -p session-integration spotlessCheck check \
  assembleDebug assembleRelease koverXmlReport \
  :probe:assembleDebug :probe:assembleRelease --max-workers=2 --rerun-tasks

session-integration/build/probe-python/bin/python -m unittest discover \
  -s session-integration/probe -p test_verify.py -v

"$ANDROID_HOME/platform-tools/adb" -s "$SERIAL" root
"$ANDROID_HOME/platform-tools/adb" -s "$SERIAL" install -r \
  session-integration/probe/build/outputs/apk/release/probe-release.apk

session-integration/build/probe-python/bin/python session-integration/probe/verify.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial "$SERIAL" --root-files \
  --apk session-integration/probe/build/outputs/apk/release/probe-release.apk \
  --evidence session-integration/build/evidence/new-release-regression

session-integration/build/probe-python/bin/python session-integration/probe/verify.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial "$SERIAL" --root-files \
  --apk session-integration/probe/build/outputs/apk/release/probe-release.apk \
  --startup-comparison-pairs 10 \
  --evidence session-integration/build/evidence/new-release-comparison
```

Use a fresh evidence directory for each run. These directories retain the installed
APK, input hashes, expected/actual results, timing samples and local exports. Keep
raw logs and records local.

For the Debug file-access check, install `probe-debug.apk`, pass it through `--apk`,
omit `--root-files` and use two pairs. This checks the shared `run-as` reader on an
emulator, not physical-device behavior or USB forwarding.

## Limits

This is source-compiling probe evidence. The published upstream release, normal
Grafana package integration, artifact-backed consumer and hosted/UI validation
remain outstanding. No phone run or full-duration wait was repeated for this
change; earlier dated evidence remains separate.

Startup samples are warm-filesystem observations on a shared host, not a
performance pass. The comparison does not time asynchronous fsync, isolate disk
cost, or cover battery/Doze behavior. It has no agreed performance threshold.
Earlier Pixel 7 timings included configuration construction and are not directly
comparable. Other ABIs were built, not run. Required gaps need current validation
or explicit follow-up acceptance before #57 closes. Keep #16 draft and test-only.
