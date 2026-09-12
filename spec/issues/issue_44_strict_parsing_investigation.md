# VIG-44: Исследовать и исправить нарушения strict parsing

- **ID:** `VIG-44`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** P2
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 1 день исследования; исправления оценить после воспроизведения
- **Уверенность:** средняя
- **Архитектурный риск:** High - protocol и identity rejection semantics на общей HTTP boundary.

## Контекст

На `d2e272c` public `ChatCompletionsRequestParser` принимает `user` с обычным
`content` и добавленным modern `tool_calls`, а также `user` с `audio.id`.
`collectMessage` обрабатывает эти поля без assistant-role check.
Используемый `BridgeIdentityClient.IDENTITY_JSON` принимает корректный object
с последующим вторым JSON root либо trailing garbage. Это подтверждено на
mapper; полный HTTP Bridge lookup outcome ещё надо воспроизвести. JWT header,
claims и startup JWK parsing также используют `readTree` без явного
full-document validation и требуют отдельного исследования. Обход signature
или доказанная утечка identity этим наблюдением не утверждаются.

## Scope lock

1. **Наблюдаемый продуктовый результат:** malformed/role-incompatible входы отклоняются на owning protocol/identity boundary по действующим safe contracts, корректные совместимые входы сохраняются.
2. **Минимальное достаточное решение:** сначала bounded investigation перечисленных двух семейств defects; затем локальные parsing/role checks после фиксации exact rejection matrix.
3. **Обязательные свойства результата:** полный JSON document, duplicate detection, strict roles, unchanged original bytes для разрешённых запросов, запрет LLM upstream до успешной identity/request validation, отсутствие secrets в failures.
4. **Явные non-goals:** Responses API, новые roles/content kinds, полная rewrite protocol layer, новые identity modes/settings, coercion/fallback, изменения cache/deadline/signature trust, исправление всей coverage matrix в одной задаче.
5. **Более сложные альтернативы:** единый universal parser и новый compatibility mode отклонены как несоразмерные известным defects; typed DTO reserialization нарушает lossless forwarding; молчаливое игнорирование конфликтующих content fields не подходит fail-closed contract.
6. **Условие пересмотра:** ветки имеют независимые решения/основные seams либо общий объём больше пяти дней - до Ready разделить реализацию на independently executable leaves, оставив здесь результат исследования и ссылки.
7. **Подтверждение:** пользователь 2026-09-10 поручил создать задачу на исследование и исправление этой находки. Detailed rejection matrix и окончательная декомпозиция пока открыты; production implementation этим Draft не разрешена.

## Context sources

- `spec/requirements/chat-completions-protocol.md#recognized-message-shapes`
- `spec/requirements/chat-completions-protocol.md#coverage-failures-и-resource-boundary`
- `spec/requirements/identity-and-context.md#offline-jwt`
- `spec/requirements/identity-and-context.md#external-bridge`
- `docs/development.md#protocol-contract-checks`
- `docs/development.md#identity-contract-checks`

## Исследование и критерии готовности

- [ ] Public parser и real-Armeria gateway: воспроизвести six-role matrix для modern function/custom tool_calls и opaque assistant audio; для каждой строки записать exact expected result из owner. Критерий - typed failure + literal HTTP error + нулевой LLM upstream для запрещённых форм; positive assistant examples сохраняют bytes.
- [ ] Public Bridge lookup через контролируемый HTTP provider: valid root + whitespace принимается; второй object/array/scalar root и trailing garbage отвергаются `INVALID_RESPONSE`, gateway даёт exact `identity_unavailable` 503/Retry-After:1 без LLM handoff. Проверить повторную попытку: невалидная identity не попадает в successful cache.
- [ ] JWT header/claims: synthetic signed credentials с корректной signature, но trailing JSON/garbage, отделяют parser failure от signature failure; safe identity 400 до body demand. Startup JWK override отдельно отвергает trailing document при загрузке configuration. Positive keys/claims/whitespace и duplicate-key negatives остаются проверены.
- [ ] Для каждого несовпадения зафиксированы requirement, stimulus, public seam, actual/expected outcome и independent oracle; неподтверждённые гипотезы явно отделены от bugs.
- [ ] До Ready зафиксированы exact codes для role mismatch и границы fixes; protocol и identity leaves выделены отдельно, если сохраняются независимые результаты/seams.
- [ ] Выполнены согласованные исправления либо созданы согласованные implementation leaves; evidence не объявляет исправление выполненным до RED/GREEN и final checks соответствующих leaves.

## Открытые решения

Exact parser code для known role с assistant-only field; полный список реально
затронутых JSON readers; отдельная leaf для startup JWK; возможность закрыть
все repairs одним reviewable slice. Исследование не расширять на semantic-kind,
SSE metadata и другие известные gaps без отдельного scope.

## Проверки

Focused public parser, `GatewayIdentityE2eTest`, `BridgeIdentityClientTest`,
`OfflineJwtIdentityExtractorTest`, configuration и request HTTP regression
cases; команды и RED/GREEN evidence закрепить после исследования.
Исправления runtime требуют `./gradlew build` через durable runner.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.4; Boundaries: 0.4; Alternatives: 0.1;
Assumptions: 0.5; Aggregate: 0.28. Draft обусловлен exact rejection matrix и
разделением независимых repairs, а не только численным aggregate.
