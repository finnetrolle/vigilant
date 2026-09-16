# Baseline качества Fast PII: 16 сентября 2026

Свежий замер одной реализации показывает сильную зависимость качества от корпуса.
Canonical gates проходят полностью. На RedMadRobot source-aligned exact recall
составляет **32,32%**, на HiveTrace **88,13%**. В AdvPIIBench exact recall
снижается с **72,51%** на baseline до **0%** во всех шести attack families,
в обоих attack stages и всех combined configurations.
Это оценка прямого `PiiDetector.detect`, без HTTP extraction, policy reactions,
JMH qualification и утверждений о качестве на production traffic.

| Корпус / view | Scope | Exact TP / FP / FN | Exact P / R / F1 | Relaxed TP / FP / FN | Relaxed P / R / F1 |
|---|---|---|---|---|---|
| Canonical mixed | 3 synthetic records | 13 / 0 / 0 | 1 / 1 / 1 | 13 / 0 / 0 | 1 / 1 / 1 |
| RedMadRobot source | full | 614 / 146 / 1286 | 0.807895 / 0.323158 / 0.461654 | 656 / 104 / 1244 | 0.863158 / 0.345263 / 0.493233 |
| RedMadRobot product | full | 631 / 129 / 1058 | 0.830263 / 0.373594 / 0.515312 | 656 / 104 / 1033 | 0.863158 / 0.388396 / 0.535729 |
| RedMadRobot nested IP v2 | full | 626 / 134 / 1284 | 0.823684 / 0.327749 / 0.468914 | 665 / 95 / 1245 | 0.875000 / 0.348168 / 0.498127 |
| HiveTrace source | Full (entity + domain) | 720 / 37 / 97 | 0.951123 / 0.881273 / 0.914867 | 723 / 34 / 94 | 0.955086 / 0.884945 / 0.918679 |
| HiveTrace product | Full (entity + domain) | 720 / 37 / 69 | 0.951123 / 0.912548 / 0.931436 | 723 / 34 / 66 | 0.955086 / 0.916350 / 0.935317 |
| AdvPIIBench source | baseline | 1079 / 83 / 409 | 0.928571 / 0.725134 / 0.814340 | 1119 / 43 / 369 | 0.962995 / 0.752016 / 0.844528 |
| AdvPIIBench source | PII-only | 0 / 0 / 8928 | N/A / 0 / 0 | 0 / 0 / 8928 | N/A / 0 / 0 |
| AdvPIIBench source | combined | 0 / 14496 / 89280 | 0 / 0 / 0 | 0 / 14496 / 89280 | 0 / 0 / 0 |

Единого headline F1 нет: корпуса, gold views и scopes несовместимы для такого
объединения. `combined` включает auxiliary examples с неполной разметкой;
14 496 source-aligned FP нельзя без оговорок считать ошибками recognizer.

## Версия и воспроизведение

Run/session: `vig46-20260916T192859Z`, 2026-09-16 UTC.
Git revision: `b9a23bc71476af4e24b5f132be3dbfc96de333fe`.
Перед первым запуском dirty state пуст. Согласованная редакция задачи сохранена
в этом revision; предыдущая база `499c6d1a34e5af80be1014f3858ce4cc789e52df`.
Detector/evaluator и build inputs не менялись между прогонами.

SHA-256 общего inventory из 420 файлов:
`3753ee44aa3520949c9aba8d99ce241b0a36d47de8bcabc69c3b4df14252735b`.
Digest пустого detector/evaluator diff:
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
Полные file digests, 19 canonical TSV, четыре dataset files и исходные metadata
находятся в manifest. Runtime toolchain - JDK 25; launcher environment:
Homebrew OpenJDK 25.0.2 (2026-01-20), macOS 26.3.1 (a), Darwin 25.3.0 arm64.
Gradle 9.7.1; запуск с `--no-daemon --offline`. Snapshot включает оба локальных
JDK 25, Gradle distribution, user Gradle configuration и environment digests.
Это provenance воспроизведения, а не измерение производительности.

Recognizer versions: EMAIL 1.1.0, PHONE 1.1.0, PAYMENT_CARD 1.0.0,
IP 1.2.0, IBAN 1.0.0+iban-registry.102, INN 1.0.0, SNILS 1.1.0,
PASSPORT 1.0.0, OMS 1.1.0. Точный mapping/контракт:
[Fast PII taxonomy](../spec/requirements/fast-pii.md#taxonomy).
Все runners используют публичный detector с `stopOnFirst=false`;
исходный Unicode и UTF-8 coordinates сохраняются, gold не выводится из predictions.

| Корпус | Run ID | Exit / applicability | Evaluator / mapping |
|---|---|---|---|
| Canonical | `42486dc014524306a476a77b9ad9bf9b` | 0 / current | `pii-corpus-v1`, exact metadata gate; `PiiQualityScorer` для mixed |
| RedMadRobot | `7f7ab68f4a574cbfa2c27b42f37e7fbe` | 0 / current | BIO adapter на указанном revision; product v1; frozen split v1; nested IP v2 |
| HiveTrace | `4e2781e8439e4a7fb83219f92f0fe048` | 0 / current | `hivetrace-v1`, `hivetrace-product-aligned-v1` |
| AdvPIIBench | `5d222e710b344affa4c4f7fddad1ce8a` | 0 / current | `advpii-source-aligned-v1`, `advpii-four-types-v1` |

Команды исполнены последовательно на существующих проверенных build-кешах:

```bash
./gradlew --no-daemon --offline piiQualityReport
./gradlew --no-daemon --offline redMadRobotPiiBenchmark
./gradlew --no-daemon --offline hiveTracePiiBenchmark
./gradlew --no-daemon --offline advPiiBenchmark
```

Каждая команда запущена через [scripts/check-run](development.md#устойчивый-запуск-проверок)
с одним `--snapshot vig46-20260916T192859Z`, timeout 1200, inputs `src`,
`buildSrc`, `build.gradle.kts`, `settings.gradle.kts`, `gradle`, `gradlew`,
`gradlew.bat`, `config`; report directory объявлен artifact. Tool declarations
включают соответствующий локальный dataset и JDK/Gradle/configuration paths.
Точный argv и declarations каждого запуска сохранены в `runs/<run-id>/request.json`.
`status <run-id>` проверяет terminal exit, input stability и целостность outputs.
Для нового замера следует выбрать новый snapshot/run directory; прежние отчёты
не подменяют новый запуск. Gradle `--offline` относится к dependency resolution:
при отсутствии dataset cache требуется явный offline input либо загрузка pinned data.

Offline input properties и их формы принадлежат постоянным методикам:
[RedMadRobot](development.md#external-pii-benchmark),
[HiveTrace](development.md#hivetrace-pii-benchmark),
[AdvPIIBench](development.md#advpiibench-adversarial-benchmark).

Исходные JSON/Markdown скопированы без изменения в
`build/reports/pii/evaluation/vig46-20260916T192859Z/`.
Локальные artifacts доступны после замера; `build/` не хранится в Git и удаляется
командой clean. В Git остаются настоящие полные агрегированные таблицы ниже.
Manifest связывает file digests, provenance, coverage, commands, run IDs и snapshots.
Прямые ссылки на manifest, closure и каждую пару JSON/Markdown находятся в
локальном индексе `build/reports/pii/evaluation/vig46-20260916T192859Z/artifacts.md`.
Для сохранённого локального замера его можно открыть на macOS командой:

```bash
open build/reports/pii/evaluation/vig46-20260916T192859Z/artifacts.md
```

| Artifact | Локальный путь относительно run directory |
|---|---|
| Индекс с прямыми ссылками | `artifacts.md` |
| Общий manifest | `manifest.json` |
| Canonical | `canonical/pii-quality-report.json`, `canonical/pii-quality-report.md` |
| RedMadRobot | `redmadrobot/redmadrobot-pii-benchmark.json`, `redmadrobot/redmadrobot-pii-benchmark.md` |
| HiveTrace | `hivetrace/hivetrace-pii-benchmark.json`, `hivetrace/hivetrace-pii-benchmark.md` |
| AdvPIIBench | `advpii/advpii-benchmark.json`, `advpii/advpii-benchmark.md` |
| Проверка таблиц | `tables.py`, `tables.md`, `table-rows.json`, `arithmetic-audit.json` |
| Проверка privacy и стабильности | `privacy-scan.py`, `privacy-scan.json`, `closure.md` |
| Durable execution records | `runs/<run-id>/request.json`, `result.json`, `command.log`, input/artifact digests |

## Закреплённые данные и denominators

License declarations ниже воспроизводят metadata владельцев datasets,
не являются юридическим заключением. В этом замере данные не менялись.

| Корпус | Revision / version | Размер bytes | SHA-256 | Attribution / license declaration |
|---|---|---:|---|---|
| Canonical | `pii-corpus-v1` + Git revision выше | 19 TSV; размеры фиксируются Git | SHA-256 каждого TSV в manifest | Repository synthetic fixtures; отдельная upstream license declaration не применяется |
| RedMadRobot test.csv | `f77ea831274daf980cc45c61a93c226be9d978d6` | 3225069 | `6bf544a380a3ee5bec94b946124bea3afaecce49e734679ad0f0c0e7c12977bb` | RedMadRobot R&D pii_benchmark / MIT |
| HiveTrace entity | `cd6a18ace16daf23e79247ccf1e2b245d4054654` | 78391 | `6e0c77d566c2f04e7213917b26f1038336e28fc006551fac7a7f44f7627d5f96` | HiveTrace PII-Bench RU / Apache-2.0 |
| HiveTrace domain | `cd6a18ace16daf23e79247ccf1e2b245d4054654` | 100083 | `7ef3574273c0fe3a987981e38e32cd2764031a82766ca5b235bfccd1f27058eb` | HiveTrace PII-Bench RU / Apache-2.0 |
| AdvPIIBench train | `02741d9f99a91b8fdcf48f4316a2c73be7a7449a` | 4258476 | `e97f6a32132e7fa058919798c030fca54aaad3318c47954d281717a435bfeb69` | Roei Arpaly and Yoni Birman, Reichman University / CC BY 4.0 |

Canonical: 960 positive, 975 hard-negative, 3 mixed; нет rejected fixtures.
Positive gate сравнивает весь ordered finding, включая type/span/evidence/recognizer
metadata; hard-negative gate требует отсутствие findings. Восемь типов имеют
по 100/100 cases; IP имеет 160/175. Это проверка fixed contract, без внешней
оценки generalization. Mixed содержит всего 13 gold spans.

RedMadRobot: 2841 records, 2839 processed, 2 rejected по token/text alignment;
5614 source BIO spans, 1902 mapped, 1900 scored. Source full/tuning/evaluation:
2839/2109/730 records и 1900/1463/437 gold spans. Tuning/evaluation disjoint,
frozen SHA-256 split v1 с evaluation buckets 0..63 из 256; tuning здесь не
использовался для настройки. Enabled: EMAIL_ADDRESS, PHONE_NUMBER, PAYMENT_CARD,
IP_ADDRESS, RU_INN, RU_SNILS, RU_PASSPORT, RU_OMS. IBAN: **not covered**.
Неподдерживаемые source labels не являются negative subset; document FPR для
RedMadRobot не определён owning benchmark и не изобретён в сводке.

HiveTrace: 1810 processed, 0 rejected, 1667 source spans, 817 mapped,
850 unsupported; product denominator 789. Full = 910 entity + 900 domain,
с девятью domain codes по 100 records. Clean означает исходный пустой entities,
до mapping: domain 378, entity 0. Unsupported-only records не clean.
Entity clean FPR: **N/A (0/0)**; domain и Full: **0/378**.
Enabled шесть mapped типов; IP_ADDRESS, IBAN и RU_OMS: **not covered**.

AdvPIIBench: 104728 processed, 0 rejected; 80936 positive, 22560 negative,
1232 hard_negative; 114101 source spans, 99696 mapped. SSN coverage-only:
14405 spans, 7772 SSN-only positive records не переводятся в negatives.
Stages: 1208 baseline, 7248 PII-only, 72480 combined. Каждая family имеет 13288
rows суммарно по двум attack stages; каждая combined configuration 7248 rows.
Полный corpus held-out, без внутреннего tuning/evaluation split.
Enabled: EMAIL_ADDRESS, PHONE_NUMBER, PAYMENT_CARD, IBAN;
IP_ADDRESS, RU_INN, RU_SNILS, RU_PASSPORT, RU_OMS: **not covered**.
Document FPR при этих четырёх enabled types: negative **0/22560 = 0%**,
hard_negative **2/1232 = 0,162338%**. Ни один показатель не является
positive document-level recall.

## Как читать таблицы

Exact = same type и идентичные half-open UTF-8 boundaries; relaxed = same type
и непустое пересечение. Внутри record/type используется one-to-one
maximum-cardinality matching. `Gold = TP + FN`, `Pred = TP + FP`;
`P = TP / Pred`, `R = TP / Gold`, `F1 = 2TP / (2TP + FP + FN)`.
Micro складывает confusion counts до вычисления ratios.
Нулевой знаменатель означает **N/A**, а не подтверждение качества;
нулевой numerator при ненулевом denominator остаётся 0.
Legacy JSON иногда сериализует 0.0 для пустого denominator: оригинал сохранён,
сводка явно показывает N/A. Ratios округлены до шести знаков, counts точные.

AdvPIIBench `comparableBaseline` сохраняет исходные input IDs, типы и веса
variants; `ΔR` = attack recall минус comparable baseline recall в процентных
пунктах. Повторные rollups пересекаются и не складываются. Все 87 subsets
даны отдельно: пять основных, 12 stage/family, десять configuration и 60
configuration/family. `suppressed` не раскрывает counts, support и ratios;
floor равен пяти distinct original input IDs, не количеству variants.

## Наблюдаемые ограничения и причины

**Taxonomy mismatch.** RedMadRobot product v1 исключает 107 gold INN из десяти
ASCII digits и объединяет 104 пары series/number PASSPORT: source denominator
1900 становится 1689. HiveTrace product v1 исключает только 28 таких INN:
817 становится 789, без passport merge. Эти версии существовали до замера,
predictions не меняются. Изменение F1 между views не является улучшением detector.
В AdvPIIBench baseline PHONE recall 62/380 = 16,32%; российская phone surface
уже ограничивает интерпретацию англоязычного корпуса. Доля ошибок именно из-за
географии не измерена и остаётся гипотезой, не установленным breakdown.

**Checksum и контекст.** RedMadRobot source exact FN: PASSPORT 489, SNILS 191,
INN 166, CARD 142, EMAIL 102, OMS 98, PHONE 84, IP 14. Passport требует bounded
контекста; CARD/INN и часть SNILS/OMS проверяют checksum. Исходные invalid values
сохранены в gold. Доступные diagnostics не классифицируют каждый пропуск по
checksum/context/separator, поэтому причинные доли неизвестны. По evidence
contribution OMS даёт 64 FP: 63 VALIDATED и 1 CONTEXTUAL; это наблюдаемая
концентрация source-aligned FP, но возможные annotation gaps не исключены.
HiveTrace PASSPORT: 56 FN, exact recall 53,33%; CARD: 21 FP, precision 81,42%.

**Границы spans.** На RedMadRobot exact -> relaxed TP растёт на 42:
EMAIL +10, PHONE +6, PASSPORT +23, IP +3. Source diagnostics отдельно фиксируют
65 exact unmatched-gold `SPAN_MISMATCH` и 23 relaxed; числа buckets нельзя
подменять числом дополнительных TP при one-to-one matching. У HiveTrace
exact -> relaxed добавляет 3 EMAIL TP. У AdvPIIBench baseline добавляет 40
EMAIL TP: recall 83,06% -> 94,17%. Эти изменения доказывают чувствительность
метрик к границам, но не определяют конкретный ошибочный separator.

**Unicode и обфускация.** В каждой из families `homoglyph`, `chunking`, `emojify`,
`char_to_word`, `invisible_chars`, `separators` exact и relaxed recall = 0,
включая каждый combined configuration/family и каждый неподавленный mapped type.
Micro ΔR = -72,513441 п.п. exact и -75,201613 п.п. relaxed относительно
сопоставимого baseline. Нулевая находка attacked gold сохраняется даже при
посторонних findings: combined даёт 14496 FP. Корпус не нормализован;
unsupported general obfuscation является известной границей fixed detector,
но замер не доказывает качество какой-либо будущей normalization strategy.

**Gaps разметки.** RedMadRobot independent IP reference v2 добавляет десять
URL-host gold spans и нормализует восемь целых IPv4:port spans до address-only.
Full denominator 1910; этот view не исправляет остальные annotations.
В AdvPIIBench оба configurations с `pi_few_shot_safe` имеют source-aligned FP
на вспомогательных неразмеченных примерах; unrelated span никогда не даёт TP
для attacked gold. Precision этих scopes имеет ограниченную интерпретацию.
HiveTrace не публикует mismatch breakdown; конкретные причины его FP/FN
не устанавливаются по одному aggregate count. Отдельные кейсы и matched values
не выводились в отчёт.

## Приоритеты последующей работы

Это предложения для отдельных задач; ни production rules, ни gold, ни
release thresholds в данном замере не изменены. Приоритет выражает полезность
следующего исследования, а не разрешение tuning на held-out evaluation.

| Приоритет | Наблюдение / типы / корпуса | Следующий проверяемый результат |
|---|---|---|
| 1 | Все четыре mapped типа, AdvPIIBench: 0 recall во всех attacks | Согласовать продуктовую границу обфускации; исследовать обратимое к исходным offsets преобразование на независимых synthetic fixtures с hard negatives, сохранив этот corpus held-out. Без такого решения обещание adversarial detection не обосновано |
| 2 | RU_PASSPORT: RedMadRobot 489 source FN и 23 дополнительных relaxed TP; HiveTrace 56 FN | Разделить format/context и span-boundary причины безопасными aggregate diagnostics; уточнить desired passport surface до отдельной реализации |
| 3 | RU_OMS: RedMadRobot 64 FP, из них 63 VALIDATED; PAYMENT_CARD: RMM 22 FP и HiveTrace 21 FP | Проверить пересечения форматов и разметки агрегированными counters; оценить последствия предлагаемых ограничений на независимых negatives до изменения rules |
| 4 | RU_SNILS/RU_INN/CARD: RedMadRobot 191/166/142 FN, taxonomy adjustments уже объясняют часть INN denominator | Получить privacy-safe checksum/context breakdown, не удаляя invalid source gold. Отделить contract mismatch от дефекта реализации |
| 5 | EMAIL/PHONE/IP boundaries: RMM +10/+6/+3 relaxed TP; Adv baseline EMAIL +40; PHONE baseline 62/380 TP | Уточнить независимые boundary fixtures; отдельно решить географический scope телефона, не расширяя его автоматически по этому evaluation |

Нулевой clean FPR наблюдается только в перечисленных finite subsets.
Canonical gate, external quality, gateway completeness и latency qualification
остаются разными видами evidence. [Coverage](requirements-coverage.md#pii-и-windowing)
сохраняет открытые runtime/performance gaps и действующие нормативные targets.

## Проверка отчёта

JSON прошли независимый пересчёт P/R/F1 из integer confusion counts через
рациональные дроби: 2348 metric objects, 2222 опубликованные строки metrics.
Проверены micro/per-type суммы, gold/prediction counts, document FPR,
recall deltas, все scoped keys и отсутствие data у suppressed groups.
Проекция не вызывает detector и не строит новую разметку или matcher.
Точные baseline counts публикуются рядом с attacked metrics.

Raw corpus остаётся в локальном cache. Проверка report schema и поиск исходных
payload/annotated values в JSON/Markdown/logs дополняют privacy contract writers;
они не доказывают отсутствие произвольного неизвестного кодирования.
В документе только aggregate diagnostics и публичная provenance.
Команды проверки и closure evidence находятся в локальном `closure.md`.
Полные таблицы ниже являются частью этого baseline, не историческими артефактами
предыдущих запусков.

## Canonical: полная таблица

Positive и hard-negative gates: PASS для каждой строки; enabled type указан в первом столбце. Mixed использует типы fixture (включая все девять через `*`).

| Тип | Positive exact contract | Hard-negative rejection |
| --- | --- | --- |
| EMAIL_ADDRESS | 100/100 | 100/100 |
| PHONE_NUMBER | 100/100 | 100/100 |
| PAYMENT_CARD | 100/100 | 100/100 |
| IP_ADDRESS | 160/160 | 175/175 |
| IBAN | 100/100 | 100/100 |
| RU_INN | 100/100 | 100/100 |
| RU_SNILS | 100/100 | 100/100 |
| RU_PASSPORT | 100/100 | 100/100 |
| RU_OMS | 100/100 | 100/100 |

### Mixed

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| micro | relaxed | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | exact | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| IP_ADDRESS | exact | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| IP_ADDRESS | relaxed | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | exact | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | relaxed | 2 | 0 | 0 | 2 | 2 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_OMS | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_OMS | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |

## RedMadRobot: полная таблица

### sourceAligned / full

Processed: 2839; scored gold: 1900.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 614 | 146 | 1286 | 1900 | 760 | 0.807895 | 0.323158 | 0.461654 |
| micro | relaxed | 656 | 104 | 1244 | 1900 | 760 | 0.863158 | 0.345263 | 0.493233 |
| EMAIL_ADDRESS | exact | 119 | 11 | 102 | 221 | 130 | 0.915385 | 0.538462 | 0.678063 |
| EMAIL_ADDRESS | relaxed | 129 | 1 | 92 | 221 | 130 | 0.992308 | 0.583710 | 0.735043 |
| PHONE_NUMBER | exact | 87 | 6 | 84 | 171 | 93 | 0.935484 | 0.508772 | 0.659091 |
| PHONE_NUMBER | relaxed | 93 | 0 | 78 | 171 | 93 | 1.000000 | 0.543860 | 0.704545 |
| PAYMENT_CARD | exact | 61 | 22 | 142 | 203 | 83 | 0.734940 | 0.300493 | 0.426573 |
| PAYMENT_CARD | relaxed | 61 | 22 | 142 | 203 | 83 | 0.734940 | 0.300493 | 0.426573 |
| IP_ADDRESS | exact | 141 | 15 | 14 | 155 | 156 | 0.903846 | 0.909677 | 0.906752 |
| IP_ADDRESS | relaxed | 144 | 12 | 11 | 155 | 156 | 0.923077 | 0.929032 | 0.926045 |
| RU_INN | exact | 97 | 2 | 166 | 263 | 99 | 0.979798 | 0.368821 | 0.535912 |
| RU_INN | relaxed | 97 | 2 | 166 | 263 | 99 | 0.979798 | 0.368821 | 0.535912 |
| RU_SNILS | exact | 32 | 0 | 191 | 223 | 32 | 1.000000 | 0.143498 | 0.250980 |
| RU_SNILS | relaxed | 32 | 0 | 191 | 223 | 32 | 1.000000 | 0.143498 | 0.250980 |
| RU_PASSPORT | exact | 5 | 26 | 489 | 494 | 31 | 0.161290 | 0.010121 | 0.019048 |
| RU_PASSPORT | relaxed | 28 | 3 | 466 | 494 | 31 | 0.903226 | 0.056680 | 0.106667 |
| RU_OMS | exact | 72 | 64 | 98 | 170 | 136 | 0.529412 | 0.423529 | 0.470588 |
| RU_OMS | relaxed | 72 | 64 | 98 | 170 | 136 | 0.529412 | 0.423529 | 0.470588 |

### sourceAligned / tuning

Processed: 2109; scored gold: 1463.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 477 | 109 | 986 | 1463 | 586 | 0.813993 | 0.326042 | 0.465593 |
| micro | relaxed | 510 | 76 | 953 | 1463 | 586 | 0.870307 | 0.348599 | 0.497804 |
| EMAIL_ADDRESS | exact | 99 | 10 | 90 | 189 | 109 | 0.908257 | 0.523810 | 0.664430 |
| EMAIL_ADDRESS | relaxed | 108 | 1 | 81 | 189 | 109 | 0.990826 | 0.571429 | 0.724832 |
| PHONE_NUMBER | exact | 72 | 5 | 67 | 139 | 77 | 0.935065 | 0.517986 | 0.666667 |
| PHONE_NUMBER | relaxed | 77 | 0 | 62 | 139 | 77 | 1.000000 | 0.553957 | 0.712963 |
| PAYMENT_CARD | exact | 45 | 16 | 94 | 139 | 61 | 0.737705 | 0.323741 | 0.450000 |
| PAYMENT_CARD | relaxed | 45 | 16 | 94 | 139 | 61 | 0.737705 | 0.323741 | 0.450000 |
| IP_ADDRESS | exact | 111 | 12 | 12 | 123 | 123 | 0.902439 | 0.902439 | 0.902439 |
| IP_ADDRESS | relaxed | 113 | 10 | 10 | 123 | 123 | 0.918699 | 0.918699 | 0.918699 |
| RU_INN | exact | 70 | 1 | 124 | 194 | 71 | 0.985915 | 0.360825 | 0.528302 |
| RU_INN | relaxed | 70 | 1 | 124 | 194 | 71 | 0.985915 | 0.360825 | 0.528302 |
| RU_SNILS | exact | 24 | 0 | 141 | 165 | 24 | 1.000000 | 0.145455 | 0.253968 |
| RU_SNILS | relaxed | 24 | 0 | 141 | 165 | 24 | 1.000000 | 0.145455 | 0.253968 |
| RU_PASSPORT | exact | 2 | 18 | 389 | 391 | 20 | 0.100000 | 0.005115 | 0.009732 |
| RU_PASSPORT | relaxed | 19 | 1 | 372 | 391 | 20 | 0.950000 | 0.048593 | 0.092457 |
| RU_OMS | exact | 54 | 47 | 69 | 123 | 101 | 0.534653 | 0.439024 | 0.482143 |
| RU_OMS | relaxed | 54 | 47 | 69 | 123 | 101 | 0.534653 | 0.439024 | 0.482143 |

### sourceAligned / evaluation

Processed: 730; scored gold: 437.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 137 | 37 | 300 | 437 | 174 | 0.787356 | 0.313501 | 0.448445 |
| micro | relaxed | 146 | 28 | 291 | 437 | 174 | 0.839080 | 0.334096 | 0.477905 |
| EMAIL_ADDRESS | exact | 20 | 1 | 12 | 32 | 21 | 0.952381 | 0.625000 | 0.754717 |
| EMAIL_ADDRESS | relaxed | 21 | 0 | 11 | 32 | 21 | 1.000000 | 0.656250 | 0.792453 |
| PHONE_NUMBER | exact | 15 | 1 | 17 | 32 | 16 | 0.937500 | 0.468750 | 0.625000 |
| PHONE_NUMBER | relaxed | 16 | 0 | 16 | 32 | 16 | 1.000000 | 0.500000 | 0.666667 |
| PAYMENT_CARD | exact | 16 | 6 | 48 | 64 | 22 | 0.727273 | 0.250000 | 0.372093 |
| PAYMENT_CARD | relaxed | 16 | 6 | 48 | 64 | 22 | 0.727273 | 0.250000 | 0.372093 |
| IP_ADDRESS | exact | 30 | 3 | 2 | 32 | 33 | 0.909091 | 0.937500 | 0.923077 |
| IP_ADDRESS | relaxed | 31 | 2 | 1 | 32 | 33 | 0.939394 | 0.968750 | 0.953846 |
| RU_INN | exact | 27 | 1 | 42 | 69 | 28 | 0.964286 | 0.391304 | 0.556701 |
| RU_INN | relaxed | 27 | 1 | 42 | 69 | 28 | 0.964286 | 0.391304 | 0.556701 |
| RU_SNILS | exact | 8 | 0 | 50 | 58 | 8 | 1.000000 | 0.137931 | 0.242424 |
| RU_SNILS | relaxed | 8 | 0 | 50 | 58 | 8 | 1.000000 | 0.137931 | 0.242424 |
| RU_PASSPORT | exact | 3 | 8 | 100 | 103 | 11 | 0.272727 | 0.029126 | 0.052632 |
| RU_PASSPORT | relaxed | 9 | 2 | 94 | 103 | 11 | 0.818182 | 0.087379 | 0.157895 |
| RU_OMS | exact | 18 | 17 | 29 | 47 | 35 | 0.514286 | 0.382979 | 0.439024 |
| RU_OMS | relaxed | 18 | 17 | 29 | 47 | 35 | 0.514286 | 0.382979 | 0.439024 |

### productAligned / full

Processed: 2839; scored gold: 1689.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 631 | 129 | 1058 | 1689 | 760 | 0.830263 | 0.373594 | 0.515312 |
| micro | relaxed | 656 | 104 | 1033 | 1689 | 760 | 0.863158 | 0.388396 | 0.535729 |
| EMAIL_ADDRESS | exact | 119 | 11 | 102 | 221 | 130 | 0.915385 | 0.538462 | 0.678063 |
| EMAIL_ADDRESS | relaxed | 129 | 1 | 92 | 221 | 130 | 0.992308 | 0.583710 | 0.735043 |
| PHONE_NUMBER | exact | 87 | 6 | 84 | 171 | 93 | 0.935484 | 0.508772 | 0.659091 |
| PHONE_NUMBER | relaxed | 93 | 0 | 78 | 171 | 93 | 1.000000 | 0.543860 | 0.704545 |
| PAYMENT_CARD | exact | 61 | 22 | 142 | 203 | 83 | 0.734940 | 0.300493 | 0.426573 |
| PAYMENT_CARD | relaxed | 61 | 22 | 142 | 203 | 83 | 0.734940 | 0.300493 | 0.426573 |
| IP_ADDRESS | exact | 141 | 15 | 14 | 155 | 156 | 0.903846 | 0.909677 | 0.906752 |
| IP_ADDRESS | relaxed | 144 | 12 | 11 | 155 | 156 | 0.923077 | 0.929032 | 0.926045 |
| RU_INN | exact | 97 | 2 | 59 | 156 | 99 | 0.979798 | 0.621795 | 0.760784 |
| RU_INN | relaxed | 97 | 2 | 59 | 156 | 99 | 0.979798 | 0.621795 | 0.760784 |
| RU_SNILS | exact | 32 | 0 | 191 | 223 | 32 | 1.000000 | 0.143498 | 0.250980 |
| RU_SNILS | relaxed | 32 | 0 | 191 | 223 | 32 | 1.000000 | 0.143498 | 0.250980 |
| RU_PASSPORT | exact | 22 | 9 | 368 | 390 | 31 | 0.709677 | 0.056410 | 0.104513 |
| RU_PASSPORT | relaxed | 28 | 3 | 362 | 390 | 31 | 0.903226 | 0.071795 | 0.133017 |
| RU_OMS | exact | 72 | 64 | 98 | 170 | 136 | 0.529412 | 0.423529 | 0.470588 |
| RU_OMS | relaxed | 72 | 64 | 98 | 170 | 136 | 0.529412 | 0.423529 | 0.470588 |

### productAligned / tuning

Processed: 2109; scored gold: 1295.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 491 | 95 | 804 | 1295 | 586 | 0.837884 | 0.379151 | 0.522063 |
| micro | relaxed | 510 | 76 | 785 | 1295 | 586 | 0.870307 | 0.393822 | 0.542265 |
| EMAIL_ADDRESS | exact | 99 | 10 | 90 | 189 | 109 | 0.908257 | 0.523810 | 0.664430 |
| EMAIL_ADDRESS | relaxed | 108 | 1 | 81 | 189 | 109 | 0.990826 | 0.571429 | 0.724832 |
| PHONE_NUMBER | exact | 72 | 5 | 67 | 139 | 77 | 0.935065 | 0.517986 | 0.666667 |
| PHONE_NUMBER | relaxed | 77 | 0 | 62 | 139 | 77 | 1.000000 | 0.553957 | 0.712963 |
| PAYMENT_CARD | exact | 45 | 16 | 94 | 139 | 61 | 0.737705 | 0.323741 | 0.450000 |
| PAYMENT_CARD | relaxed | 45 | 16 | 94 | 139 | 61 | 0.737705 | 0.323741 | 0.450000 |
| IP_ADDRESS | exact | 111 | 12 | 12 | 123 | 123 | 0.902439 | 0.902439 | 0.902439 |
| IP_ADDRESS | relaxed | 113 | 10 | 10 | 123 | 123 | 0.918699 | 0.918699 | 0.918699 |
| RU_INN | exact | 70 | 1 | 42 | 112 | 71 | 0.985915 | 0.625000 | 0.765027 |
| RU_INN | relaxed | 70 | 1 | 42 | 112 | 71 | 0.985915 | 0.625000 | 0.765027 |
| RU_SNILS | exact | 24 | 0 | 141 | 165 | 24 | 1.000000 | 0.145455 | 0.253968 |
| RU_SNILS | relaxed | 24 | 0 | 141 | 165 | 24 | 1.000000 | 0.145455 | 0.253968 |
| RU_PASSPORT | exact | 16 | 4 | 289 | 305 | 20 | 0.800000 | 0.052459 | 0.098462 |
| RU_PASSPORT | relaxed | 19 | 1 | 286 | 305 | 20 | 0.950000 | 0.062295 | 0.116923 |
| RU_OMS | exact | 54 | 47 | 69 | 123 | 101 | 0.534653 | 0.439024 | 0.482143 |
| RU_OMS | relaxed | 54 | 47 | 69 | 123 | 101 | 0.534653 | 0.439024 | 0.482143 |

### productAligned / evaluation

Processed: 730; scored gold: 394.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 140 | 34 | 254 | 394 | 174 | 0.804598 | 0.355330 | 0.492958 |
| micro | relaxed | 146 | 28 | 248 | 394 | 174 | 0.839080 | 0.370558 | 0.514085 |
| EMAIL_ADDRESS | exact | 20 | 1 | 12 | 32 | 21 | 0.952381 | 0.625000 | 0.754717 |
| EMAIL_ADDRESS | relaxed | 21 | 0 | 11 | 32 | 21 | 1.000000 | 0.656250 | 0.792453 |
| PHONE_NUMBER | exact | 15 | 1 | 17 | 32 | 16 | 0.937500 | 0.468750 | 0.625000 |
| PHONE_NUMBER | relaxed | 16 | 0 | 16 | 32 | 16 | 1.000000 | 0.500000 | 0.666667 |
| PAYMENT_CARD | exact | 16 | 6 | 48 | 64 | 22 | 0.727273 | 0.250000 | 0.372093 |
| PAYMENT_CARD | relaxed | 16 | 6 | 48 | 64 | 22 | 0.727273 | 0.250000 | 0.372093 |
| IP_ADDRESS | exact | 30 | 3 | 2 | 32 | 33 | 0.909091 | 0.937500 | 0.923077 |
| IP_ADDRESS | relaxed | 31 | 2 | 1 | 32 | 33 | 0.939394 | 0.968750 | 0.953846 |
| RU_INN | exact | 27 | 1 | 17 | 44 | 28 | 0.964286 | 0.613636 | 0.750000 |
| RU_INN | relaxed | 27 | 1 | 17 | 44 | 28 | 0.964286 | 0.613636 | 0.750000 |
| RU_SNILS | exact | 8 | 0 | 50 | 58 | 8 | 1.000000 | 0.137931 | 0.242424 |
| RU_SNILS | relaxed | 8 | 0 | 50 | 58 | 8 | 1.000000 | 0.137931 | 0.242424 |
| RU_PASSPORT | exact | 6 | 5 | 79 | 85 | 11 | 0.545455 | 0.070588 | 0.125000 |
| RU_PASSPORT | relaxed | 9 | 2 | 76 | 85 | 11 | 0.818182 | 0.105882 | 0.187500 |
| RU_OMS | exact | 18 | 17 | 29 | 47 | 35 | 0.514286 | 0.382979 | 0.439024 |
| RU_OMS | relaxed | 18 | 17 | 29 | 47 | 35 | 0.514286 | 0.382979 | 0.439024 |

### nestedIpAligned / full

Processed: 2839; scored gold: 1910.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 626 | 134 | 1284 | 1910 | 760 | 0.823684 | 0.327749 | 0.468914 |
| micro | relaxed | 665 | 95 | 1245 | 1910 | 760 | 0.875000 | 0.348168 | 0.498127 |
| EMAIL_ADDRESS | exact | 119 | 11 | 102 | 221 | 130 | 0.915385 | 0.538462 | 0.678063 |
| EMAIL_ADDRESS | relaxed | 129 | 1 | 92 | 221 | 130 | 0.992308 | 0.583710 | 0.735043 |
| PHONE_NUMBER | exact | 87 | 6 | 84 | 171 | 93 | 0.935484 | 0.508772 | 0.659091 |
| PHONE_NUMBER | relaxed | 93 | 0 | 78 | 171 | 93 | 1.000000 | 0.543860 | 0.704545 |
| PAYMENT_CARD | exact | 61 | 22 | 142 | 203 | 83 | 0.734940 | 0.300493 | 0.426573 |
| PAYMENT_CARD | relaxed | 61 | 22 | 142 | 203 | 83 | 0.734940 | 0.300493 | 0.426573 |
| IP_ADDRESS | exact | 153 | 3 | 12 | 165 | 156 | 0.980769 | 0.927273 | 0.953271 |
| IP_ADDRESS | relaxed | 153 | 3 | 12 | 165 | 156 | 0.980769 | 0.927273 | 0.953271 |
| RU_INN | exact | 97 | 2 | 166 | 263 | 99 | 0.979798 | 0.368821 | 0.535912 |
| RU_INN | relaxed | 97 | 2 | 166 | 263 | 99 | 0.979798 | 0.368821 | 0.535912 |
| RU_SNILS | exact | 32 | 0 | 191 | 223 | 32 | 1.000000 | 0.143498 | 0.250980 |
| RU_SNILS | relaxed | 32 | 0 | 191 | 223 | 32 | 1.000000 | 0.143498 | 0.250980 |
| RU_PASSPORT | exact | 5 | 26 | 489 | 494 | 31 | 0.161290 | 0.010121 | 0.019048 |
| RU_PASSPORT | relaxed | 28 | 3 | 466 | 494 | 31 | 0.903226 | 0.056680 | 0.106667 |
| RU_OMS | exact | 72 | 64 | 98 | 170 | 136 | 0.529412 | 0.423529 | 0.470588 |
| RU_OMS | relaxed | 72 | 64 | 98 | 170 | 136 | 0.529412 | 0.423529 | 0.470588 |

### nestedIpAligned / tuning

Processed: 2109; scored gold: 1471.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 486 | 100 | 985 | 1471 | 586 | 0.829352 | 0.330387 | 0.472533 |
| micro | relaxed | 517 | 69 | 954 | 1471 | 586 | 0.882253 | 0.351462 | 0.502674 |
| EMAIL_ADDRESS | exact | 99 | 10 | 90 | 189 | 109 | 0.908257 | 0.523810 | 0.664430 |
| EMAIL_ADDRESS | relaxed | 108 | 1 | 81 | 189 | 109 | 0.990826 | 0.571429 | 0.724832 |
| PHONE_NUMBER | exact | 72 | 5 | 67 | 139 | 77 | 0.935065 | 0.517986 | 0.666667 |
| PHONE_NUMBER | relaxed | 77 | 0 | 62 | 139 | 77 | 1.000000 | 0.553957 | 0.712963 |
| PAYMENT_CARD | exact | 45 | 16 | 94 | 139 | 61 | 0.737705 | 0.323741 | 0.450000 |
| PAYMENT_CARD | relaxed | 45 | 16 | 94 | 139 | 61 | 0.737705 | 0.323741 | 0.450000 |
| IP_ADDRESS | exact | 120 | 3 | 11 | 131 | 123 | 0.975610 | 0.916031 | 0.944882 |
| IP_ADDRESS | relaxed | 120 | 3 | 11 | 131 | 123 | 0.975610 | 0.916031 | 0.944882 |
| RU_INN | exact | 70 | 1 | 124 | 194 | 71 | 0.985915 | 0.360825 | 0.528302 |
| RU_INN | relaxed | 70 | 1 | 124 | 194 | 71 | 0.985915 | 0.360825 | 0.528302 |
| RU_SNILS | exact | 24 | 0 | 141 | 165 | 24 | 1.000000 | 0.145455 | 0.253968 |
| RU_SNILS | relaxed | 24 | 0 | 141 | 165 | 24 | 1.000000 | 0.145455 | 0.253968 |
| RU_PASSPORT | exact | 2 | 18 | 389 | 391 | 20 | 0.100000 | 0.005115 | 0.009732 |
| RU_PASSPORT | relaxed | 19 | 1 | 372 | 391 | 20 | 0.950000 | 0.048593 | 0.092457 |
| RU_OMS | exact | 54 | 47 | 69 | 123 | 101 | 0.534653 | 0.439024 | 0.482143 |
| RU_OMS | relaxed | 54 | 47 | 69 | 123 | 101 | 0.534653 | 0.439024 | 0.482143 |

### nestedIpAligned / evaluation

Processed: 730; scored gold: 439.

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 140 | 34 | 299 | 439 | 174 | 0.804598 | 0.318907 | 0.456770 |
| micro | relaxed | 148 | 26 | 291 | 439 | 174 | 0.850575 | 0.337130 | 0.482871 |
| EMAIL_ADDRESS | exact | 20 | 1 | 12 | 32 | 21 | 0.952381 | 0.625000 | 0.754717 |
| EMAIL_ADDRESS | relaxed | 21 | 0 | 11 | 32 | 21 | 1.000000 | 0.656250 | 0.792453 |
| PHONE_NUMBER | exact | 15 | 1 | 17 | 32 | 16 | 0.937500 | 0.468750 | 0.625000 |
| PHONE_NUMBER | relaxed | 16 | 0 | 16 | 32 | 16 | 1.000000 | 0.500000 | 0.666667 |
| PAYMENT_CARD | exact | 16 | 6 | 48 | 64 | 22 | 0.727273 | 0.250000 | 0.372093 |
| PAYMENT_CARD | relaxed | 16 | 6 | 48 | 64 | 22 | 0.727273 | 0.250000 | 0.372093 |
| IP_ADDRESS | exact | 33 | 0 | 1 | 34 | 33 | 1.000000 | 0.970588 | 0.985075 |
| IP_ADDRESS | relaxed | 33 | 0 | 1 | 34 | 33 | 1.000000 | 0.970588 | 0.985075 |
| RU_INN | exact | 27 | 1 | 42 | 69 | 28 | 0.964286 | 0.391304 | 0.556701 |
| RU_INN | relaxed | 27 | 1 | 42 | 69 | 28 | 0.964286 | 0.391304 | 0.556701 |
| RU_SNILS | exact | 8 | 0 | 50 | 58 | 8 | 1.000000 | 0.137931 | 0.242424 |
| RU_SNILS | relaxed | 8 | 0 | 50 | 58 | 8 | 1.000000 | 0.137931 | 0.242424 |
| RU_PASSPORT | exact | 3 | 8 | 100 | 103 | 11 | 0.272727 | 0.029126 | 0.052632 |
| RU_PASSPORT | relaxed | 9 | 2 | 94 | 103 | 11 | 0.818182 | 0.087379 | 0.157895 |
| RU_OMS | exact | 18 | 17 | 29 | 47 | 35 | 0.514286 | 0.382979 | 0.439024 |
| RU_OMS | relaxed | 18 | 17 | 29 | 47 | 35 | 0.514286 | 0.382979 | 0.439024 |

### Product adjustments и independent IP reference

| Правило | Версия | Full count |
| --- | --- | --- |
| LEGAL_ENTITY_INN_TAXONOMY_MISMATCH | 1 | 107 |
| PASSPORT_SERIES_NUMBER_MERGE | 1 | 104 |

Reference: `redmadrobot-ip-canonical-v2`; SHA-256 `47aaf409f74c7d3fb3787a895ea73b7944034f70134ea2586da56267ac4ea5c8`. Added IP spans: 10; normalized endpoint spans: 8.

### Безопасные source-aligned diagnostics, full

exact: totals

| Bucket | Count |
| --- | --- |
| NO_OVERLAPPING_FINDING | 1216 |
| SPAN_MISMATCH | 65 |
| TYPE_MISMATCH | 5 |
| EXTRA_PREDICTION | 146 |

Публикуемый breakdown (floor 5; отсутствующие buckets не восстанавливаются):

| Bucket | Тип | Count |
| --- | --- | --- |
| NO_OVERLAPPING_FINDING | EMAIL_ADDRESS | 92 |
| NO_OVERLAPPING_FINDING | PHONE_NUMBER | 78 |
| NO_OVERLAPPING_FINDING | PAYMENT_CARD | 141 |
| NO_OVERLAPPING_FINDING | IP_ADDRESS | 11 |
| NO_OVERLAPPING_FINDING | RU_INN | 163 |
| NO_OVERLAPPING_FINDING | RU_SNILS | 191 |
| NO_OVERLAPPING_FINDING | RU_PASSPORT | 443 |
| NO_OVERLAPPING_FINDING | RU_OMS | 97 |
| SPAN_MISMATCH | EMAIL_ADDRESS | 10 |
| SPAN_MISMATCH | PHONE_NUMBER | 6 |
| SPAN_MISMATCH | RU_PASSPORT | 46 |
| EXTRA_PREDICTION | EMAIL_ADDRESS | 11 |
| EXTRA_PREDICTION | PHONE_NUMBER | 6 |
| EXTRA_PREDICTION | PAYMENT_CARD | 22 |
| EXTRA_PREDICTION | IP_ADDRESS | 15 |
| EXTRA_PREDICTION | RU_PASSPORT | 26 |
| EXTRA_PREDICTION | RU_OMS | 64 |

relaxed: totals

| Bucket | Count |
| --- | --- |
| NO_OVERLAPPING_FINDING | 1216 |
| SPAN_MISMATCH | 23 |
| TYPE_MISMATCH | 5 |
| EXTRA_PREDICTION | 104 |

Публикуемый breakdown (floor 5; отсутствующие buckets не восстанавливаются):

| Bucket | Тип | Count |
| --- | --- | --- |
| NO_OVERLAPPING_FINDING | EMAIL_ADDRESS | 92 |
| NO_OVERLAPPING_FINDING | PHONE_NUMBER | 78 |
| NO_OVERLAPPING_FINDING | PAYMENT_CARD | 141 |
| NO_OVERLAPPING_FINDING | IP_ADDRESS | 11 |
| NO_OVERLAPPING_FINDING | RU_INN | 163 |
| NO_OVERLAPPING_FINDING | RU_SNILS | 191 |
| NO_OVERLAPPING_FINDING | RU_PASSPORT | 443 |
| NO_OVERLAPPING_FINDING | RU_OMS | 97 |
| SPAN_MISMATCH | RU_PASSPORT | 23 |
| EXTRA_PREDICTION | PAYMENT_CARD | 22 |
| EXTRA_PREDICTION | IP_ADDRESS | 12 |
| EXTRA_PREDICTION | RU_OMS | 64 |

### Source-aligned evidence contribution, full

| Тип | Evidence | Pred | Exact TP | Exact FP | Relaxed TP | Relaxed FP |
| --- | --- | --- | --- | --- | --- | --- |
| EMAIL_ADDRESS | FORMAT_ONLY | 130 | 119 | 11 | 129 | 1 |
| PHONE_NUMBER | CONTEXTUAL | 8 | 2 | 6 | 8 | 0 |
| PHONE_NUMBER | FORMAT_ONLY | 85 | 85 | 0 | 85 | 0 |
| PAYMENT_CARD | VALIDATED | 83 | 61 | 22 | 61 | 22 |
| IP_ADDRESS | VALIDATED | 156 | 141 | 15 | 144 | 12 |
| RU_INN | VALIDATED | 99 | 97 | 2 | 97 | 2 |
| RU_SNILS | VALIDATED | 1 | 1 | 0 | 1 | 0 |
| RU_SNILS | CONTEXTUAL | 31 | 31 | 0 | 31 | 0 |
| RU_PASSPORT | CONTEXTUAL | 31 | 5 | 26 | 28 | 3 |
| RU_OMS | VALIDATED | 77 | 14 | 63 | 14 | 63 |
| RU_OMS | CONTEXTUAL | 59 | 58 | 1 | 58 | 1 |

## HiveTrace: полная таблица

Enabled types: `EMAIL_ADDRESS, PHONE_NUMBER, PAYMENT_CARD, RU_INN, RU_SNILS, RU_PASSPORT`. `not covered`: `IP_ADDRESS, IBAN, RU_OMS`.

| Scope | Total | Processed | Rejected | Source spans | Mapped | Unsupported | Product | INN adjustment | Clean FPR n/d | FPR |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| entity | 910 | 910 | 0 | 910 | 420 | 490 | 420 | 0 | 0/0 | N/A |
| domain | 900 | 900 | 0 | 757 | 397 | 360 | 369 | 28 | 0/378 | 0.000000 |
| domain/L-CHAT | 100 | 100 | 0 | 50 | 34 | 16 | 34 | 0 | 0/50 | 0.000000 |
| domain/L-DIALOG | 100 | 100 | 0 | 54 | 37 | 17 | 37 | 0 | 0/50 | 0.000000 |
| domain/S-AUTO | 100 | 100 | 0 | 83 | 36 | 47 | 26 | 10 | 0/42 | 0.000000 |
| domain/S-BANK | 100 | 100 | 0 | 85 | 55 | 30 | 55 | 0 | 0/35 | 0.000000 |
| domain/S-DELIVERY | 100 | 100 | 0 | 101 | 25 | 76 | 25 | 0 | 0/49 | 0.000000 |
| domain/S-HR | 100 | 100 | 0 | 100 | 67 | 33 | 67 | 0 | 0/35 | 0.000000 |
| domain/S-RE | 100 | 100 | 0 | 111 | 56 | 55 | 48 | 8 | 0/34 | 0.000000 |
| domain/S-SUPPORT | 100 | 100 | 0 | 70 | 37 | 33 | 37 | 0 | 0/45 | 0.000000 |
| domain/S-TELECOM | 100 | 100 | 0 | 103 | 50 | 53 | 40 | 10 | 0/38 | 0.000000 |
| Full | 1810 | 1810 | 0 | 1667 | 817 | 850 | 789 | 28 | 0/378 | 0.000000 |

### entity / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 361 | 21 | 59 | 420 | 382 | 0.945026 | 0.859524 | 0.900249 |
| micro | relaxed | 361 | 21 | 59 | 420 | 382 | 0.945026 | 0.859524 | 0.900249 |
| EMAIL_ADDRESS | exact | 69 | 0 | 1 | 70 | 69 | 1.000000 | 0.985714 | 0.992806 |
| EMAIL_ADDRESS | relaxed | 69 | 0 | 1 | 70 | 69 | 1.000000 | 0.985714 | 0.992806 |
| PHONE_NUMBER | exact | 62 | 7 | 8 | 70 | 69 | 0.898551 | 0.885714 | 0.892086 |
| PHONE_NUMBER | relaxed | 62 | 7 | 8 | 70 | 69 | 0.898551 | 0.885714 | 0.892086 |
| PAYMENT_CARD | exact | 70 | 14 | 0 | 70 | 84 | 0.833333 | 1.000000 | 0.909091 |
| PAYMENT_CARD | relaxed | 70 | 14 | 0 | 70 | 84 | 0.833333 | 1.000000 | 0.909091 |
| RU_INN | exact | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 20 | 0 | 50 | 70 | 20 | 1.000000 | 0.285714 | 0.444444 |
| RU_PASSPORT | relaxed | 20 | 0 | 50 | 70 | 20 | 1.000000 | 0.285714 | 0.444444 |

### entity / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 361 | 21 | 59 | 420 | 382 | 0.945026 | 0.859524 | 0.900249 |
| micro | relaxed | 361 | 21 | 59 | 420 | 382 | 0.945026 | 0.859524 | 0.900249 |
| EMAIL_ADDRESS | exact | 69 | 0 | 1 | 70 | 69 | 1.000000 | 0.985714 | 0.992806 |
| EMAIL_ADDRESS | relaxed | 69 | 0 | 1 | 70 | 69 | 1.000000 | 0.985714 | 0.992806 |
| PHONE_NUMBER | exact | 62 | 7 | 8 | 70 | 69 | 0.898551 | 0.885714 | 0.892086 |
| PHONE_NUMBER | relaxed | 62 | 7 | 8 | 70 | 69 | 0.898551 | 0.885714 | 0.892086 |
| PAYMENT_CARD | exact | 70 | 14 | 0 | 70 | 84 | 0.833333 | 1.000000 | 0.909091 |
| PAYMENT_CARD | relaxed | 70 | 14 | 0 | 70 | 84 | 0.833333 | 1.000000 | 0.909091 |
| RU_INN | exact | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 70 | 0 | 0 | 70 | 70 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 20 | 0 | 50 | 70 | 20 | 1.000000 | 0.285714 | 0.444444 |
| RU_PASSPORT | relaxed | 20 | 0 | 50 | 70 | 20 | 1.000000 | 0.285714 | 0.444444 |

### domain / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 359 | 16 | 38 | 397 | 375 | 0.957333 | 0.904282 | 0.930052 |
| micro | relaxed | 362 | 13 | 35 | 397 | 375 | 0.965333 | 0.911839 | 0.937824 |
| EMAIL_ADDRESS | exact | 99 | 3 | 4 | 103 | 102 | 0.970588 | 0.961165 | 0.965854 |
| EMAIL_ADDRESS | relaxed | 102 | 0 | 1 | 103 | 102 | 1.000000 | 0.990291 | 0.995122 |
| PHONE_NUMBER | exact | 147 | 5 | 0 | 147 | 152 | 0.967105 | 1.000000 | 0.983278 |
| PHONE_NUMBER | relaxed | 147 | 5 | 0 | 147 | 152 | 0.967105 | 1.000000 | 0.983278 |
| PAYMENT_CARD | exact | 22 | 7 | 0 | 22 | 29 | 0.758621 | 1.000000 | 0.862745 |
| PAYMENT_CARD | relaxed | 22 | 7 | 0 | 22 | 29 | 0.758621 | 1.000000 | 0.862745 |
| RU_INN | exact | 20 | 0 | 28 | 48 | 20 | 1.000000 | 0.416667 | 0.588235 |
| RU_INN | relaxed | 20 | 0 | 28 | 48 | 20 | 1.000000 | 0.416667 | 0.588235 |
| RU_SNILS | exact | 27 | 0 | 0 | 27 | 27 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 27 | 0 | 0 | 27 | 27 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 44 | 1 | 6 | 50 | 45 | 0.977778 | 0.880000 | 0.926316 |
| RU_PASSPORT | relaxed | 44 | 1 | 6 | 50 | 45 | 0.977778 | 0.880000 | 0.926316 |

### domain / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 359 | 16 | 10 | 369 | 375 | 0.957333 | 0.972900 | 0.965054 |
| micro | relaxed | 362 | 13 | 7 | 369 | 375 | 0.965333 | 0.981030 | 0.973118 |
| EMAIL_ADDRESS | exact | 99 | 3 | 4 | 103 | 102 | 0.970588 | 0.961165 | 0.965854 |
| EMAIL_ADDRESS | relaxed | 102 | 0 | 1 | 103 | 102 | 1.000000 | 0.990291 | 0.995122 |
| PHONE_NUMBER | exact | 147 | 5 | 0 | 147 | 152 | 0.967105 | 1.000000 | 0.983278 |
| PHONE_NUMBER | relaxed | 147 | 5 | 0 | 147 | 152 | 0.967105 | 1.000000 | 0.983278 |
| PAYMENT_CARD | exact | 22 | 7 | 0 | 22 | 29 | 0.758621 | 1.000000 | 0.862745 |
| PAYMENT_CARD | relaxed | 22 | 7 | 0 | 22 | 29 | 0.758621 | 1.000000 | 0.862745 |
| RU_INN | exact | 20 | 0 | 0 | 20 | 20 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 20 | 0 | 0 | 20 | 20 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 27 | 0 | 0 | 27 | 27 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 27 | 0 | 0 | 27 | 27 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 44 | 1 | 6 | 50 | 45 | 0.977778 | 0.880000 | 0.926316 |
| RU_PASSPORT | relaxed | 44 | 1 | 6 | 50 | 45 | 0.977778 | 0.880000 | 0.926316 |

### domain/L-CHAT / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 34 | 0 | 0 | 34 | 34 | 1.000000 | 1.000000 | 1.000000 |
| micro | relaxed | 34 | 0 | 0 | 34 | 34 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PAYMENT_CARD | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/L-CHAT / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 34 | 0 | 0 | 34 | 34 | 1.000000 | 1.000000 | 1.000000 |
| micro | relaxed | 34 | 0 | 0 | 34 | 34 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PAYMENT_CARD | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/L-DIALOG / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 37 | 2 | 0 | 37 | 39 | 0.948718 | 1.000000 | 0.973684 |
| micro | relaxed | 37 | 2 | 0 | 37 | 39 | 0.948718 | 1.000000 | 0.973684 |
| EMAIL_ADDRESS | exact | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 14 | 2 | 0 | 14 | 16 | 0.875000 | 1.000000 | 0.933333 |
| PHONE_NUMBER | relaxed | 14 | 2 | 0 | 14 | 16 | 0.875000 | 1.000000 | 0.933333 |
| PAYMENT_CARD | exact | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | relaxed | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/L-DIALOG / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 37 | 2 | 0 | 37 | 39 | 0.948718 | 1.000000 | 0.973684 |
| micro | relaxed | 37 | 2 | 0 | 37 | 39 | 0.948718 | 1.000000 | 0.973684 |
| EMAIL_ADDRESS | exact | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 14 | 2 | 0 | 14 | 16 | 0.875000 | 1.000000 | 0.933333 |
| PHONE_NUMBER | relaxed | 14 | 2 | 0 | 14 | 16 | 0.875000 | 1.000000 | 0.933333 |
| PAYMENT_CARD | exact | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | relaxed | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/S-AUTO / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 26 | 1 | 10 | 36 | 27 | 0.962963 | 0.722222 | 0.825397 |
| micro | relaxed | 26 | 1 | 10 | 36 | 27 | 0.962963 | 0.722222 | 0.825397 |
| EMAIL_ADDRESS | exact | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 16 | 0 | 0 | 16 | 16 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 16 | 0 | 0 | 16 | 16 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 1 | 1 | 0 | 1 | 2 | 0.500000 | 1.000000 | 0.666667 |
| PAYMENT_CARD | relaxed | 1 | 1 | 0 | 1 | 2 | 0.500000 | 1.000000 | 0.666667 |
| RU_INN | exact | 1 | 0 | 10 | 11 | 1 | 1.000000 | 0.090909 | 0.166667 |
| RU_INN | relaxed | 1 | 0 | 10 | 11 | 1 | 1.000000 | 0.090909 | 0.166667 |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/S-AUTO / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 26 | 1 | 0 | 26 | 27 | 0.962963 | 1.000000 | 0.981132 |
| micro | relaxed | 26 | 1 | 0 | 26 | 27 | 0.962963 | 1.000000 | 0.981132 |
| EMAIL_ADDRESS | exact | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 16 | 0 | 0 | 16 | 16 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 16 | 0 | 0 | 16 | 16 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 1 | 1 | 0 | 1 | 2 | 0.500000 | 1.000000 | 0.666667 |
| PAYMENT_CARD | relaxed | 1 | 1 | 0 | 1 | 2 | 0.500000 | 1.000000 | 0.666667 |
| RU_INN | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/S-BANK / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 52 | 0 | 3 | 55 | 52 | 1.000000 | 0.945455 | 0.971963 |
| micro | relaxed | 52 | 0 | 3 | 55 | 52 | 1.000000 | 0.945455 | 0.971963 |
| EMAIL_ADDRESS | exact | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | relaxed | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | exact | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 8 | 0 | 3 | 11 | 8 | 1.000000 | 0.727273 | 0.842105 |
| RU_PASSPORT | relaxed | 8 | 0 | 3 | 11 | 8 | 1.000000 | 0.727273 | 0.842105 |

### domain/S-BANK / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 52 | 0 | 3 | 55 | 52 | 1.000000 | 0.945455 | 0.971963 |
| micro | relaxed | 52 | 0 | 3 | 55 | 52 | 1.000000 | 0.945455 | 0.971963 |
| EMAIL_ADDRESS | exact | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 10 | 0 | 0 | 10 | 10 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | relaxed | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | exact | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 9 | 0 | 0 | 9 | 9 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 8 | 0 | 0 | 8 | 8 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 8 | 0 | 3 | 11 | 8 | 1.000000 | 0.727273 | 0.842105 |
| RU_PASSPORT | relaxed | 8 | 0 | 3 | 11 | 8 | 1.000000 | 0.727273 | 0.842105 |

### domain/S-DELIVERY / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| micro | relaxed | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| EMAIL_ADDRESS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PHONE_NUMBER | exact | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PAYMENT_CARD | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/S-DELIVERY / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| micro | relaxed | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| EMAIL_ADDRESS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PHONE_NUMBER | exact | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 25 | 0 | 0 | 25 | 25 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PAYMENT_CARD | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/S-HR / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 66 | 5 | 1 | 67 | 71 | 0.929577 | 0.985075 | 0.956522 |
| micro | relaxed | 67 | 4 | 0 | 67 | 71 | 0.943662 | 1.000000 | 0.971014 |
| EMAIL_ADDRESS | exact | 12 | 1 | 1 | 13 | 13 | 0.923077 | 0.923077 | 0.923077 |
| EMAIL_ADDRESS | relaxed | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 13 | 3 | 0 | 13 | 16 | 0.812500 | 1.000000 | 0.896552 |
| PHONE_NUMBER | relaxed | 13 | 3 | 0 | 13 | 16 | 0.812500 | 1.000000 | 0.896552 |
| PAYMENT_CARD | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 19 | 0 | 0 | 19 | 19 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 19 | 0 | 0 | 19 | 19 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 21 | 1 | 0 | 21 | 22 | 0.954545 | 1.000000 | 0.976744 |
| RU_PASSPORT | relaxed | 21 | 1 | 0 | 21 | 22 | 0.954545 | 1.000000 | 0.976744 |

### domain/S-HR / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 66 | 5 | 1 | 67 | 71 | 0.929577 | 0.985075 | 0.956522 |
| micro | relaxed | 67 | 4 | 0 | 67 | 71 | 0.943662 | 1.000000 | 0.971014 |
| EMAIL_ADDRESS | exact | 12 | 1 | 1 | 13 | 13 | 0.923077 | 0.923077 | 0.923077 |
| EMAIL_ADDRESS | relaxed | 13 | 0 | 0 | 13 | 13 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 13 | 3 | 0 | 13 | 16 | 0.812500 | 1.000000 | 0.896552 |
| PHONE_NUMBER | relaxed | 13 | 3 | 0 | 13 | 16 | 0.812500 | 1.000000 | 0.896552 |
| PAYMENT_CARD | exact | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | relaxed | 1 | 0 | 0 | 1 | 1 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 19 | 0 | 0 | 19 | 19 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 19 | 0 | 0 | 19 | 19 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 21 | 1 | 0 | 21 | 22 | 0.954545 | 1.000000 | 0.976744 |
| RU_PASSPORT | relaxed | 21 | 1 | 0 | 21 | 22 | 0.954545 | 1.000000 | 0.976744 |

### domain/S-RE / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 46 | 5 | 10 | 56 | 51 | 0.901961 | 0.821429 | 0.859813 |
| micro | relaxed | 47 | 4 | 9 | 56 | 51 | 0.921569 | 0.839286 | 0.878505 |
| EMAIL_ADDRESS | exact | 11 | 1 | 1 | 12 | 12 | 0.916667 | 0.916667 | 0.916667 |
| EMAIL_ADDRESS | relaxed | 12 | 0 | 0 | 12 | 12 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 18 | 0 | 0 | 18 | 18 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 18 | 0 | 0 | 18 | 18 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 4 | 0 | 0 | 4 | 0.000000 | N/A | 0.000000 |
| PAYMENT_CARD | relaxed | 0 | 4 | 0 | 0 | 4 | 0.000000 | N/A | 0.000000 |
| RU_INN | exact | 6 | 0 | 8 | 14 | 6 | 1.000000 | 0.428571 | 0.600000 |
| RU_INN | relaxed | 6 | 0 | 8 | 14 | 6 | 1.000000 | 0.428571 | 0.600000 |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 11 | 0 | 1 | 12 | 11 | 1.000000 | 0.916667 | 0.956522 |
| RU_PASSPORT | relaxed | 11 | 0 | 1 | 12 | 11 | 1.000000 | 0.916667 | 0.956522 |

### domain/S-RE / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 46 | 5 | 2 | 48 | 51 | 0.901961 | 0.958333 | 0.929293 |
| micro | relaxed | 47 | 4 | 1 | 48 | 51 | 0.921569 | 0.979167 | 0.949495 |
| EMAIL_ADDRESS | exact | 11 | 1 | 1 | 12 | 12 | 0.916667 | 0.916667 | 0.916667 |
| EMAIL_ADDRESS | relaxed | 12 | 0 | 0 | 12 | 12 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 18 | 0 | 0 | 18 | 18 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 18 | 0 | 0 | 18 | 18 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 4 | 0 | 0 | 4 | 0.000000 | N/A | 0.000000 |
| PAYMENT_CARD | relaxed | 0 | 4 | 0 | 0 | 4 | 0.000000 | N/A | 0.000000 |
| RU_INN | exact | 6 | 0 | 0 | 6 | 6 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 6 | 0 | 0 | 6 | 6 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 11 | 0 | 1 | 12 | 11 | 1.000000 | 0.916667 | 0.956522 |
| RU_PASSPORT | relaxed | 11 | 0 | 1 | 12 | 11 | 1.000000 | 0.916667 | 0.956522 |

### domain/S-SUPPORT / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 35 | 1 | 2 | 37 | 36 | 0.972222 | 0.945946 | 0.958904 |
| micro | relaxed | 36 | 0 | 1 | 37 | 36 | 1.000000 | 0.972973 | 0.986301 |
| EMAIL_ADDRESS | exact | 18 | 1 | 2 | 20 | 19 | 0.947368 | 0.900000 | 0.923077 |
| EMAIL_ADDRESS | relaxed | 19 | 0 | 1 | 20 | 19 | 1.000000 | 0.950000 | 0.974359 |
| PHONE_NUMBER | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PAYMENT_CARD | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/S-SUPPORT / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 35 | 1 | 2 | 37 | 36 | 0.972222 | 0.945946 | 0.958904 |
| micro | relaxed | 36 | 0 | 1 | 37 | 36 | 1.000000 | 0.972973 | 0.986301 |
| EMAIL_ADDRESS | exact | 18 | 1 | 2 | 20 | 19 | 0.947368 | 0.900000 | 0.923077 |
| EMAIL_ADDRESS | relaxed | 19 | 0 | 1 | 20 | 19 | 1.000000 | 0.950000 | 0.974359 |
| PHONE_NUMBER | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| PAYMENT_CARD | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_INN | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |

### domain/S-TELECOM / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 38 | 2 | 12 | 50 | 40 | 0.950000 | 0.760000 | 0.844444 |
| micro | relaxed | 38 | 2 | 12 | 50 | 40 | 0.950000 | 0.760000 | 0.844444 |
| EMAIL_ADDRESS | exact | 12 | 0 | 0 | 12 | 12 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 12 | 0 | 0 | 12 | 12 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 1 | 2 | 0 | 1 | 3 | 0.333333 | 1.000000 | 0.500000 |
| PAYMENT_CARD | relaxed | 1 | 2 | 0 | 1 | 3 | 0.333333 | 1.000000 | 0.500000 |
| RU_INN | exact | 4 | 0 | 10 | 14 | 4 | 1.000000 | 0.285714 | 0.444444 |
| RU_INN | relaxed | 4 | 0 | 10 | 14 | 4 | 1.000000 | 0.285714 | 0.444444 |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 4 | 0 | 2 | 6 | 4 | 1.000000 | 0.666667 | 0.800000 |
| RU_PASSPORT | relaxed | 4 | 0 | 2 | 6 | 4 | 1.000000 | 0.666667 | 0.800000 |

### domain/S-TELECOM / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 38 | 2 | 2 | 40 | 40 | 0.950000 | 0.950000 | 0.950000 |
| micro | relaxed | 38 | 2 | 2 | 40 | 40 | 0.950000 | 0.950000 | 0.950000 |
| EMAIL_ADDRESS | exact | 12 | 0 | 0 | 12 | 12 | 1.000000 | 1.000000 | 1.000000 |
| EMAIL_ADDRESS | relaxed | 12 | 0 | 0 | 12 | 12 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | exact | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PHONE_NUMBER | relaxed | 17 | 0 | 0 | 17 | 17 | 1.000000 | 1.000000 | 1.000000 |
| PAYMENT_CARD | exact | 1 | 2 | 0 | 1 | 3 | 0.333333 | 1.000000 | 0.500000 |
| PAYMENT_CARD | relaxed | 1 | 2 | 0 | 1 | 3 | 0.333333 | 1.000000 | 0.500000 |
| RU_INN | exact | 4 | 0 | 0 | 4 | 4 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 4 | 0 | 0 | 4 | 4 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_SNILS | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| RU_PASSPORT | exact | 4 | 0 | 2 | 6 | 4 | 1.000000 | 0.666667 | 0.800000 |
| RU_PASSPORT | relaxed | 4 | 0 | 2 | 6 | 4 | 1.000000 | 0.666667 | 0.800000 |

### Full / sourceAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 720 | 37 | 97 | 817 | 757 | 0.951123 | 0.881273 | 0.914867 |
| micro | relaxed | 723 | 34 | 94 | 817 | 757 | 0.955086 | 0.884945 | 0.918679 |
| EMAIL_ADDRESS | exact | 168 | 3 | 5 | 173 | 171 | 0.982456 | 0.971098 | 0.976744 |
| EMAIL_ADDRESS | relaxed | 171 | 0 | 2 | 173 | 171 | 1.000000 | 0.988439 | 0.994186 |
| PHONE_NUMBER | exact | 209 | 12 | 8 | 217 | 221 | 0.945701 | 0.963134 | 0.954338 |
| PHONE_NUMBER | relaxed | 209 | 12 | 8 | 217 | 221 | 0.945701 | 0.963134 | 0.954338 |
| PAYMENT_CARD | exact | 92 | 21 | 0 | 92 | 113 | 0.814159 | 1.000000 | 0.897561 |
| PAYMENT_CARD | relaxed | 92 | 21 | 0 | 92 | 113 | 0.814159 | 1.000000 | 0.897561 |
| RU_INN | exact | 90 | 0 | 28 | 118 | 90 | 1.000000 | 0.762712 | 0.865385 |
| RU_INN | relaxed | 90 | 0 | 28 | 118 | 90 | 1.000000 | 0.762712 | 0.865385 |
| RU_SNILS | exact | 97 | 0 | 0 | 97 | 97 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 97 | 0 | 0 | 97 | 97 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 64 | 1 | 56 | 120 | 65 | 0.984615 | 0.533333 | 0.691892 |
| RU_PASSPORT | relaxed | 64 | 1 | 56 | 120 | 65 | 0.984615 | 0.533333 | 0.691892 |

### Full / productAligned

| Тип | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | exact | 720 | 37 | 69 | 789 | 757 | 0.951123 | 0.912548 | 0.931436 |
| micro | relaxed | 723 | 34 | 66 | 789 | 757 | 0.955086 | 0.916350 | 0.935317 |
| EMAIL_ADDRESS | exact | 168 | 3 | 5 | 173 | 171 | 0.982456 | 0.971098 | 0.976744 |
| EMAIL_ADDRESS | relaxed | 171 | 0 | 2 | 173 | 171 | 1.000000 | 0.988439 | 0.994186 |
| PHONE_NUMBER | exact | 209 | 12 | 8 | 217 | 221 | 0.945701 | 0.963134 | 0.954338 |
| PHONE_NUMBER | relaxed | 209 | 12 | 8 | 217 | 221 | 0.945701 | 0.963134 | 0.954338 |
| PAYMENT_CARD | exact | 92 | 21 | 0 | 92 | 113 | 0.814159 | 1.000000 | 0.897561 |
| PAYMENT_CARD | relaxed | 92 | 21 | 0 | 92 | 113 | 0.814159 | 1.000000 | 0.897561 |
| RU_INN | exact | 90 | 0 | 0 | 90 | 90 | 1.000000 | 1.000000 | 1.000000 |
| RU_INN | relaxed | 90 | 0 | 0 | 90 | 90 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | exact | 97 | 0 | 0 | 97 | 97 | 1.000000 | 1.000000 | 1.000000 |
| RU_SNILS | relaxed | 97 | 0 | 0 | 97 | 97 | 1.000000 | 1.000000 | 1.000000 |
| RU_PASSPORT | exact | 64 | 1 | 56 | 120 | 65 | 0.984615 | 0.533333 | 0.691892 |
| RU_PASSPORT | relaxed | 64 | 1 | 56 | 120 | 65 | 0.984615 | 0.533333 | 0.691892 |

## AdvPIIBench: полная таблица

Enabled types: `EMAIL_ADDRESS, PHONE_NUMBER, PAYMENT_CARD, IBAN`. `not covered`: `IP_ADDRESS, RU_INN, RU_SNILS, RU_PASSPORT, RU_OMS`. `overlap` JSON показан как `relaxed`; исходные artifacts не изменены.

| Subset | Document FPR n/d | FPR |
| --- | --- | --- |
| negative | 0/22560 | 0.000000 |
| hard_negative | 2/1232 | 0.001623 |

### baseline

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | sourceAligned | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | sourceAligned | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | sourceAligned | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | sourceAligned | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | 318 | 0.000000 | 0.000000 |
| PHONE_NUMBER | 337 | 0.000000 | 0.000000 |
| PAYMENT_CARD | 364 | 0.000000 | 0.000000 |
| IBAN | 300 | 0.000000 | 0.000000 |

### pii_only

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined

Rows: 72480; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 14496 | 89280 | 89280 | 14496 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 14496 | 89280 | 89280 | 14496 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 64740 | 4980 | 24540 | 89280 | 69720 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 67140 | 2580 | 22140 | 89280 | 69720 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 14496 | 21600 | 21600 | 14496 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 14496 | 21600 | 21600 | 14496 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 17940 | 2400 | 3660 | 21600 | 20340 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 20340 | 0 | 1260 | 21600 | 20340 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 22800 | 22800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 22800 | 22800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 3720 | 0 | 19080 | 22800 | 3720 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 3720 | 0 | 19080 | 22800 | 3720 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 22800 | 22800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 22800 | 22800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 21000 | 2580 | 1800 | 22800 | 23580 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 21000 | 2580 | 1800 | 22800 | 23580 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 22080 | 22080 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 22080 | 22080 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 22080 | 0 | 0 | 22080 | 22080 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 22080 | 0 | 0 | 22080 | 22080 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### negative

Rows: 22560; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| micro | sourceAligned | relaxed | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |
| EMAIL_ADDRESS | suppressed | - | - | - | - | - | - | - | - | - |
| PHONE_NUMBER | suppressed | - | - | - | - | - | - | - | - | - |
| PAYMENT_CARD | suppressed | - | - | - | - | - | - | - | - | - |
| IBAN | suppressed | - | - | - | - | - | - | - | - | - |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 22560 | N/A | N/A |

### hard_negative

Rows: 1232; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 2 | 0 | 0 | 2 | 0.000000 | N/A | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 2 | 0 | 0 | 2 | 0.000000 | N/A | 0.000000 |
| EMAIL_ADDRESS | suppressed | - | - | - | - | - | - | - | - | - |
| PHONE_NUMBER | suppressed | - | - | - | - | - | - | - | - | - |
| PAYMENT_CARD | suppressed | - | - | - | - | - | - | - | - | - |
| IBAN | suppressed | - | - | - | - | - | - | - | - | - |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1232 | N/A | N/A |

### pii_only/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### pii_only/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### pii_only/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### pii_only/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### pii_only/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### pii_only/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/char_to_word

Rows: 12080; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 10790 | 830 | 4090 | 14880 | 11620 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 11190 | 430 | 3690 | 14880 | 11620 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 2990 | 400 | 610 | 3600 | 3390 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 3390 | 0 | 210 | 3600 | 3390 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/chunking

Rows: 12080; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 10790 | 830 | 4090 | 14880 | 11620 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 11190 | 430 | 3690 | 14880 | 11620 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 2990 | 400 | 610 | 3600 | 3390 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 3390 | 0 | 210 | 3600 | 3390 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/emojify

Rows: 12080; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 10790 | 830 | 4090 | 14880 | 11620 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 11190 | 430 | 3690 | 14880 | 11620 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 2990 | 400 | 610 | 3600 | 3390 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 3390 | 0 | 210 | 3600 | 3390 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/homoglyph

Rows: 12080; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 10790 | 830 | 4090 | 14880 | 11620 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 11190 | 430 | 3690 | 14880 | 11620 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 2990 | 400 | 610 | 3600 | 3390 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 3390 | 0 | 210 | 3600 | 3390 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/invisible_chars

Rows: 12080; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 10790 | 830 | 4090 | 14880 | 11620 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 11190 | 430 | 3690 | 14880 | 11620 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 2990 | 400 | 610 | 3600 | 3390 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 3390 | 0 | 210 | 3600 | 3390 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/separators

Rows: 12080; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 2416 | 14880 | 14880 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 10790 | 830 | 4090 | 14880 | 11620 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 11190 | 430 | 3690 | 14880 | 11620 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 2416 | 3600 | 3600 | 2416 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 2990 | 400 | 610 | 3600 | 3390 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 3390 | 0 | 210 | 3600 | 3390 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 620 | 0 | 3180 | 3800 | 620 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 3800 | 3800 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 3500 | 430 | 300 | 3800 | 3930 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 3680 | 3680 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 3680 | 0 | 0 | 3680 | 3680 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+pi_category_prime+supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+pi_category_prime+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+pi_category_prime+supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+pi_category_prime+supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+pi_category_prime+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+pi_category_prime+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+pi_category_prime+supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_category_prime+supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+pi_few_shot_safe+supportive_context

Rows: 7248; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 7248 | 8928 | 8928 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 7248 | 8928 | 8928 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 7248 | 2160 | 2160 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 7248 | 2160 | 2160 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+pi_few_shot_safe+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+pi_few_shot_safe+supportive_context/chunking

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+pi_few_shot_safe+supportive_context/emojify

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+pi_few_shot_safe+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+pi_few_shot_safe+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+pi_few_shot_safe+supportive_context/separators

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_ignore_pii+supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_redacted+pi_ceo_instruct+supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_redacted+pi_ceo_instruct+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_redacted+pi_ceo_instruct+supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_redacted+pi_ceo_instruct+supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_redacted+pi_ceo_instruct+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_redacted+pi_ceo_instruct+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/affix_redacted+pi_ceo_instruct+supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_category_prime+supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_category_prime+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_category_prime+supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_category_prime+supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_category_prime+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_category_prime+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_category_prime+supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_educational_framing+supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_educational_framing+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_educational_framing+supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_educational_framing+supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_educational_framing+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_educational_framing+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_educational_framing+supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_few_shot_safe+supportive_context

Rows: 7248; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 7248 | 8928 | 8928 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 7248 | 8928 | 8928 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 7248 | 2160 | 2160 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 7248 | 2160 | 2160 | 7248 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_few_shot_safe+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_few_shot_safe+supportive_context/chunking

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_few_shot_safe+supportive_context/emojify

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_few_shot_safe+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_few_shot_safe+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_few_shot_safe+supportive_context/separators

Rows: 1208; few-shot precision caveat: yes.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 1208 | 1488 | 1488 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 1208 | 360 | 360 | 1208 | 0.000000 | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 1208 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_hypothetical+supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_hypothetical+supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_hypothetical+supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_hypothetical+supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_hypothetical+supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_hypothetical+supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/pi_hypothetical+supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/supportive_context

Rows: 7248; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 8928 | 8928 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 6474 | 498 | 2454 | 8928 | 6972 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 6714 | 258 | 2214 | 8928 | 6972 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 2160 | 2160 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 1794 | 240 | 366 | 2160 | 2034 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 2034 | 0 | 126 | 2160 | 2034 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 372 | 0 | 1908 | 2280 | 372 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 2280 | 2280 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 2100 | 258 | 180 | 2280 | 2358 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 2208 | 2208 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 2208 | 0 | 0 | 2208 | 2208 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/supportive_context/char_to_word

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/supportive_context/chunking

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/supportive_context/emojify

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/supportive_context/homoglyph

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/supportive_context/invisible_chars

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |

### combined/config/supportive_context/separators

Rows: 1208; few-shot precision caveat: no.

| Тип | View | Mode | TP | FP | FN | Gold | Pred | P | R | F1 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| micro | sourceAligned | exact | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | sourceAligned | relaxed | 0 | 0 | 1488 | 1488 | 0 | N/A | 0.000000 | 0.000000 |
| micro | comparableBaseline | exact | 1079 | 83 | 409 | 1488 | 1162 | 0.928571 | 0.725134 | 0.814340 |
| micro | comparableBaseline | relaxed | 1119 | 43 | 369 | 1488 | 1162 | 0.962995 | 0.752016 | 0.844528 |
| EMAIL_ADDRESS | sourceAligned | exact | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | sourceAligned | relaxed | 0 | 0 | 360 | 360 | 0 | N/A | 0.000000 | 0.000000 |
| EMAIL_ADDRESS | comparableBaseline | exact | 299 | 40 | 61 | 360 | 339 | 0.882006 | 0.830556 | 0.855508 |
| EMAIL_ADDRESS | comparableBaseline | relaxed | 339 | 0 | 21 | 360 | 339 | 1.000000 | 0.941667 | 0.969957 |
| PHONE_NUMBER | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PHONE_NUMBER | comparableBaseline | exact | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PHONE_NUMBER | comparableBaseline | relaxed | 62 | 0 | 318 | 380 | 62 | 1.000000 | 0.163158 | 0.280543 |
| PAYMENT_CARD | sourceAligned | exact | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | sourceAligned | relaxed | 0 | 0 | 380 | 380 | 0 | N/A | 0.000000 | 0.000000 |
| PAYMENT_CARD | comparableBaseline | exact | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| PAYMENT_CARD | comparableBaseline | relaxed | 350 | 43 | 30 | 380 | 393 | 0.890585 | 0.921053 | 0.905563 |
| IBAN | sourceAligned | exact | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | sourceAligned | relaxed | 0 | 0 | 368 | 368 | 0 | N/A | 0.000000 | 0.000000 |
| IBAN | comparableBaseline | exact | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |
| IBAN | comparableBaseline | relaxed | 368 | 0 | 0 | 368 | 368 | 1.000000 | 1.000000 | 1.000000 |

| Тип | Distinct inputs | ΔR exact, п.п. | ΔR relaxed, п.п. |
| --- | --- | --- | --- |
| micro | 1208 | -72.513441 | -75.201613 |
| EMAIL_ADDRESS | 318 | -83.055556 | -94.166667 |
| PHONE_NUMBER | 337 | -16.315789 | -16.315789 |
| PAYMENT_CARD | 330 | -92.105263 | -92.105263 |
| IBAN | 300 | -100.000000 | -100.000000 |
