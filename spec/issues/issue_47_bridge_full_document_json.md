# VIG-47: Отклонять trailing data в Bridge identity JSON

- **ID:** `VIG-47`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** P1
- **Зависит от:** нет
- **Выполненные предпосылки:** [External Bridge contract](../requirements/identity-and-context.md#external-bridge)
- **Блокирует:** нет
- **Оценка:** 1-2 инженерных дня
- **Уверенность:** высокая
- **Архитектурный риск:** Low - локальное conformance исправление существующего Bridge JSON contract без нового public behavior.

## Результат

External Bridge success принимает ровно один полный JSON object с optional
trailing JSON whitespace. Второй JSON root и любые trailing non-whitespace
bytes дают `INVALID_RESPONSE`; gateway возвращает existing safe
`503 identity_unavailable` до request-body demand и LLM upstream.

## Context sources

- `spec/requirements/identity-and-context.md#external-bridge`
- `spec/requirements/identity-and-context.md#cache-keys-и-expiry`
- `spec/requirements/http-gateway.md#inspection-error-matrix`
- `docs/development.md#identity-contract-checks`

## Согласованный план реализации

Цель - сделать existing Bridge JSON parsing full-document strict, сохранив
остальной HTTP, identity, cache и safe-error contract без изменений.

Текущий `BridgeIdentityClient.parseResponse` передаёт полное агрегированное тело
в `IDENTITY_JSON.readTree`, но mapper включает только
`STRICT_DUPLICATE_DETECTION`. Все parser exceptions уже преобразуются в
`Unavailable(INVALID_RESPONSE)`. `CachingExternalIdentityLookup` сохраняет
только `Resolved`, поэтому invalid document уже удаляет in-flight generation и
следующий request с тем же token создаёт fresh Bridge lookup.

Обязательные решения:

- включить `DeserializationFeature.FAIL_ON_TRAILING_TOKENS` на существующем
  `BridgeIdentityClient.IDENTITY_JSON`, по уже применяемому в OpenAI parsers
  проектному паттерну; не вводить новый parser, setting или compatibility path;
- сохранить `STRICT_DUPLICATE_DETECTION`, существующий `parseResponse` mapping,
  aggregation limit и cache implementation;
- доказывать parser contract через real Armeria responses с exact raw bytes и
  независимыми literal/typed expectations;
- доказывать public failure и fresh retry через real Bridge, gateway, existing
  cache boundary и один token.

Порядок выполнения:

1. В `BridgeIdentityClientTest` добавить B1-B3 matrices. Сначала получить
   behavioral RED на B2 или B3 через public `BridgeIdentityClient` seam, затем
   сохранить полный набор whitespace, second-root и trailing-garbage cases.
2. В `BridgeIdentityClient` включить full-document validation и обновить KDoc
   mapper, чтобы все trailing non-whitespace попадали в existing
   `INVALID_RESPONSE`, а trailing JSON whitespace и valid additive fields
   оставались допустимыми.
3. В `GatewayIdentityE2eTest` добавить representative second-root и garbage
   scenarios. Первый request должен дать exact safe 503 без body demand,
   upstream и sentinel disclosure; второй request с тем же token должен вызвать
   fresh Bridge lookup, получить valid identity и дойти до upstream. Независимые
   counters подтверждают ровно два Bridge calls.
4. В `docs/requirements-coverage.md` заменить Bridge full-document gap ссылкой
   на фактическое source/test evidence, не объявляя закрытыми отдельные JWT/JWK
   gaps. После этого выполнить focused suites, `detekt` и один полный build
   командами из раздела «Проверки»; B1-B6 и существующие regressions должны быть
   GREEN.

Имена новых test helpers и группировка dynamic tests остаются на усмотрение
исполнителя. Добавленные или изменённые Kotlin methods и tests обязаны иметь
актуальный KDoc. Агент-исполнитель должен следовать этому плану; существенное
отступление от обязательных решений требует предварительного обсуждения с
оператором.

## Критерии готовности и evidence contract

| ID / stimulus | Public seam | Observable result | Independent oracle |
|---|---|---|---|
| `B1_VALID_WHITESPACE`: valid object + empty, SP, TAB, LF, CRLF suffix | Real `BridgeIdentityClient` | `Resolved` с existing normalized identity | Literal expected user/groups |
| `B2_SECOND_ROOT`: valid object + object, array, string, number, boolean или null root | Real `BridgeIdentityClient` | `Unavailable(INVALID_RESPONSE)` для каждой named row | Finite raw-byte fixture matrix, typed literal outcome |
| `B3_TRAILING_GARBAGE`: valid object + identifier, punctuation и truncated token | Real `BridgeIdentityClient` | `Unavailable(INVALID_RESPONSE)` | Exact raw bytes; no production parser used by oracle |
| `B4_PUBLIC_FAILURE`: representative second-root и garbage responses | Real client -> gateway -> Bridge/upstream | Exact 503, `Retry-After: 1`, `Content-Type: application/json`, exact `identity_unavailable`; no body demand/upstream; sentinels absent | Literal response contract, body-demand and call counters |
| `B5_RETRY`: same token получает invalid document, затем valid document | Gateway с real cache и controlled Bridge | Первый request safe 503; второй делает fresh Bridge call и reaches upstream; two Bridge calls | Controlled response sequence and independent counters |
| `B6_REGRESSION`: malformed JSON, invalid UTF-8, duplicate keys, non-object root, valid additive fields | Existing Bridge client suite | Existing finite outcomes unchanged | Current literal fixtures |

- [ ] `B1`-`B6` GREEN; `B2` или `B3` сначала даёт behavioral RED на public Bridge seam.
- [ ] No new setting, retry, cache rule, response detail или parser abstraction.
- [ ] Added/modified methods и tests имеют актуальный KDoc.
- [ ] `./gradlew build` проходит через durable runner.

## Проверки

```bash
./scripts/check-run start --label vig-47-identity --snapshot <session> --input src/main --input src/test --input spec --input docs -- ./gradlew test -x processTest --tests 'io.vigilant.gateway.identity.BridgeIdentityClientTest' --tests 'io.vigilant.gateway.identity.CachingExternalIdentityLookupTest' --tests 'io.vigilant.gateway.proxy.GatewayIdentityE2eTest'
./scripts/check-run start --label vig-47-detekt --snapshot <session> --input src/main --input src/test -- ./gradlew detekt
./scripts/check-run start --label vig-47-build --snapshot <session> --input . --artifact directory:build/test-results -- ./gradlew build
```

## Не входит

- JWT/JWK parsing, Bridge size/media/status/deadline semantics, cache TTL/size,
  retries, compression, telemetry schema и новые identity modes.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.1;
Assumptions: 0.1; Aggregate: 0.06.
