# VIG-41: Распознавать IPv4 с портом перед последующим текстом

- **ID:** `VIG-41`
- **Тип:** Issue
- **Статус:** Ready for implementation
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

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.1; Aggregate: 0.04. Риск High относится к behavioral impact,
а не к необходимости новой архитектуры.
