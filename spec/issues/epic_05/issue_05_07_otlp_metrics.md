# VIG-05-07: OTel metrics прокси-трафика

**Статус:** Ready for implementation
**Epic:** [EPIC-05](../../epics/epic_05_v0_hardening.md)
**Ветка:** Наблюдаемость > OTel metrics
**Зависит от:** нет
**Предпочтительный порядок (не блокировка):** после issues trace ID (общий OTel SDK и exporter-конфигурация) и стабильных proxy-ошибок (классификация transport errors)
**Блокирует:** нет
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Gateway экспортирует через OpenTelemetry/OTLP метрики прокси-трафика в объёме
минимума из спецификации наблюдаемости.

## Требования

Раздел «Наблюдаемость»: request count, active requests, upstream latency,
gateway processing latency, response status, timeouts, cancellations, transport
errors.

## Критерии готовности

- [ ] Экспортируются counter'ы: request count, response status (по классам
  статусов), timeouts, cancellations, transport errors.
- [ ] Экспортируются histogram'ы/gauge: upstream latency, gateway processing
  latency, active requests.
- [ ] Proxy overhead не вычисляется gateway на боевых запросах (по спецификации
  это задача нагрузочного теста, VIG-05-08) - метрики не вводят такую
  семантику.
- [ ] Метрики не содержат тел, заголовков, query-строк и секретов; имена и
  атрибуты согласованы с политикой безопасных логов.
- [ ] Экспорт конфигурируется теми же настройками OTLP, что и traces
  (переиспользуется конфигурация VIG-05-06; при независимой реализации -
  согласованные названия).
- [ ] Тест: прогоны успешного запроса, запроса с 4xx/5xx от upstream,
  отменённого запроса и запроса к мёртвому upstream меняют соответствующие
  метрики (in-memory SDK reader или тестовый OTLP endpoint).
- [ ] `./gradlew build` проходит.

## Не входит

Prometheus scrape endpoint (если не выбран как транспорт решения), дашборды,
алерты, метрики plugin'ов, per-tenant разрезы.

## Рекомендуемые решения внутри issue

- Сбор через Armeria `RequestLog` listener: один listener заполняет все
  метрики, атрибуты - минимальный набор (status class, transport error class).
- Транспорт экспорта - OTLP, общий с traces.
