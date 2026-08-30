# VIG-21-02: Qualification граничных request inspection shapes

**Статус:** Done
**Epic:** [EPIC-21](../../epics/epic_21_post_milestone_architecture_closure.md)
**Ветка:** Inspection resource evidence > max-shape packaged-process qualification
**Зависит от:** нет
**Блокирует:** [VIG-21-05](issue_21_05_roadmap_frontier_reconciliation.md)
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Воспроизводимая test-only qualification измеряет heap/RSS, latency, HTTP,
audit, replay и cleanup на граничных request shapes, которые runtime реально
принимает. Report отделяет подтверждённые bounds от observed deviations и не
распространяет один `64 KiB` profile на всю supported surface.

## Требования

`CONC-01`, `CONC-03`, roadmap safety gates `no OOM`, `no unbounded memory`,
`no truncation`, deterministic outcome; finding AR-11.

## Критерии готовности

- [x] Packaged `MainKt` и real upstream запускаются в отдельных processes с
  fixed heap, direct-memory limit, source limits и recorded environment.
- [x] Matrix включает exact default per-request boundary `8 MiB`: один большой
  text fragment, largest accepted normalized-fragment count перед parser
  rejection и gap-dense supported content.
- [x] Concurrent case удерживает несколько accepted requests в пределах
  default `64 MiB` raw-source quota и отдельно проверяет expected stable
  capacity rejection за границей.
- [x] Для каждого accepted case проверены exact HTTP outcome, byte-identical
  replay, один expected safe audit event и отсутствие silent truncation.
- [x] После success, rejection, client cancellation и process shutdown source
  owners, retained bytes, executor tasks и memory trend возвращаются к
  опубликованному bounded baseline.
- [x] Multi-fragment case публикует total inspection duration и явно показывает
  multiplier последовательной per-fragment policy evaluation; новый latency
  threshold не придумывается внутри test.
- [x] Report отдельно показывает raw source bytes и peak heap/RSS, потому что
  Jackson tree, decoded strings, gaps, detector preflight arrays и windows не
  входят в `RequestSourceQuota.retainedBytes`.
- [x] Synthetic fixtures не содержат production payload или raw PII и не
  записывают body/locators в report или logs.
- [x] Production code не меняется. Если qualification выявляет defect, он
  получает отдельную RED-first TDD issue с exact failing case.
- [x] Focused contract tests, qualification command и `./gradlew build`
  проходят; versioned report сохраняется в `docs/`.

## Completion evidence

[Versioned report](../../../docs/inspection-resource-qualification-2026-08-30.md)
фиксирует `PASS` на packaged `MainKt` и отдельном real Armeria upstream:
все три exact `8 MiB` accepted shapes дали HTTP `200`, byte-identical replay и
ровно один ожидаемый safe audit event; `16 385`-й fragment дал локальный
`400 unsupported_schema`; восемь удерживаемых sources сохранили `67 108 856`
raw bytes, test-only server-side observation подтвердил exact `8` active
owners и `67 108 856` retained bytes, а единственный measured request после
observation получил стабильный `503`.

На зафиксированном Mac OS X/aarch64/JDK 25 profile с heap `1 GiB` и direct
memory `512 MiB` peak heap составил `410.7 MiB`, peak RSS `1033.1 MiB`, а
terminal sample вернулся к `20.4/561.3 MiB` heap/RSS внутри опубликованного
full-profile warm high-water baseline `20.8/1033.1 MiB` + `64 MiB`. Baseline
фиксируется максимумами пяти последовательных forced-GC observations внутри
`16 MiB` heap/RSS window. Exact source owner/byte и executor cleanup дополнен
focused public-seam contracts для success, rejection, cancellation и shutdown.
Production code не изменён, qualification дважды подряд прошёл на свежих
processes, отдельная defect issue не потребовалась.

## Test/demo seam

Packaged `MainKt`, real Armeria upstream, deterministic synthetic request
generator, bounded OS/JVM memory sampler и safe audit reader. Cases запускаются
последовательно либо в явно описанной concurrent phase.

## Не входит

Изменение source defaults, parser semantics, fragment concatenation, policy
deadline semantics, production optimization без failing gate, response/SSE
storage и disk spill.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.05  supported max-shape envelope измеряется
  Acceptance:   0.15  shape, process и terminal matrices заданы
  Boundaries:   0.05  test-only until a defect is proven
  Alternatives: 0.15  fixture implementation остаётся локальным выбором
  Assumptions:  0.25  measured hardware envelope не является universal promise
  Aggregate:    0.13  below threshold (0.3 issue)
```
