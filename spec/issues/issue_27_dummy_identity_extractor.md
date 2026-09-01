# VIG-27: Dummy Bearer identity extractor

- **ID:** `VIG-27`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** Medium
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 1-2 инженерных дня

## Результат

Отдельный `DummyIdentityExtractor` не проверяет и не сохраняет Bearer token,
не вызывает external service и всегда возвращает configured normalized
user/groups. Real Bearer extractor-ы не содержат Dummy branch, fallback или
Dummy-specific configuration.

VIG-27 removes current `ANONYMOUS`, `TRUSTED_HEADERS` and `BASIC` modes,
their extractor/configuration classes, `TrustedNetwork`, their tests and all
documentation/example configuration that describes them. No compatibility
alias, deprecated setting or migration mode remains.

This deliberately makes production startup impossible after VIG-27: `DUMMY`
is rejected in production and no real Bearer extractor exists yet. The future
Bearer identity epic restores a valid production identity mode. This temporary
startup rejection is an explicit accepted migration state, not an error path.

## Конфигурация

```hocon
environment = "development"             # development | test | production
identity-mode = "DUMMY"
identity-dummy-user = "local-user"
identity-dummy-groups = ["local-group"] # optional
```

`VIGILANT_ENVIRONMENT`, `VIGILANT_IDENTITY_MODE`,
`VIGILANT_IDENTITY_DUMMY_USER` и `VIGILANT_IDENTITY_DUMMY_GROUPS` override
HOCON under existing environment-over-file precedence. User is required;
groups are optional, normalized, deduplicated and bounded by current identity
contract. Removed legacy settings (`identity-user-header`,
`identity-groups-header`, `identity-trusted-cidrs`) are invalid. Startup
permits `DUMMY` only in `development`/`test`, rejects it in `production`.

## HTTP contract

Every `DUMMY` request requires exactly one `Authorization` header with
case-insensitive `Bearer` scheme. Token value may be empty and is ignored.

| Input | Result before body demand |
|---|---|
| Valid `Bearer`, empty/non-empty token | configured user/groups |
| Missing `Authorization` | `401` + `WWW-Authenticate: Bearer realm="vigilant"` |
| `Basic` or another scheme | `401` + `WWW-Authenticate: Bearer realm="vigilant"` |
| Duplicate `Authorization` | `400 {"error":"invalid_identity"}` |
| Malformed Bearer representation | `400 {"error":"invalid_identity"}` |

Original accepted `Authorization` is forwarded byte-for-byte to LiteLLM. Raw
token never enters `PolicyContext`, audit, logs, metrics, traces or errors.

## Legacy removal and non-goals

Remove `IdentityExtractor`, `IdentityMode`, `IdentitySettings`, `TrustedNetwork`
and obsolete anonymous identity helpers only when they have no remaining
production consumer. Remove or replace their focused tests. Preserve generic
`NormalizedIdentity`, `PolicyContext` and policy matching contracts.

Do not retain deprecated identity configuration or documentation merely to let
production start. A later Bearer epic owns all real replacement extractors.

## Не входит

Keycloak JWT, Bridge lookup, real Bearer implementation, anonymous fallback,
Basic Auth, trusted headers, provider registry, LiteLLM auth/token-format
changes, token cache or persistence.

## Критерии приёмки

- [x] Separate Dummy class returns configured identity for every valid Bearer
  header; real extractor classes contain no Dummy branches.
- [x] Full HTTP matrix above is covered by real-Armeria tests, including no
  body demand before all reject responses.
- [x] Invalid/missing config prevents startup; production rejects `DUMMY`.
- [x] `ANONYMOUS`, `TRUSTED_HEADERS`, `BASIC`, their configuration keys,
  extractor code, trusted-network code, dead helpers/tests and documentation
  are removed. A legacy mode/key causes validated startup rejection.
- [x] Production startup deterministically rejects after legacy removal because
  `DUMMY` is the sole available mode and is production-forbidden; this outcome
  is covered by focused configuration/process evidence.
- [x] Policy selection sees configured identity and LiteLLM receives original
  accepted Authorization unchanged.
- [x] KDoc, focused TDD evidence, `./gradlew build` and
  `./gradlew validateWorkItems` pass.

## Test seam

Real Armeria request through `PiiShadowProxyService` under `DUMMY`: observe
policy identity, forwarding and pre-body rejection. Each observable branch
starts RED, then minimal implementation and GREEN.

## Ambiguity Report

```text
Goals: 0.0; Acceptance: 0.0; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.05; Aggregate: 0.01. Ready for implementation.
```
