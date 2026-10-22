# Android sessions: guide draft

Draft for [#57](https://github.com/grafana/frontend-o11y-knowledge-workbench/issues/57).
This is not a supported package setup yet. The integration uses the merged
getter and unmerged reset pin in [the README](../README.md#pins). It does not
change the published package or the accepted cross-SDK contract.

## Initialize once per process

Initialize in `Application.onCreate`, before emitting telemetry. Supply the real,
stable Android process name, including any `:worker` suffix, not the PID. The
local probe shows one way to obtain it on API 23 and later.

```kotlin
// Internal draft API, not available in a released package.
val runtime = DraftSessions.initialize(application, configuration, processName)
val rum = runtime.rum
```

Each process gets its own session record in `noBackupFilesDir`, an exclusive
writer lock, its own telemetry buffer directory and a `process.title` resource
attribute. Main-process native-crash capture stays enabled when its module is
present. Secondary processes suppress it because upstream crash storage is
app-wide. They can still buffer logs and traces in their own directories.

Do not share a session record between processes or close the storage when an
Activity is destroyed. Clearing app data breaks continuity. The upstream
installation ID is separate and still uses app-wide preferences; process
isolation here does not establish concurrent first-install ID consistency.

## Restart and reset

A cold process launch creates a new session. The first built-in `session.start`
links to the last saved ID through `session.previous_id`. It does not resume the
old session or emit another `session.end` for that historical session.

`runtime.startNewSession()` ends the current session and replaces it immediately.
There is no session-free interval. The current upstream draft returns `false`
when another change is still being delivered; it does not queue that call.
This return contract is still under review. Do not spin or retry indefinitely.

Spans already started keep their original ID after reset. New logs and spans
use the new ID. Replayed records keep the ID captured when they were recorded;
do not relabel them with the current session.

Stop telemetry callers before shutdown. `runtime.close()` releases storage even
when SDK shutdown throws. Initialization failure also releases it. In either
case, restart the process instead of trying to initialize the SDK again.

## Storage failures

Missing or malformed records start fresh. Unknown versions, oversized records
and unreadable existing records are preserved. An unavailable directory or a
competing owner falls back to memory. Telemetry and reset must still work when
a session write fails.

After a failed write, the next launch can link only to the last durable session,
which may be older than the immediately preceding process. `storageFailure`
keeps the latest failure; success does not clear it. Writes are atomic and synced, but not a device power-loss
guarantee. Physical-device startup and storage costs still need measurement.

## Configuration that still needs agreement

The [preview proposal](https://github.com/grafana/frontend-o11y-knowledge-workbench/issues/57#issuecomment-6324231741)
asks to keep the 15-minute background timeout, four-hour maximum lifetime,
client-side 100% session sampling and disabled metric export for the private
preview. The Slack clarification is not yet recorded as an accepted scope on
#57; keep public-preview follow-ups separate.

This draft does not detect foreground user inactivity, persist sampling decisions
or add configurable all-signal sampling. It leaves the upstream trace sampler
unchanged: parent-based sampling can still drop spans with an unsampled parent.
Do not describe that default as a verified 100% all-signal policy.

Before publishing this guide, replace the draft API and snapshot references with
merged implementations, settle the preview limits and run the final integration
against that exact configuration. Native `abort()`/replay passed on API 23 and
35 in the minified release probe. API 23's signal-only trigger still times out,
including without OTel capture; see [the evidence](evidence-20261022.md) and
[gap update](gap-matrix-draft.md). This is not general signal-handler or
physical-device validation.
