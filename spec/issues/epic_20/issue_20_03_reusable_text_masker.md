# VIG-20-03: Reusable text masker

**Статус:** Done
**Epic:** [EPIC-20](../../epics/epic_20_atomic_in_memory_response_analysis.md)
**Ветка:** Response enforcement > transport-neutral masking
**Зависит от:** нет
**Блокирует:** [VIG-20-02](issue_20_02_response_inspection_enforcement.md) и будущий SSE enforcement leaf EPIC-20
**Оценка:** 2-3 инженерных дня; confidence High

## Цель

Добавить один transport-neutral `TextMasker`, который получает source text и
canonical masking instructions и возвращает masked text. Policy/detector layer
готовит instructions; protocol adapters только применяют returned text к своим
JSON locations.

Один и тот же class должен подключаться к разным policies и detectors без
знания OpenAI, HTTP, stdout, конкретного detector или policy file.

## Принятые решения

- Это один Kotlin class/package, не отдельный Gradle module, worker, provider
  registry или plugin API.
- Input содержит original text и immutable canonical instructions: UTF-8 span
  plus replacement marker. Output — new masked text; input не мутируется.
- `TextMasker` не запускает detector, не выбирает policy, не пишет audit event
  и не знает JSON/SSE. Он применяет только уже выбранные `MASK` instructions.
- Policy layer constructs each instruction from selected reaction and FindingType;
  it chooses replacement marker. `TextMasker` never infers type or marker from
  source text and needs no change when a new policy/detector is added.
- `policy.domain` owns immutable `MaskingInstruction(span, marker)`. Final
  reaction result exposes canonical instructions; `ReactionAggregator` creates
  and merges them once. Request/response protocol adapters pass that result to
  `TextMasker` without detector rerun or local aggregation.
- Default PII markers irreversible and typed: `[EMAIL_MASKED]`,
  `[CARD_MASKED]`, `[PHONE_MASKED]` и equivalents for supported PII types.
- Result должен заменять только exact selected spans and preserve all other
  Unicode text unchanged.
- Overlapping или adjacent instructions объединяются in one union span. Если
  markers совпадают, используется этот marker; если types/markers различаются,
  используется generic irreversible `[PII_MASKED]`. Non-overlapping spans
  сохраняют typed markers. Result is deterministic независимо от входного
  order detectors/policies.
- Invalid instruction - out-of-range span, span not on UTF-8 boundary или
  invalid marker - produces typed failure and no partial output. Dependent
  response integration VIG-20-02 maps this failure through VIG-29 `503
  response_inspection_unavailable`; VIG-20-03 does not add an otherwise-unused
  HTTP mapper or response workflow.
- `REMOVE` не входит в MVP. Startup policy validation rejects any configured
  `REMOVE`; it is never ignored, passed to `TextMasker` or implemented as
  second transformation path.

## Известный контекст

До этой issue `ReactionPlan` хранил `TransformationOperation` with
transformation kind and UTF-8 span, но не хранил FindingType/replacement.
Теперь final plan exposes enriched immutable canonical instructions; detector
не запускается повторно, а masker не угадывает type по text.

## Не входит

- New detector, policy selection, plugin registry, Gradle module or runtime
  configuration.
- `REMOVE` transformation, request/response JSON patching, SSE parsing or HTTP
  response handling.
- Logging/audit persistence, payload retention or external delivery.

## Критерий готовности задачи

- [x] `MaskingInstruction(span, marker)` is immutable policy-domain contract;
  final reaction aggregation creates it once from selected `MASK` reactions and
  findings, without rerunning a detector.
- [x] `TextMasker` is a pure transport-neutral class over text and canonical
  instructions; it changes only selected UTF-8 spans and preserves all other
  Unicode text.
- [x] Tests cover ASCII, multibyte UTF-8, multiple, adjacent and overlapping
  spans, equal and conflicting markers, typed markers and input immutability.
- [x] Invalid instruction produces typed failure and no partial output. Causal
  response mapping to existing `503 response_inspection_unavailable` belongs
  to dependent VIG-20-02, which owns the first response caller of `TextMasker`.
- [x] Startup validation rejects `REMOVE`; focused test proves app configuration
  never silently ignores or executes it.
- [x] All new or modified Kotlin declarations and test methods have current
  KDoc; focused tests and `./gradlew build` pass.

## Ambiguity Report

```text
Goals:        0.0   one reusable pure masker defined
Acceptance:   0.10  deterministic test matrix explicit
Boundaries:   0.0   no protocol or detector ownership
Alternatives: 0.10  class chosen over new module
Assumptions:  0.10  current reaction result is canonical extension seam
Aggregate:    0.06  Ready for implementation.
```
