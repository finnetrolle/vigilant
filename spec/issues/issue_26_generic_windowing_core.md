# VIG-26: Универсальное ядро windowed inspection

- **ID:** `VIG-26`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** Medium
- **Зависит от:** [EPIC-07](../epics/epic_07_windowed_payload_processing.md), [VIG-11](issue_11_fast_pii_policy_adapter.md)
- **Блокирует:** нет
- **Связанные требования:** `PERF-03`, `PERF-04`, `CONC-01`, `CONC-02`, `CONC-03`
- **Оценка:** 3-5 инженерных дней
- **Уверенность:** Medium

## Контекст

`io.vigilant.windowing` реализует protocol-agnostic алгоритм: UTF-8-safe
window generation, ownership cores, capability-derived overlap, translation
offsets, aggregation, deterministic deduplication, cancellation и bounded CPU
execution. Однако его production API и implementation напрямую используют
`PiiDetector`, `PiiFinding`, `PiiType` и Fast PII capability. Это делает
алгоритмическое ядро PII-specific, хотя PII-specific остаются только вызов
detector-а, metadata finding, identity, metadata comparison и ordering.

Нужен reusable generic core без добавления второго production detector-а.
Нынешний Fast PII path остаётся единственным runtime consumer и сохраняет
своё observable behavior.

## Результат

`io.vigilant.windowing` предоставляет PII-free generic executor для одного
decoded text fragment. Он принимает immutable detector invocation input и
typed detector contract, возвращает immutable global findings либо typed safe
error. Fast PII остаётся отдельным тонким adapter-ом над generic core.

Ни HTTP path, ни policy semantics, ни detector taxonomy, ни result Fast PII
не меняются.

## Public seam

Generic seam выполняет одну inspection задачу на существующем bounded CPU
executor:

```kotlin
fun <I, F, K : Any> inspect(
    fragment: InspectableTextFragment,
    input: I,
    contract: WindowedDetectorContract<I, F, K>,
): Future<WindowedInspectionResult<F>>
```

Точные Kotlin class names могут следовать project naming conventions, но
следующие типы, ownership и semantics обязательны.

### Входные данные

```kotlin
class InspectableTextFragment(
    val text: String,
    val provenance: FragmentReference,
)

data class WindowedCapability(
    val version: String,
    val maxWindowUtf8Bytes: Int,
    val maximumEvidenceSpanUtf8Bytes: Int?,
)

data class LocalFinding<F>(
    val value: F,
    val startUtf8: Long,
    val endUtf8: Long,
)
```

- `text` — полный decoded logical fragment; core не конкатенирует fragments.
- `provenance` — opaque immutable reference; `toString()` не раскрывает text.
- `I` — immutable detector-specific input snapshot. Для Fast PII это current
  enabled PII type set.
- `F` — immutable detector-specific finding metadata без ownership core.
- `K` — immutable stable semantic identity для duplicate detection.
- `WindowedCapability` задаёт detector-specific bound и доказанный evidence
  span. Unbounded span сохраняет existing direct-only/`WINDOWING_UNSUPPORTED`
  behavior.

`WindowedDetectorContract<I, F, K>` обязан предоставить capability, полный
`detect(window, input): List<LocalFinding<F>>`, semantic identity,
metadata-equivalence и canonical comparator для global findings. Contract
владеет detector-specific semantics; generic core не интерпретирует `I`, `F`
или `K` и не зависит от PII package.

### Результат

```kotlin
data class GlobalFinding<F>(
    val value: F,
    val startUtf8: Long,
    val endUtf8: Long,
)

sealed interface WindowedInspectionResult<out F> {
    data class Success<F>(
        val provenance: FragmentReference,
        val findings: List<GlobalFinding<F>>,
    ) : WindowedInspectionResult<F>

    data class Error(
        val code: WindowedInspectionErrorCode,
    ) : WindowedInspectionResult<Nothing>
}
```

`Success.findings` — immutable canonical snapshot in original fragment UTF-8
coordinates. `Error` не содержит fragment/window text, partial findings или
raw exception. Cooperative cancellation остаётся `CancellationException`.

## Граница ответственности

### Generic core

- validates Unicode, capability и local UTF-8 offsets;
- plans sequential ownership cores and actual left/right context;
- invokes one detector window at a time on bounded CPU executor;
- translates local offsets into global original-fragment offsets;
- assigns finding to core containing its global start;
- deduplicates with `K`, rejects conflicting metadata and returns canonical
  immutable aggregate;
- stops new calls after first detector error and returns no partial aggregate;
- preserves existing cancellation and bounded-memory semantics.

Private working state может использовать one materialized `String` window и
`MutableMap<K, GlobalFinding<F>>`; it must not retain raw fragment/window text
after terminal completion.

### Fast PII adapter

- owns `FastPiiWindowCapability` proof and invokes `FastPiiDetector` with
  current `stopOnFirst=false` and enabled types;
- converts between `PiiFinding` and generic local/global metadata;
- provides PII semantic key, metadata equivalence и canonical PII ordering;
- maps generic result/errors back to current PII-facing result consumed by
  `FastPiiPolicyAdapter`.

`FastPiiPolicyAdapter`, policy result mapping and DI wiring remain unchanged
from their callers' perspective.

## Критерии приёмки

- [x] Generic core production files import no `io.vigilant.detectors.pii.*`
  type and can inspect a synthetic non-PII detector through public seam.
- [x] Public generic contract carries `I`, `F`, `K`, local/global UTF-8 spans,
  capability, immutable result and typed error exactly as defined above; no
  nullable/empty-list convention replaces explicit errors.
- [x] Existing Fast PII boundary corpus produces exactly same finding count,
  metadata, original UTF-8 offsets, canonical order and duplicate behavior as
  before extraction, including all supported PII formats and Unicode widths.
- [x] Existing error matrix remains unchanged: invalid capability, unbounded
  oversized fragment, invalid local detector result, conflicting duplicate,
  invalid Unicode and detector failure yield current safe outcome with no
  partial findings; cancellation propagates unchanged.
- [x] Generic synthetic-detector tests prove cross-window discovery,
  core ownership, global offset translation, duplicate suppression and
  contract-controlled ordering without importing PII types.
- [x] Executor remains sequential per fragment, uses existing bounded CPU
  executor, starts no new detector call after cancellation/error and retains
  no more than one materialized window plus aggregate state.
- [x] `FastPiiPolicyAdapter` focused tests and real Armeria shadow proxy
  characterization tests preserve current HTTP, audit and policy behavior.
- [x] Modified Kotlin declarations and tests contain accurate KDoc; focused
  tests, `./gradlew build` and `./gradlew validateWorkItems` pass.

## Не входит

- Второй production detector, detector registry, plugin SPI, DI multibinding
  или config option для detector selection.
- Изменение Fast PII recognizers, PII taxonomy, capability bounds, quality
  corpus, policy decision, audit schema, HTTP/protocol handling или reactions.
- Parallel window execution, streaming detector API, cross-fragment windows,
  encoded-source reverse mapping или retention of raw text.
- Новые runtime dependencies, Gradle subproject или public compatibility mode.

## План изменений

- Extract PII-free generic models, contract and executor in
  `io.vigilant.windowing`; keep KDoc ownership/offset/error wording exact.
- Replace current PII-bound executor internals with a Fast PII adapter that
  supplies current capability and PII semantics to generic core.
- Preserve current adapter seam used by `FastPiiPolicyAdapter` and
  `InspectionResources`; update callers only where type adaptation requires it.
- Split/extend windowing tests: generic synthetic-contract tests plus existing
  Fast PII conformance characterization. Keep policy adapter and proxy tests.
- Update `CLAUDE.md` source map and architecture documentation only where they
  name windowing as Fast-PII-bound rather than generic core plus adapter.

## Test strategy

This is behavior-preserving refactoring. Before first production edit run
`WindowedFastPiiExecutorTest`, `FastPiiPolicyAdapterTest` and focused shadow
proxy characterization tests as GREEN baseline. Add generic-core tests before
extracting each contract slice; preserve current Fast PII tests throughout.
After final slice run affected tests, then `./gradlew build` and
`./gradlew validateWorkItems`.

## Рассмотренные альтернативы

- Оставить PII-specific executor: отклонено; generic algorithm and PII
  semantics remain coupled.
- Добавить только numeric parameters: отклонено; identity, metadata comparison
  and deterministic order are detector semantics and require typed strategies.
- Ждать второго detector-а: отклонено по явному решению; reusable boundary
  нужна сейчас, без speculative runtime consumer.
- Создать generic plugin registry: отклонено; нет текущего consumer и это
  расширяет runtime scope.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   generic core and unchanged Fast PII behavior fixed
  Acceptance:   0.1   generic and PII characterization evidence enumerated
  Boundaries:   0.0   no second detector or runtime extension points
  Alternatives: 0.0   parameters alone and registry rejected
  Assumptions:  0.1   implementation selects names, not contract semantics
  --------------------------------------------------------------
  Aggregate:    0.04  below threshold (0.3); Ready for implementation
```
