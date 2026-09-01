# VIG-28: Offline trusted JWT Bearer identity extractor

- **ID:** `VIG-28`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** [VIG-27](issue_27_dummy_identity_extractor.md)
- **Блокирует:** нет
- **Оценка:** 3-5 инженерных дней

## Результат

Production Bearer identity implementation validates a signed JWT and extracts
normalized user/groups locally, without any Keycloak network call. It becomes
the main real implementation of a common Bearer identity contract; future
Bridge lookup implements the same contract separately.

The extractor validates signature through locally configured pinned public
key(s), issuer, audience, expiry and not-before before reading any identity
claim. It maps validated claims into `NormalizedIdentity`, fails closed before
body demand and preserves original `Authorization` byte-for-byte for LiteLLM
forwarding.

## Границы

- No Keycloak discovery, JWKS, UserInfo, introspection or Admin API call;
  no Bridge lookup, token exchange, refresh, persistence or token cache.
- No dummy behavior inside JWT implementation and no fallback to Dummy.
- No change to LiteLLM token format, policy semantics, audit schema or response
  inspection.
- JWT payload is never trusted before cryptographic validation and raw token
  never enters context, audit, logs, metrics, traces or errors.

## Claim contract

```text
user   = required string `sub`
groups = optional top-level JSON array `groups`
```

`sub` is the stable Keycloak subject identifier. `groups` is emitted by the
Keycloak Group Membership mapper. Missing `groups` becomes the empty set;
missing/blank `sub`, a non-array groups claim, blank/non-string group member or
duplicate normalized group fails closed. No nested claim paths, roles-as-groups
mapping or configurable claim names are introduced.

## Trust configuration

```hocon
identity-mode = "JWT"
identity-jwt-issuer = "https://keycloak.example/realms/platform"
identity-jwt-audience = "vigilant"
identity-jwt-jwks = [ { "kty": "RSA", "kid": "key-2026-01", ... } ]
```

`issuer`, `audience` and a non-empty JWK set are required. `iss` equals issuer
exactly; `aud` contains audience. Only `RS256` is accepted. Every configured
asymmetric public JWK has a non-empty unique `kid`; JWT header `kid` selects
exactly one configured key. Unknown/missing/duplicate `kid`, unsupported
algorithm, invalid JWK, invalid signature, expiry, not-before, issuer or
audience mismatch fail closed.

Key rotation is explicit deployment configuration: add the new JWK before
removing the old one. No discovery, JWKS fetch, key refresh or identity lookup
occurs at runtime.

## Initial acceptance direction

- Valid JWT produces one normalized identity for policy matching without a
  Keycloak/network call.
- Missing/duplicate/non-Bearer header follows existing Bearer HTTP matrix;
  invalid signature, issuer, audience, expiry, not-before or required identity
  claims fail closed before body demand.
- Public-key rotation requires explicit configuration/deployment update; no
  identity validation network I/O occurs on Armeria event loop.
- Original accepted `Authorization` reaches LiteLLM unchanged.
- Focused JWT/config tests, real-Armeria seam, TDD, `./gradlew build` and
  `./gradlew validateWorkItems` are required.

## Критерии приёмки

- [ ] Common Bearer identity contract selects `JWT` mode at startup without
  mixing Dummy behavior into `OfflineJwtIdentityExtractor`.
- [ ] Table-driven JWT matrix covers all required claims, valid RS256 signature,
  each configured key, unknown/missing `kid`, duplicate JWK kid, invalid JWK,
  wrong algorithm/signature/issuer/audience, expired/not-before token and every
  invalid `sub`/`groups` shape; each invalid case fails before body demand.
- [ ] Runtime makes no network call for discovery, key fetch, Keycloak or
  identity lookup. Key rotation is proven solely by configuration containing
  old/new JWK sets.
- [ ] Existing Bearer HTTP matrix remains exact: missing/non-Bearer gives `401`
  challenge, malformed/duplicate header gives `400`, valid JWT with empty
  `groups` reaches policy selection and original Authorization reaches LiteLLM
  byte-for-byte.
- [ ] Raw JWT and decoded claim values never appear in policy context beyond
  normalized user/groups, audit, logs, metrics, traces or errors.
- [ ] Modified Kotlin declarations/tests contain KDoc; focused tests,
  `./gradlew build` and `./gradlew validateWorkItems` pass.

## План и test seam

- Add validated JWT settings and static JWK parser; reject JWT settings outside
  `JWT` mode and legacy/Dummy fields in `JWT` mode.
- Add Bearer-header parsing shared with VIG-27 and `OfflineJwtIdentityExtractor`
  on a blocking-safe path; preserve original request header ownership for proxy
  forwarding.
- Wire extractor in `AppComponent`, replace production-rejecting post-VIG-27
  mode selection and update configuration/runtime documentation and examples.
- Use real Armeria request through `PiiShadowProxyService` as primary seam:
  assert configured policy identity, pre-body reject and byte-exact upstream
  Authorization. Apply RED-GREEN TDD one behavior at a time.

## Ambiguity Report

```text
Goals: 0.0; Acceptance: 0.0; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.05; Aggregate: 0.01. Ready for implementation.
```
