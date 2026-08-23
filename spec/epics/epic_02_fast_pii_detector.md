# Epic 02: Быстрый детерминированный PII-детектор

**ID:** `EPIC-02`  
**Тип:** Epic  
**Статус:** In progress  
**Приоритет:** High  
**Суммарная оценка:** 33-48 инженерных дней  
**Связанные требования:** `MVP-10`, `MVP-13`, `MVP-15`, `MVP-16`, `MVP-18`, `MVP-19`, `PERF-02`–`PERF-05`, `CONC-01`–`CONC-04`, `EXT-01`–`EXT-04`

## Карта декомпозиции

```text
EPIC-02 Fast PII detector
├── Public contract
│   ├── API and result invariants
│   ├── payload preflight and UTF-8 offsets
│   └── recognizer pipeline and cancellation
├── Recognizers
│   ├── EMAIL_ADDRESS
│   ├── PHONE_NUMBER
│   ├── PAYMENT_CARD
│   ├── IP_ADDRESS
│   ├── IBAN
│   ├── RU_INN
│   ├── RU_SNILS
│   ├── RU_PASSPORT
│   └── RU_OMS
└── Evidence
    ├── cross-recognizer semantics
    ├── quality corpora and report
    └── JMH performance baseline
```

Листья дерева реализуются отдельными issues. Нормативные правила форматов,
offsets, порядка и безопасности остаются в этом epic, а дочерние issues задают
небольшой observable slice и его тестовый seam.

## Дочерние issues

- [x] [VIG-02-01: Public API и invariants](../issues/epic_02/issue_02_01_public_contract.md) - `Done`
- [x] [VIG-02-02: Payload preflight и UTF-8 offsets](../issues/epic_02/issue_02_02_payload_preflight.md) - `Done`
- [ ] [VIG-02-03: Recognizer pipeline](../issues/epic_02/issue_02_03_recognizer_pipeline.md) - `Ready for implementation`
- [ ] [VIG-02-04: EMAIL_ADDRESS](../issues/epic_02/issue_02_04_email_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-05: PHONE_NUMBER](../issues/epic_02/issue_02_05_phone_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-06: PAYMENT_CARD](../issues/epic_02/issue_02_06_payment_card_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-07: IP_ADDRESS](../issues/epic_02/issue_02_07_ip_address_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-08: IBAN](../issues/epic_02/issue_02_08_iban_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-09: RU_INN](../issues/epic_02/issue_02_09_ru_inn_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-10: RU_SNILS](../issues/epic_02/issue_02_10_ru_snils_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-11: RU_PASSPORT](../issues/epic_02/issue_02_11_ru_passport_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-12: RU_OMS](../issues/epic_02/issue_02_12_ru_oms_recognizer.md) - `Ready for implementation`
- [ ] [VIG-02-13: Cross-recognizer semantics](../issues/epic_02/issue_02_13_cross_recognizer_semantics.md) - `Ready for implementation`
- [ ] [VIG-02-14: Quality corpora и report](../issues/epic_02/issue_02_14_quality_corpora.md) - `Ready for implementation`
- [ ] [VIG-02-15: JMH baseline](../issues/epic_02/issue_02_15_jmh_baseline.md) - `Ready for implementation`

## Контекст

Vigilant требуется первая реализация PII-детектора, которую можно разработать и проверить без обучения ML-модели. Детектор должен находить структурированные персональные данные в русском тексте детерминированными recognizer-правилами. Более сложная контекстная детекция ФИО, свободных адресов и других семантических сущностей будет реализована отдельным ML-детектором позднее.

Быстрый детектор не должен зависеть от будущего ML-детектора. Оба детектора должны возвращать совместимые findings, чтобы orchestration layer мог выполнять быстрый detector отдельно, каскадно или параллельно с другими реализациями.

## Цель

Реализовать встроенный быстрый PII-detector как отдельный Kotlin package текущего application module. Detector принимает текст и с минимальной задержкой сообщает о первом найденном структурированном PII span. По явному запросу тот же detector выполняет полный поиск и возвращает все найденные spans с типом, координатами и объяснимым основанием срабатывания.

Главный приоритет первой версии — latency до первого finding. Полнота списка findings в режиме по умолчанию не является целью.

## Размещение в приложении

Первая версия является доверенным built-in компонентом и выполняется in-process:

```text
Vigilant application module
        |
        v
detectors parent package
        |
        v
fast PII detector package
```

Detector компилируется вместе с приложением и не загружается динамически. Он не считается сторонним plugin в смысле `EXT-01` и не используется как граница изоляции недоверенного кода.

Требования:

- detector package не зависит от Armeria, Netty, gateway HTTP-моделей или application DI framework;
- gateway/application packages могут зависеть от detector API, но detector package не зависит от них;
- detector не выполняет I/O, сетевые вызовы или блокирующие операции;
- вызов detector из request path не выполняется на Netty event loop;
- application запускает detector на ограниченном CPU executor с bounded queue в соответствии с `CONC-03`;
- dynamic JAR loading и регистрация сторонних recognizer'ов в этой версии запрещены;
- gRPC/Protobuf worker и вынос detector out-of-process остаются возможной последующей реализацией того же логического контракта.

### Single-responsibility boundary

Эта задача только добавляет detector package и не подключает его к runtime request path.

- `BypassProxyService` и остальные gateway services не изменяются.
- Detector не регистрируется в Metro graph и не получает application lifecycle.
- Detector package не создаёт executor, background task или coroutine scope.
- HTTP body не агрегируется и OpenAI/Anthropic JSON не разбирается.
- Policy decision, запуск fast/ML cascade и реакция на findings не входят в package.
- Интеграция будет выполнена отдельной задачей после появления policy engine.

### Kotlin packages

- Публичный контракт и модели размещаются в `io.vigilant.detectors.pii`.
- Быстрая rule-based implementation размещается в `io.vigilant.detectors.pii.fast`.
- Future ML implementation должна использовать тот же публичный detector interface и размещаться в `io.vigilant.detectors.pii.ml`.
- Детали конкретных recognizer'ов остаются `internal` для package/application module.
- Новый Gradle subproject не создаётся; `settings.gradle.kts` и project dependency graph ради detector не изменяются.

### Публичный API

```kotlin
package io.vigilant.detectors.pii

import java.util.Collections
import java.util.EnumSet

enum class PiiType {
    EMAIL_ADDRESS,
    PHONE_NUMBER,
    PAYMENT_CARD,
    IP_ADDRESS,
    IBAN,
    RU_INN,
    RU_SNILS,
    RU_PASSPORT,
    RU_OMS,
}

val ALL_PII_TYPES: Set<PiiType> =
    Collections.unmodifiableSet(EnumSet.allOf(PiiType::class.java))

interface PiiDetector {
    fun detect(
        payload: String,
        stopOnFirst: Boolean = true,
        enabledTypes: Set<PiiType> = ALL_PII_TYPES,
    ): List<PiiFinding>
}
```

- `ALL_PII_TYPES` создаётся один раз, содержит все значения enum и не допускает изменения caller'ом.
- Единственная публичная реализация V1 — `io.vigilant.detectors.pii.fast.FastPiiDetector` с конструктором без аргументов.
- `FastPiiDetector` реализует `PiiDetector`; отдельные публичные методы для recognizer'ов не предоставляются.
- Возвращаемый список не допускает изменения caller'ом.
- API не содержит Armeria, coroutine, logging, policy или transport types.

### Зависимости

Production-код detector package использует только Kotlin standard library и Java API.

- Regex реализуются заранее скомпилированными `java.util.regex.Pattern` без unbounded nested quantifiers и других конструкций с риском catastrophic backtracking.
- Luhn, mod-97, ИНН, СНИЛС и ОМС validators реализуются внутри detector package небольшими pure functions.
- IDN domain validation использует `java.net.IDN`.
- Phone и IP validation реализуются строгими локальными parser'ами без DNS lookup.
- Country-specific IBAN lengths поставляются version-controlled application resource; resource version отражается в `recognizerVersion`.
- Сторонние runtime dependencies, включая `libphonenumber`, не добавляются.
- JMH и генераторы тестовых данных допускаются только в test/benchmark configuration и не попадают в production runtime classpath.
- Для benchmark source set используется Gradle plugin [`me.champeau.jmh` версии `0.7.3`](https://plugins.gradle.org/plugin/me.champeau.jmh) и JMH `1.37`; они не являются runtime dependencies приложения.

### Вход detector

`payload` — один логический текст, уже извлечённый application layer из транспортного запроса.

- Detector не принимает `HttpRequest`, raw HTTP body или JSON document model.
- Detector не знает схемы OpenAI, Anthropic или tool calls.
- Detector не декодирует JSON escapes и не выбирает поля `messages`, `content`, arguments или results.
- Если caller передал сериализованный JSON как обычную строку, detector рассматривает его как обычный текст; offsets относятся к этой переданной строке.
- Извлечение и объединение логических текстовых полей выполняет application layer и не входит в эту спецификацию.
- Locale первой версии не передаётся параметром: detector реализует зафиксированные русские и language-neutral recognizer'ы этой спецификации.

## Семантика поиска

Операция детекции использует параметры публичного `PiiDetector.detect` выше. API синхронный, потому что detector выполняет только CPU- и memory-bound работу и не содержит suspension points. Вызывающая сторона отвечает за выполнение вне Netty event loop и за отмену/timeout на уровне task/executor.

- Значение по умолчанию — `stopOnFirst=true`.
- При `stopOnFirst=false` detector выполняет полный поиск и возвращает все найденные findings.
- При `stopOnFirst=true` detector прекращает поиск после первого валидного finding и возвращает список из одного элемента.
- Если совпадений нет, операция в обоих режимах возвращает пустой список.
- Режим выбирает вызывающая сторона для каждого запроса; отдельные реализации detector для `ANY` и `ALL` не создаются.
- Параметр влияет только на полноту поиска и не меняет правила распознавания, валидации или evidence найденной сущности.
- По умолчанию `enabledTypes` содержит все поддерживаемые первой версией PII types.
- Recognizer отключённого типа не запускается.
- Пустой `enabledTypes` немедленно возвращает пустой список без preflight и без сканирования payload; поэтому размер и Unicode-корректность неиспользуемого payload в таком вызове не проверяются.
- `enabledTypes` фильтрует канонический порядок recognizer'ов, но не переупорядочивает его.
- Локальный Kotlin API принимает только значения закрытого `PiiType` enum; семантика неизвестных типов для будущего transport contract в эту задачу не входит.
- В режиме `stopOnFirst=true` «первым» считается первое валидное совпадение по фиксированному порядку выполнения recognizer’ов, а не finding с минимальным offset в тексте.
- Каждый recognizer возвращает своё первое валидное совпадение по возрастанию offset. После такого совпадения следующие recognizer’ы не запускаются.
- Порядок recognizer’ов является частью версионируемого поведения detector и не может зависеть от порядка завершения потоков или недетерминированного обхода коллекции.

### Размер payload

- Если `enabledTypes` не пуст, до запуска recognizer'ов detector выполняет один линейный preflight pass по всей Kotlin `String`.
- Preflight одновременно проверяет корректность UTF-16 surrogate pairs и вычисляет точный размер будущего UTF-8 representation без создания полной `ByteArray`-копии payload.
- `stopOnFirst=true` не прерывает preflight: early exit применяется только к recognizer pipeline после успешной валидации всего входа.
- Пустой payload допустим и немедленно возвращает пустой список.
- Максимальный размер одного payload — `1 048 576` байт (`1 MiB`).
- Payload в пределах лимита анализируется целиком.
- Превышение лимита возвращает типизированную ошибку `PAYLOAD_TOO_LARGE` до запуска recognizer'ов.
- Detector не обрезает и не разбивает payload на чанки.
- Chunking входов больше `1 MiB` выполняет caller; эта спецификация не задаёт его размер окна или overlap.

### Ошибки входа

Ожидаемые ошибки передаются типизированным исключением без изменения return type основной операции:

```kotlin
class PiiDetectionException(
    val code: PiiDetectionError,
) : RuntimeException()

enum class PiiDetectionError {
    PAYLOAD_TOO_LARGE,
    INVALID_UNICODE,
}
```

- Невалидная UTF-16 surrogate-последовательность в Kotlin `String` возвращает `INVALID_UNICODE`.
- Размер UTF-8 representation больше `1 MiB` возвращает `PAYLOAD_TOO_LARGE`.
- При непустом `enabledTypes` валидация Unicode и размера завершается до запуска recognizer'ов.
- Preflight проходит строку полностью; если одновременно обнаружены invalid Unicode и превышение размера, приоритет имеет `INVALID_UNICODE`, иначе возвращается `PAYLOAD_TOO_LARGE`.
- Exception message не содержит payload, candidate, matched text или preview.
- Пустой payload и пустой `enabledTypes` являются корректными запросами, а не ошибками.
- Непредвиденная programming/runtime error не преобразуется в один из перечисленных input error codes.

## Контракт результата

Detector не возвращает найденное PII отдельной строкой. Finding ссылается на исходный payload полуинтервалом UTF-8 byte offsets:

```kotlin
data class PiiFinding(
    val type: PiiType,
    val startUtf8: Long,
    val endUtf8: Long,
    val confidence: Double?,
    val evidenceStrength: EvidenceStrength,
    val recognizerId: String,
    val recognizerVersion: String,
)
```

```kotlin
enum class EvidenceStrength {
    VALIDATED,
    CONTEXTUAL,
    FORMAT_ONLY,
}
```

Требования:

- `startUtf8` включительно, `endUtf8` исключительно: `[startUtf8, endUtf8)`.
- Offsets отсчитываются в байтах UTF-8 исходного payload, полученного detector, до какой-либо нормализации.
- Границы обязаны указывать на границы валидных UTF-8 code points.
- `0 <= startUtf8 < endUtf8 <= payloadUtf8Size`.
- `type` принадлежит закрытому версионируемому enum поддерживаемой таксономии.
- `recognizerId` стабильно идентифицирует реализацию правила, а `recognizerVersion` — версию его логики и данных.
- Finding не содержит `matchedText`, preview, нормализованное значение или другую копию чувствительных данных.
- Caller при необходимости извлекает span из уже имеющегося исходного payload.
- Для первой детерминированной реализации `confidence` всегда равен `null`: detector не выдаёт вручную назначенное число за измеренную вероятность.
- `evidenceStrength=VALIDATED` означает, что кандидат прошёл проверку формата и checksum либо строгого parser'а.
- `evidenceStrength=CONTEXTUAL` означает, что кандидат прошёл проверку формата и обязательного текстового контекста.
- `evidenceStrength=FORMAT_ONLY` означает, что finding основан на достаточно характерном формате, для которого нет checksum и не требуется контекст.
- `evidenceStrength` описывает основание срабатывания, но не является вероятностью и не задаёт универсальный порядок качества между разными PII types.
- Если `confidence` не равен `null` в будущей реализации, значение обязано находиться в диапазоне `[0.0, 1.0]`; V1 такое значение не создаёт.
- `PiiFinding` проверяет при создании только context-free invariants:
  `0 <= startUtf8 < endUtf8`, диапазон ненулевого `confidence` и непустые
  `recognizerId` / `recognizerVersion`. Invalid state отклоняется без включения
  чувствительных данных в сообщение.
- Payload-dependent invariants (`endUtf8 <= payloadUtf8Size` и принадлежность
  offsets границам UTF-8 code points) проверяет detector implementation после
  preflight и до возврата результата. Public model не принимает и не хранит
  payload только ради повторной проверки этих условий.

### Идентификаторы recognizer'ов

| PII type | `recognizerId` | Начальная `recognizerVersion` |
|---|---|---|
| `EMAIL_ADDRESS` | `fast.email_address` | `1.0.0` |
| `PHONE_NUMBER` | `fast.phone_number.ru` | `1.0.0` |
| `PAYMENT_CARD` | `fast.payment_card.luhn` | `1.0.0` |
| `IP_ADDRESS` | `fast.ip_address` | `1.0.0` |
| `IBAN` | `fast.iban` | `1.0.0+iban-registry.102` |
| `RU_INN` | `fast.ru_inn` | `1.0.0` |
| `RU_SNILS` | `fast.ru_snils` | `1.0.0` |
| `RU_PASSPORT` | `fast.ru_passport` | `1.0.0` |
| `RU_OMS` | `fast.ru_oms` | `1.0.0` |

`recognizerId` не меняется при совместимой эволюции того же правила. `recognizerVersion` изменяется при любом observable изменении accepted/rejected inputs, offsets, evidence strength или reference data. IBAN metadata фиксируется по [официальному SWIFT IBAN Registry, release 102, June 2026](https://www.swift.com/standards/data-standards/iban-international-bank-account-number).

## Нормализация

Detector не выполняет глобальную Unicode-нормализацию, case folding или транслитерацию payload.

- Recognizer выполняет регистронезависимое сопоставление только для тех частей собственного правила, где регистр незначим.
- Допустимые пробелы, дефисы, скобки и другие разделители удаляются только из локального кандидата перед parser/checksum-проверкой.
- Finding всегда ссылается на непрерывный span исходного payload, включающий допустимые разделители внутри найденного значения.
- Изменение длины локальной нормализованной формы не требует общей offset map: итоговые offsets берутся из границ кандидата в исходном payload.
- Unicode homoglyphs, zero-width символы, транслитерация и иная преднамеренная обфускация не распознаются первой версией.
- Невалидная UTF-16 surrogate-последовательность не является допустимым входом и отклоняется detector preflight с `INVALID_UNICODE`.

## Поддерживаемая таксономия

Первая версия обязана поддерживать:

- `EMAIL_ADDRESS`;
- `PHONE_NUMBER`;
- `PAYMENT_CARD`;
- `IBAN`;
- `RU_INN`;
- `RU_SNILS`;
- `RU_PASSPORT`;
- `RU_OMS`;
- `IP_ADDRESS`.

### Порядок выполнения recognizer'ов

В первой версии recognizer'ы выполняются последовательно в фиксированном порядке:

1. `EMAIL_ADDRESS`;
2. `PHONE_NUMBER`;
3. `PAYMENT_CARD`;
4. `IP_ADDRESS`;
5. `IBAN`;
6. `RU_INN`;
7. `RU_SNILS`;
8. `RU_PASSPORT`;
9. `RU_OMS`.

Порядок не настраивается пользователем или для отдельного запроса. Его изменение считается изменением версии detector и требует повторного regression/performance тестирования. После накопления репрезентативного production-профиля порядок разрешено изменить в следующей версии с учётом стоимости recognizer'а и вероятности найти PII до перехода к следующему recognizer'у.

### Граница `RU_INN`

`RU_INN` в PII-detector означает только 12-значный ИНН физического лица.

- Кандидат состоит ровно из 12 ASCII-цифр без внутренних разделителей.
- Слева и справа от кандидата не должно быть ASCII-цифры.
- Обе контрольные цифры должны быть корректны по алгоритму ИНН физического лица.
- Валидный finding имеет `evidenceStrength=VALIDATED`.
- 10-значный ИНН юридического лица не является finding типа `RU_INN` и игнорируется первой версией.

## Критерии recognizer'ов

Каждый recognizer обязан сначала выделить непрерывный span исходного payload, затем построить локальную нормализованную форму и применить перечисленные проверки. Кандидат, не прошедший обязательную проверку, не является finding и не останавливает поиск в режиме `stopOnFirst=true`.

| PII type | Критерий finding | Evidence strength |
|---|---|---|
| `EMAIL_ADDRESS` | ASCII local-part и валидный DNS/IDN domain. Кириллический domain проверяется после `IDN.toASCII`; Unicode local-part не поддерживается | `FORMAT_ONLY` |
| `PHONE_NUMBER` | Российский номер с префиксом `+7` или `8`; допускаются пробелы, круглые скобки и дефисы; после локальной нормализации — ровно 11 цифр | `FORMAT_ONLY` |
| `PAYMENT_CARD` | От 13 до 19 цифр после удаления допустимых пробелов/дефисов; обязательна проверка Luhn | `VALIDATED` |
| `IP_ADDRESS` | Строгий IPv4 или IPv6 parser без DNS lookup; private, loopback и link-local адреса также являются findings | `VALIDATED` |
| `IBAN` | Две ASCII-буквы страны, две контрольные цифры и основной буквенно-цифровой блок; допускаются пробелы; обязательны country-specific length и mod-97 | `VALIDATED` |
| `RU_INN` | Правила раздела «Граница `RU_INN`» | `VALIDATED` |
| `RU_SNILS` | 11 цифр в компактной форме или `XXX-XXX-XXX-XX` / `XXX-XXX-XXX XX`; номер больше `001-001-998`; обязательна контрольная сумма по официальному алгоритму | `VALIDATED` |
| `RU_PASSPORT` | Серия из 4 и номер из 6 цифр с поддерживаемым разделителем; обязательно контекстное правило из отдельного раздела ниже | `CONTEXTUAL` |
| `RU_OMS` | 16 цифр после удаления допустимых пробелов; обязательна проверка Mod10 единого номера полиса ОМС | `VALIDATED` |

### Точная поддерживаемая поверхность форматов

Если явно не указано обратное, «пробел» означает только `U+0020`, «дефис» — только `U+002D`, а цифра — ASCII `0`–`9`. Неразрывные пробелы, typographic dashes и Unicode digits не нормализуются и не считаются допустимыми разделителями V1.

#### `EMAIL_ADDRESS`

- Local part имеет длину 1–64 ASCII characters и использует dot-atom subset: letters, digits и ``!#$%&'*+/=?^_`{|}~.-``.
- Точка не может быть первой, последней или повторяться подряд.
- Unicode domain преобразуется через `IDN.toASCII` с STD3 rules; ошибка преобразования отклоняет candidate.
- ASCII domain содержит не менее двух labels; каждый label имеет длину 1–63, содержит letters/digits/hyphen и не начинается/не заканчивается hyphen.
- Общая длина normalized email не превышает 254 ASCII characters.
- Quoted local parts, comments, domain literals и Unicode local parts не поддерживаются.

#### `PHONE_NUMBER`

- Candidate начинается с `+7` либо `8` и содержит ещё ровно 10 digits.
- Между digits допускаются `U+0020`, `U+002D` и одна сбалансированная пара `(` `)` вокруг ровно трёх digits area code.
- Separators не допускаются в начале/конце normalized national number и не повторяются подряд.
- Extension (`доб.`, `ext`, `#`) не входит в finding и не валидируется.
- Актуальность operator/area code по внешнему справочнику не проверяется.

#### `PAYMENT_CARD`

- После удаления одиночных `U+0020`/`U+002D` между digits остаётся 13–19 digits.
- Separators не могут находиться на краях или повторяться подряд.
- Candidate должен пройти Luhn и не может состоять из одной повторяющейся digits sequence, включая все нули.

#### `IP_ADDRESS`

- IPv4 содержит четыре decimal octets `0`–`255`; leading zero допускается только в octet `0`.
- IPv6 поддерживает full и `::` compressed forms, а также embedded IPv4 tail.
- Brackets вокруг IPv6 не входят в finding.
- Zone identifiers (`%eth0`) и hostname resolution не поддерживаются.

#### `IBAN`

- Input принимается compact либо в canonical groups по четыре characters слева направо с последней group длиной 1–4; между groups допускается ровно один `U+0020`. Normalization удаляет spaces и переводит ASCII letters в uppercase.
- Compact form начинается с двух ASCII letters и двух digits, затем содержит только ASCII alphanumeric characters.
- Country code и полная длина обязаны присутствовать в pinned SWIFT registry release 102.
- Candidate обязан пройти mod-97 (`remainder == 1`).

#### `RU_INN`, `RU_SNILS`, `RU_PASSPORT`, `RU_OMS`

- Для `RU_INN` действуют правила отдельного раздела без внутренних separators.
- Для `RU_SNILS` поддерживаются только compact `XXXXXXXXXXX`, `XXX-XXX-XXX-XX` и `XXX-XXX-XXX XX`.
- Для `RU_PASSPORT` поддерживаются только четыре формы из раздела «Контекст `RU_PASSPORT`»; region/year plausibility отдельно не проверяется.
- Для `RU_OMS` поддерживается compact 16-digit form либо четыре группы по четыре digits, разделённые одиночными `U+0020`; hyphens не поддерживаются.

### Контрольные алгоритмы

Ниже `d1` означает крайнюю левую цифру нормализованного кандидата. Арифметика выполняется над числовыми значениями ASCII-цифр, без преобразования всего кандидата в целочисленный тип.

- `PAYMENT_CARD`: применяется стандартный Luhn. Начиная с крайней правой цифры, суммируются цифры; каждая вторая цифра слева от неё удваивается, и из результата вычитается `9`, если он больше `9`. Кандидат валиден, только если итоговая сумма кратна `10`.
- `IBAN`: первые четыре ASCII characters переносятся в конец, letters заменяются числами `A=10` … `Z=35`, затем остаток вычисляется потоково, без создания большого integer. Кандидат валиден, только если остаток по модулю `97` равен `1`.
- `RU_INN`: для цифр `d1…d12` ожидаемая `d11` равна `((7*d1 + 2*d2 + 4*d3 + 10*d4 + 3*d5 + 5*d6 + 9*d7 + 4*d8 + 6*d9 + 8*d10) mod 11) mod 10`; ожидаемая `d12` равна `((3*d1 + 7*d2 + 2*d3 + 4*d4 + 10*d5 + 3*d6 + 5*d7 + 9*d8 + 4*d9 + 6*d10 + 8*d11) mod 11) mod 10`. Обе цифры должны совпасть.
- `RU_SNILS`: для первых девяти цифр вычисляется `S = 9*d1 + 8*d2 + … + 1*d9`, затем `K = S mod 101`; значение `K=100` заменяется на `00`, остальные значения сравниваются с последними двумя цифрами как двухразрядное число. Проверка применяется только когда первые девять цифр численно больше `001001998`.
- `RU_OMS`: `d16` является контрольной цифрой. Для `d1…d15` цифры в нечётных позициях при счёте справа записываются по порядку как число и умножаются на `2`; цифры в чётных позициях при счёте справа приписываются слева к результату. Складываются все цифры полученной записи. Контрольная цифра равна разности между этой суммой и ближайшим большим либо равным числом, кратным `10`.

Literal regex strings не являются частью публичного контракта. Реализация может использовать bounded regex или ручной scanner, если полностью соблюдает описанную поверхность, corpus fixtures и performance methodology.

Нормативные источники для контрольных алгоритмов:

- [ФНС: ИНН физического лица состоит из 12 цифр, юридического — из 10](https://www.nalog.gov.ru/rn77/terms/7756931/);
- [Алгоритм формирования контрольного числа СНИЛС](https://www.consultant.ru/document/cons_doc_LAW_142584/1d9155a863a5949b14b95ecbb536aa84856a2a2e/);
- [Алгоритм расчёта контрольного числа единого номера полиса ОМС](https://www.consultant.ru/document/cons_doc_LAW_204797/2f414afe2cdbf7daa370f6e58a2ad6337f728249/).

### Контекст `RU_PASSPORT`

Поддерживаемые формы значения:

- `4503 123456`;
- `45 03 123456`;
- `45-03 123456`;
- `45 03 № 123456`.

Для каждого кандидата recognizer рассматривает не более 64 Unicode code points слева и 64 Unicode code points справа, не выходя за границы payload.

Для этого правила словом считается максимальная непустая последовательность characters `А`–`Я`, `а`–`я`, `Ё`, `ё`; сравнение выполняется после lowercase с `Locale.ROOT`. Кандидат является finding, если в окне выполняется хотя бы одно условие:

1. присутствует слово, начинающееся с `паспорт`;
2. одновременно присутствуют отдельные слова, точно равные `серия` и `номер`.

Одного слова `серия` или одного слова `номер` недостаточно. Контекстные слова не включаются в span finding.

### Общие границы кандидатов

- Кандидат не может начинаться или заканчиваться внутри более длинной последовательности символов, допустимых для данного идентификатора.
- Для `PHONE_NUMBER`, `PAYMENT_CARD`, `RU_INN`, `RU_SNILS`, `RU_PASSPORT` и `RU_OMS` непосредственно слева и справа от полного candidate span не может находиться ASCII-цифра.
- Для `IBAN` непосредственно слева и справа не может находиться ASCII letter или digit.
- Для `EMAIL_ADDRESS` слева не может находиться character, допустимый в local part, а справа — ASCII letter, digit, hyphen или dot.
- Для IPv4 непосредственно слева и справа не может находиться ASCII letter, digit, dot или colon; это исключает отдельный finding для IPv4-tail внутри IPv6. Для IPv6 непосредственно слева и справа не может находиться ASCII hex digit или colon, а справа дополнительно не допускается `%`.
- Разделитель является частью span только когда находится между двумя частями одного кандидата.
- Пробелы и пунктуация до и после значения не входят в finding.
- После невалидного кандидата recognizer продолжает поиск следующего кандидата того же типа.

## Пересечения, дубликаты и порядок результата

В режиме `stopOnFirst=false`:

- findings разных типов сохраняются, даже если их spans совпадают или пересекаются;
- detector не пытается выбрать «правильный» тип между несколькими валидными recognizer'ами;
- удаляется только точный дубликат с одинаковыми `type`, `startUtf8`, `endUtf8` и `recognizerId`;
- результат упорядочен сначала по каноническому порядку recognizer'ов, затем по возрастанию `startUtf8`, `endUtf8` и `recognizerId`;
- при неизменных payload, request parameters и detector version порядок результата детерминирован;
- если findings существуют, результат `detect(payload, stopOnFirst=true, enabledTypes)` совпадает с первым элементом `detect(payload, stopOnFirst=false, enabledTypes)`.

Разрешение неоднозначности типа не входит в первую версию detector.

## Производительность

### Thread-safety и cancellation

- Detector immutable после создания и безопасен для одновременных вызовов из нескольких потоков.
- Precompiled patterns и metadata доступны только для чтения.
- Detector не хранит payload, candidates или findings после завершения вызова и не использует глобальный result cache.
- Detector package не создаёт threads, executor или coroutine scope.
- Detector проверяет `Thread.currentThread().isInterrupted` при входе, между recognizer'ами и между последовательными candidate validations.
- При обнаружении interruption detector бросает стандартный `CancellationException` и не возвращает частичный список findings.
- Cancellation не преобразуется в `PiiDetectionException` и не очищает interrupt status.
- Один уже выполняющийся bounded `Matcher.find()` может завершиться до следующей interrupt checkpoint; bounded regex patterns, payload limit и latency tests ограничивают эту задержку.
- Application layer владеет bounded CPU executor, deadline и отменой submitted task.

### Методика benchmark

- Используется JMH в режиме `SampleTime` с публикацией p50, p95 и p99.
- Отдельно измеряются ASCII, русский и mixed-Unicode payload datasets.
- Worst-case no-match corpus не содержит валидного PII, но содержит похожие невалидные кандидаты и неверные checksums.
- Обязательные размеры payload: `1 KiB`, `64 KiB` и `1 MiB`.
- Для `stopOnFirst=true` отдельно измеряются ранний `EMAIL_ADDRESS` и findings каждого последующего типа при включённых предыдущих recognizer'ах.
- Для `stopOnFirst=false` отдельно измеряется полный no-match scan и payload с несколькими findings каждого типа.
- Измеряется только синхронный вызов `detect`; HTTP parsing, DI, executor queue и orchestration не входят в результат.
- До измерения выполняется достаточный warmup до стабилизации JIT/GC-профиля.
- Вместе с результатом фиксируются CPU model, число выделенных cores, RAM, OS, JVM build/flags, warmup, число forks и measurement iterations.
- Benchmark output сохраняется как build artifact.
- Первая версия не задаёт числовой release threshold для latency; результаты формируют baseline для последующей оптимизации и regression budgets.

## Проверка корректности

### Corpus gate

Для каждого поддерживаемого PII type создаётся фиксированный version-controlled corpus:

- не менее 100 валидных положительных примеров во всех заявленных форматах;
- не менее 100 hard negatives, похожих на валидное значение;
- ожидаемые `type`, `startUtf8`, `endUtf8`, `evidenceStrength` и порядок findings задаются явно;
- положительный corpus должен проходить со 100% exact type/span match;
- hard-negative corpus должен проходить со 100% rejection;
- изменение corpus или recognizer logic требует изменения test fixtures в том же change set с объяснением новой семантики.

Corpus files хранятся в UTF-8 TSV без test-framework-specific serialization. Первая строка в точности равна `# pii-corpus-v1`. Каждая следующая строка содержит `caseId`, comma-separated `enabledTypes` либо `*`, Base64 от точных UTF-8 bytes payload и ожидаемые findings. Findings кодируются в каноническом порядке как разделённые `;` записи `type,startUtf8,endUtf8,evidenceStrength,recognizerId,recognizerVersion`; пустая последняя колонка означает отсутствие findings. Все corpus cases выполняются с `stopOnFirst=false`, а соответствие stop-on-first проверяется общей property ниже.

### Property-based проверки

Для `PAYMENT_CARD`, `IBAN`, `RU_INN`, `RU_SNILS` и `RU_OMS` тесты обязаны:

- детерминированно генерировать валидные значения с фиксированным seed;
- проверять compact и все поддерживаемые formatted representations;
- мутировать каждую значимую цифру/позицию и проверять rejection, когда мутация нарушает checksum;
- проверять кандидаты на границах payload и рядом с более длинными буквенно-цифровыми последовательностями;
- сохранять найденный counterexample как постоянный regression fixture.

Генераторы реализуются обычным test-only Kotlin-кодом поверх `kotlin.random.Random(seed)`; обязательной сторонней property-testing library нет. Seed и число итераций фиксируются в имени/выводе теста, чтобы любой failure воспроизводился локально.

### Realistic report

Отдельный mixed-text corpus используется для публикации exact и relaxed span precision, recall и F1 по каждому типу и в целом. Exact match требует одинаковых `type`, `startUtf8` и `endUtf8`; relaxed match требует одинакового `type` и непустого пересечения spans. Для relaxed metric каждый expected и actual finding участвует не более чем в одной паре; выбирается maximum-cardinality matching с детерминированным tie-break по offsets. В первой версии эти агрегатные метрики публикуются, но не являются release threshold: обязательный gate задаётся точным контрактом поддерживаемых форматов выше.

### Общие regression tests

- Пустой payload и пустой `enabledTypes`.
- Пустой `enabledTypes` возвращает empty result без preflight также для oversized payload и payload с unpaired surrogate.
- `stopOnFirst=true` соответствует первому finding полного результата.
- Детерминированный порядок при повторных запусках.
- Корректные UTF-8 byte offsets на ASCII, кириллице, emoji и supplementary Unicode code points.
- `INVALID_UNICODE` для unpaired surrogates.
- `PAYLOAD_TOO_LARGE` на первом байте сверх лимита и успех ровно на лимите.
- Пересекающиеся findings разных типов и удаление точных дубликатов.
- Read-only поведение `ALL_PII_TYPES` и возвращаемого списка для Kotlin- и Java-callers.
- Параллельные вызовы одного `FastPiiDetector` и interruption до/во время candidate scan.
- Отсутствие payload/PII в exception messages.
- Adversarial inputs для проверки отсутствия catastrophic regex backtracking.

## Безопасность данных и observability

- Detector package не зависит от SLF4J и ничего не логирует.
- Detector не создаёт metrics, traces или audit events; это ответственность будущего caller/policy engine.
- Payload, candidates, normalized values и findings не сохраняются после возврата из `detect`.
- Не используется cache, interning или static/thread-local storage, содержащий данные запроса.
- Exception messages и test failure helpers не должны автоматически включать production payload.
- Production corpus не собирается этой реализацией; repository fixtures используют только синтетические значения.
- Benchmark output содержит только имя сценария, размеры и timing, но не печатает payload или findings.

## Выбранные и отклонённые альтернативы

| Вариант | Решение | Причина |
|---|---|---|
| Built-in Kotlin package | Выбран | Минимальный implementation scope и отсутствие transport/worker overhead до появления policy engine |
| Отдельный Gradle module | Отклонён для V1 | Package boundary достаточно; дополнительный project graph пока не даёт практической изоляции |
| Изолированный gRPC worker | Отложен | Требует plugin protocol и orchestration, которых ещё нет |
| Presidio или ML/LLM detector | Отложен до V2 | Нужны модель, dataset и отдельный quality/latency lifecycle |
| Сторонние runtime validators | Отклонены | Узкая таксономия реализуется небольшими deterministic Kotlin/JDK functions |
| Глобальная Unicode normalization | Отклонена | Усложняет offsets и распознаёт незафиксированную поверхность inputs |
| Raw HTTP/JSON input | Отклонён | Нарушает SRP и связывает detector с gateway schemas |
| Возврат matched PII text | Отклонён | Дублирует чувствительное значение и не нужен при наличии точных offsets |
| Ранний поиск самого левого span среди всех типов | Отклонён | Требует работы всех recognizer'ов и противоречит latency-first default |

## План изменений по файлам

### Создать

- `src/main/kotlin/io/vigilant/detectors/pii/PiiDetector.kt` — interface, `PiiType` и immutable `ALL_PII_TYPES`.
- `src/main/kotlin/io/vigilant/detectors/pii/PiiFinding.kt` — result model и `EvidenceStrength`.
- `src/main/kotlin/io/vigilant/detectors/pii/PiiDetectionException.kt` — typed input errors.
- `src/main/kotlin/io/vigilant/detectors/pii/fast/FastPiiDetector.kt` — orchestration, preflight, stop-on-first и result ordering.
- `src/main/kotlin/io/vigilant/detectors/pii/fast/PiiRecognizer.kt` — internal recognizer contract.
- `src/main/kotlin/io/vigilant/detectors/pii/fast/recognizers/` — девять internal recognizer implementations и pure validators.
- `src/main/resources/io/vigilant/detectors/pii/fast/iban-country-lengths.csv` — pinned SWIFT registry release 102 country-length mapping с source/version header.
- `src/test/kotlin/io/vigilant/detectors/pii/` — public contract, preflight, ordering, Unicode offset и error tests.
- `src/test/kotlin/io/vigilant/detectors/pii/fast/` — recognizer, checksum, corpus и property-based tests.
- `src/test/resources/io/vigilant/detectors/pii/` — version-controlled positive, hard-negative и realistic synthetic corpora.
- `src/jmh/java/io/vigilant/detectors/pii/fast/FastPiiDetectorBenchmark.java` — обязательные benchmark scenarios.

### Изменить

- `build.gradle.kts` — добавить benchmark-only JMH plugin/configuration; production dependency list detector не расширяет.

### Не изменять

- `settings.gradle.kts`;
- `AppConfig.kt`, `AppComponent.kt`, `Main.kt`;
- `BypassProxyService.kt` и HTTP request path;
- существующие gateway tests ради вызова detector.

## Порядок реализации

1. Добавить public models/interface и contract tests.
2. Реализовать Unicode/UTF-8 preflight и input errors.
3. Реализовать internal recognizer contract и канонический sequential orchestration.
4. Реализовать recognizer'ы в зафиксированном порядке с unit/property tests.
5. Добавить overlap/dedup/order и stop-on-first regression tests.
6. Добавить pinned IBAN registry resource и provenance test.
7. Добавить synthetic corpora и выполнить correctness gate.
8. Добавить JMH source set, выполнить обязательные scenarios и сохранить baseline report.
9. Проверить production runtime classpath и отсутствие gateway/DI/logging integration.

## Критерии готовности

1. Package `io.vigilant.detectors.pii` предоставляет согласованный public API, а `FastPiiDetector` реализует его без gateway dependencies.
2. Все девять PII types распознают заявленные форматы и возвращают exact UTF-8 byte spans с правильными metadata.
3. `stopOnFirst=true` по умолчанию возвращает первое валидное finding по каноническому recognizer order; `false` возвращает полный детерминированный список.
4. `enabledTypes` отключает recognizer'ы без изменения порядка оставшихся.
5. При непустом `enabledTypes` input preflight корректно обрабатывает empty payload, invalid Unicode, ровно `1 MiB` и превышение лимита без копирования payload в exception; при пустом `enabledTypes` preflight не запускается.
6. Corpus gate, checksum property tests, Unicode offsets, overlap/dedup и adversarial regex tests проходят полностью.
7. JMH benchmark выполняет все обязательные datasets/sizes/modes и сохраняет baseline с описанием среды; числовой latency gate для V1 отсутствует.
8. Detector immutable, concurrent-safe, cooperative-cancellable и не хранит/логирует PII.
9. Production detector code не добавляет сторонних runtime dependencies.
10. Gateway, DI, policy engine и HTTP path не изменены.
11. Полный `./gradlew test` завершается успешно.

## Не входит в первую версию

- ФИО и отдельные компоненты имени.
- Свободные почтовые адреса.
- Дата рождения.
- Произвольные номера банковских счетов, не распознанные как IBAN.
- PII, распознавание которых преимущественно зависит от семантического контекста.
- Обучение, fine-tuning или inference ML/LLM-модели.
- Реакция на findings: блокировка, маскирование, удаление, эскалация или изменение исходного текста.
- Оркестрация каскада быстрого и ML-детектора.
- Фоновый или отложенный полный анализ запросов после возврата первого finding.
- Сохранение или логирование исходного payload для последующего анализа.
- gRPC/Protobuf transport и отдельный detector worker process.
- Dynamic plugin loading и выполнение сторонних recognizer'ов.
- Регистрация detector в DI и его вызов из gateway или будущего policy engine.

## Открытые решения

Нет. Literal regex и конкретная внутренняя декомпозиция validators остаются implementation details, ограниченными наблюдаемым контрактом и обязательными fixtures.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ clear
  Acceptance:   0.0   ✓ clear
  Boundaries:   0.0   ✓ clear
  Alternatives: 0.0   ✓ clear
  Assumptions:  0.1   ✓ clear; numerical latency budget intentionally deferred
  ──────────────────────────────
  Aggregate:    0.02  ✓ below threshold (0.2 spec)

Push lightly on: production latency threshold after the first measured JMH baseline.
```
