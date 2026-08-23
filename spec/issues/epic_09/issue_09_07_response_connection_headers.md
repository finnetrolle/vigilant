# VIG-09-07: Dynamic hop-by-hop response headers

**Статус:** Ready for implementation
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Proxy reliability > dynamic response hop-by-hop stripping
**Зависит от:** нет
**Блокирует:** нет
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

E2E test подтверждает, что header, названный upstream в response `Connection`,
удаляется вместе с `Connection`, а соседний end-to-end header и response body
сохраняются.

## Требования

`PROXY-02`, project protocol compatibility instruction; finding AR-07.

## Критерии готовности

- [ ] Upstream возвращает `Connection: x-remove` и одновременно headers
  `x-remove`/`x-keep` через HTTP/1.1 response.
- [ ] Client не видит `Connection` и `x-remove`, но получает `x-keep`, status и
  body без изменений.
- [ ] Multiple `Connection` values и mixed-case token обрабатываются
  детерминированно хотя бы одним regression case.
- [ ] Existing fixed hop-by-hop and request-side dynamic tests остаются GREEN.
- [ ] Production code не меняется. Если test выявляет defect, fix создаётся
  отдельной issue с RED-first TDD.
- [ ] Focused test и `./gradlew build` проходят.

## Test/demo seam

Реальные Armeria upstream/gateway/client; при невозможности Armeria upstream
выдать wire-level `Connection` допускается raw HTTP/1.1 upstream.

## Не входит

HTTP/2 prohibited-header validation, trailer semantics, production fix внутри
test-only issue.
