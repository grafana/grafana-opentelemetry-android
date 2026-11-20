# Android sessions: release and close-out checks

This is the remaining test plan for [#57](https://github.com/grafana/frontend-o11y-knowledge-workbench/issues/57),
not a completion record. Keep [#16](https://github.com/grafana/grafana-opentelemetry-android/pull/16)
draft and test-only. Do not merge this standalone project into `main`.

## Before the release

- [x] Retain the existing regression, real-time timeout and Debug phone evidence.
  See [real-time waits](2026-11-02-real-time-timeouts.md) and
  [Pixel 7 results](2026-11-02-pixel7.md). They cover pinned source, not a supported package.
- [x] Prepare and exercise the opt-in [startup comparison](../README.md#startup-comparison).
  [November 2 results](2026-11-02-release-preparation.md) cover API 23/35 Release
  and API 35 Debug, with APK/source pins, raw samples retained locally and stated
  noise limits. This validates the comparison, not production performance.
- [ ] Prepare the hosted target record below. Do not guess the stack, app or data
  source, reuse another experiment's permission, or send telemetry before approval.

The latest release checked on November 2 was v1.8.0; it predates reset
[#2141](https://github.com/open-telemetry/opentelemetry-android/pull/2141).
Recheck at execution time. No additional upstream implementation is needed for
the accepted private-preview scope.

## 1. Verify the released dependency

- [ ] Check the release/tag and published artifacts, not just a merge or version number.
  In a clean upstream checkout, fetch the candidate tag and check that it contains
  reset merge `cc2c7d504a093fb0c637b0ab6f01473e921e6744` and previous-session getter
  #2126. Inspect the released `IncubatingOpenTelemetryRum.startNewSession()` and
  `SessionStorage.getPreviousSession()` signatures and behavior. If changes were
  backported, compare the actual released code rather than relying on ancestry.
- [ ] Resolve the candidate BOM, agent, session and native-crash artifacts from
  their published repository in a fresh dependency cache. Record the dependency
  report and checksums. No snapshot, local Maven substitution or moving branch.

Useful read-only starting points:

```sh
gh release view --repo open-telemetry/opentelemetry-android \
  --json tagName,publishedAt,url
gh pr view 2141 --repo open-telemetry/opentelemetry-android \
  --json state,mergedAt,mergeCommit
gh pr view 2126 --repo open-telemetry/opentelemetry-android \
  --json state,mergedAt,mergeCommit
```

## 2. Integrate the normal package

- [ ] Start a focused PR from current Grafana `main`, not this draft branch.
  Port persistence, restart linking, linked reset and process configuration into
  `android/`, with their tests. Review any changes since the pinned probe.
- [ ] Keep the accepted configuration: 15-minute background timeout, four-hour
  maximum lifetime, no client trace/log sampling, metrics off, independent process
  chains and main-process-only native-crash capture. Foreground-idle rotation,
  configurable sampling and native unwinding are not part of this close-out.
- [ ] Preserve the incubating API boundary. Test rejected/busy reset, exceptions,
  shutdown and custom-provider incompatibility; do not add automatic reset retries.
- [ ] Run package checks, API compatibility, lint and Debug/Release builds. Validate
  the produced AAR through the existing separate Maven consumer, including removal
  of the Grafana layer. Review any intentional API baseline changes.
- [ ] Adapt this existing probe to consume that exact AAR and its published upstream
  dependencies. Remove the source-set copies of `android/src/main` and draft sources
  from that validation build; prove the dependency graph contains the intended artifact.
  The current source-compiling probe cannot count as packaged-consumer validation.

Grafana publication credentials belong to the packaging work. A local test-repository
AAR with released upstream dependencies can test the package; do not imply that the
Grafana artifact has been publicly released. Do not publish as part of these checks.

## 3. Run the acceptance checks

Reuse `verify.py` and the tests in this standalone build after the artifact adaptation.
Record the exact candidate AAR/APK and source revisions for every run. Any subsequent
relevant change invalidates the corresponding evidence.

| Check | Required observation | Existing test to reuse |
| --- | --- | --- |
| Cold start and restart | New ID, one linked start, no historical session ended | Eight-scenario regression |
| Manual reset | Old end before linked new start; existing spans retain original ID | Regression and JVM reset tests |
| Background expiry | 14-minute return retains; 16-minute return replaces and links | `--real-time` |
| Foreground and lifetime | Idle 16 minutes retains; 3h59 retains; 4h1 replaces and links | `--real-time` |
| Storage failures | Corrupt recovery; unknown format preserved; failed writes keep telemetry working and use last durable link | Regression and storage JVM tests |
| Process ownership | Main/worker chains stay independent; competing writer rejected; buffers remain isolated | Regression and ownership JVM tests |
| Sampling and metrics | Root and all four parent cases export correlated logs/spans; no metric request | Every telemetry-producing regression launch |
| Buffered replay | Historical session IDs survive export failure and process death | Regression's local HTTP 503 fixture |
| Native crash replay | Natural abort, replay once under the crashed session, no duplicate on another restart | Regression's native abort fixture |
| Startup/storage cost | Controlled comparison, correct durable records, reviewed timings and limitations | `--startup-comparison-pairs 10` plus phone investigation below |

- [ ] Run Debug and R8 Release on the minimum supported API and a current API.
- [ ] Repeat the bounded regression on a physical phone. Never root the phone or
  close Vysor/PiP. The present phone driver requires Debug and does not prove R8 behavior.
- [ ] Resolve the non-arm64 runtime gap using a compatible device/runner, or record
  explicit follow-up acceptance. Building all four ABIs is not running all four.
- [ ] For physical-device performance, compare the same candidate/build mode on the
  same phone. Review synchronous initialization cost and record growth. The comparison
  does not time asynchronous fsync or battery/Doze behavior; use a focused device trace
  if those costs need investigation. Do not label the earlier Debug observations a
  production benchmark. Agree any acceptance limit before claiming a performance pass.

Full-duration phone, other device models and R8 phone runs are evidence limits, not
automatically new scope. Keep required checks separate from suggested extra coverage.
For any required check carried forward, record who accepted it, why, its owner and
the linked follow-up. A note saying "not tested" is not acceptance.

## 4. Hosted ingestion and product UI

This portion is prepared but not run. The current driver has only local loopback
collectors; it has no hosted destination switch or forwarding path.

Before execution, record privately:

- Approved non-production stack, application, collector endpoint and time window.
- Receiver/frontend versions, storage backend, relevant flags and data sources.
- Exact candidate AAR/APK, upstream versions, device/API/build mode and a unique run label.
- Agreed test volume and cleanup/retention. Do not use customer traffic, commit app
  keys or tokens, or change stack settings as part of the test.

After approval, use the artifact-backed probe/consumer to generate fresh synthetic
events with the same test cases. Add hosted configuration only in that explicit run;
do not replay old raw logs or assume a successful HTTP response proves storage.

1. Capture session IDs, predecessor IDs, event timestamps and trace/span IDs for a
   cold start, restart and manual reset. Keep a span open across reset and end it
   afterward. Record the expected chain before opening the UI.
2. Query the actual backend records for that run. Compare session links, timestamps,
   trace/log correlation and process identity. Account for the receiver's current
   `session.start` to `session_start` mapping; verify it in the deployed version.
3. Find the same sessions in the Sessions UI and open their details. Check early
   startup telemetry, linked sessions, old-span attribution, environment/app version
   and the session timeline. A zero-row UI with stored backend records is still a gap.
4. Exercise main and worker processes, then a controlled export outage using only a
   local test proxy/receiver, not a shared-service outage. Restore it and verify that
   buffered records retain their original session and process in storage and the UI.
5. Trigger one probe-native abort, restart and restart again. Verify exactly one
   recovered crash against its original session in storage and the UI, not the new
   launch. This is attribution testing, not native-stack-unwinding work.
6. Check the root and sampled/unsampled-parent cases reach storage; confirm metrics
   stay disabled from the candidate's configuration and local request capture.
   Lack of a metrics chart alone is not proof. Do not infer cross-signal sampling.

Retain query time ranges and redacted screenshots with a small expected/actual table.
Keep raw records and credentials local. Record failures as well as passes, and
separate backend evidence from UI evidence.

## 5. Close #57

- [ ] Package integration is merged and the supported combination is identified.
- [ ] Each required check above has current evidence or explicit accepted follow-up.
- [ ] Update the Android guide, gap matrix and #57 with supported versions,
  configuration, results and accepted limits. Preserve dated probe evidence as history.
- [ ] Reconcile every remaining #57 checkbox against that evidence, then close it
  with permission. A released upstream API alone does not complete these steps.
