# VIG-44: Проверять recognized message payload независимо от role

- **ID:** `VIG-44`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** P2
- **Зависит от:** нет
- **Выполненные предпосылки:** [request protocol](../requirements/chat-completions-protocol.md#request), [REQUEST enforcement](../requirements/request-enforcement.md#field-classification)
- **Блокирует:** нет
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** высокая
- **Архитектурный риск:** High - меняется public request protocol contract и finite matrix accepted message shapes.

## Контекст

Current detector/policy execution получает каждый normalized fragment text и
не использует `MessageRole` или `semanticKind` для selection. Однако parser
применяет несовместимые role gates: modern `tool_calls` и `audio.id` уже
принимаются при всех шести roles, а `function_call`, `reasoning`, refusal и
user media/file parts отклоняются вне прежней role-specific union. Tool-call
fragments при этом получают hard-coded `ASSISTANT`, а не actual enclosing role.

Bounded discovery 2026-09-17 через production parser подтвердил эту matrix;
run `7cef639b7a1d4fa28e6e62bde7e8f8ab`. Пользователь подтвердил, что текущий
product contract проверяет все recognized message payloads независимо от role.
Role-aware policies и tool execution semantics относятся к future scope.

## Scope lock

1. **Наблюдаемый продуктовый результат:** request с любой из шести известных roles проверяет все recognized message payloads; known role не запрещает recognized field или content part и не исключает его из detector/policy execution.
2. **Минимальное достаточное решение:** убрать role gates из recognized request message shapes, сделать content-presence rule role-neutral, сохранить existing field semantics/`semanticKind` и записывать actual enclosing role в provenance.
3. **Обязательные свойства результата:** один finite contract для шести roles; unchanged nested shape validation; unknown/missing/non-string role остаётся failure; каждый non-empty recognized text проверяется отдельно; gaps сохраняются; no-policy и ALLOW replay используют original bytes; role/`semanticKind` не фильтруют текущие policies.
4. **Явные non-goals:** новые или unknown roles/content kinds, role-aware policy matching, tool execution/result correlation, изменение reactions или detector API, response/SSE role semantics, Responses API, inner parsing tool arguments, новые settings.
5. **Более сложные альтернативы:** отдельная policy matrix по roles отклонена до появления role-aware product behavior; flatten всех `semanticKind` отклонён как ненужная migration существующей metadata; provider-specific compatibility mode не нужен для единого подтверждённого contract.
6. **Условие пересмотра:** согласованная policy или tool capability начинает различать message roles либо новый protocol snapshot добавляет content-bearing role/shape; такое изменение получает отдельную implementation-ready issue.
7. **Подтверждение:** пользователь 2026-09-17 подтвердил role-neutral request inspection, сохранение actual role и existing `semanticKind` только как metadata и отсутствие текущей role-aware detector/policy логики.

## Согласованный контракт

`role` остаётся required non-blank exact string из finite set `developer`,
`system`, `user`, `assistant`, `tool`, `function`. Missing/non-string/blank role
даёт `MALFORMED_MESSAGE`; unknown role даёт `AMBIGUOUS_CONTENT`.

Для каждой известной role parser принимает одинаковый recognized message
envelope:

| Field / shape | Role-neutral result |
|---|---|
| String или array `content`; `null` | Existing content fragments/parts; `null` не создаёт fragment/gap |
| `type=text` part | Existing role-derived `INSTRUCTION`, `MESSAGE_TEXT` или `TOOL_RESULT` |
| `type=refusal` part | `REFUSAL` с actual enclosing role |
| `type=image_url`, `input_audio`, `file` parts | Existing `IMAGE`, `AUDIO`, `FILE` gaps; filename остаётся `LABEL` с actual role |
| modern function/custom `tool_calls` | Existing `LABEL`/`TOOL_ARGUMENT`; actual enclosing role вместо hard-coded `ASSISTANT` |
| deprecated `function_call` | Existing `LABEL`/`TOOL_ARGUMENT` с actual role |
| `audio.id` | Existing `OPAQUE_AUDIO_REFERENCE` gap |
| `reasoning.text`, `reasoning.summary`, `reasoning.encrypted_content` | Existing `REASONING` fragments и `OPAQUE_REASONING` gap с actual role |

Message valid, если present `content` либо хотя бы одно recognized sibling
`tool_calls`, `function_call`, `audio`, `reasoning`. Present `content=null`
является recognized empty content для любой известной role. Message без любого
recognized content source остаётся `MALFORMED_MESSAGE`.

Known fields сохраняют current nested type/required-member/discriminator rules.
Role-neutral acceptance не превращает malformed shape или unknown discriminator
в gap. Fragment order, field classification, source coordinates, exact MASK
eligibility и coverage выводятся из recognized fields по existing contracts.

## Context sources

- `spec/WORK_ITEMS.md#risk-based-readiness`
- `spec/requirements/chat-completions-protocol.md#normalized-result`
- `spec/requirements/chat-completions-protocol.md#semantic-field-map`
- `spec/requirements/chat-completions-protocol.md#recognized-message-shapes`
- `spec/requirements/chat-completions-protocol.md#request-source-coordinates`
- `spec/requirements/request-enforcement.md#field-classification`
- `spec/requirements/http-gateway.md#request-parse-outcomes`
- `docs/development.md#protocol-contract-checks`

## План изменений

- Permanent protocol owner: заменить assistant-only/user-only request wording
  role-neutral finite matrix; не менять response contract.
- `ChatCompletionsRequestParser`: передавать actual role во все message field
  collectors, убрать role rejection, унифицировать content-presence/null rule.
- `ChatCompletionsRequestParserTest`: добавить полный cartesian role/field
  contract с independent expected fragments, gaps, kinds, roles и failures.
- `RequestInspectionE2eTest`: доказать одинаковую detector execution и exact
  original replay через real Armeria gateway; обновить coverage/runtime docs.

## Критерии готовности и evidence contract

| ID / stimulus | Public seam | Observable result | Independent oracle |
|---|---|---|---|
| `R1_ROLE_FIELD_MATRIX`: каждая из 6 roles x 8 строк таблицы выше, unique literals | Public request parser | Success с exact ordered fragments/gaps, current `semanticKind`, actual role и coverage | Literal finite table в test fixture; expected tuples не строятся production helpers |
| `R2_CONTENT_PRESENCE`: для каждой role absent content + каждый sibling source; `content=null`; envelope без source | Public request parser | Recognized sibling/null принимается; empty envelope даёт `MALFORMED_MESSAGE` | Отдельные raw JSON fixtures и literal typed outcome |
| `R3_HTTP_EXECUTION`: шесть combined requests, по одному на role, со всеми inspectable field families и gaps | Real client -> Armeria gateway -> upstream | Один detector call на каждый non-empty fragment независимо от role; ALLOW пересылает exact body; один upstream call | Unique per-field literals, captured detector sequence и exact input bytes |
| `R4_NO_POLICY`: те же six-role envelopes при `policies=[]` | Real gateway | Shape валидируется, detector/audit не запускается, exact body forwarded | Detector/upstream counters и literal body |
| `R5_FAILURES`: missing/non-string/blank/unknown role; wrong known nested types; unknown discriminators | Parser и real gateway | Existing typed code; exact 400 body; no detector/upstream; no partial result/source disclosure | Literal HTTP bodies из gateway owner, counters и sentinel absence |
| `R6_REWRITE_REGRESSION`: current free-text/structural MASK cases при noncanonical roles | Existing rewrite planner и real gateway | Existing field-class reaction и exact patch/block не меняются | Approved literal input/output fixtures, не reconstructed expected body |

- [ ] `R1`-`R6` GREEN; первый changed role gate имеет behavioral RED, compilation/fixture failure не считается RED.
- [ ] Protocol owner, runtime guide и requirements coverage согласованы; нигде не осталось assistant-only/user-only claim для request fields из contract выше.
- [ ] Added/modified Kotlin declarations и test methods имеют актуальный KDoc.
- [ ] `./gradlew build` проходит через durable runner после focused tests и detekt.

## Проверки

```bash
./scripts/check-run start --label vig-44-parser --snapshot <session> --input src/main --input src/test --input spec --input docs -- ./gradlew test -x processTest --tests 'io.vigilant.protocol.openai.ChatCompletionsRequestParserTest' --tests 'io.vigilant.gateway.proxy.RequestInspectionE2eTest'
./scripts/check-run start --label vig-44-detekt --snapshot <session> --input src/main --input src/test -- ./gradlew detekt
./scripts/check-run start --label vig-44-build --snapshot <session> --input . --artifact directory:build/test-results -- ./gradlew build
```

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.1; Alternatives: 0.1;
Assumptions: 0.1; Aggregate: 0.08.
