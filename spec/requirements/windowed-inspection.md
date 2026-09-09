# Windowed inspection

Постоянный protocol-neutral контракт проверки одного decoded logical fragment.
Он уточняет [MVP-02](../MVP_FUNCTIONS.md#mvp-02-fast-pii),
[CONC-01/03/04](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#ресурсы-и-cancellation).
Runtime ownership описан в [architecture](../../docs/architecture.md#5-fast-pii-detector),
evidence и его пробелы в [coverage](../../docs/requirements-coverage.md#pii-и-windowing).

## Fragment

`InspectableTextFragment(text: String, provenance: FragmentReference)` содержит
полный точный decoded text и immutable opaque reference исходного фрагмента.
Reference nonblank; `toString()` fragment/reference не раскрывает текст или
protocol locator. Caller передаёт полный decoded fragment; ingest и квоты
остаются ответственностью source owner.
Core не читает HTTP/JSON, не выбирает fields, не соединяет разные messages,
tool/function arguments или независимые schema fields. Между ними не возникает
общий context. Window использует только действительный substring фрагмента,
без synthetic prefix/suffix или padding.

Coordinates результата - UTF-8 исходного decoded fragment, с неизменным parent
provenance. Ни window ID, ни ownership core не становятся пользовательским
provenance. Mapping в encoded source и rewrite принадлежат protocol adapter.
Request limits/quota и response heap lifecycle принадлежат
[MVP NFR](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#conc-01-request-bounds-и-response-heap-lifecycle),
не window-size capability.

## Contract

PII-free `WindowedInspectionExecutor` предоставляет:

```kotlin
fun <I, F, K : Any> inspect(
    fragment: InspectableTextFragment,
    input: I,
    contract: WindowedDetectorContract<I, F, K>,
): Future<WindowedInspectionResult<F>>
```

`I` - immutable invocation snapshot; `F` - immutable detector metadata;
`K` - immutable stable semantic identity. Caller/adapter обеспечивает их
immutability. `LocalFinding<F>(value, startUtf8, endUtf8)` относится к window;
`GlobalFinding<F>` имеет те же поля в original-fragment coordinates.

Contract предоставляет versioned capability, exhaustive
`detect(window, input): List<LocalFinding<F>>`, `semanticIdentity`,
`hasEquivalentMetadata` и `canonicalComparator` global findings. Core не
интерпретирует `I/F/K`, не импортирует PII types и не задаёт taxonomy, identity
или ordering за detector. Synthetic non-PII contract должен работать через
этот же public seam; второго production detector-а или plugin registry нет.

`Success(provenance, findings)` - полный immutable canonical snapshot,
unmodifiable и для Java. `Error(code)` явно отличен от clean empty result:
не содержит partial findings, fragment/window text или raw exception.
Cooperative cancellation остаётся cancellation, а не `Error`.

## Capability

`WindowedCapability` содержит nonblank `version`, positive
`maxWindowUtf8Bytes=W` и nullable `maximumEvidenceSpanUtf8Bytes=E`.
Finite E включает не только finding, но **весь** обязательный lookbehind,
lookahead, boundary и discriminator context для каждой поддержанной surface.
Bound обосновывается versioned rules и boundary corpus; изменение rules
требует повторной проверки доказательства. Произвольный overlap недопустим.

Для finite E обязательны `E > 0`, `E <= W` и
`W - 2 * (E - 1) >= 4`. Вычисление отношений не должно переполняться.
`C = E - 1` - byte budget context с каждой стороны ownership core;
`W - 2C` - core budget. Минимум четыре bytes обеспечивает прогресс на любом
Unicode code point. Capability валидируется до detector call, включая direct
path. Blank version, nonpositive W/E, E больше W или недостаточный core
дают `INVALID_CAPABILITY`.

Без finite E fragment размером `<= W` проверяется одним direct call.
Больший fragment даёт `WINDOWING_UNSUPPORTED` до detector execution.
Нельзя угадывать overlap, обрезать хвост или выдавать clean/partial success.
Finite capability также использует direct path для fragment `<= W`.

## Windows

Preflight проверяет Unicode всего fragment до detector call. Ownership cores
последовательны, не пересекаются и покрывают весь fragment. Их границы и границы
окон всегда code-point/UTF-8-safe, не разделяют surrogate pair или multibyte
sequence. Каждый core продвигается минимум на один code point.

Core получает фактически доступный left/right context в пределах C bytes;
округление границ делается по code points. Window не превышает W bytes.
Первое/последнее окно используют настоящий край fragment. Evidence span
длиной не больше E для finding со start внутри core должен целиком попадать
в окно, включая lookarounds с обеих сторон. Context не считается отдельным
fragment и не создаёт нового source ownership.

Каждый вызов exhaustive. `stopOnFirst` прямого PII API не является режимом
generic core: остановка внутри отдельного окна потеряла бы findings и не
определяла бы global first. Sequential full scan сохраняется и при наличии
раннего finding. Ошибка и cancellation - единственные terminal причины
не запускать оставшиеся окна.

## Aggregation

Для каждого local finding проверяются `0 <= start < end <= windowUtf8Size`
и обе UTF-8 code-point boundaries. Все local findings валидируются, включая
полученные только в context. Invalid span даёт `INVALID_DETECTOR_RESULT`.
Translation: `globalStart/End = windowStartUtf8 + localStart/End`.
Сохраняется finding, чей global start входит в полуоткрытый ownership core;
finding из одного только чужого context отбрасывается.

Owned findings дедуплицируются по contract-provided K. Повторный K требует
metadata equivalence; конфликт даёт `INCONSISTENT_WINDOW_RESULT` без partial
aggregate. Разные identities сохраняются, в том числе overlaps разных типов.
После последнего окна применяется contract canonical comparator, результат
копируется в immutable snapshot. Direct path проходит те же bounds,
deduplication, metadata-conflict и sorting checks. Identity/comparator не
должны зависеть от window boundaries, completion order или mutable state.

## Errors

| Условие | Typed safe outcome | Дальнейшее исполнение |
|---|---|---|
| Невалидная capability | `INVALID_CAPABILITY` | Ни одного detector call |
| Invalid Unicode fragment | `INVALID_FRAGMENT` | Ни одного detector call |
| Fragment > W без finite E | `WINDOWING_UNSUPPORTED` | Ни одного detector call |
| Negative/empty/reversed/out-of-window span; start/end внутри UTF-8 code point | `INVALID_DETECTOR_RESULT` | Остановить, без partial aggregate |
| Duplicate K с неэквивалентной metadata | `INCONSISTENT_WINDOW_RESULT` | Остановить, без partial aggregate |
| Detector runtime failure, включая typed input failure detector-а | `DETECTOR_ERROR` | Первый failure останавливает последующие calls |
| Interrupt/future cancellation или detector `CancellationException` | Cancellation | Не превращать в ERROR/CLEAN; без partial findings |

Precedence: entry cancellation, capability validation, fragment preflight,
direct/unsupported/windows. Errors безопасны и не публикуют payload или raw
exception. Deadline/executor rejection и admission остаются ответственностью
вызывающего уровня, не новым window error mode.

## Lifecycle

Executor получает caller-owned bounded CPU executor. Один fragment - одна
задача и последовательные detector calls вне caller/event-loop thread.
Core не создаёт threads, executors, scopes, очереди, I/O или logging.
Parallel window execution и streaming detector API не предусмотрены.

Рабочая память: original fragment reference, максимум одна materialized String
window и её local batch, плюс metadata aggregate `MutableMap<K, GlobalFinding<F>>`.
Нет полной UTF-8 копии, списка всех windows, копий полного body на окно или
retention сырых fragments после terminal completion. Caller source owner и
его quota освобождаются по собственному lifecycle; core не закрывает чужой
executor. Window size не является общей квотой aggregate findings.

Cancellation проверяется на CPU entry, между окнами, перед detector call и
во время aggregation/translation. Interrupt flag не очищается; после
наблюдаемой отмены новый detector call не начинается. Cancellation активного
future передаёт interrupt cooperative detector-у. Success, first error и
cancellation освобождают временное состояние без payload-bearing telemetry.

## Fast PII adapter

`WindowedFastPiiExecutor` предоставляет generic core detector invocation и
PII-specific semantics. До executor handoff копирует enabled type set; каждый
window вызывает `FastPiiDetector.detect(..., stopOnFirst=false, enabledTypes)`.
Generic core остаётся независимым от PII package.

Текущая capability `fast-pii-window-capability@2`: `W=1 048 576`, `E=4 096`,
`C=4 095` bytes с каждой стороны, core budget `1 040 386` bytes до округления.
Bound должен покрывать все [PII surfaces](fast-pii.md#taxonomy) с context,
а не только длину нормализованного value. Степень подтверждения этого proof
отмечена в [coverage](../../docs/requirements-coverage.md#pii-и-windowing).

PII key точно `(type, globalStart, globalEnd, recognizerId)`. При duplicate
должны совпадать `recognizerVersion`, `evidenceStrength` и `confidence`.
PII aggregate сортируется по `globalStart`, `globalEnd`, `type.name`,
`recognizerId`, `recognizerVersion`. Это global offset order, в том числе
для direct path executor-а; type pipeline order отдельного `PiiDetector.detect`
не подменяет его. Обратное преобразование сохраняет все поля `PiiFinding`,
provenance и immutable snapshot. Generic error codes отображаются по имени
в `WindowedPiiInspectionErrorCode`; Future adapter сохраняет cancellation,
timeout/get semantics и не создаёт вторую CPU task.

### Policy adapter

`FastPiiPolicyAdapter` реализует `policy.domain.Detector.detect`, stable
registry ID `fast-pii`, adapter version `fast-pii@1`. Он всегда проверяет все
девять типов exhaustive через оконный adapter. No findings -> `CLEAN`,
findings -> `DETECTED`; order, type, exact UTF-8 span и confidence переносятся
без потерь, evidence/recognizer ID/version сохраняются под keys
`evidence_strength`, `recognizer_id`, `recognizer_version`. Matched text
не переносится. Invocation provenance opaque (`policy-fast-pii`); protocol
field ownership остаётся у caller.

Typed window failure -> policy `ERROR` с `FAST_PII_<code>`, прочий failed
execution -> `FAST_PII_EXECUTION_FAILED`; безопасное сообщение
`Fast PII inspection failed`. При interrupt ожидающего worker-а adapter
отменяет future с interrupt, восстанавливает interrupt flag и выбрасывает
`CancellationException`; detector cancellation из `ExecutionException`
сохраняется как cancellation. Blocking wait разрешён только policy worker-у,
CPU work остаётся на переданном bounded executor.

HTTP routing, parsing, policy selection/decision, reactions, audit, spooling,
DI composition и application lifecycle не принадлежат adapter-у.
Public seam tests и полная обязательная matrix перечислены в
[development guide](../../docs/development.md#pii-contract-checks).
