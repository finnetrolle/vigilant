# VIG-48: Отклонять trailing data в JWT header и claims

- **ID:** `VIG-48`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** P1
- **Зависит от:** нет
- **Выполненные предпосылки:** [Offline JWT contract](../requirements/identity-and-context.md#offline-jwt)
- **Блокирует:** нет
- **Оценка:** 1-2 инженерных дня
- **Уверенность:** высокая
- **Архитектурный риск:** Low - локальное conformance исправление existing signed-JWT validation без изменения trust contract.

## Результат

Decoded JWT protected header и claims payload каждый содержат ровно один полный
JSON object с optional trailing JSON whitespace. Correctly signed token с
вторым root или trailing garbage в любом segment получает existing safe
`400 invalid_identity` до request-body demand и LLM upstream.

## Context sources

- `spec/requirements/identity-and-context.md#single-bearer-boundary`
- `spec/requirements/identity-and-context.md#offline-jwt`
- `docs/development.md#identity-contract-checks`

## План изменений

- `OfflineJwtIdentityExtractor.JWT_JSON`: включить full-document validation для
  обоих decoded segments, сохранив duplicate detection и validation order.
- JWT fixture: подписывать exact caller-supplied raw header/claims bytes, чтобы
  parser failure не маскировался invalid signature.
- `OfflineJwtIdentityExtractorTest`: finite header/claims matrix.
- `GatewayIdentityE2eTest`: safe HTTP, no body demand/upstream и privacy.
- Requirements coverage/evidence: записать фактическую contract protection.

## Критерии готовности и evidence contract

| ID / stimulus | Public seam | Observable result | Independent oracle |
|---|---|---|---|
| `J1_VALID_WHITESPACE`: valid header или claims + empty/SP/TAB/LF/CRLF, signature над exact bytes | Public `OfflineJwtIdentityExtractor` | Existing normalized identity success | Literal claims/user/groups and fixed clock |
| `J2_HEADER_SECOND_ROOT`, `J3_CLAIMS_SECOND_ROOT`: object, array, string, number, boolean, null после valid object; exact signed bytes | Public extractor | `Failure(INVALID_CREDENTIAL)` | Independent raw signer, finite suffix matrix, literal result |
| `J4_HEADER_GARBAGE`, `J5_CLAIMS_GARBAGE`: identifier, punctuation, truncated token после valid object; exact signed bytes | Public extractor | `Failure(INVALID_CREDENTIAL)` | Same independent raw signer; signature validity established by positive control over same key/input path |
| `J6_PUBLIC_FAILURE`: representative valid-signature trailing case в каждом segment | Real client -> gateway -> upstream | Exact 400 `{"error":"invalid_identity"}`; no body demand/upstream; credential/payload sentinels absent | Literal body, demand/call counters and accepting telemetry sink |
| `J7_REGRESSION`: valid rotations/claims/whitespace; duplicate keys; wrong signature/alg/kid/trust/time/identity shapes | Existing unit and real-gateway suites | Existing outcomes unchanged | Current exhaustive JWT matrix |

- [ ] `J1`-`J7` GREEN; `J2`-`J5` RED обязан падать из-за accepted trailing document, не signature/fixture error.
- [ ] Signature остаётся над exact compact signing input; validation не декодирует или нормализует token bytes иначе.
- [ ] No raw credential/header/claims in logs, errors, spans or audit.
- [ ] Added/modified methods и tests имеют актуальный KDoc.
- [ ] `./gradlew build` проходит через durable runner.

## Проверки

```bash
./scripts/check-run start --label vig-48-identity --snapshot <session> --input src/main --input src/test --input spec --input docs -- ./gradlew test -x processTest --tests 'io.vigilant.gateway.identity.OfflineJwtIdentityExtractorTest' --tests 'io.vigilant.gateway.proxy.GatewayIdentityE2eTest'
./scripts/check-run start --label vig-48-detekt --snapshot <session> --input src/main --input src/test -- ./gradlew detekt
./scripts/check-run start --label vig-48-build --snapshot <session> --input . --artifact directory:build/test-results -- ./gradlew build
```

## Не входит

- JWK environment parsing, algorithms кроме RS256, claim mapping, discovery,
  key refresh, leeway, clock semantics, identity grammar и token persistence.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.1;
Assumptions: 0.1; Aggregate: 0.06.
