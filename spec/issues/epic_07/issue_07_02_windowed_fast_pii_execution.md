# VIG-07-02: Windowed fast PII execution

**Статус:** Ready for implementation  
**Epic:** [EPIC-07](../../epics/epic_07_windowed_payload_processing.md)  
**Ветка:** Window generation, execution and aggregation  
**Зависит от:** [VIG-07-01](issue_07_01_windowing_contract.md), [VIG-06-01](../epic_06/issue_06_01_protocol_contract.md), [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Блокирует:** PII shadow request tracer bullet  
**Оценка:** 3-5 инженерных дней  
**Уверенность:** Medium

## Результат

Transport-neutral public capability проверяет один decoded text fragment
любого размера до configured request limit через `FastPiiDetector`, не
пропускает finding на window boundary, возвращает exact original-fragment
UTF-8 offsets и не дублирует findings из overlap.

## Public seam

Pure fragment inspection API получает immutable text, opaque provenance,
enabled PII types и cooperative cancellation context. Result содержит
original provenance, deterministic aggregated findings либо typed safe error.
Tests используют production `FastPiiDetector` и synthetic boundary corpus,
не мокают recognizers или internal window provider.

## Критерии приёмки

- [ ] Versioned capability proof и table-driven conformance corpus покрывают
  каждый detector/window rule раздела EPIC-07 «Нормативный контракт» через
  public fragment-inspection seam.
- [ ] Boundary corpus сравнивает public result с direct detection и доказывает
  exact original-fragment offsets, metadata и deterministic deduplication для
  всех поддерживаемых PII formats и Unicode boundary classes.
- [ ] Negative matrix покрывает invalid capability, unsupported windowing,
  invalid detector result и conflicting duplicates, проверяя единый typed safe
  outcome без partial findings или payload preview.
- [ ] Execution/cancellation matrix доказывает normative ordering, resource и
  CPU-executor constraints без моков recognizers или window provider.
- [ ] Каждый success result сохраняет только разрешённую parent contract
  provenance и не раскрывает window-scoped или encoded-source данные.
- [ ] Focused tests и `./gradlew build` проходят.

## Не входит

Protocol parsing, source spooling, HTTP integration, policy matching,
parallel window execution, detector taxonomy/quality changes, reactions и
reverse mapping в encoded JSON.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ exact fragment-level result
  Acceptance:   0.15  ✓ exhaustive boundary evidence required
  Boundaries:   0.05  ✓ transport-neutral public seam
  Alternatives: 0.10  ✓ sequential execution and overlap selected
  Assumptions:  0.20  ✓ recognizer span proof must be recorded in code/tests
  Aggregate:    0.10  ✓ below threshold (0.3 issue)
```
