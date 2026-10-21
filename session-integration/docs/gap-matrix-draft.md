# Android session gap update (draft)

Prepared for the Android section of the shared
[session gap matrix](https://github.com/grafana/frontend-o11y-knowledge-workbench/blob/main/docs/conventions/mobile-session-management-gap-matrix.md).
Do not replace its supported-configuration claims with this experimental setup.
Flutter, React Native and Swift are outside this update.

## Implemented in this draft

- **Cold restart:** a new ID links to the last durable session through the
  previous-session getter. It does not restore the historical session.
- **Persistence:** versioned, bounded identity/start-time records with atomic
  replacement and one writer per process name. Failure falls back to memory;
  the next launch may link to an older durable session.
- **Manual reset:** the proposed extension immediately replaces the session.
  Concurrent reset rejection and its Boolean return remain provisional.
- **Process isolation:** independent records and buffer directories, plus
  `process.title`. Native-crash capture is restricted to the main process.
- **Replay:** original attribution is required for both native crash recovery
  and accepted buffered telemetry. Use the fresh run results in the
  [evidence note](evidence-20261021.md); a synthetic historical-ID log is not
  native-crash evidence.

These are draft-integration results, not released-package alignment. The normal
Grafana package still uses its unchanged released dependency.

## Gaps that remain

- **Activity lookup:** the pinned upstream `getSessionId()` no longer refreshes
  inactivity. It can still create or rotate a session. An internal interaction
  hook exists, but this does not provide automatic foreground input tracking.
  Replace the older audit's claim that every ID lookup refreshes inactivity;
  do not mark meaningful-activity alignment complete.
- **Foreground inactivity:** automatic input tracking is not implemented here.
  Keep [#2088](https://github.com/open-telemetry/opentelemetry-android/pull/2088)
  separate from manual reset.
- **Sampling:** no saved cross-signal decision or decision-history store.
  [#970](https://github.com/open-telemetry/opentelemetry-android/issues/970)
  remains separate. Existing parent-based trace sampling is not proof of the
  proposed 100% preview policy.
- **Metrics:** export remains disabled. No recording-time session sampling is
  claimed for metrics.
- **Processes:** secondary-process native crashes are intentionally not captured.
  Installation-ID races remain separate from session-record ownership.
- **Validation:** merged API pins, the agreed configuration, physical-device
  storage/startup behavior and hosted ingestion/UI still need their own evidence.
  API 23 native-crash replay remains unverified: the emulator's SIGABRT process
  did not terminate. API 35 passed that check; both passed the other seven groups.

## Before #57 can close

1. Land the required upstream APIs and adapt this draft to the merged code.
2. Agree on the preview scope and record accepted limits explicitly on #57.
3. Finish the supported Grafana package integration, including its sampling
   configuration, and rerun the focused tests against that exact build.
4. Review the guide and evidence, and give any remaining gaps an explicit
   follow-up or accepted limitation. Package publication remains tracked by #113.

Nothing in this draft changes the accepted contract or closes an issue.
