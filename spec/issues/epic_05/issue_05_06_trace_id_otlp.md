# VIG-05-06: Correlation/trace ID и OTel traces

**Статус:** Done
**Epic:** [EPIC-05](../../epics/epic_05_v0_hardening.md)
**Ветка:** Наблюдаемость > correlation/trace ID и OTel traces
**Зависит от:** нет
**Предпочтительный порядок (не блокировка):** раньше issue OTel metrics - она переиспользует OTel SDK
**Блокирует:** нет
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Каждый запрос получает correlation/trace ID, видимый в структурированных
логах; gateway экспортирует distributed traces через OpenTelemetry/OTLP. Без
экспортёра (по умолчанию выключен или не настроен) gateway работает как раньше.

## Требования

Раздел «Наблюдаемость»: correlation/trace ID, OTel traces, структурированные
логи без секретов.

## Критерии готовности

- [x] Входящий W3C `traceparent` принимается и продолжается; при отсутствии
  gateway генерирует новый trace ID.
- [x] Correlation/trace ID попадает в MDC и присутствует в каждой строке JSONL
  логов, относящейся к запросу.
- [x] На proxy-обмен создаётся span с атрибутами метода, пути, статусов и
  длительностей upstream/gateway; тела и auth headers в атрибуты не попадают.
- [x] OTLP exporter конфигурируется (endpoint, включён/выключен) через
  существующий механизм `env > file > defaults`; export выключен, когда
  endpoint не задан.
- [x] Тест: один прогон запроса производит логи с одинаковым trace ID и span с
  ожидаемыми атрибутами (in-memory SDK reader или тестовый OTLP endpoint).
- [x] Существующие тесты утечек сентинелов (INFO/DEBUG stdout) продолжают
  проходить.
- [x] `./gradlew build` проходит.

## Не входит

Metrics (VIG-05-07), propagation trace context в upstream-заголовки
(допускается как решение внутри issue, если выбрано), sampling-политики,
бэкенды визуализации.

## Рекомендуемые решения внутри issue

- OpenTelemetry SDK + `OTLP HTTP` exporter; артефакт `io.opentelemetry:opentelemetry-sdk`.
- Интеграция через Armeria OpenTelemetry-модули или `RequestLog` listener -
  выбрать по минимуму зависимости от внутренних API.
