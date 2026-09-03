# Packaged durability qualification

> Superseded historical evidence for completed EPIC-22. EPIC-32 replaced the
> qualified durable subsystem with best-effort stdout audit and VIG-32-02 removed
> its runtime and qualification task. The original result below is preserved
> unchanged as evidence of the former contract.

- Work item: [VIG-22-04](../spec/issues/epic_22/issue_22_04_packaged_durability_qualification.md)
- Remediation: [VIG-22-05](../spec/issues/epic_22/issue_22_05_audit_exhaustion_admission_mapping.md)
- Started (UTC): 2026-08-31T16:28:13.457559Z
- Finished (UTC): 2026-08-31T16:29:08.985722Z
- Verdict: `PASS`

VIG-22-05 separated lifecycle traffic admission from composite readiness. The
GREEN rerun proves exact `503 {"error":"audit_unavailable"}` for retained
capacity while preserving `503 draining` for graceful shutdown.

## Fixed environment and commands

- Git revision: `00687f86390f906d36548687e0395efd5735c377`; worktree dirty during run: `true`.
- OS: `Mac OS X 26.3.1`; architecture: `aarch64`; Java: `25`; Docker: `29.2.0`.
- Persistent-volume filesystem: `apfs`.
- Installed command: `build/install/vigilant/bin/vigilant`.
- OCI image ID: `sha256:84576674798f0c348f622fb3cfa44286441c94e348a0f23efdbfd68b537f65d`.
- Fixed JVM arguments: `-Xms256m -Xmx512m -XX:MaxDirectMemorySize=256m --enable-native-access=ALL-UNNAMED`.
- Default audit bounds: event 65536; pending 128; retained 1073741824; segment 16777216; age 5000 ms.
- Exhaustion audit bounds: event 65536; pending 128; retained 66500; segment 65536; age 100 ms.

## Decision and supported-failure matrix

| Case | HTTP | Client error | Upstream requests | Upstream body bytes | Durable records | Decision | Error | Safe schema |
|---|---:|---|---:|---:|---:|---|---|---:|
| clean | 200 | none | 1 | 91 | 1 | CLEAN | none | true |
| detected | 200 | none | 1 | 110 | 1 | DETECTED | none | true |
| inspection-gap | 200 | none | 1 | 167 | 1 | INSPECTION_GAP | none | true |
| parser-failure | 400 | malformed_message | 0 | 0 | 1 | ERROR | MALFORMED_MESSAGE | true |
| detector-error | 200 | none | 1 | 1048647 | 1 | ERROR | POLICY_DEADLINE_EXCEEDED | true |
| source-failure | 413 | request_too_large | 0 | 0 | 1 | ERROR | REQUEST_TOO_LARGE | true |
| identity-failure | 403 | untrusted_identity | 0 | 0 | 1 | ERROR | UNTRUSTED_IDENTITY | true |
| inspection-failure | 500 | inspection_failed | 0 | 0 | 1 | ERROR | INSPECTION_FAILED | true |

Packaged installed processes produce every externally reachable row. The
`inspection-failure` row is additionally gated by the focused real-Armeria
unexpected-orchestration contract because production provides no failure-injection
configuration and this qualification leaf does not modify production code.

## Audit exhaustion matrix

| Case | HTTP | Client error | Upstream requests | Body bytes before response | Readiness | Bounded | Safe diagnostics |
|---|---:|---|---:|---:|---:|---:|---:|
| admission-queue | 503 | audit_unavailable | 0 | 0 | 503 | true | true |
| event-size | 503 | audit_unavailable | 0 | 0 | 503 | true | true |
| retained-byte | 503 | audit_unavailable | 0 | 0 | 503 | true | true |
| filesystem-write | 503 | audit_unavailable | 0 | 0 | 503 | true | true |
| filesystem-force | 503 | audit_unavailable | 0 | 0 | 503 | true | true |

The retained-byte row is observed on the installed process. Admission queue,
event-size, filesystem-write and filesystem-force failure paths are gated by
`durabilityQualificationContractTest`, whose public request mapping and distinct
real-store causal barriers prove each typed outcome without a production fault switch.

## Causal crash and restart matrix

| Case | Causal barrier | Recovered sequences | Records | Valid set | Client success | Upstream observed | Orphan rule |
|---|---|---|---:|---:|---:|---:|---:|
| before-write | before first frame byte | [] | 0 | true | false | false | true |
| after-write-before-force | complete frame before force | [1] | 1 | true | false | false | true |
| after-force-before-upstream | after force before upstream handoff | [1] | 1 | true | false | false | true |
| after-upstream-before-response | after upstream handoff before client response | [1] | 1 | true | false | true | true |
| after-external-store-before-ack | after external force before ack | [1] | 1 | true | false | true | true |
| after-ack-before-reclaim | after forced ack prefix | [] | 0 | true | false | true | true |

The installed process proves after-handoff crash, partial-tail recovery and
persistent sequence continuation. Before-write, after-write, after-force and
ack/reclaim internal barriers are the forked causal process tests required by
`durabilityQualificationContractTest`; no row uses timestamps as evidence.

### Same-volume recovery

- Exact recovered sequences: `[1, 2]`.
- Partial tail removed: `true`; no sequence reuse: `true`; no acknowledged-record loss: `true`.

## Graceful and forced lifecycle

- Readiness became 503 before drain: `true`.
- Admitted append completed: `true`; active segment sealed: `true`.
- Bounded process exit: `true`; forced tail not accepted: `true`.

## Collector outage, acknowledgement and at-least-once delivery

- Outage reached retained bound: `true`; fail-closed at bound: `true`.
- Durable ack published: `true`; reclaim observed: `true`; readiness recovered: `true`.
- New request succeeded after recovery: `true`.
- Duplicate deliveries of one event ID: 2.
- External events after deduplication: 1; duplicate local sequence: `false`.

## Installed distribution and OCI evidence

- Installed distribution complete runtime seam: `true`.
- OCI image complete runtime seam: `true`.
- Installed distribution: launched `true`; fixed JVM `true`; persistent volume `true`;
  real Armeria upstream `true`; separate Collector `true`; restart recovery `true`.
- OCI image: launched `true`; fixed JVM `true`; persistent volume `true`; real
  Armeria upstream `true`; separate Collector `true`; restart recovery `true`.

## Safety and durability boundary

- WAL safe: `true`; manifests safe: `true`; acknowledgements safe: `true`.
- Stdout safe: `true`; errors safe: `true`; report safe: `true`.
- A successful `force(true)` on the recorded persistent volume proves the process,
  container and retained-volume boundary only. It does not cover volume loss,
  storage corruption, operator deletion, or broken hardware flush semantics.

## Reproduction

- Qualification command: `./gradlew durabilityQualification`.
- Work-item validation: `./gradlew validateWorkItems`.
- Final build: `./gradlew build`.

The qualification command completed successfully with the complete fail-closed
matrix, persistent recovery and payload-free safety boundary.
