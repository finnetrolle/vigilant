# Epic 03: Извлечение контекста применения политик

**ID:** `EPIC-03`  
**Тип:** Epic  
**Статус:** Draft  
**Приоритет:** High  
**Предварительная оценка:** 17-27 инженерных дней после закрытия решений  
**Связанные требования:** `MVP-14`, `MVP-20`, `MVP-21`

## Карта декомпозиции

```text
EPIC-03 Policy context extraction
├── Context contract and trust boundary
├── URL normalization
├── Identity
│   ├── configurable extraction
│   └── secret-safe upstream stripping
├── Context assembly
│   └── HTTP-derived + protocol-derived attributes
└── Lifecycle
    ├── request-to-response handoff
    └── end-to-end security behavior
```

Epic остаётся `Draft`, потому что перечисленные ниже issues зависят от решений
из раздела «Открытые решения». Первая дочерняя issue фиксирует эти решения и
обновляет нормативный контракт epic; только после этого зависимые issues могут
быть переведены в `Ready for implementation`.

## Дочерние issues

- [ ] [VIG-03-01: Контракт и trust boundary](../issues/epic_03/issue_03_01_context_contract.md) - `Draft`
- [ ] [VIG-03-02: Нормализация URL](../issues/epic_03/issue_03_02_url_normalization.md) - `Draft`
- [ ] [VIG-03-03: Настраиваемое identity extraction](../issues/epic_03/issue_03_03_identity_extraction.md) - `Draft`
- [ ] [VIG-03-04: Сборка PolicyContext из нормализованных данных](../issues/epic_03/issue_03_04_model_extraction.md) - `Draft`
- [ ] [VIG-03-05: Перенос контекста в response phase](../issues/epic_03/issue_03_05_response_handoff.md) - `Draft`
- [ ] [VIG-03-06: E2E security и upstream stripping](../issues/epic_03/issue_03_06_security_e2e.md) - `Draft`

## Контекст

Policy engine должен выбирать политики по четырём параметрам:

- URL назначения;
- модели;
- фазе обработки: запрос к модели или ответ модели;
- субъекту запроса: отдельному пользователю или группе.

Эти значения приходят из разных частей HTTP-обмена. URL, phase и identity
принадлежат HTTP context extraction. Значения внутри LLM protocol body или
events, включая model, извлекает отдельный
[EPIC-06](epic_06_llm_message_parsing.md). EPIC-03 получает их в готовом
нормализованном виде и не разбирает raw body повторно.

Пользователь может определяться через Basic Authentication или специальный
HTTP-заголовок. Названия служебных заголовков не могут быть жёстко заданы в
коде и должны настраиваться.

Извлечение и нормализация этих значений отделяются от механизма выбора политик. Policy engine должен получать готовый контекст и не должен самостоятельно разбирать HTTP-запрос, авторизацию или тело сообщения.

## Цель

Определить и реализовать слой, который получает нормализованные HTTP-derived
и protocol-derived данные, строит `PolicyContext` и сохраняет необходимые
значения для последующей проверки ответа.

Предварительный состав контекста:

```text
PolicyContext
  url
  model
  phase
  user
  groups
```

`phase` принимает одно из двух значений:

```text
REQUEST
RESPONSE
```

Для ответа должны использоваться URL, модель и субъект, установленные при
обработке соответствующего запроса. Reported model из upstream response не
переопределяет request model. Policy engine и EPIC-03 не восстанавливают эти
значения по телу ответа.

## Предварительные требования

- URL назначения приводится к согласованному нормализованному виду.
- Модель и любые другие body-derived attributes принимаются из результата
  EPIC-06 и не извлекаются в EPIC-03.
- Фаза определяется точкой вызова policy engine, а не содержимым payload.
- Пользователь и группы извлекаются настраиваемым способом.
- Названия HTTP-заголовков с identity-данными задаются через конфигурацию.
- Должна быть предусмотрена возможность извлечения пользователя из Basic Authentication.
- Служебные identity-заголовки не должны передаваться upstream, если они предназначены только для Vigilant.
- Пароли, токены и исходные значения credentials не должны попадать в контекст политики, ошибки или логи.
- Результат сборки context не зависит от конкретной реализации
  `PolicyProvider`.

## Не входит в задачу

- Выбор подходящих политик.
- Разрешение `overrides` между политиками.
- Запуск детекторов.
- Выбор итоговой реакции.
- Хранение политик в файле или БД.
- Проверка существования пользователя или группы во внешнем каталоге.
- Разбор JSON, SSE, Realtime events или Batch JSONL.
- Извлечение payload fragments или любых attributes из protocol message.

## Открытые решения

- Какой URL участвует в сопоставлении: полный URL, origin, path или их комбинация.
- Правила нормализации URL.
- Точный provider-neutral input contract для attributes из EPIC-06.
- Допустимые источники пользователя: произвольный header, Basic Authentication или оба варианта.
- Формат списка групп и правила экранирования значений.
- Поведение при отсутствии пользователя, группы или обязательного
  protocol-derived attribute.
- Может ли один запрос одновременно сопоставляться с пользователем и несколькими группами.
- Модель доверия к identity-заголовкам и защита от их подмены клиентом.
- Точная структура конфигурации источников identity.
- Способ переноса контекста от запроса к streaming/non-streaming ответу.

## Предварительные критерии приёмки

- Policy engine получает единый нормализованный контекст без доступа к HTTP headers или сырому request body.
- EPIC-03 не получает raw body, protocol events или protocol-specific document
  model и не извлекает из них attributes.
- Одинаковые логические значения из поддерживаемых источников дают одинаковый контекст.
- Контекст ответа содержит URL, модель, пользователя и группы исходного запроса.
- Названия служебных identity-заголовков не зашиты в коде.
- Чувствительные authentication-данные не передаются upstream и не попадают в логи.
- Все открытые решения выше закрыты до перевода epic в `Ready for implementation`.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.25  ✓ общий результат понятен
  Acceptance:   0.45  ⚠ зависит от input contract и identity decisions
  Boundaries:   0.25  ✓ основные non-goals перечислены
  Alternatives: 0.4   ⚠ варианты identity extraction и handoff не выбраны
  Assumptions:  0.4   ⚠ input contract и trust boundary не подтверждены
  ──────────────────────────────
  Aggregate:    0.35  ⚠ above threshold (0.2 spec)

Keep as Draft. Push on: VIG-03-01 contract and trust-boundary decisions.
```
