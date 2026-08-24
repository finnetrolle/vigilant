# Epic 10: Повышение качества детерминированного PII-распознавания

**ID:** `EPIC-10`
**Тип:** Epic
**Статус:** Ready for implementation
**Приоритет:** High
**Суммарная оценка:** 21-29 инженерных дней
**Связанные требования:** `MVP-15`, `MVP-19`

## Контекст

EPIC-02 создал транспортно-независимый `FastPiiDetector`, canonical quality
gate и внешний RedMadRobot benchmark. На закреплённой revision RedMadRobot
текущая реализация обрабатывает 2 839 из 2 841 cases и показывает:

- exact: precision `0.786765`, recall `0.225263`, F1 `0.350245`;
- relaxed: precision `0.829044`, recall `0.237368`, F1 `0.369067`.

Разбор mapped gold spans показал несколько разных классов причин: greedy
candidate boundaries у IP, поддерживаемые upstream, но отсутствующие в V1
формы записи, whitespace-обфускацию email, строгую российскую phone surface,
checksum-invalid СНИЛС/ОМС и несовпадение продуктовой таксономии с внешней
разметкой. Один агрегированный F1 не позволяет безопасно отличить дефект
detector от noisy label или несовпадения сущностей.

Epic повышает реальное качество detector, а не подгоняет его под один внешний
test set. Source-aligned RedMadRobot metrics остаются неизменяемым внешним
свидетельством, canonical synthetic corpus остаётся release gate контракта, а
расширенная диагностика разделяет улучшения recall, новые false positives и
taxonomy/span mismatches.

## Карта декомпозиции

```text
EPIC-10 PII detection quality
├── Evidence
│   ├── safe reason-coded FN/FP diagnostics and frozen split
│   └── additive product-aligned semantic report
├── Boundary robustness
│   └── IP before terminal punctuation and IPv4 port delimiters
├── Obfuscation tolerance
│   ├── email with bounded whitespace around separators
│   └── Russian phone surface and contextual national forms
├── Contextual recovery
│   ├── checksum-invalid SNILS under strong context
│   └── checksum-invalid OMS under strong context
└── Qualification
    ├── canonical regression gate
    ├── RedMadRobot quality thresholds
    └── JMH regression evidence
```

## Дочерние issues

- [ ] [VIG-10-01: Safe quality diagnostics и frozen evaluation split](../issues/epic_10/issue_10_01_quality_diagnostics.md) - `Ready for implementation`
- [ ] [VIG-10-02: IP перед завершающей пунктуацией](../issues/epic_10/issue_10_02_ip_candidate_boundaries.md) - `Ready for implementation`
- [ ] [VIG-10-03: Product-aligned external quality report](../issues/epic_10/issue_10_03_product_aligned_report.md) - `Ready for implementation`
- [ ] [VIG-10-04: Whitespace-обфускация email](../issues/epic_10/issue_10_04_email_obfuscation.md) - `Ready for implementation`
- [ ] [VIG-10-05: Расширенные формы российских телефонов](../issues/epic_10/issue_10_05_phone_surfaces.md) - `Ready for implementation`
- [ ] [VIG-10-06: Contextual fallback для СНИЛС](../issues/epic_10/issue_10_06_snils_contextual.md) - `Ready for implementation`
- [ ] [VIG-10-07: Contextual fallback для ОМС](../issues/epic_10/issue_10_07_oms_contextual.md) - `Ready for implementation`
- [ ] [VIG-10-08: Итоговая quality и performance qualification](../issues/epic_10/issue_10_08_quality_qualification.md) - `Ready for implementation`

VIG-10-01 и VIG-10-02 образуют начальный frontier. VIG-10-03..07 зависят от
VIG-10-01, потому что их realistic-corpus acceptance требует frozen split и
safe diagnostics до настройки production behavior. VIG-10-08 зависит от всех
предыдущих issues и от JMH baseline VIG-02-15.

Предпочтительный порядок после VIG-10-01, не являющийся блокировкой:
VIG-10-04 и VIG-10-05 до contextual checksum fallbacks, поскольку
format-preserving recall имеет меньший false-positive risk.

## Цель

`FastPiiDetector` повышает recall на реалистичных русскоязычных payload при
контролируемом precision, сохраняет точные UTF-8 offsets, различает validated,
contextual и format-only evidence и не ухудшает canonical correctness или
обязательные performance scenarios.

## Нормативная стратегия качества

### Независимые представления внешнего качества

- Source-aligned report продолжает оценивать опубликованные RedMadRobot labels
  без изменения gold spans, denominator или matching rules.
- Product-aligned report публикуется дополнительно и не заменяет
  source-aligned metrics. Он может классифицировать только заранее
  зафиксированные taxonomy/span mismatches.
- 10-значный ИНН юридического лица не становится `RU_INN` только ради внешней
  метрики. Такое значение учитывается как taxonomy mismatch; 12-значный ИНН
  физического лица остаётся единственной семантикой `RU_INN`.
- Раздельно размеченные серия и номер паспорта могут образовать одну
  product-aligned expected entity только по детерминированному правилу
  VIG-10-03. Source-aligned gold spans не меняются.
- Checksum-invalid values не удаляются из source-aligned denominator и не
  объявляются автоматически ошибкой dataset.

### Frozen tuning/evaluation protocol

- Pinned RedMadRobot revision и полный report сохраняются.
- Stable case IDs детерминированно разделяются на frozen tuning и evaluation
  partitions по версионированной hash-функции с pinned counts.
- Production rules разрешено уточнять по aggregate tuning diagnostics и
  synthetic fixtures. Evaluation partition не используется для выбора
  конкретных regex, keywords или separator lists.
- Reports и failures не содержат raw text, tokens, candidates, matched values
  или обратимо кодируемые previews.

### Evidence semantics

- Строго распарсенный или checksum-valid candidate сохраняет
  `EvidenceStrength.VALIDATED`.
- Candidate с допустимой структурой, но ошибочной checksum может стать
  finding только при сильном ограниченном контексте и получает
  `EvidenceStrength.CONTEXTUAL`.
- Контекст не входит в finding span. Finding указывает на исходный непрерывный
  UTF-8 span кандидата, включая только допустимые внутренние separators.
- Standalone checksum-invalid длинное число не является finding.
- Локальная нормализация не изменяет payload и не требует глобального Unicode
  normalization или case folding.

### Числовые критерии

На полном pinned RedMadRobot source-aligned scored subset после VIG-10-02..07:

- exact precision не ниже `0.75`;
- exact recall не ниже `0.30`;
- exact F1 не ниже `0.42`;
- relaxed F1 не ниже `0.45`;
- `IP_ADDRESS` exact recall не ниже `0.90`.

Evaluation partition обязан показать улучшение exact F1 относительно baseline
VIG-10-01 без падения exact precision ниже `0.75`. Числовой target не заменяет
per-type diagnostics: итоговый report объясняет вклад каждого recognizer и
новые false positives.

## Требования

- Все изменения production behavior проходят через существующий публичный seam
  `PiiDetector.detect` и обязательный RED -> GREEN процесс проекта.
- `startUtf8`/`endUtf8`, deterministic ordering, `stopOnFirst`, enabled type
  filtering, cancellation и immutable result semantics EPIC-02 сохраняются.
- Изменение accepted surface, evidence или offsets повышает соответствующий
  `recognizerVersion`; стабильный `recognizerId` сохраняется для эволюции того
  же правила, новый независимый rule получает новый ID.
- Canonical positive corpus сохраняет 100% exact match, hard-negative corpus
  сохраняет 100% rejection после осознанного обновления только тех fixtures,
  чья нормативная семантика изменена этой issue.
- Новые hard negatives проверяют соседние обычные числа, punctuation,
  identifiers, timestamps, versions, order numbers и adversarial long input.
- Scanner-ы остаются bounded/linear, не выполняют I/O, DNS или network calls и
  проверяют cooperative cancellation между кандидатами.
- JMH qualification сравнивает обязательные scenarios VIG-02-15 с сохранённым
  baseline и не скрывает устойчивую деградацию ради quality metrics.
- Source datasets, reports, logs и diagnostics не раскрывают PII values.

## Не входит

- Подключение detector к gateway, body aggregation, JSON parsing и runtime
  policy enforcement.
- ML/NER, имена, адреса, организации и попытка повторить headline leaderboard
  RedMadRobot.
- Production telemetry, сбор пользовательских payload или генерация fixtures
  из production logs.
- Добавление 10-значного ИНН юридического лица в PII taxonomy.
- Принятие checksum-invalid payment card без отдельного доказанного контракта.
- Изменение публичных `PiiType`, `PiiFinding` или `PiiDetector` без отдельной
  expand/migrate/contract issue.
- Ослабление canonical hard-negative gate или исключение неудобных
  source-aligned labels из исходного report.

## Критерии готовности

- VIG-10-01..08 имеют статус `Done`, checklist и реестр обновлены в тех же
  change sets.
- Полный pinned RedMadRobot report достигает числовых source-aligned критериев
  epic и отдельно публикует frozen evaluation evidence.
- Product-aligned report является дополнительным, воспроизводимым и содержит
  явные counts каждого taxonomy/span adjustment.
- Canonical positive и hard-negative gates проходят без необъяснённых
  изменений expected findings.
- Все recognizer changes имеют RED evidence через `PiiDetector.detect`, затем
  focused GREEN и финальный `./gradlew build`.
- JMH comparison не показывает устойчивой регрессии более 10% по p95 или p99
  в обязательных full-scan/no-match scenarios; превышение требует отдельного
  profile-guided решения, а не waiver внутри отчёта.
- Work-item validator проходит, а отчёты и test diagnostics не содержат raw
  PII.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   quality означает recall при precision floor
  Acceptance:   0.05  числовые и canonical gates заданы
  Boundaries:   0.0   deterministic detector без gateway и ML
  Alternatives: 0.1   product-aligned view дополняет source-aligned evidence
  Assumptions:  0.1   contextual invalid-checksum values полезны guardrail
  Aggregate:    0.05  below threshold (0.2 spec)
```
