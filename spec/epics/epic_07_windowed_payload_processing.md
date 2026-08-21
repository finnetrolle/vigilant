# Epic 07: Windowed payload processing

**ID:** `EPIC-07`  
**Тип:** Epic  
**Статус:** Draft  
**Приоритет:** High  
**Предварительная оценка:** после закрытия windowing-контракта  
**Связанные требования:** `PERF-03`, `PERF-04`, `CONC-01`, `CONC-02`, `CONC-03`

## Подтверждённое решение

Большой logical payload fragment не блокируется только из-за detector payload
limit и не обрезается. Для обработки используется отдельная
protocol-agnostic windowing capability.

Capability оформляется самостоятельным epic, потому что пересекает контракты
parser, policy engine, detector execution, offsets и reaction mapping, но не
принадлежит ни одному конкретному LLM protocol adapter.

## Предварительная карта декомпозиции

```text
EPIC-07 Windowed payload processing
├── windowing contract and detector capabilities
├── window generation and overlap
├── detector execution over windows
├── global UTF-8 offsets and finding deduplication
├── cancellation, ordering and resource bounds
└── reaction mapping to original fragment
```

Карта является предварительной. Исполняемые issues создаются после решения,
где проходит граница между window provider, detector executor и result
aggregation.

## Дочерние issues

- [ ] [VIG-07-01: Контракт windowed payload processing](../issues/epic_07/issue_07_01_windowing_contract.md) - `Draft`

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

Точная ответственность capability за detector invocation пока не выбрана.

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

## Открытые решения

1. Window provider только создаёт окна либо также оркестрирует detector calls.
2. Как detector сообщает безопасный window size и обязательный overlap.
3. Как гарантировать отсутствие false negatives для detector без известной
   максимальной длины finding.
4. Exact window size, overlap и switch threshold для каждого detector class.
5. Последовательное или bounded-parallel выполнение окон и стабильный порядок
   результатов.
6. Translation локальных UTF-8 offsets окна в offsets исходного fragment.
7. Дедупликация duplicate и overlapping findings из соседних окон.
8. Aggregation detector errors, cancellation и fail-fast между окнами.
9. Bounded memory, backpressure и hard resource exhaustion.
10. Mapping итоговых findings обратно через provenance для `MASK` и `REMOVE`.

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
  Goals:        0.15  общий результат понятен
  Acceptance:   0.50  overlap и error semantics ещё не выбраны
  Boundaries:   0.45  ownership detector orchestration не закрыт
  Alternatives: 0.60  window strategy и detector capability contract открыты
  Assumptions:  0.55  resource model требует baseline
  Aggregate:    0.45  выше порога implementation-ready issue
```

Оставить `Draft` до закрытия windowing-контракта.
