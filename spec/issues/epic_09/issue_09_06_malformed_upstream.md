# VIG-09-06: Некорректный upstream HTTP как stable proxy error

**Статус:** Done
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Proxy reliability > malformed upstream response
**Зависит от:** нет
**Блокирует:** нет
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

E2E test через raw TCP upstream доказывает, что malformed HTTP response до
начала client response преобразуется в stable safe `502` proxy error,
учитывается как transport failure и не раскрывает parser/framework details.

## Требования

`PROXY-03`; finding AR-06.

## Критерии готовности

- [x] Raw upstream принимает request и возвращает детерминированно malformed
  status line, headers или framing до начала valid response.
- [x] Client получает `502` и stable `{"error":"upstream_unavailable"}` без
  exception class, raw bytes или stack trace.
- [x] Transport-error metric и safe structured log получают bounded category;
  malformed bytes и request sentinels не попадают в telemetry.
- [x] Existing pass-through tests подтверждают, что корректные upstream 4xx/5xx
  не интерпретируются и не преобразуются.
- [x] Production code не меняется. Если test выявляет defect, fix создаётся
  отдельной issue с RED-first TDD.
- [x] Focused test и `./gradlew build` проходят.

## Test/demo seam

Реальный gateway и минимальный raw `ServerSocket` upstream с bounded lifecycle;
client обращается через Armeria `WebClient`.

## Не входит

Mid-response corruption после отправки client headers, provider-specific error
mapping, retry, production fix внутри test-only issue.
