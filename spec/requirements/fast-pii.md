# Fast PII

Постоянный контракт встроенного детектора для [MVP-02](../MVP_FUNCTIONS.md#mvp-02-fast-pii).
Runtime composition описана в [PII guide](../../docs/pii-detection.md), методики
проверок в [development guide](../../docs/development.md#pii-quality), фактические
ограничения evidence в [coverage](../../docs/requirements-coverage.md#pii-и-windowing).

## API

`io.vigilant.detectors.pii.PiiDetector` предоставляет синхронный вызов:

```kotlin
fun detect(
    payload: String,
    stopOnFirst: Boolean = true,
    enabledTypes: Set<PiiType> = ALL_PII_TYPES,
): List<PiiFinding>
```

`FastPiiDetector` имеет публичный конструктор без аргументов; recognizer-ы
internal. `ALL_PII_TYPES` и возвращаемые коллекции immutable, включая Java
caller perspective. Payload - точный decoded text одного logical fragment,
без HTTP, JSON/schema, policy, DI или coroutine types в публичном API.
Detector не выбирает protocol fields и не соединяет messages/fragments.
`enabledTypes` - внутренний invocation API; администратор MVP включает весь
`fast-pii`, отдельного policy selector по PII types нет.

Порядок вызова recognizer-ов точно совпадает с порядком строк [taxonomy](#taxonomy).
Отключённые типы не вызываются и не переставляют остальные. Каждый recognizer
просматривает кандидатов по возрастанию source offset, после invalid candidate
продолжает поиск. `stopOnFirst=true` возвращает первый валидный finding первого
совпавшего recognizer-а, а не самый левый finding среди разных типов.
Для каждого subset типов он равен первому элементу полного результата.
`false` возвращает все валидные findings.
Порядок не настраивается пользователем или отдельным request; его изменение
требует новой detector version и повторных regression/performance проверок.

## Preflight

Проверка interrupt при входе предшествует empty-set shortcut. Пустой
`enabledTypes` возвращает пустой результат без preflight даже для oversized
payload или unpaired surrogate. При непустом наборе полный preflight обязателен
до любого recognizer-а, в том числе при `stopOnFirst=true` и раннем совпадении.

Один линейный проход валидирует весь UTF-16, точно считает UTF-8 bytes и
предоставляет преобразование character boundaries в UTF-8 offsets без полной
`ByteArray`-копии. Пустая строка валидна. Прямой вызов принимает максимум
`1 048 576` bytes: ровно лимит допустим, первый байт сверх него даёт
`PiiDetectionException(PAYLOAD_TOO_LARGE)`. Любой unpaired high/low surrogate
даёт `INVALID_UNICODE`; эта ошибка имеет приоритет над размером независимо
от положения surrogate. Сообщения безопасны, без payload/preview. Unexpected
implementation failure не маскируется под один из двух input error codes.

Detector сам не chunk-ит, не обрезает и не пропускает хвост. Большие fragments
обрабатываются по [windowing contract](windowed-inspection.md#capability).
Request source limits и current product SLO остаются у
[MVP NFR](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md), не равны лимиту detector call.

## Findings

`PiiFinding` содержит только `type`, `startUtf8: Long`, `endUtf8: Long`,
`confidence: Double?`, `evidenceStrength`, `recognizerId`, `recognizerVersion`.
Диапазон `[startUtf8, endUtf8)` относится к UTF-8 исходного decoded payload
до локальной нормализации: `0 <= startUtf8 < endUtf8 <= payloadUtf8Size`;
обе границы совпадают с code-point boundaries. Это не UTF-16 indices и не
offsets encoded JSON source. В finding нет matched text, preview или value.

Модель проверяет context-free invariants: неотрицательный start, end больше
start, finite confidence в `[0, 1]` либо `null`, непустые/nonblank recognizer
ID и version. Payload-dependent bounds проверяет detector. Детерминированные
правила сейчас всегда возвращают `confidence=null`.

`VALIDATED` означает строгий parser/checksum, `CONTEXTUAL` - обязательный
ограниченный контекст, `FORMAT_ONLY` - характерный формат. Это основание
срабатывания, не вероятность и не ranking качества. Checksum-invalid fallback
допустим только для явно перечисленных ниже СНИЛС/ОМС surfaces; строгий путь
имеет приоритет, один span не получает одновременно оба evidence.

Полный прямой результат сортируется по canonical recognizer order, затем
`startUtf8`, `endUtf8`, `recognizerId`. Удаляются только точные дубликаты
`(type, startUtf8, endUtf8, recognizerId)`. Пересечения разных типов сохраняются,
включая совместный PAYMENT_CARD/RU_OMS. Оконный aggregate имеет отдельный
[порядок](windowed-inspection.md#fast-pii-adapter).

## Normalization

Payload не подвергается global Unicode normalization, case folding,
транслитерации или удалению whitespace. Допускается только нормализация внутри
конкретного правила: IDN domain, локальный ASCII uppercase IBAN, разрешённые
внутренние separators и locale-independent context matching. Finding всегда
покрывает непрерывный исходный span со всеми допустимыми внутренними separators;
внешние пробелы, context, brackets IP и phone extension в него не входят.

Если ниже не указан exception, цифры - ASCII `0..9`, пробел - `U+0020`,
дефис - `U+002D`. Numeric boundaries запрещают непосредственную соседнюю ASCII
цифру слева или справа. Нельзя вырезать подходящий substring из более длинного
невалидного candidate; candidate boundaries каждого типа уточнены ниже.

## Taxonomy

Фиксированный полный набор и текущие версии правил:

| Порядок | `PiiType` | `recognizerId` | `recognizerVersion` | Evidence |
|---:|---|---|---|---|
| 1 | `EMAIL_ADDRESS` | `fast.email_address` | `1.1.0` | FORMAT_ONLY |
| 2 | `PHONE_NUMBER` | `fast.phone_number.ru` | `1.1.0` | FORMAT_ONLY / CONTEXTUAL |
| 3 | `PAYMENT_CARD` | `fast.payment_card.luhn` | `1.0.0` | VALIDATED |
| 4 | `IP_ADDRESS` | `fast.ip_address` | `1.1.0` | VALIDATED |
| 5 | `IBAN` | `fast.iban` | `1.0.0+iban-registry.102` | VALIDATED |
| 6 | `RU_INN` | `fast.ru_inn` | `1.0.0` | VALIDATED |
| 7 | `RU_SNILS` | `fast.ru_snils` | `1.1.0` | VALIDATED / CONTEXTUAL |
| 8 | `RU_PASSPORT` | `fast.ru_passport` | `1.0.0` | CONTEXTUAL |
| 9 | `RU_OMS` | `fast.ru_oms` | `1.1.0` | VALIDATED / CONTEXTUAL |

Наблюдаемое изменение accepted/rejected surface, offsets, evidence или reference
data требует новой version. ID сохраняется для эволюции того же правила;
независимое правило имеет собственный ID. IBAN reference version входит в
recognizer version. Нельзя менять fixtures только ради улучшения метрики.

### EMAIL_ADDRESS

Local part - ASCII dot-atom длиной `1..64`: ASCII letters/digits и символы
``!#$%&'*+/=?^_`{|}~.-``. Leading/trailing dot и consecutive dots запрещены.
Domain проходит `IDN.toASCII(..., IDN.USE_STD3_ASCII_RULES)`, имеет минимум
две DNS labels по `1..63` ASCII letters/digits/hyphen без edge hyphens.
После такой нормализации весь email имеет максимум `254` ASCII characters.
Quoted local parts, comments, domain literals и Unicode local parts не
поддерживаются. Unicode/IDN domain сохраняется в исходном span.

При whitespace obfuscation допускаются только gaps по `1..3` `U+0020`
непосредственно до/после `@` и до/после domain dots. Каждая позиция независима;
необфусцированная форма имеет нулевые gaps. Пробелы внутри local atom, DNS
label, около других local symbols и gaps длиной `4+` не разрешены. После
удаления разрешённых gaps действуют те же strict validators и length limits.
Внутренние gaps входят в finding, соседние слова/prose whitespace не входят.

Scanner берёт maximal local run и domain candidate. Слева нельзя продолжать
local-part character run или отрезать suffix Unicode local part; справа
ASCII letter/digit/hyphen/dot не является границей domain. Spaced local symbol
не разрешает suffix match. IDN dots `U+3002`, `U+FF0E`, `U+FF61` принимаются
через те же STD3 rules; допустимая IDN punctuation проверяется самим IDN,
не generic punctuation stripping. Одиночная завершающая domain dot не
превращается в допустимый адрес. `word @ word`, arithmetic, spaced punctuation,
invalid local dots, single-label domains остаются hard negatives. General
zero-width/homoglyph обфускация и arbitrary whitespace collapse не поддержаны.

### PHONE_NUMBER

Формат без контекста: `+7` либо `8` и ровно десять national digits, всего
11 ASCII digits после локального удаления separators; evidence FORMAT_ONLY.
Между digits допустим один separator; одна balanced pair parentheses может
охватывать только три начальные national digits (area code). Separator
допустим также между prefix и `(` либо между `)` и следующими digits.
Repeated/edge separators, неправильные и вложенные скобки запрещены.

Полный набор одиночных separators: `U+0020`, `U+002D`, `U+00A0`, `U+2009`,
`U+2010`, `U+2011`, `U+2013`. Каждое вхождение занимает прежнюю separator
позицию, arbitrary Unicode whitespace не поддержан. Extension `доб.`, `ext.`,
`#` не входит в span и не валидируется. Operator/area-code lookup отсутствует.

Ровно десять national digits либо одиннадцать с начальной `7` без plus
разрешены только с whole-word `телефон`, `тел`, `мобильный`, `моб`, `phone`
или `contact` в пределах `32` Unicode code points с любой стороны;
evidence CONTEXTUAL. Whole word - полный maximal run Unicode letters/digits
и `_`, case-insensitive без зависимости от process locale; обрезок слова
на краю context window не считается словом. Контекст исключён из span.
Digit boundaries, maximal numeric run, parentheses и extension rules остаются.
Нельзя начинать внутри plus/digit run или продолжать число через `.`/`:`/`/`
и следующую цифру. Partial/distant keyword, timestamps, versions, order IDs,
длинные последовательности цифр и unsupported separators - hard negatives.

### PAYMENT_CARD

`13..19` ASCII digits compact либо с одиночным ASCII space/hyphen между digits,
без repeated/edge separators. Обязателен Luhn: справа check digit не удваивается,
затем каждая вторая цифра удваивается, при результате больше 9 вычитается 9;
сумма кратна 10. Всё число не преобразуется в integer. Одинаковая повторённая
цифра, включая нули, отклоняется. Numeric boundaries обязательны. Evidence
VALIDATED; checksum-invalid fallback, BIN lookup и платёжная система отсутствуют.

### IP_ADDRESS

IPv4 содержит ровно четыре decimal octets `0..255`, каждый `1..3` digits,
без leading zero кроме одиночного `0`. IPv6 допускает восемь hex units по
`1..4` hex digits, единственный `::`, заменяющий минимум одну unit, и strict
IPv4 tail вместо последних двух units. При compression сумма явно заданных
units меньше восьми. IPv4 tail внутри IPv6 не возвращается отдельно.
Private, loopback и link-local адреса являются findings. Brackets вокруг
IPv6 исключаются из span; zones (`%eth0`), DNS, CIDR interpretation и URL
parsing отсутствуют.

IPv4 boundary запрещает ASCII letter/digit, dot или colon слева/справа;
IPv6 boundary запрещает hex digit/colon, а справа также `%`. Разрешённые
terminal exceptions: одна завершающая dot/colon исключается из span, если
оставшийся адрес строгий и нет address continuation; colon не расширяет
существующий colon run. Валидный IPv4 перед decimal `:1..65535` возвращается
без port. Пятый IPv4 octet, лишняя IPv6 group, второе `::`, следующий hex/digit
token не разрешают усечение до валидного prefix. Неоднозначный unbracketed
IPv6 suffix не интерпретируется как host-and-port. Runtime restriction port
на конце payload отдельно отражён в [coverage](../../docs/requirements-coverage.md#pii-и-windowing).

### IBAN

Compact либо canonical groups по четыре characters с одним `U+0020`; последняя
group имеет `1..4` characters. Нормализация только локальный ASCII uppercase.
Начало - две ASCII letters страны и две check digits, далее ASCII alphanumeric.
Обязательны точная длина страны из pinned SWIFT IBAN Registry release 102
(June 2026) и mod-97: перенести первые четыре characters в конец, letters
заменить `A=10..Z=35`, потоково считать остаток, требовать `1`. Большой integer
не создаётся. Соседняя ASCII letter/digit запрещает boundary.
Все страны и lengths заданы version-controlled
[resource](../../src/main/resources/io/vigilant/detectors/pii/fast/iban-country-lengths.csv)
с provenance; runtime не загружает обновления и не делает bank lookup.

### RU_INN

Только compact **12 ASCII digits ИНН физического лица**, с numeric boundaries;
10-значный ИНН юридического лица не finding. Внутренние separators запрещены.
Для digits `d1..d12` обе контрольные цифры обязательны:

```text
d11 = ((7d1 + 2d2 + 4d3 + 10d4 + 3d5 + 5d6 + 9d7 + 4d8 + 6d9 + 8d10) % 11) % 10
d12 = ((3d1 + 7d2 + 2d3 + 4d4 + 10d5 + 3d6 + 5d7 + 9d8 + 4d9 + 6d10 + 8d11) % 11) % 10
```

Evidence VALIDATED; проверка существования/выдачи отсутствует.
Происхождение границы 12/10 digits:
[ФНС, определение ИНН](https://www.nalog.gov.ru/rn77/terms/7756931/).

### RU_SNILS

Ровно 11 ASCII digits compact либо groups `3,3,3,2` со следующими тройками
separators (это исчерпывающий список):

| Между groups | Допустимые separator triples |
|---|---|
| ASCII hyphen forms | `(-, -, -)`, `(-, -, U+0020)` |
| Dotted form | `(., ., U+0020)` |
| Consistent forms | `(s, s, s)`, где `s` ровно один из `U+0020`, `U+00A0`, `U+2009`, `U+2010`, `U+2011` |

Для checksum path первые девять digits больше `001001998`.
`S = 9d1 + 8d2 + 7d3 + 6d4 + 5d5 + 4d6 + 3d7 + 2d8 + d9`;
`K = S % 101`, при `K=100` ожидается `00`, иначе двухзначный `K`.
Checksum-valid candidate даёт VALIDATED независимо от context.

Checksum-invalid plausible surface допускается с whole-word marker `снилс`
в пределах `32` Unicode code points с любой стороны, CONTEXTUAL. Exact word
boundaries те же, что у телефона; weak/partial keyword не подходит. Numeric
boundaries обязательны; одинаковые повторённые digits не допускают fallback.
Context не входит в span. Runtime сохраняет порог первых девяти digits также
для fallback; предел согласованного contextual требования отмечен в
[coverage](../../docs/requirements-coverage.md#pii-и-windowing).
Standalone invalid checksum, distant context, unsupported/mixed separators
не дают finding. Invalid contextual и последующий valid candidate сохраняются
оба в source order; validated priority исключает двойной evidence одного span.
Нормативный источник checksum:
[алгоритм контрольного числа СНИЛС](https://www.consultant.ru/document/cons_doc_LAW_142584/1d9155a863a5949b14b95ecbb536aa84856a2a2e/).

### RU_PASSPORT

Series из четырёх и number из шести ASCII digits, ровно четыре layouts:
`DDDD DDDDDD`, `DD DD DDDDDD`, `DD-DD DDDDDD`, `DD DD № DDDDDD`.
Разделители ровно показанные `U+0020`/`U+002D`; digit boundaries обязательны.
Region/year plausibility и проверка выдачи отсутствуют.

Обязателен контекст в пределах `64` Unicode code points слева и справа от
candidate: maximal Cyrillic words из `А-Яа-яЁё`, lowercase `Locale.ROOT`.
Подходит слово с prefix `паспорт` либо одновременно отдельные слова `серия`
и `номер`. Одного `серия` или одного `номер` недостаточно. Контекст исключён
из finding; evidence CONTEXTUAL. Supplementary code point считается одним.

### RU_OMS

Ровно 16 ASCII digits compact либо четыре groups по четыре digits с одним
и тем же одиночным separator между всеми groups: `U+0020`, `U+002D`, `U+2010`,
`U+2011`, `U+00A0` или `U+2009`. Mixed/repeated separators и соседние digits
отклоняются. Старые non-16-digit forms не поддерживаются.

Mod10 для `d1..d16`: `d16` - check digit. Среди `d1..d15` позиции считаются
справа; digits нечётных позиций в порядке обхода справа налево образуют число,
которое умножается на 2. К нему слева приписывается число из digits чётных
позиций. Пусть `S` - сумма digits результата; ожидаемый check digit равен
`(10 - S % 10) % 10`. Вычисление не требует преобразования всего кандидата
в integer. Checksum-valid candidate имеет VALIDATED независимо от context.

Checksum-invalid plausible surface допускается только при whole-word `омс`
либо последовательности полных слов `полис обязательного медицинского страхования`
в пределах `48` Unicode code points на одной из сторон; правила whole-word
и case matching те же, что у телефона. Fallback CONTEXTUAL запрещает одну
повторяющуюся цифру; `полис` сам по себе, partial/distant context и standalone
invalid number остаются negatives. Context исключён из span, validated priority
исключает duplicate contextual finding; mixed contextual/valid sequence
сохраняет оба результата. External registry lookup отсутствует.
Нормативный источник checksum:
[алгоритм контрольного числа полиса ОМС](https://www.consultant.ru/document/cons_doc_LAW_204797/2f414afe2cdbf7daa370f6e58a2ad6337f728249/).

## Quality

Canonical synthetic corpora являются release gate: минимум `100` positive и
`100` hard-negative cases **для каждого из девяти типов**. Каждый поддерживаемый
format, boundary, Unicode offset class и metadata variant имеет oracle.
Positive gate требует `100%` exact type/span/evidence/recognizer metadata match
в canonical order; hard-negative gate - `100%` rejection. Canonical fixtures
не содержат production или external data. Отдельный synthetic mixed-text corpus
измеряет качество сочетаний и overlaps без нового numeric release threshold.

Exact matching требует одинаковые type/start/end; relaxed - тот же type и
непустое пересечение half-open spans. Для каждого case/type и каждого mode
используется one-to-one maximum-cardinality matching; deterministic tie-break
по offsets запрещает повторное использование gold/prediction. `TP` - число
пар, `FP=predictions-TP`, `FN=gold-TP`; precision/recall/F1 публикуются по type
и micro aggregate. Reports не смешивают exact и relaxed метрики.

External pinned RedMadRobot оценивает опубликованные labels без удаления
checksum-invalid values или незаметного изменения source-aligned denominator.
Он не определяет recognizer surface и не заменяет canonical gate. Separate
product-aligned view допускает только заранее заданные taxonomy/span
adjustments, с версиями и counts до scoring. Правила и воспроизведение:
[external methodology](../../docs/development.md#external-pii-benchmark).

Для qualification сохраняются source-aligned floors полного scored subset:

| Метрика | Условие |
|---|---:|
| Exact precision | `>= 0.75` |
| Exact recall | `>= 0.30` |
| Exact F1 | `>= 0.42` |
| Relaxed F1 | `>= 0.45` |
| IP exact recall | `>= 0.90` |
| IP exact precision | Не ниже сохранённого baseline |
| PHONE exact precision | `>= 0.90` |
| Frozen evaluation exact F1 | Строго выше сохранённого baseline |
| Frozen evaluation exact precision | `>= 0.75` |

Tuning diagnostics допускают выбор rules на synthetic fixtures; evaluation
не используется для выбора regex, keywords или separators. Full, tuning и
evaluation публикуются отдельно с pinned disjoint counts. Per-type и
per-evidence TP/FP/FN contribution не должен скрывать регрессии отдельных типов.
Парные JMH qualification runs на одинаковой среде требуют отсутствия устойчивой
median p95/p99 regression более `10%` для обязательных worst-case no-match и
full-scan scenarios. Превышение требует profiling и отдельного TDD fix, без
самостоятельного waiver. Метрики прошлого прогона не являются новым
[PERF-01 evidence](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#perf-01-guardrail-latency).

## Lifecycle и privacy

Один instance immutable и безопасен для concurrent calls, не сохраняет request
state. Все временные candidate/offset данные принадлежат вызову. Нет payload
caches, interning, ThreadLocal, собственных threads/executors/scopes. Runtime
использует только Kotlin/JDK, не выполняет I/O, DNS, network или blocking calls;
CPU work запускается на caller-owned bounded executor вне Netty event loop.
Scanner-ы bounded/linear, без catastrophic regex backtracking.
Literal regex strings не являются публичным контрактом: bounded regex или
ручной scanner обязаны сохранять те же surfaces, fixtures и performance rules.

Interrupt проверяется при входе, между recognizer-ами и candidate validations;
`CancellationException` не очищает interrupt flag и не возвращает partial
findings. Текущий bounded scan/`Matcher.find` может закончиться до следующего
checkpoint; deadline и future cancellation принадлежат orchestration.
Обязательные terminal tests и concurrent repeatability перечислены в
[verification matrix](../../docs/development.md#pii-contract-checks).

Внутри detector нет logs, metrics, tracing/audit или dependency на logging.
Payload, candidates, matched values, tokens, auth headers и reversible previews
не попадают в errors, test output или reports. Допустимы safe error/category,
synthetic case ID и aggregate counters. External diagnostic privacy floor и
suppression описаны в [methodology](../../docs/development.md#external-pii-benchmark).

## Boundaries

Детектор не распознаёт ФИО/части имён, свободные адреса, даты рождения,
произвольные счета вне IBAN, secrets/API keys, prompt injection/jailbreak,
ML/LLM semantic NER, OCR, general homoglyph/zero-width/transliteration
обфускацию. Нет dynamic plugin, worker isolation, detector selection config
или нового production detector-а. Полные product non-goals находятся в
[OUT](../OUT_OF_SCOPE_FUNCTIONS.md#mvp-specific-non-goals).
No finding означает только отсутствие совпадений в этом fixed contract.
Policy decisions, reactions, field selection и source reverse mapping принадлежат
соответствующим [owners](README.md#владение-подробными-контрактами).
