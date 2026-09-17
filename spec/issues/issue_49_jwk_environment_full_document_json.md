# VIG-49: Отклонять trailing data в JWK environment JSON

- **ID:** `VIG-49`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** P1
- **Зависит от:** нет
- **Выполненные предпосылки:** [Offline JWT startup contract](../requirements/identity-and-context.md#offline-jwt)
- **Блокирует:** нет
- **Оценка:** 1 инженерный день
- **Уверенность:** высокая
- **Архитектурный риск:** Low - локальное startup validation исправление существующего environment contract.

## Результат

`VIGILANT_IDENTITY_JWT_JWKS` принимает ровно один полный JSON array с optional
trailing JSON whitespace. Второй JSON root или trailing non-whitespace bytes
дают existing value-free configuration error; installed process завершается
с code 2 без запуска gateway.

## Context sources

- `spec/requirements/identity-and-context.md#startup-selection`
- `spec/requirements/identity-and-context.md#offline-jwt`
- `docs/development.md#identity-contract-checks`

## План изменений

- `IdentityConfig.JWK_ENV_JSON`: включить full-document validation без изменения
  exact field allowlist, duplicate detection или RSA key validation.
- `AppConfigLoadingTest`: finite raw environment suffix matrix и positive
  whitespace controls.
- `MainTest`: representative installed `MainKt` startup rejection, exact exit
  code/value-free stderr и отсутствие process survival.
- Requirements coverage/evidence: закрыть startup parsing gap.

## Критерии готовности и evidence contract

| ID / stimulus | Public seam | Observable result | Independent oracle |
|---|---|---|---|
| `K1_VALID_WHITESPACE`: valid public JWK array + empty/SP/TAB/LF/CRLF | `loadAppConfig` с exact environment map | JWT settings load с exact configured `kid` | Independently generated public RSA JWK and literal key set |
| `K2_SECOND_ROOT`: valid array + object, array, string, number, boolean, null root | `loadAppConfig` | `IllegalArgumentException` с exact value-free JWK message | Finite raw-string matrix and literal message |
| `K3_TRAILING_GARBAGE`: valid array + identifier, punctuation, truncated token | `loadAppConfig` | Same exact value-free failure; sentinel absent | Exact raw strings and literal safe message |
| `K4_PROCESS`: representative second-root и garbage values через real environment | Installed `MainKt` process | Exit 2, expected safe stderr, sentinel absent, process/readers closed | `GatewayProcessFixture.launchForStartupRejection` and literal assertions |
| `K5_REGRESSION`: malformed JSON, duplicate/unknown/private fields, invalid/missing RSA members, duplicate kid | Existing config/startup suites | Existing errors and privacy unchanged | Current fixture matrix |

- [ ] `K1`-`K5` GREEN; `K2` или `K3` сначала даёт behavioral RED на `loadAppConfig` seam.
- [ ] Error не содержит raw JWK JSON, modulus, exponent, private material или sentinel.
- [ ] No new config key, fallback, file parser or key-fetch behavior.
- [ ] Added/modified methods и tests имеют актуальный KDoc.
- [ ] `./gradlew build` проходит через durable runner.

## Проверки

```bash
./scripts/check-run start --label vig-49-config --snapshot <session> --input src/main --input src/test --input spec --input docs -- ./gradlew test -x processTest --tests 'io.vigilant.gateway.config.AppConfigLoadingTest' --tests 'io.vigilant.gateway.MainTest'
./scripts/check-run start --label vig-49-detekt --snapshot <session> --input src/main --input src/test -- ./gradlew detekt
./scripts/check-run start --label vig-49-build --snapshot <session> --input . --artifact directory:build/test-results -- ./gradlew build
```

## Не входит

- JWT credential parsing, HOCON JWK list decoding, algorithms/key types кроме
  existing RSA contract, key discovery/refresh, file-based JWKS и secret storage.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.1;
Assumptions: 0.1; Aggregate: 0.06.
