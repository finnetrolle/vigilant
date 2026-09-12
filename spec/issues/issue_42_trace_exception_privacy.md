# VIG-42: Безопасная диагностика transport failures в traces

- **ID:** `VIG-42`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** P1
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 2-3 инженерных дня после уточнения
- **Уверенность:** средняя
- **Архитектурный риск:** High - внешний telemetry contract и несколько span owners.

## Контекст и объяснение риска

`BypassProxyService.exchange` передаёт исходный transport cause в CLIENT
`Span.recordException`; `TracingService.completeExchange` делает это для
SERVER при некоторых failure paths. OpenTelemetry exception event может
содержать exception type, исходное message и stack trace с цепочкой causes.
Сообщение исключения не является безопасным по происхождению: нижний HTTP
слой или иной dependency может включить URL/query, headers, фрагмент данных
либо внутренние подробности. Очищенные application logs не очищают этот
отдельный telemetry channel. OTLP stdout export включён по умолчанию.

На `d2e272c` подтверждены вызовы без sanitization. Реальная утечка секрета и
полный HTTP путь, позволяющий вызвать её пользовательскими данными, пока не
воспроизведены. Нельзя подменять эту границу утверждением о доказанной утечке.
[Privacy owner](../requirements/observability.md#privacy-by-channel) прямо
запрещает raw exception event/message/stack в traces.

## Scope lock

1. **Наблюдаемый продуктовый результат:** экспортируемые SERVER/CLIENT spans сохраняют диагностику transport failure в разрешённых безопасных полях.
2. **Минимальное достаточное решение:** предложение - удалить raw exception recording и использовать finite failure classification; окончательная схема диагностических полей пока открыта.
3. **Обязательные свойства результата:** никакого message/stack/cause-chain или произвольного exception class; сохранить trace lineage, status, duration, session/correlation в пределах owner и существующие HTTP outcomes.
4. **Явные non-goals:** новый exporter/collector, хранение traces, deployment chain, sampling redesign, изменения retry/timeout/identity, новые inspection metrics, очистка стороннего telemetry storage.
5. **Более сложные альтернативы:** regex redaction raw exception отклоняется как ненадёжная для неизвестных dependency messages; отключение tracing лишает разрешённой диагностики; новый asynchronous pipeline не нужен. Полное отсутствие failure attributes проще, но его достаточность надо обсудить.
6. **Условие пересмотра:** нужная диагностика не выражается безопасными finite полями; сначала согласовать exact schema, не возвращать raw exception как fallback.
7. **Подтверждение:** пользователь 2026-09-10 попросил объяснить риск и пока создать именно Draft. Privacy запрет согласован у owner; предложенная replacement schema ещё не утверждена.

## Context sources

- `spec/requirements/observability.md#privacy-by-channel`
- `spec/requirements/observability.md#tracing-and-propagation`
- `spec/requirements/observability.md#otlp-output-and-lifecycle`
- `docs/development.md#observability-contract-checks`

## Открытые решения

- Выбрать точные finite attributes/events для timeout, cancellation, connect/DNS и прочих transport failures; хватает ли existing outcome flags.
- Установить reachable HTTP reproduction отдельно для SERVER и upstream CLIENT. Проверка ручного `span.recordException` вне production path не закрывает issue.
- Зафиксировать observation exported OTLP для failure после headers и до headers, включая cause chain и suppressed exceptions.

## Критерии готовности

- [ ] До Ready закрыты открытые решения и scope lock; recorded acceptance matrix имеет literal schema/allowed values.
- [ ] Real-Armeria failure stimuli вызывают owning SERVER/CLIENT failure paths. In-memory finished spans и packaged OTLP проверены независимо: в attributes/events отсутствуют синтетические sentinels body/query/header/identity, exception message/stack; test oracle не использует production sanitizer.
- [ ] Проверены connect/DNS failure, response timeout, mid-response failure и client cancellation; нормальное завершение и policy BLOCK не приобрели ложные exception events.
- [ ] Сохранены HTTP status/body/headers, bounded cancellation, span tree и ровно одно завершение каждого span; существующие tracing и gateway tests проходят.
- [ ] Coverage OBS-02 обновлена только в объёме фактически проверенных каналов.

## Проверки

После согласования: focused `TracingServiceTest`, `BypassProxyServiceTest`,
real-Armeria failure tests и packaged exporter observation через существующие
fixtures, затем `./gradlew build` через durable runner.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.5; Boundaries: 0.1; Alternatives: 0.4;
Assumptions: 0.5; Aggregate: 0.30. Draft сохранён по прямому запросу пользователя;
replacement schema и reachable disclosure reproduction остаются открытыми.
