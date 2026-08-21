# VIG-05-02: Health и readiness endpoints

**Статус:** Ready for implementation
**Epic:** [EPIC-05](../../epics/epic_05_v0_hardening.md)
**Ветка:** Эксплуатация > health/readiness endpoints
**Зависит от:** нет
**Блокирует:** нет
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

Gateway сам отвечает на liveness и readiness пробы; Kubernetes/orchestrator
проверяет здоровье gateway, а не upstream. Сейчас любой путь, включая пробы,
проксируется upstream.

## Требования

Раздел «Входит в v0»: health/readiness endpoints.

## Критерии готовности

- [ ] Liveness endpoint (рекомендованный baseline: `GET /healthz`) отвечает
  `200`, пока сервер принимает соединения.
- [ ] Readiness endpoint (рекомендованный baseline: `GET /readyz`) отвечает
  `200`, когда gateway готов обрабатывать трафик, и `503` после начала
  graceful shutdown, до фактического закрытия.
- [ ] Оба пути обслуживаются gateway и никогда не проксируются upstream.
- [ ] Выбранные пути не конфликтуют с пространством путей LLM API; выбор
  зафиксирован в README.
- [ ] E2E-тест: ответы endpoint'ов через запущенный gateway; пути отсутствуют
  в логах upstream-запросов тестового upstream.
- [ ] `./gradlew build` проходит.

## Не входит

Глубокие проверки зависимости от upstream (readiness не проверяет доступность
upstream), метрики на базе health, аутентификация проб.

## Рекомендуемые решения внутри issue

- Пути `/healthz` и `/readyz` (конвенция Kubernetes); регистрация отдельными
  service'ами до catch-all `BypassProxyService`.
- Readiness переключается в `503` в shutdown hook до `server.stop()`.
