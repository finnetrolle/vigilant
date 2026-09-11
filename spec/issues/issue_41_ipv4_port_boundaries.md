# VIG-41: Распознавать IPv4 с портом перед последующим текстом

- **ID:** `VIG-41`
- **Тип:** Issue
- **Статус:** Blocked
- **Причина блокировки:** реализация проходит synthetic и HTTP acceptance, но нарушены owning qualification floors: IP exact precision ниже baseline и frozen evaluation exact F1 не улучшилась. Требуется решение владельца о совместимости port contract с external quality gate; автоматического waiver нет.
- **Приоритет:** P1
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** высокая
- **Архитектурный риск:** High - исправление меняет наблюдаемые findings и результаты MASK/BLOCK.

## Контекст

На `d2e272c` public `FastPiiDetector.detect` возвращает одну IP finding для
`192.0.2.1:443`, но ноль для `192.0.2.1:443 ` и `connect 192.0.2.1:443 now`.
Это синтетический documentation address. Причина подтверждена в
`IpAddressCandidateBoundaryResolver.findIpv4EndBeforeTerminalPort`:
`candidateEnd == payload.length` ограничивает port exception концом fragment.
Применимая MASK/BLOCK policy не получает пропущенную находку.

## Scope lock

1. **Наблюдаемый продуктовый результат:** IPv4 перед decimal port распознаётся в конце fragment и перед разрешённым последующим текстом; request/response policies реагируют на эту находку.
2. **Минимальное достаточное решение:** исправить локальное определение правой границы IPv4 candidate, сохранив strict address/port validation и исходные UTF-8 offsets.
3. **Обязательные свойства результата:** port `1..65535` исключён из finding; нельзя вырезать валидный prefix из invalid candidate; сохранить IPv6, terminal punctuation, тип/evidence и остальные detector contracts; обновить recognizer version и применимое window evidence по owner.
4. **Явные non-goals:** URL/DNS/CIDR parsing, IPv6 host-and-port, новые PII types, policies, реакции, settings, external identity, общий redesign recognizer или нагрузочного стенда.
5. **Более сложные альтернативы:** общий URL parser отклонён как расширение surface; нормализация/пересборка payload отклонена из-за exact offsets и lossless forwarding; исключение IP из policies противоречит фиксированному detector MVP.
6. **Условие пересмотра:** правило не может отделить port от continuation по существующему контракту либо требует изменения evidence-span bound; сначала уточнить owner, не расширять accepted surface автоматически.
7. **Подтверждение:** пользователь 2026-09-10 прямо поручил создать задачу на воспроизведённый IPv4/port дефект; граница исправления задана согласованным [IP contract](../requirements/fast-pii.md#ip_address) и [MVP-02](../MVP_FUNCTIONS.md#mvp-02-fast-pii), без нового product scope.

## Context sources

- `spec/requirements/fast-pii.md#ip_address`
- `spec/requirements/fast-pii.md#findings`
- `spec/requirements/fast-pii.md#quality`
- `spec/requirements/windowed-inspection.md#fast-pii-adapter`
- `docs/development.md#pii-contract-checks`
- `docs/agent-workflow.md#behavior-first-development-and-selective-tdd`

## Критерии готовности

- [ ] Regression RED через real-Armeria gateway: request с IPv4:port перед prose и выбранной BLOCK policy получает штатный policy BLOCK без upstream handoff; independent oracle - literal HTTP outcome и нулевой upstream counter. Сначала зафиксировать текущий ошибочный ALLOW.
- [ ] Public detector: ports `1`, `443`, `65535` в EOF, перед space, tab, LF и CRLF с последующим словом дают ровно один IP span без port. Expected UTF-8 offsets вычислены из independently written prefix/address, включая Unicode prefixes шириной 1/2/3/4 bytes.
- [ ] Negative detector corpus: port `0`, `65536`, signed, с ASCII letter/дополнительным colon; invalid octets, пятый octet, запрещённая левая граница не превращаются в находку валидного prefix. Пустой port не является port exception: одиночное terminal colon в EOF или перед whitespace остаётся разрешённой пунктуацией. Сохранены существующие IPv6 и punctuation cases. Уточнение владельца 2026-09-11: сохранить правило пунктуации, отклонять malformed continuations вроде `:+443`, `:-443`, `:abc`.
- [ ] Windowed public seam: адрес и port пересекают ownership boundary в трёх позициях (внутри адреса, перед colon, внутри port); literal oracle требует одну finding с global offsets, включая suffix prose и Unicode padding.
- [ ] Real-Armeria REQUEST и ordinary JSON/SSE RESPONSE: ALLOW сохраняет bytes, MASK заменяет только адрес и сохраняет port/prose, BLOCK возвращает действующий safe outcome; response не раскрывает upstream body. Expected bodies написаны независимо от production masker.
- [ ] Обновлены owning runtime documentation, recognizer version/evidence и coverage; частные improvements не объявлены закрытием всей PII qualification matrix.

## Проверки

Через `scripts/check-run`: focused detector/window/gateway regression cases,
затем `./gradlew piiQualityReport`, `./gradlew build` и применимые
[PII qualification checks](../../docs/development.md#pii-contract-checks).
Сохранить RED/GREEN commands и literal outcomes; полного build недостаточно
для отдельного performance или external quality claim.

### Наблюдения реализации

Согласованный source сохранён в Git commit `a594af3`. Regression RED
`f86e4e62d8df4571aba23c0a9700d252` через real gateway дал HTTP `200` и upstream
counter `1` вместо `403` и `0`. Focused GREEN
`1a0a8643323b408b9d2cb629319a841d` прошёл detekt и 346 tests: 190 direct port
cases, 12 window cases, 9 HTTP reaction cases и затронутые старые contracts.

Qualification run `7129e9a4e2854ed69e75127266c9b988` завершился exit `1`.
Canonical gate: 960 positive, 975 negative, 3 mixed, exact/rejection `100%`.
Все 18 paired JMH cases сопоставимы; median p95/p99 regression для
`NO_MATCH_FULL_SCAN` +1.51%/+1.73%, для `FULL_SCAN` +0.20%/+7.21%, ниже 10%.
External source-aligned IP precision `0.972413793 -> 0.903846154`, evaluation
exact F1 `0.450657895 -> 0.448445172`; PHONE precision `0.935483871` сохранена.
Evaluation cases не просматривались для подбора правил. Floors, gold labels и
denominator не изменены. Полное закрытие issue и qualification не заявлены.

## Решение владельца о закрытии

2026-09-11 владелец явно разрешил: «Сохранить IP-контракт; разрешаю закрыть
VIG-41 с явным qualification FAIL». Разрешение относится только к закрытию
этой задачи при невыполненных IP exact precision baseline и strict frozen
evaluation exact F1 gates. Пороги, source labels, denominator и scorer не
меняются; полная PII qualification не заявляется.

Tuning-only диагностика через production detector и canonical adapter/split/
matcher подтвердила 8 contract-valid port findings без exact match; у 6 нет
даже пересекающегося IP gold. При 155 gold во всём scored subset верхняя
граница precision с этими обязательными FP: `155 / 161 = 0.962733`, ниже
baseline `141 / 145 = 0.972414`. Это расхождение recognition/scoring contracts,
а не доказанная ошибка upstream gold. Evaluation cases не исследовались.

Final build `8df71d6fba0d431198fc82a58cd08789`: exit `0`, 1508 regular,
57 process и 55 work-item validator tests, без failures/errors/skips.
Standards и Spec подтвердили диагностику и отсутствие оснований сужать IP
surface без нового решения владельца. Exact source с данным разрешением
сохраняется в Git до удаления issue по completion protocol.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.1; Aggregate: 0.04. Риск High относится к behavioral impact,
а не к необходимости новой архитектуры.
