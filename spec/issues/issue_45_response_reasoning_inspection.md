# VIG-45: Проверять текстовый reasoning в ответах Chat Completions

- **ID:** `VIG-45`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** P1
- **Зависит от:** нет
- **Выполненные предпосылки:** [response parsing](../requirements/chat-completions-protocol.md#response-descriptor), [RESPONSE enforcement](../requirements/response-enforcement.md#atomic-boundary)
- **Блокирует:** нет
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** средняя
- **Архитектурный риск:** High - расширяется внешний protocol contract и набор проверяемых response fragments.

## Контекст

Задача возникла при подготовке цепочки Filin OpenClaw -> Vigilant -> LiteLLM.
В исследованной локальной конфигурации Filin выбрана Qwen3.6-27B-FP8 и включён
`OPENCLAW_MODEL_REASONING=true`. Харнес запрашивает thinking и поддерживает
SSE `choices[].delta.reasoning_content`. Синтетический fixture Filin явно
содержит такое поле. Реальный ответ корпоративного LiteLLM в этом исследовании
не снимался; наличие поля в каждом ответе модели не утверждается.

Текущее поведение Vigilant подтверждено чтением исходников:

- `ChatCompletionsResponseParser.ResponseCollector.collectChoice` не извлекает
  `message.reasoning_content` из ordinary JSON, поэтому текст в этом поле
  не участвует в проверке.
- `SseResponseCollector.validateDeltaFields` отклоняет строковый
  `delta.reasoning_content` как `AMBIGUOUS_CONTENT`. На HTTP boundary такой
  response заменяется `502 invalid_upstream_response`.
- Semantic kind `REASONING` уже существует. Request parser использует его для
  `messages[].reasoning.text` и `.summary`; encrypted content остаётся gap.
- RESPONSE workflow проверяет normalized fragments и уже умеет атомарные
  ALLOW/MASK/BLOCK, source-preserving JSON/SSE rewrite и safe audit.

Нужно включить открытый текст reasoning в существующую проверку PII.
Это не проверка правильности рассуждений модели и не извлечение скрытого
или зашифрованного reasoning.

## Scope lock

1. **Наблюдаемый продуктовый результат:** корректный Chat Completions response с текстовым `reasoning_content` проходит protocol parsing; PII в reasoning вызывает такую же policy reaction, как PII в других проверяемых response fields.
2. **Минимальное достаточное решение:** поддержать `choices[].message.reasoning_content` в ordinary JSON и `choices[].delta.reasoning_content` в SSE как отдельные fragments существующего kind `REASONING`; расширить source mapping для точного MASK и использовать действующий RESPONSE workflow.
3. **Обязательные свойства результата:** полная проверка reasoning до первого client byte; ALLOW сохраняет исходные bytes; MASK меняет только выбранные spans; BLOCK скрывает весь response. Reasoning не смешивается с final content, refusal, tool arguments или другими choices. Сохраняются existing errors, lifecycle ownership, privacy и отсутствие новых mandatory settings.
4. **Явные non-goals:** request-side aliases и история reasoning в запросах; поля `reasoning`, `reasoning_text`, `reasoning_details` в ответах; encrypted/opaque reasoning; Responses/Anthropic API; новый detector или оценка качества reasoning; live streaming до завершения проверки; изменение buffering, timeouts, quotas, identity, policy schema или deployment. Подключение и запуск Filin не входят в issue.
5. **Более сложные альтернативы:** универсальный provider adapter или schema registry не нужны для двух известных paths. Поиск текста в произвольных неизвестных fields создаёт неоднозначность. Удаление reasoning или forwarding без inspection не достигает цели. Добавление reasoning к `content` теряет semantic boundary и изменяет исходный response.
6. **Условие пересмотра:** воспроизводимый совместимый response Filin/LiteLLM требует другой content-bearing shape либо ожидаемый результат не укладывается в существующие source maps. До расширения scope зафиксировать отдельный fixture и согласовать изменение; не добавлять aliases или silent fallback автоматически.
7. **Подтверждение:** пользователь 2026-09-15 ответом «подтверждаю. давай проработаем задачу» утвердил предложенную границу: `reasoning_content` в JSON/SSE responses, существующие ALLOW/MASK/BLOCK и буферизация, без request history, других reasoning formats и подключения Filin. Контракт ниже конкретизирует эту границу. Scope lock закрыт; runtime implementation ещё не начата.

## Согласованный контракт

### Формы поля

Одинаковая finite matrix для `message.reasoning_content` и
`delta.reasoning_content`:

| Case | Значение | Результат parser |
|---|---|---|
| `TEXT` | Непустая string | `REASONING`, role `ASSISTANT`, direction `RESPONSE` |
| `EMPTY` | `""` | Нет самостоятельного fragment/gap; в SSE не обнуляет ранее накопленный текст |
| `ABSENT` | Поля нет | Нет fragment/gap |
| `NULL` | `null` | Нет fragment/gap; в SSE не обнуляет buffer |
| `WRONG_SCALAR` | `0`, `false` | `MALFORMED_MESSAGE` |
| `WRONG_CONTAINER` | `{}`, `[]` | `MALFORMED_MESSAGE` |
| `DUPLICATE` | Два одноимённых keys в одном object | `AMBIGUOUS_CONTENT` |

Корректный reasoning не требует соседнего final content: ordinary JSON с
`content` missing/null и непустым `reasoning_content`, а также SSE только с
reasoning deltas и terminal `[DONE]` должны проверяться. Непустой reasoning
создаёт inspectable text, а не gap. Сам по себе он не меняет coverage соседних
полей. `role` missing/assistant, `choices`, indexes и terminal rules сохраняют
действующий контракт; explicit non-assistant role остаётся отказом.

Отказ parser в gateway даёт действующий `502 invalid_upstream_response` до
раскрытия upstream bytes. Известное поле с неверным типом не трактуется как
additive metadata или inspection gap. Проверка shape выполняется и при
`policies = []`. Request descriptor остаётся `POST /v1/chat/completions`.

В ordinary JSON порядок fragments одного choice: content, refusal,
reasoning_content, затем прежний обход tool calls/function call/audio.
Их прежний относительный порядок сохраняется. Locator reasoning указывает
на `/choices/<array-position>/message/reasoning_content`.

В SSE logical buffer определяется `choice.index + reasoning_content`;
string deltas конкатенируются в порядке событий. Порядок fragments следует
первому появлению string delta logical field, включая empty string;
полностью пустые buffers отбрасываются только при формировании результата.
`null` не создаёт buffer и не резервирует место в порядке fragments.
Внутри одного event сохраняется
порядок content, refusal, reasoning_content, calls. Source map хранит связь
с исходными string literals каждого reasoning delta. `null` и empty deltas
не создают ложного gap. Завершение остаётся по standalone `[DONE]` с проверкой
остатка source. Chunk boundary внутри UTF-8 и JSON escape не меняет результат.
Locator SSE reasoning: `/choices/<choice.index>/delta/reasoning_content`.
Reported choice index в ordinary JSON не заменяет array position в locator;
в SSE используется именно `choice.index`, а не позиция в event array.

### Реакции и точное сохранение данных

Reasoning является отдельным free-text fragment существующего RESPONSE
workflow. Его findings участвуют в текущей агрегации: technical error имеет
приоритет над BLOCK, затем MASK, затем ALLOW. Другие fragment kinds сохраняют
свою семантику. Не добавляются отдельная политика, detector, feature flag
или новая конфигурация для включения reasoning inspection.

- ALLOW, включая detected=ALLOW и no-policy, сохраняет весь original body.
- MASK использует существующие typed markers. Для cross-event span marker
  появляется один раз в первом затронутом string value; covered text удаляется
  из последующих values, сами events сохраняются.
- BLOCK из reasoning заменяет весь response существующим `403 policy_blocked`.
- Detector error/deadline или невозможность точного rewrite дают существующий
  `503 response_inspection_unavailable`, `Retry-After: 1`, без partial output.
- Audit учитывает reasoning findings в существующих outcome/counts без
  добавления raw reasoning, previews, locators или нового telemetry schema.

При единственной применённой RESPONSE policy, одном reasoning fragment с
одним email и без других текстовых fragments успешный анализ даёт
`fragments.inspected=1`, `findings.total=1`, `findings.by_type=EMAIL:1`,
`coverage=FULLY_INSPECTABLE`, `outcome=DETECTED` и выбранную
`reaction=ALLOW|MASK|BLOCK`. Несколько deltas этого reasoning не увеличивают
число fragments/findings. При detector error outcome=ERROR, reaction отсутствует.
При no-policy речь об отсутствии RESPONSE detector/audit; REQUEST-фаза
сохраняет собственные правила. В HTTP fixtures REQUEST policies выключены,
чтобы не смешивать наблюдения двух фаз.

### Независимые примеры

Для RESPONSE policy с `fast-pii`, detected=MASK, clean=ALLOW, error=BLOCK:

```json
{"choices":[{"message":{"role":"assistant","content":"OK","reasoning_content":"Contact alice@example.com"}}]}
```

Ожидаемый body, записанный literal fixture без production rewriter:

```json
{"choices":[{"message":{"role":"assistant","content":"OK","reasoning_content":"Contact [EMAIL_MASKED]"}}]}
```

SSE reasoning deltas `"alice@"`, `"example.com"` одного choice дают canonical
`"alice@example.com"`. При MASK ожидаемые values: `"[EMAIL_MASKED]"`, `""`.
Final `content: "OK"` и `[DONE]` сохраняются. Те же две половины в разных
choices либо в reasoning/content одного choice не образуют общий email.
Для независимости отрицательного примера остальные fragments пусты;
policy проверяет email, оба отдельных фрагмента не содержат полного адреса.

Case `EMPTY_FIRST_ORDER`: для одного choice последовательно приходят
`reasoning_content: ""`, `content: "OK"`, `reasoning_content: "Plan"`.
Ожидаемый порядок terminal fragments: `REASONING("Plan")`, `OUTPUT_TEXT("OK")`.
Если в первом событии вместо empty string стоит `null`, порядок обратный:
`OUTPUT_TEXT("OK")`, `REASONING("Plan")`. Если третье событие отсутствует,
результат в обоих случаях содержит только `OUTPUT_TEXT("OK")`.

Минимальный самостоятельный SSE stimulus для `R1_SSE_MASK`:

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"alice@"}}]}

data: {"choices":[{"index":0,"delta":{"reasoning_content":"example.com"}}]}

data: {"choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

data: [DONE]

```

Fixture заканчивается двумя LF после `[DONE]`; затем upstream завершает HTTP
body. Expected body отличается только двумя literal replacements:
`"alice@"` -> `"[EMAIL_MASKED]"`, `"example.com"` -> `""`.
Expected записывается независимо до запуска gateway. Для BLOCK используется
тот же stimulus с другой policy reaction; expected - полный safe 403 из HTTP
owner. Никакая часть reasoning или `content=OK` до решения не раскрывается.

Дополнительные именованные cases входят в R2/R5/R6:

| Case / criterion | Stimulus | Независимое ожидание |
|---|---|---|
| `REASONING_ONLY_JSON` / R2 | `message` содержит reasoning email, `content` отсутствует; повторить с `content=null` | Один REASONING fragment и MASK/BLOCK по policy |
| `REASONING_ONLY_SSE` / R2 | Первые два reasoning events из примера, затем DONE без content event | Один REASONING fragment и та же reaction |
| `SPARSE_CHOICE_INDEX` / R5 | Один choice на array position 0 с reported index 7 | JSON locator `/choices/0/message/reasoning_content`; SSE locator `/choices/7/delta/reasoning_content` |
| `IDENTICAL_FIELDS` / R5 | reasoning и content каждый содержат полный `alice@example.com` | Два независимых fragments и два findings; MASK применяется к обоим полям, dedup по равному тексту отсутствует |
| `EMPTY_BETWEEN_PARTS` / R6 | reasoning deltas `alice@`, `""`, `null`, `example.com` | Один email; MASK values `[EMAIL_MASKED]`, `""`, `null`, `""`; empty/null events сохранены |
| `SPLIT_THREE` / R6 | reasoning deltas `ali`, `ce@exam`, `ple.com` | MASK values `[EMAIL_MASKED]`, `""`, `""` |
| `ESCAPED_EMAIL` / R6 | JSON reasoning literal `"До 🌍 alice\u0040example.com после"` | Decoded finding email; expected raw literal `"До 🌍 [EMAIL_MASKED] после"`; prefix/suffix bytes сохранены |

R6 также прогоняет минимальный SSE stimulus с LF и CRLF, с comment event
перед первым chunk и с `choices: []` usage event перед DONE. Эти варианты
не меняют reasoning texts или expected patches. Отдельные ingest schedules
режут body внутри четырёхбайтового UTF-8 символа и внутри JSON escape;
expected response bytes совпадают с цельным input case.

## Context sources

- `spec/WORK_ITEMS.md#risk-based-readiness`
- `CLAUDE.md#protocol-compatibility-principle`
- `spec/requirements/chat-completions-protocol.md#normalized-result`
- `spec/requirements/chat-completions-protocol.md#ordinary-json-response`
- `spec/requirements/chat-completions-protocol.md#sse`
- `spec/requirements/chat-completions-protocol.md#response-source-maps`
- `spec/requirements/response-enforcement.md#atomic-boundary`
- `spec/requirements/response-enforcement.md#retained-source-and-ownership`
- `spec/requirements/response-enforcement.md#fragments-gaps-and-reactions`
- `spec/requirements/response-enforcement.md#source-maps-and-exact-rewrite`
- `spec/requirements/response-enforcement.md#failures-and-cancellation`
- `spec/requirements/http-gateway.md#inspection-error-matrix`
- `spec/requirements/observability.md#analysis-lifecycle-audit`
- `spec/requirements/observability.md#privacy-by-channel`
- `docs/development.md#protocol-contract-checks`
- `docs/development.md#response-and-gateway-contract-checks`

## Критерии готовности и evidence contract

Основной acceptance seam - HTTP gateway с реальными Armeria gateway/upstream.
Public parser и rewriters дают дополнительные точные наблюдения semantic kind,
source coordinates и typed failures. Корпоративный LiteLLM, доступы Filin и
запуск соседних репозиториев не являются обязательной зависимостью этих tests.

| ID / stimulus | Public seam | Observable result | Independent oracle |
|---|---|---|---|
| `R1_JSON_MASK`, `R1_SSE_MASK`: email находится только в reasoning, content=OK, policy MASK | HTTP | JSON и SSE возвращают masked reasoning; content=OK | Literal bodies/values из примеров; остальные raw bytes fixture неизменны |
| `R2_SHAPES`: каждая строка matrix форм поля, оба transports | Public parser и HTTP | Указанные kind/role/direction/locator либо typed failure и 502; malformed не проходит и без policies | Таблица форм; literal safe error body из HTTP owner, без production encoder |
| `R3_ALLOW`: clean+ALLOW, detected+ALLOW, no-policy, оба transports | HTTP | Exact upstream status, end-to-end headers и body; no-policy не запускает RESPONSE detector/audit | Fixtures со status 200/429/500, безопасным header `x-fixture: kept`, заданными byte arrays и счётчиком detector |
| `R4_BLOCK`: email только в reasoning; соседний content чистый или также содержит PII, оба transports | HTTP | Whole-response 403; исходный body не раскрывается | Literal `policy_blocked` body и отсутствие синтетических sentinels upstream |
| `R5_ISOLATION`: две halves email в одном reasoning buffer / в двух choices / в reasoning и content; interleaved SSE events; `EMPTY_FIRST_ORDER` с empty/null и с продолжением/без продолжения | Parser и HTTP | В первом случае finding/MASK; в двух остальных нет общего finding; порядок и locators следуют контракту, включая empty-first cases | Ручной список logical texts, fragments и expected body; порядок empty-first задан независимыми примерами выше |
| `R6_REWRITE`: email пересекает два и три reasoning events; Unicode prefix, JSON escapes, LF/CRLF, comment и usage event | Public rewriters и HTTP | Один marker на span, корректные UTF-8/JSON; untouched bytes и framing сохранены; Content-Length корректен | Отдельно записанные input/expected byte fixtures, без вызова production mapping/masker для expected |
| `R7_FAILURES`: reasoning detector error / deadline / rewrite failure в JSON и SSE; truncated JSON и SSE EOF без DONE; malformed reasoning из R2 | HTTP; существующий workflow seam для инъекции rewrite failure | Error/deadline/rewrite: 503 + Retry-After:1; invalid protocol: 502; partial output отсутствует | Literal HTTP contract, управляемые fixture barriers/failures и upstream sentinels |
| `R8_ATOMIC`: JSON и SSE upstream удерживается до HTTP body completion; затем reasoning detector удерживается barrier | HTTP subscriber | Ни status, ни headers/body до complete protocol-valid source и final decision; после release применена reaction | Upstream barrier и detector barrier; для SSE перед EOF присутствует DONE; sleep сам по себе не evidence |
| `R9_CLEANUP`: JSON и SSE, cancellation при ingest, при analysis и во время replay reasoning response | HTTP и существующая source observation fixture | Upstream/analysis/replay прекращается согласно owner; retained source released, нового handoff после cancellation нет | Управляемый publisher/detector/client, bounded await owning cleanup observation |
| `R10_PRIVACY`: reasoning-only finding при ALLOW/MASK/BLOCK и detector error, оба transports | HTTP + captured audit/trace output | Одна RESPONSE audit pair после начатого analysis; успешные counts/outcome/reaction заданы выше, ERROR без reaction; reasoning sentinels отсутствуют в telemetry/errors | Один email, один fragment; literal safe metadata, unique synthetic sentinel, исправно принимающий test sink и bounded await logger/exporter completion |

- [x] Scope lock согласован 2026-09-15; boundary и независимые примеры закрыты для передачи в реализацию.
- [ ] Получен behavioral RED `R1_SSE_MASK`: отказ именно из-за неизвестного reasoning field, а не fixture/compilation failure. Для `R1_JSON_MASK` показано отсутствие требуемого masking; затем те же tests GREEN.
- [ ] Выполнены `R1`-`R10`; green parser tests не заменяют HTTP inspection evidence.
- [ ] Сохраняются существующие regression cases content/refusal/tool arguments, unknown SSE content-bearing field rejection, JSON/SSE terminal rules и request reasoning object/gap.
- [ ] Обновлены permanent protocol field map, RESPONSE fragment list, runtime docs и requirements coverage; до реализации эти документы не объявляют новый contract доступным.
- [ ] Выполнены focused checks, detekt и текущий full build по project workflow.

## Последовательность реализации

1. В существующих `JsonResponseEnforcementE2eTest` и
   `SseResponseEnforcementE2eTest` воспроизвести R1 через реальный gateway с
   controlled upstream. Зафиксировать две причины RED: JSON пропускает PII
   без masking, SSE отклоняет известный теперь reasoning field.
2. Завершить JSON slice: recognition/validation в response parser, существующее
   построение `JsonStringSourceCoordinates` из normalized fragments, R1/R2
   и JSON rewrite cases. `REASONING` enum не добавляется повторно.
3. Завершить SSE slice: recognition/validation и независимый buffer, existing
   `SseDeltaSegmentSourceCoordinates`, cross-event MASK и ordering cases.
   Не обходить `validateDeltaFields` целиком ради одного нового поля.
4. Прогнать R3-R10 через тот же `ResponseInspectionWorkflow`. JSON/SSE
   rewriters уже используют generic source maps и не выбирают fields по
   имени; менять их только при доказанной необходимости точного mapping.
   Retained source, HTTP adapter, policy engine и audit schema сохраняют
   ответственность; отдельный reasoning workflow не создаётся.
5. Выполнить existing regression suites и синхронизировать owning sections
   protocol/response requirements, `docs/openai-chat-completions.md` и
   `docs/requirements-coverage.md`. Прежний negative fixture именно для
   `reasoning_content`, если он есть, намеренно мигрирует в positive; контроль
   отказа неизвестного текстового delta сохраняется на другом имени поля.

Одна issue остаётся достаточной: результат - одна capability response
inspection, основной seam общий HTTP gateway, новые resource owners,
services и deployment steps отсутствуют. JSON/SSE - два последовательных
implementation slices одного результата.

## Проверки

Focused после реализации, через durable runner:

```bash
./scripts/check-run start --label vig45-focused --timeout 600 -- ./gradlew test -x processTest --tests 'io.vigilant.protocol.openai.ChatCompletionsResponseParserTest' --tests 'io.vigilant.protocol.openai.JsonResponseRewriterTest' --tests 'io.vigilant.protocol.openai.SseResponseRewriterTest' --tests 'io.vigilant.gateway.proxy.JsonResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.proxy.SseResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.proxy.ResponseInspectionWorkflowTest' detekt
./scripts/check-run start --label vig45-build --timeout 1200 -- ./gradlew build
./scripts/task-context VIG-45
git diff --check
```

Вторую Gradle invocation запускать только после подтверждённого завершения
первой через `check-run wait/status`. Expected fixtures не перегенерировать
из actual output. Дополнительные process/OCI/load benchmarks и Pitest этой
задачей не требуются. Live Filin acceptance относится к будущему подключению;
её отсутствие не скрывать за synthetic compatibility fixture.

## Readiness и Ambiguity Report

Существенных открытых решений внутри согласованной границы нет. Scope lock
утверждён, формы и errors заданы конечной матрицей, generic mapping существует,
HTTP stimulus и independent oracles выполнимы без внешнего стенда.
Runtime acceptance criteria остаются невыполненными до реализации.

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.1; Aggregate: 0.04. Непроверенный live payload корпоративного
LiteLLM ограничивает утверждение о полной совместимости Filin, но не блокирует
реализацию явно согласованного `reasoning_content` contract.
