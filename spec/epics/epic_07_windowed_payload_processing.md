# Epic 07: Windowed payload processing

**ID:** `EPIC-07`  
**Тип:** Epic  
**Статус:** Done
**Приоритет:** High  
**Предварительная оценка:** 0 дней осталось
**Связанные требования:** `PERF-03`, `PERF-04`, `CONC-01`, `CONC-02`, `CONC-03`

## Подтверждённое решение

Большой logical payload fragment не блокируется только из-за detector payload
limit и не обрезается. Для обработки используется отдельная
protocol-agnostic windowing capability.

Capability оформляется самостоятельным epic, потому что пересекает контракты
parser, policy engine, detector execution, offsets и reaction mapping, но не
принадлежит ни одному конкретному LLM protocol adapter.

## Карта декомпозиции

```text
EPIC-07 Windowed payload processing
├── windowing contract and detector capabilities (Done)
└── windowed fast PII execution (Done)
    ├── UTF-8 window generation and capability-derived overlap
    ├── detector execution over windows
    ├── global offsets and finding deduplication
    └── cancellation, ordering and resource bounds
```

## Дочерние issues

- [x] [VIG-07-01: Контракт windowed payload processing](../issues/epic_07/issue_07_01_windowing_contract.md) - `Done`
- [x] [VIG-07-02: Windowed fast PII execution](../issues/epic_07/issue_07_02_windowed_fast_pii_execution.md) - `Done`

Resource bounds и phase latency подтверждены standalone release evidence
[VIG-18](../issues/issue_18_inspection_load_report.md). Versioned результаты
опубликованы в [inspection-load baseline](../../docs/inspection-load-result.md).

## Контекст

[EPIC-06](epic_06_llm_message_parsing.md) возвращает упорядоченные logical
text fragments с provenance. Один fragment может превышать максимальный input
конкретного detector. Blanket reject по размеру сделал бы длинные диалоги и
большие tool results непригодными для работы.

Sliding window позволяет проверять большой fragment частями, но создаёт новые
инварианты: совпадение может пересекать границу окон, один finding может быть
обнаружен несколько раз, detector offsets становятся локальными для окна, а
reaction должна ссылаться на исходный fragment.

## Цель

Определить и реализовать protocol-agnostic capability, которая обрабатывает
большой decoded text fragment ограниченными окнами без пропусков на границах и
возвращает детерминированный результат в UTF-8 координатах исходного fragment.

Предварительный поток данных:

```text
logical text fragment + provenance
        |
        v
window generation with overlap
        |
        v
detector execution per window
        |
        v
offset translation + deduplication
        |
        v
findings relative to original fragment
```

Windowed executor владеет генерацией окон, вызовом одного detector,
translation, validation, deduplication и aggregation для одного logical
fragment. Он не выбирает policies и не применяет reactions.

## Нормативный контракт

### Detector capability и overlap

Detector, допускающий windowing, публикует:

```text
maxWindowUtf8Bytes
maximumEvidenceSpanUtf8Bytes?
```

Evidence span включает finding целиком и весь lookbehind/lookahead, нужный
recognizer-у для boundary или contextual validation. Required context с каждой
стороны ownership core равен `maximumEvidenceSpanUtf8Bytes - 1`. Значение
обязано быть доказано из versioned recognizer rules и boundary corpus, а не
выбрано по типичному input.

Если finite evidence span отсутствует, fragment не длиннее
`maxWindowUtf8Bytes` проверяется одним direct call. Более длинный fragment
возвращает `WINDOWING_UNSUPPORTED`: executor не угадывает overlap, не обрезает
text и не пропускает suffix. Capability invalid, если maximum evidence span
неположителен, больше window size или после двустороннего context не оставляет
ownership-core progress минимум на один четырёхбайтовый UTF-8 code point.

### Window boundaries

Fragment делится на последовательные непересекающиеся ownership cores. Размер
core не превышает
`maxWindowUtf8Bytes - 2 * (maximumEvidenceSpanUtf8Bytes - 1)`, а его границы
всегда совпадают с Unicode code-point boundaries. Detector input содержит core
и максимальный доступный actual fragment context не более required context
слева и справа. У первого и последнего core отсутствующая сторона context
заканчивается на настоящей границе fragment.

При такой схеме любой contiguous evidence span длиной не больше declared
maximum, чей finding начинается внутри core, полностью содержится в detector
input этого core. Окна никогда не переходят между fragments и не включают
synthetic prefix/suffix.

### Execution и aggregation

Первый increment выполняет окна последовательно на bounded CPU executor и
всегда вызывает `FastPiiDetector` с `stopOnFirst=false`, потому что safe audit
требует total finding counts. Completion order поэтому совпадает с window
order, но итог дополнительно canonicalized и не зависит от chunking.

Local finding обязан лежать на valid UTF-8 boundaries внутри window. Global
span вычисляется добавлением `windowStartUtf8`. В aggregate принимаются только
findings, чей global start принадлежит текущему ownership core; поэтому
обрезанный boundary-кандидат из context не может стать результатом. Semantic
duplicate identity:

```text
type + global start/end + recognizerId
```

Exact duplicates удаляются. `recognizerVersion`, `evidenceStrength` и
`confidence` у duplicate identity обязаны совпасть; mismatch возвращает
`INCONSISTENT_WINDOW_RESULT`. Итог сортируется по global start, global end,
PII type, recognizer ID и version.

Первый detector error прекращает новые calls и возвращает один safe error без
partial findings. Cancellation остаётся cancellation. Executor удерживает
original fragment и не более одного materialized window/result batch, не
создаёт unbounded queue и не логирует fragment, window или matched text.

Aggregated result сохраняет original fragment provenance без window IDs.
Offsets остаются координатами decoded fragment; future protocol rewriter
отдельно отвечает за mapping в encoded source.

## Нормативные ограничения

- Окна создаются только внутри одного logical fragment и никогда не
  пересекают границы messages, fields или protocol items.
- Исходный fragment не конкатенируется с соседними fragments.
- Text не обрезается молча, а findings на границах окон не должны теряться.
- Итоговые offsets относятся к исходному decoded fragment в UTF-8 bytes.
- Overlap не должен создавать duplicate findings в итоговом результате.
- Порядок окон, параллельное завершение и chunking входного transport не
  должны менять итог.
- Raw payload, matched text и content preview не попадают в логи или errors.
- CPU-bound detector execution не выполняется на Netty event loop.
- Cancellation освобождает window buffers и прекращает новые detector calls.

## Связи с соседними epics

- [EPIC-02](epic_02_fast_pii_detector.md) задаёт detector input limit,
  UTF-8 offsets и detector result.
- [EPIC-04](epic_04_policy_engine.md) задаёт policy matching, detector
  execution и reaction aggregation для одного payload.
- [EPIC-06](epic_06_llm_message_parsing.md) задаёт logical fragment,
  provenance и mapping к protocol source.

## Отложенные решения

- Bounded-parallel window execution допускается только после baseline,
  доказывающего benefit и сохраняющего те же result/cancellation semantics.
- Reverse mapping для `MASK`/`REMOVE` принадлежит future rewriter EPIC-06.
- Detector без finite evidence span требует detector-specific streaming
  algorithm или explicit unsupported outcome; generic overlap не добавляется.

## Не входит в epic

- Разбор protocol schemas.
- Объединение разных logical fragments в один detector payload.
- Изменение detector taxonomy или конкретных правил распознавания.
- HTTP forwarding, request spooling и proxy error mapping.
- Выбор policies по `PolicyContext`.

## Предварительные критерии готовности epic

- Fragment, превышающий detector limit, проверяется без blanket reject и
  silent truncation.
- Corpus cases с finding на каждой возможной window boundary не теряют и не
  дублируют finding.
- Итоговые UTF-8 offsets точно указывают исходный fragment.
- Результат не зависит от порядка завершения окон.
- Cancellation и error semantics не оставляют retained window state.
- Memory и concurrency bounds подтверждены benchmark/baseline, а не выбраны
  без измерений.
- Logs и errors не содержат fragment text, window text или matched content.
- Для добавленных и изменённых Kotlin declarations написан KDoc.
- `./gradlew build` проходит после реализации всех дочерних issues.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ exact fragment-level result
  Acceptance:   0.15  ✓ capability proof and boundary corpus required
  Boundaries:   0.05  ✓ executor ownership fixed
  Alternatives: 0.10  ✓ sequential strategy and unsupported path fixed
  Assumptions:  0.20  ✓ concrete Fast PII span is implementation evidence
  Aggregate:    0.10  ✓ below threshold (0.3 epic)
```
