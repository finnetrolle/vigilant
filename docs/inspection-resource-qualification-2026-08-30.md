# Adversarial request inspection resource qualification

**Work item:** [VIG-21-02](../spec/issues/epic_21/issue_21_02_adversarial_inspection_resource_qualification.md)

- Started (UTC): 2026-08-30T13:24:00.665516Z
- Finished (UTC): 2026-08-30T13:24:26.179500Z
- Verdict: `PASS`

## Fixed environment

- Git revision: `bfca2fa66f43101689147a578aaf2cf7935f1ca7`; worktree dirty during run: `true`.
- OS: `Mac OS X 26.3.1`; architecture: `aarch64`; available processors: `14`.
- Java: `25`.
- Gateway heap limit: 1024 MiB; direct-memory limit: 512 MiB.
- Source limits: per request 8388608 bytes; global retained 67108864 bytes; owners 128; segments per request 128.
- Policy: `config/qualification/politics-resource.conf`, `fast-pii`, 30 second
  per-fragment deadline, shadow-only `ALLOW` reactions.
- Warm-up: Repeated full-profile warm-up cycles establish the baseline only after five consecutive
  post-workload forced-GC observations remain inside a 16 MiB heap/RSS window; the component-wise
  window maxima define the published baseline and warm-up outcomes are excluded from the measured matrix.

## Exact request-shape matrix

| Case | Request bytes | Expected fragments | Inspected fragments | Inspection gaps | HTTP | Audit decision | Coverage | Error | Events | Transport exact | Inspection ms |
|---|---:|---:|---:|---:|---:|---|---|---|---:|---:|---:|
| max-single-fragment | 8388608 | 1 | 1 | 0 | 200 | CLEAN | FULLY_INSPECTABLE |  | 1 | true | 102 |
| max-normalized-fragments | 8388608 | 16384 | 16384 | 0 | 200 | CLEAN | FULLY_INSPECTABLE |  | 1 | true | 625 |
| gap-dense | 8388608 | 0 | 0 | 16384 | 200 | INSPECTION_GAP | UNINSPECTABLE |  | 1 | true | 0 |
| fragment-overflow | 8388608 | 16385 | 0 | 0 | 400 | ERROR | UNINSPECTABLE | UNSUPPORTED_SCHEMA | 1 | true | 0 |

Every accepted case required HTTP 200, byte-identical digest replay at the real
upstream, one matching safe audit event, complete normalized counts, and no silent
truncation. The overflow case required local HTTP 400 with one `UNSUPPORTED_SCHEMA`
audit and no upstream request.

- Single-fragment total inspection duration: 102 ms.
- Max-fragment total inspection duration: 625 ms.
- Gap-dense total inspection duration: 0 ms.
- No new latency threshold is applied; durations are observations of sequential
  per-fragment policy evaluation.

## Concurrent retained-source boundary

- Held admitted requests: 8.
- Held raw source bytes: 67108856.
- Accepted requests completed after release: 8.
- Server-side quota observation: active owners 8; retained bytes 67108856.
- Measured over-capacity outcome: HTTP 503 `{"error":"inspection_capacity_exhausted"}`.
- Measured capacity probes: 1.
- Matching safe audit events: 9.
- Accepted audit outcome: one `CLEAN/FULLY_INSPECTABLE` event per request;
  the sole measured capacity audit is `ERROR/INSPECTION_CAPACITY_EXHAUSTED`.
- Byte-identical replay for every accepted request: `true`.
- Post-cleanup success probe: `true`.

## Memory and cleanup

Raw source bytes below are quota accounting. JVM heap and RSS include the Jackson
tree, decoded strings, fragments, gaps, detector arrays, windows, JVM and native
transport allocations and are intentionally reported separately.

Success is sampled immediately after the accepted matrix; rejection is sampled after a separate
fragment-overflow request. The two cleanup claims therefore have distinct causal observations.

- Memory samples: 10.
- Baseline JVM heap used: 20.8 MiB.
- Peak JVM heap used: 410.7 MiB.
- Final JVM heap used: 20.4 MiB.
- Baseline gateway RSS: 1033.1 MiB.
- Peak gateway RSS: 1033.1 MiB.
- Final gateway RSS: 561.3 MiB.
- Final heap/RSS within the canonical baseline + 64 MiB allowance: `true`.
- Success returned to bounded baseline: `true`.
- Rejection returned to bounded baseline: `true`.
- Client cancellation returned to bounded baseline: `true`.
- Packaged interrupted-upload audit outcome: `ERROR/SOURCE_ERROR`.
- Process shutdown completed within its bound: `true`.
- Exact source owners and retained bytes returned to zero in focused public-seam tests: `true`.
- Inspection executor tasks drained in focused lifecycle tests: `true`.

## Safety and reproduction

- OutOfMemoryError observed: `false`.
- Synthetic body marker, root padding field or protocol locator observed in logs or report: `false`.
- Fixtures contain only generated ASCII structure, synthetic non-sensitive literal
  values, and no production payload.
- Exact cleanup command: `./gradlew inspectionResourceContractTest`.
- Command: `./gradlew inspectionResourceQualification`.

The generated report never includes request bodies, decoded fragment text, matched
text, credentials, or protocol locators.

### Ordered memory samples

| Stage | JVM heap used | Gateway RSS |
|---|---:|---:|
| baseline | 20.8 MiB | 1033.1 MiB |
| periodic-1 | 130.8 MiB | 560.7 MiB |
| post-success | 20.3 MiB | 560.8 MiB |
| periodic-2 | 80.2 MiB | 560.8 MiB |
| post-rejection | 20.3 MiB | 560.9 MiB |
| periodic-3 | 97.7 MiB | 560.9 MiB |
| post-concurrency | 410.7 MiB | 1023.9 MiB |
| post-cancellation | 20.4 MiB | 1027.2 MiB |
| periodic-4 | 20.4 MiB | 586.8 MiB |
| terminal | 20.4 MiB | 561.3 MiB |
