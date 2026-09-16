# Разработка и проверки

## Требования

Проект собирается через Gradle Wrapper и требует JDK 25:

~~~bash
./gradlew --version
~~~

Отдельная установка Gradle не требуется.

## Основные команды

~~~bash
./gradlew build
./gradlew test
./gradlew test -x processTest --tests "io.vigilant.gateway.proxy.BypassProxyServiceTest"
./gradlew processTest --tests "io.vigilant.gateway.MainTest"
./gradlew run
./gradlew installDist
./gradlew ociArtifact
~~~

`run` компилирует и запускает `io.vigilant.gateway.MainKt` прямо из Gradle;
ему нужны те же environment variables и `politics.conf`, что packaged
application. `installDist` создаёт локальный runnable distribution в
`build/install/vigilant/`. `ociArtifact` создаёт reproducible versioned tar в
`build/distributions/`, который использует Dockerfile.
`./scripts/installed-distribution-smoke-test` после `installDist` проверяет
env-only и HOCON startup, readiness, exact request replay, stdout audit и
graceful shutdown без application-owned audit directory.

`./gradlew build` включает:

- Kotlin/JVM compilation с warnings-as-errors;
- unit, integration и E2E tests;
- detekt с project configuration
  [config/detekt/detekt.yml](../config/detekt/detekt.yml);
- fixture tests work-item validator;
- validation graph в `spec/`;
- проверку, что JMH dependencies отсутствуют в production runtime classpath.

Каждый `./gradlew test` и `./gradlew build` сначала выполняет полный
`process-e2e` набор ровно один раз через отдельный serial `processTest`, затем
выполняет остальные tests через `test`. Focused child-process case запускается
командой `./gradlew processTest --tests <pattern>`. `processTest` всегда
использует один fork. `test` по умолчанию использует ровно четыре forks;
`-PtestMaxParallelForks=N` переопределяет только `test` и принимает exact
integer `1..4`. Нулевое, отрицательное, дробное, пустое, нечисловое значение
или число больше четырёх останавливает Gradle configuration с явной ошибкой.
JUnit XML двух lanes хранится в разных directories; timing report отклоняет
duplicate testcase identity вместо повторного подсчёта.

Proxy behavior tests используют реальные Armeria servers на ephemeral ports.
Cross-process gateway tests получают never-reused loopback ports, process и
stdout/stderr readers только через `GatewayProcessFixture`. Fixture до ожидания
readiness запускает оба output readers, применяет общий production-like набор
startup inputs (`upstream`, environment, выбранный identity mode и его settings,
policy snapshot, port), ждёт readiness/exit с bounded diagnostics и на каждом
terminal path завершает child, readers, streams и isolated client factory.
Новая обязательная startup setting добавляется в общий launcher и во все
normal, packaged, performance и OCI consumers одним change set.

## Дополнительные проверки

~~~bash
./gradlew detekt
./gradlew validateWorkItems
./gradlew piiProductionRuntimeClasspathCheck
./gradlew dependencyCheckAnalyze
./gradlew verifyAll
~~~

`verifyAll` объединяет `build` и OWASP dependency check.

## Режим разработки

[Project testing mode](agent-workflow.md#behavior-first-development-and-selective-tdd)
владеет порядком behavior-first/TDD slices, independent examples и affected
consumers. [Defect prevention](agent-workflow.md#pre-verification-defect-prevention)
владеет criteria, lifecycle и semantic review checks. Обязательные точки чтения
заданы в [startup routing](../CLAUDE.md#mandatory-routing).

На следующих 3-5 сопоставимых issue записывать в рабочий evidence ledger:
режим тестирования, модель/effort когда известны, cached input отдельно от
uncached input и output, время инструментов отдельно от пауз пользователя,
число full builds и причины повторов, initial-review defects, пропуски criteria
и remediation waves. Недоступную метрику помечать unavailable. Не менять модель
одновременно с testing mode в сравнении; не обещать экономию до измерения.

## Устойчивый запуск проверок

`scripts/check-run` - единственный supervisor локальных checks. Python 3.11+
и Git обязательны. Run хранится в `<git-dir>/check-runs/<run-id>/`, отдельно
для каждого worktree, и переживает `gradle clean`. stdout - компактный TOON;
argv, Git head, repository/worktree, timestamps, monotonic duration, exit,
selected inputs до/после и artifact digests находятся в JSON. Environment
сохраняется только в виде digests. stdin команды - `/dev/null`.
Не передавать секреты в argv и не выводить их в command log.

~~~bash
./scripts/check-run start --label lint --snapshot issue-local-1 --timeout 600 -- ./gradlew detekt
./scripts/check-run start --label affected --snapshot issue-local-1 --input src --artifact directory:build/test-results/test -- ./gradlew test -x processTest --tests '<pattern>'
./scripts/check-run lookup --label affected --snapshot issue-local-1 --input src --artifact directory:build/test-results/test -- ./gradlew test -x processTest --tests '<pattern>'
./scripts/check-run status <run-id>
./scripts/check-run wait <run-id> --seconds 60
./scripts/check-run cancel <run-id>
./scripts/check-run metrics --snapshot issue-local-1
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
~~~

Пример affected scope иллюстрирует CLI, а не полный Gradle input contract:
для реальной проверки добавить build/config/toolchain inputs и старые consumers.
Набор tests задаёт implementer; автоматического affected-test inference нет.

### Контракт reusable evidence

`--snapshot` - явный ID одной verification session одной задачи. Для OWASP,
Sonar и других external-data gates reuse допустим только внутри этой session,
никогда между задачами по source hash. Новый session получает новый ID;
отдельной freshness policy нет. Без `--snapshot` start создаёт уникальный ID
и всегда новый run. ID snapshot не является semantic approval.

`start` под worktree execution lock ищет current запись с exact label, argv,
timeout, snapshot, input/tool/environment/artifact declarations и gate kind.
При совпадении возвращает прежний run ID и `evidence: reused`, без процесса.
Иначе возвращает новый ID и `evidence: new`. `lookup` делает тот же read-only
поиск, никогда не запускает команду и не увеличивает reuse counter; отсутствие
пригодной записи - exit 1, `evidence: NOT RUN` и причина. HEAD хранится как
provenance; commit неизменных selected files сам по себе не инвалидирует run.
Порядок argv значим, порядок повторяемых declarations нормализуется.

Перед каждым reuse consumer вызывает `status <id>` или exact `lookup`:

| Applicability | Значение и следующее действие |
|---|---|
| `current` | Terminal exit 0, стабильные inputs до/после, текущие command/toolchain/environment inputs и целые artifacts. Reviewer отдельно проверяет semantic applicability |
| `stale` | Изменён, добавлен, удалён или недоступен selected input/toolchain/environment; запустить check на текущем contract |
| `corrupt` | Partial/legacy/повреждённая запись, отсутствующий/изменённый artifact или symlink в artifact path; восстановить точные artifacts либо выполнить новый check |
| `starting`, `running` | Terminal evidence ещё нет; bounded wait, без повторного запуска |
| `failed`, `timeout`, `cancelled`, `infrastructure_failure` | Собственный terminal verdict, никогда не reusable; исправить причину или завершить cleanup перед новым run |

`state: passed` после `wait` - сохранённый exit, а не разрешение пропустить
проверку. Только `applicability: current` после status/lookup подтверждает
mechanical reuse. Старый `status --check-inputs` сохранён как alias полной
проверки. Неисправные данные дают короткий reason/action, без полного log.
Manifest публикуется последним и связывает request/result, before/after и
artifacts; это integrity check локальных файлов, не криптографическая подпись.

Повторяемый `--input <repo-relative-path>` выбирает Git-visible files, включая
новые untracked files под выбранными directories. Default - весь Git-visible
worktree. Deleted tracked files и executable mode учитываются. Не исключать
реальные consumers: docs-only remediation оставляет узкий runtime run current,
только если docs не читались им; shared build/contract input инвалидирует gate.
Изменение inputs между before/after делает exit 0 stale. Не редактировать
selected inputs во время check и не использовать transient edit/restore как
доказательство неизменности: snapshots наблюдают границы, не весь интервал.

`--env-key <name>` добавляет digest значения без сохранения значения. Автоматически
учитываются JAVA_HOME, GRADLE_OPTS, JAVA_OPTS, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS,
PATH, GRADLE_USER_HOME, KOTLIN_OPTS. Runner, Python, Git, выбранный executable
и OS identity также входят в snapshot. `--tool <file-or-directory>` добавляет
пути, resolved paths и bytes установленного JDK, Gradle/analyzer или внешней
конфигурации; выбирать все реально влияющие tools. Отсутствующий tool path и
пустой directory тоже записываются: последующее появление файла инвалидирует
status по прежнему ID. File symlinks учитываются
с referent, directory symlinks внутри tool tree отклоняются: выбрать resolved
root отдельно. Hashes не доказывают полноту declarations; это обязанность reviewer.

`--artifact file:<repo-path>` или `--artifact directory:<repo-path>` повторяется
для каждого результата. Directory должен содержать хотя бы один regular file;
учитываются весь состав, bytes и mode, включая добавления/удаления. Symlinks
в любом компоненте artifact path и специальные файлы отклоняются. Без declarations
record явно содержит `none`; это допустимо для checks без report artifacts,
но не заменяет обязательные test/OWASP/Sonar reports. Изменённый command или
набор artifact declarations не совпадает с reusable contract.

Supervisor переживает закрытие terminal. Один execution lock передаётся от
start supervisor и далее дочерней команде; он запрещает одновременные runner
commands и сериализует reuse decision. Status не ждёт окончания команды и
не запускает её; время чтения зависит от объёма declared files. Timeout/cancel
и normal exit очищают исходную process group с bounded TERM/KILL grace. Lost
supervisor - infrastructure failure; проверить log и принадлежащую run группу
перед следующим запуском. Direct внешние Gradle invocations lock не блокирует:
проверить их отсутствие отдельно. Нельзя стартовать второй Gradle до terminal
exit первого. Общие Gradle daemons runner не завершает; намеренно отделённые
sessions/groups вне гарантии cleanup. Не запускать background services через runner.

Предпочитать completion notification harness; иначе один `wait --seconds 60`
в полезной точке с согласованным outer yield. Runner не добавляет push notifications.

### Метрики verification cycle

`--gate full|affected|review` и `--wave <nonnegative integer>` описывают check;
wave 0 - начальная работа. Wave не меняет applicability. `metrics --snapshot`
считает реально стартовавшие full gates, reuse events отдельно от read-only lookup,
reused full gates, наблюдаемое tool duration без пользовательских пауз,
remediation waves и причины invalidation. Rejected launch не считается full gate.
JSON records/events в private run directory - исходные наблюдения для ledger.

На следующих 3-5 сопоставимых issues consumer ведёт одну строку на намеренный
запрос gate, связывая reuse event с исходным run, а не считая status polls:

| Issue/session | Consumer / run ID | New / reused / NOT RUN | Gate / wave | Причина invalidation | Tool seconds | Task elapsed / user pause seconds |
|---|---|---|---|---|---:|---|
| `<issue>/<snapshot>` | `<implementation, verify-changes, delivery>/<run>` | `<mode>` | `<kind>/<wave>` | `<reason or none>` | `<record duration; reused run не суммировать повторно>` | `<measured or unavailable>` |

Количество устранённых full reruns равно намеренным reused full requests;
сумма durations только уникальных новых runs - фактическое tool time. Для
wall-clock сравнения отдельно измерять task elapsed и паузы, контролировать
сопоставимость issues/testing mode/model. Duration прежнего run не является
наблюдённой wall-clock экономией; CLI сообщает её как `unavailable`. До 3-5
сопоставимых наблюдений не обещать процент экономии. Reviewer defects и cached/
uncached model usage остаются отдельными полями рабочего ledger, если доступны.

## Agent papercuts

`.papercuts.jsonl` is the tracked append-only journal of repository, tooling and
documentation friction. Before diagnosing a known symptom, retrieve both open
and resolved entries by its explicit exact tag (repeat for each declared tag):

```bash
rtk proxy ./scripts/papercuts --pretty list --status all --tag tooling --limit 5
```

Use tags declared by the issue or an exact existing tag justified by the symptom.
Do not guess a nearby tag. With no known symptom/tag, the explicit fallback is
`rtk proxy ./scripts/papercuts --pretty list --status all`; broad retrieval is
not startup context. No tag declaration means task-context never reads the journal.

Record new actionable friction before continuing the primary task:

```bash
rtk proxy ./scripts/papercuts add "<symptom; context; prevention>" --tag <exact-tag> --severity <minor|major|blocker>
rtk proxy ./scripts/papercuts resolve <id> --note "<root cause; durable fix/workaround; verification command>"
rtk proxy ./scripts/papercuts doctor
```

Keep working unless it is a real blocker. Product defects and planned work
belong in `spec/issues/`. Prefer fixing the underlying script/config/docs; a
resolution note does not replace the fix. Reuse an old solution only after
checking that its context still applies. Never record secrets, bodies, auth
headers, raw environment dumps or unredacted stderr that may contain them.
Run doctor after manual journal conflict resolution or suspected corruption.

## Test timing report

~~~bash
./gradlew testTimingReport
~~~

`testTimingReport` зависит от `test`, который завершает обе execution lanes,
читает distinct актуальные JUnit XML results `processTest` и `test` и
детерминированно создаёт два generated artifact:

- `build/reports/test-throughput/test-timing.json` - machine-readable source
  of truth с snapshot metadata и task/class/whole-suite totals;
- `build/reports/test-throughput/test-timing.md` - human-readable представление
  того же snapshot со всеми test classes от самой медленной к самой быстрой.

Оба файла заменяются целиком при каждом выполнении task и не добавляются в
Git. JSON schema version 1 содержит machine/source snapshot, requested tasks,
effective worker counts, rerun marker, totals каждого task/class и whole-suite
totals. Task records сортируются по path; class records - по duration descending,
затем task path и class name. Markdown отображает тот же snapshot без новых
значений. Missing, empty, malformed, duplicate или incomplete JUnit input
завершает task ошибкой и не оставляет успешный stale report.

Для сопоставимого single-worker baseline выполняются три строго
последовательных uncached команды на одном machine, Git HEAD и dirty-tree
fingerprint:

~~~bash
./gradlew test testTimingReport --rerun-tasks --no-daemon -PtestMaxParallelForks=1
~~~

Каждый generated JSON сохраняется отдельно вне Git; summary хранит три
wall-clock values и mathematical median. Historical durations не являются
current measurement и не переносятся в документацию требований.

Полная локальная квалификация four-worker topology запускается одной командой:

~~~bash
./gradlew testThroughputQualification --no-daemon
~~~

Task последовательно выполняет три single-worker baseline runs, три
four-worker candidate runs и, только после performance gate, десять
four-worker stability runs. Generated `qualification.json`,
`qualification.md` и per-run logs находятся только под
`build/reports/test-throughput/` и не добавляются в Git. До первого run runner
фиксирует machine identity, Git HEAD и dirty-tree fingerprint; любое изменение
snapshot инвалидирует всю series. Gate проходит только при
`candidateMedianMs <= baselineMedianMs * 0.70`. Каждый stability run обязан
завершиться с полным exact testcase inventory, нулевыми failures/timeouts и
без run-owned gateway/Gradle-worker process после bounded cleanup. Runs не
перекрываются, retries и fallback на два или три workers отсутствуют.

Health/startup/shutdown evidence использует причинные наблюдения, а не
timestamps или sleeps: readiness проверяется после опубликованного
`markNotReady`, server connection close и первый draining probe связываются
explicit barriers, streaming test освобождает последний upstream chunk только
после первого непустого client body observation, а unavailable-upstream fixture
удерживает loopback socket до реального connection reset. Cleanup пытается
закрыть каждый owned resource, сохраняя первую ошибку и последующие suppressed.

## PII quality

Canonical synthetic corpus является gating частью обычного `test`. Отдельный
human-readable и machine-readable отчёт создаётся командой:

~~~bash
./gradlew piiQualityReport
~~~

Результаты находятся в `build/reports/pii/canonical/`. Исходные synthetic
fixtures лежат в
`src/test/resources/io/vigilant/detectors/pii/quality/canonical/` и могут быть
детерминированно пересозданы командой:

~~~bash
./scripts/generate-canonical-pii-corpora
~~~

Изменять corpus следует только вместе с соответствующим recognizer contract и
focused tests.

Внешний non-gating benchmark RedMadRobot запускается явно:

~~~bash
./gradlew redMadRobotPiiBenchmark
~~~

Task скачивает и проверяет pinned dataset, затем пишет отчёты в
`build/reports/pii/redmadrobot/`. Для offline run путь к заранее полученному
dataset передаётся через Gradle property `redMadRobotPiiDataset`:

~~~bash
./gradlew redMadRobotPiiBenchmark \
  -PredMadRobotPiiDataset=/absolute/path/to/test.csv
~~~

Dataset не входит в repository и не попадает в production runtime classpath.

### Canonical corpus format

Нормативные surfaces, metadata и gates принадлежат
[Fast PII](../spec/requirements/fast-pii.md#quality). Формат каждого UTF-8 TSV:
первая строка строго `# pii-corpus-v1`, далее четыре tab-separated columns:

1. Unique `caseId`.
2. Comma-separated `PiiType` names либо `*` для всех типов.
3. Base64 точных UTF-8 bytes синтетического payload.
4. Expected findings через `;`, каждый как
   `type,startUtf8,endUtf8,evidenceStrength,recognizerId,recognizerVersion`;
   пустая последняя column означает negative case.

Runner вызывает публичный `PiiDetector.detect(..., stopOnFirst=false)` и
сравнивает полный ordered result, не только count. Parser отклоняет invalid
header, columns, Base64/Unicode, enum/offset values, ordering и duplicate IDs.
Diagnostics называют только caseId/category/error code. Generator независимо
вычисляет checksums и exact byte offsets, не берёт их из production detector.
Per-type positive/hard-negative corpora отделены от `mixed.tsv`: несколько
типов, overlaps, punctuation, кириллица, emoji и hard negatives в одном тексте.
JSON/Markdown report фиксирует corpus version, case counts, per-type и aggregate
exact/relaxed scores. External data и metrics сюда не подмешиваются.

### External PII benchmark

`prepareRedMadRobotPiiCorpus` получает только `test.csv` pinned dataset
`redmadrobot-rnd/pii_benchmark`, revision
`f77ea831274daf980cc45c61a93c226be9d978d6`; размер `3 225 069` bytes и SHA-256
`6bf544a380a3ee5bec94b946124bea3afaecce49e734679ad0f0c0e7c12977bb`
проверяются до parsing и для download, и для offline input. Failed download
или integrity check не оставляет принимаемый следующим run partial file.
URL, MIT license declaration upstream и attribution зафиксированы в
[metadata resource](../src/test/resources/io/vigilant/detectors/pii/benchmark/redmadrobot/metadata.properties).
License declaration является metadata, не юридическим заключением.

CSV columns строго `text`, `tokens`, `ner_tags`; последние две содержат JSON
arrays одинаковой длины с token-level BIO labels. Stable caseId
`rmm-test-NNNNNN` - ordinal parsed data record с единицы после header, не
физический номер строки. Tokens выравниваются с исходным text точным
case-sensitive поиском слева направо, без case folding, normalization или
реконструкции текста. Impossible/ambiguous alignment отклоняет весь case;
invalid schema/JSON/BIO transitions дают safe code и caseId без raw token/tag.
BIO spans преобразуются в UTF-8 исходного text.

Mapping исчерпывающий: `EMAIL -> EMAIL_ADDRESS`, `PHONE -> PHONE_NUMBER`,
`CREDIT_CARD -> PAYMENT_CARD`, `IP_ADDRESS -> IP_ADDRESS`, `INN -> RU_INN`,
`SNILS -> RU_SNILS`, `PASSPORT -> RU_PASSPORT`, `OMS -> RU_OMS`. Остальные
labels out of scope, IBAN в этом dataset отсутствует. Pinned coverage:
`2 841` total cases, `2 839` processed, `2` rejected (`rmm-test-000001`,
`rmm-test-001629`, token/text case mismatch), `5 614` total BIO spans,
`1 902` mapped и `1 900` scored mapped spans. Rejected cases содержат по
одному PHONE span. Report раскрывает все эти denominators.

Frozen split v1 вычисляет SHA-256 UTF-8 строки из salt
`vigilant-redmadrobot-split-v1`, revision и caseId, соединённых `U+0000`.
Первый unsigned hash byte - bucket из 256; `0..63` evaluation, `64..255`
tuning. Rejected cases исключаются до scoring. Counts воспроизводимы
независимо от locale, timezone, filesystem/iteration order или random seed:

| Partition | Processed cases | Scored mapped spans |
|---|---:|---:|
| Full | 2 839 | 1 900 |
| Tuning | 2 109 | 1 463 |
| Evaluation | 730 | 437 |

Tuning/evaluation disjoint и дают full в сумме. Report публикует algorithm,
salt/version, boundary, counts и metrics каждого partition. Evaluation нельзя
использовать для подбора конкретных правил.

Source-aligned gold и denominator остаются опубликованными upstream, включая
typos и checksum-invalid identifiers. Отдельный product-aligned view v1
применяет только два versioned adjustments до scoring, сохраняя их zero/nonzero
counts, provenance и исходный view:

- `LEGAL_ENTITY_INN_TAXONOMY_MISMATCH`: исключает только gold INN span из
  ровно 10 ASCII digits в product view; 12 digits остаются, даже с invalid checksum.
- `PASSPORT_SERIES_NUMBER_MERGE`: соседние gold PASSPORT spans одной записи
  в порядке offsets, ровно четыре digits, затем шесть, nonoverlap, образуют
  одну entity от начала series до конца number. Gap максимум 32 Unicode code
  points, только whitespace/punctuation и последовательность слов из точного
  списка: пусто, `серия`, `номер`, `серия номер` (case-insensitive).
  Детерминированное left-to-right one-to-one pairing не переиспользует spans;
  reversed order, third entity, arbitrary prose, digits/symbols вне gap grammar
  и ambiguous multi-part merge не разрешены.

Checksum-invalid CARD/SNILS/OMS не удаляются ни одним adjustment. Matching
обоих views использует [единый exact/relaxed contract](../spec/requirements/fast-pii.md#quality).
Aggregate и per-type P/R/F1 нельзя сравнивать с headline model leaderboard:
здесь восемь structured types без model threshold, с другим scope/aggregation.

Отдельный `nestedIpAligned` view использует reference `redmadrobot-ip-canonical-v2`.
До scoring adapter добавляет к source gold IP-literal **host** каждой upstream
BIO URL entity. Полный URI разбирается JDK `URI`, без DNS или production recognizer:
разрешены scheme authority и schemeless authority, canonical IPv4 из четырёх
decimal octets `0..255` без leading zeros либо unscoped IPv6; embedded IPv4
подчиняется тем же octet rules. Необязательный port содержит `1..5` ASCII digits,
число `1..65535`. Userinfo, brackets, port, path, query и fragment исключены из
UTF-8 span. Invalid URI, domain host и IP-подобный текст в userinfo/path/query
этим правилом не размечаются. Совпадающий source gold не дублируется. Исходные
source/product views и source coverage сохраняются; product INN/passport rules
не переносятся в nested view.

V2 также нормализует **целую** upstream `IP_ADDRESS` entity, если она состоит из
canonical IPv4, одного colon и port из `1..5` ASCII digits со значением `1..65535`.
В canonical view end offset переносится на конец адреса; type, start и число
исходных entities сохраняются. IPv6 endpoints, другие labels, invalid octets,
leading-zero octets, signed/empty/out-of-range ports, whitespace, URL prefix,
path/query/fragment и prose внутри entity не нормализуются. Подстрока валидного
endpoint внутри более длинной метки не извлекается. Source/product gold остаётся
исходным; ни один prediction не отфильтровывается. Exact matching остаётся строгим.

Аннотатор не читает predictions и одинаково обрабатывает все entities обеих
frozen partitions. Rule ID/provenance, `addedIpSpans`, `normalizedIpEndpointSpans`
и SHA-256 всего reference (dataset digest, rule, case IDs и type/offset tuples,
без публикации индивидуальных hashes) позволяют проверить общий denominator.
Изменение policy требует новой версии и повторного scoring обоих detector-ов.
V1 artifacts сохраняются как исходная evidence, но не принимаются для v2
qualification. Это ограниченная канонизация разметки, не исправление всех
upstream annotation gaps.

Safe diagnostics классифицируют unmatched gold/prediction для обоих matching
modes: `NO_OVERLAPPING_FINDING`, `SPAN_MISMATCH`, `TYPE_MISMATCH`,
`EXTRA_PREDICTION`. Bucket totals сохраняются; breakdown `(bucket,type)`
публикуется только при count `>= 5` (`diagnostics.privacyFloor`). Текущий
writer не выводит individual cases, spans, raw text/tokens/candidates,
matched values, value hashes или обратимые previews. Surface diagnostics
ограничены необратимыми aggregate digit-count/separator/checksum/discriminator
classes с тем же privacy floor; текущий writer не публикует эти attributes.
Per-type/per-evidence contribution показывает predictions, exact/relaxed
matches и false positives; итоговая таблица также сохраняет FN и type deltas.

Reports `redmadrobot-pii-benchmark.json` и `.md` находятся только под `build/`.
Preparation/benchmark не запускаются стандартными `build`/`test`, не требуют
сети от обычных tests и не vendor-ят внешний dataset.

### HiveTrace PII benchmark

`prepareHiveTracePiiCorpus` и `hiveTracePiiBenchmark` - явные external/non-gating
seams для [HiveTrace PII-Bench RU](https://huggingface.co/datasets/hivetrace/pii-bench).
Они не запускаются обычными `build`/`test`, не меняют recognizer behavior и не
задают release threshold. Apache Parquet reader и его Hadoop dependencies
доступны только в test classpath; `piiProductionRuntimeClasspathCheck`
проверяет их отсутствие в production. Dataset остаётся только под `build/`
либо во внешнем offline-каталоге, без добавления в Git.

~~~bash
./gradlew hiveTracePiiBenchmark
./gradlew hiveTracePiiBenchmark \
  -PhiveTracePiiCorpusDirectory=/absolute/path/to/hivetrace
~~~

Offline-каталог должен содержать оба файла:
`entity-00000-of-00001.parquet` и `domain-00000-of-00001.parquet`.
[Metadata resource](../src/test/resources/io/vigilant/detectors/pii/benchmark/hivetrace/metadata.properties)
владеет immutable revision `cd6a18ace16daf23e79247ccf1e2b245d4054654`, exact URL,
size, SHA-256 и независимо квалифицированными counts каждого файла.
Upstream license declaration `Apache-2.0` и attribution `HiveTrace PII-Bench RU`
являются metadata, а не юридической оценкой.

Download, offline import и повторное использование кеша выполняют одинаковую
проверку size/SHA-256 до parsing. Каждый файл публикуется через временный файл
и атомарный rename там, где filesystem поддерживает его; неудачная загрузка
удаляет временный файл. Валидный первый split может остаться в кеше после сбоя
второго, но полный benchmark требует оба корректных файла и повторно проверяет
их перед открытием первого reader. Offline input с ошибкой не заменяется
скачиванием. HTTP connection и streams закрываются на каждом пути завершения;
размер загрузки ограничен закреплённым размером, connect/read timeouts заданы.

Adapter проверяет точную Parquet schema: `id`, `domain`, `text` как UTF-8 strings
и `entities: list<struct<end:int64,start:int64,text:string,type:string>>`.
Null required values, повторный ID внутри split, неизвестный label/domain,
неверные границы или несовпадение `entities[].text` отклоняют целую запись.
Offsets - Unicode code-point indices, которые переводятся в исходные UTF-8
byte offsets; case folding, Unicode normalization и реконструкция текста
отсутствуют. `L-DIALOG` передаётся детектору как целая строка, без разбора
вложенного JSON. Нечитаемый файл/схема дают safe failure; сообщения и causes
библиотек не публикуются. В явных benchmark processes библиотечные логи отключены
[отдельной конфигурацией](../src/test/resources/io/vigilant/detectors/pii/benchmark/hivetrace/logback.xml).

Mapping: `EMAIL -> EMAIL_ADDRESS`, `PHONE_NUMBER -> PHONE_NUMBER`,
`BANK_CARD_NUMBER -> PAYMENT_CARD`, `INN -> RU_INN`, `SNILS -> RU_SNILS`,
`PASSPORT_NUMBER -> RU_PASSPORT`. `NAME`, `ADDRESS`, `CVC`, `KPP`, `OGRN`,
`OGRNIP`, `TOKEN` остаются unsupported с явными source counts.
`IP_ADDRESS`, `IBAN`, `RU_OMS` обозначаются `not covered`.
`FastPiiDetector.detect` получает исходный text, `stopOnFirst=false` и ровно
шесть mapped types. Эти enabled types опубликованы для metrics и clean FPR.

Source-aligned gold сохраняет все mapped source spans, включая checksum-invalid
значения и все опубликованные паспортные формы. Product-aligned v1 применяет
только `LEGAL_ENTITY_INN_TAXONOMY_MISMATCH`: исключает gold `INN`, состоящий
ровно из десяти ASCII digits. Двенадцатизначные ИНН остаются независимо от
checksum; паспортные spans не меняются и не объединяются. Predictions
сохраняются в обоих views. Версия, provenance и zero/nonzero counts adjustment
публикуются. RedMadRobot passport merge и frozen partitions сюда не переносятся.

| Split | Total / processed | Rejected | Clean | Source spans | Mapped | Unsupported | Product |
|---|---:|---:|---:|---:|---:|---:|---:|
| entity | 910 | 0 | 0 | 910 | 420 | 490 | 420 |
| domain | 900 | 0 | 378 | 757 | 397 | 360 | 369 |
| Full | 1810 | 0 | 378 | 1667 | 817 | 850 | 789 |

Полный прогон проверяет эти counts, source-type counts из metadata и по 100
записей каждого domain code: `L-CHAT`, `L-DIALOG`, `S-AUTO`, `S-BANK`,
`S-DELIVERY`, `S-HR`, `S-RE`, `S-SUPPORT`, `S-TELECOM`. Расхождение не позволяет
опубликовать прогон как полный. Coverage отдельно показывает total/processed,
rejected reasons, mapped/unsupported/product spans; source-span counts относятся
к processed records, invalid rows не участвуют в частичном scoring.

`PiiQualityScorer` применяет общий [exact/relaxed contract](../spec/requirements/fast-pii.md#quality):
maximum-cardinality one-to-one matching внутри case/type, per-type и micro
aggregate TP/FP/FN/P/R/F1 для обоих views. Отдельные scopes - `entity`, `domain`
и каждый domain code. Дополнительный `Full` суммирует confusion counts до
вычисления ratios; F1 разных splits не усредняются. Состав смеси явно указан,
она не объявляется распределением production traffic или tuning/evaluation split.

Clean subset определяется только исходным пустым `entities`, до mapping и
adjustments. Document FPR = clean records с хотя бы одним finding / processed
clean records. Для полного `domain` denominator равен 378; у каждого domain
свой clean count. Для `entity` denominator 0 и ratio `null`/`N/A`. Entity FP
публикуются отдельно. Unsupported-only records не считаются чистыми.

`build/reports/pii/hivetrace/hivetrace-pii-benchmark.json` и `.md` строятся
из одной aggregate-only модели. Они содержат provenance, mapping, enabled types,
coverage, adjustments и metrics, без case IDs, raw text, matched values,
индивидуальных spans, tokens, candidates или обратимых fingerprints.
Дополнительный mismatch breakdown отсутствует; при его добавлении применяется
[privacy floor external methodology](#external-pii-benchmark).

Synthetic fixtures создают настоящие Parquet-файлы без внешних данных:

~~~bash
./gradlew test -x processTest \
  --tests 'io.vigilant.detectors.pii.benchmark.hivetrace.*' \
  --tests 'io.vigilant.detectors.pii.quality.PiiQualityScorerTest'
~~~

### AdvPIIBench adversarial benchmark

`prepareAdvPiiCorpus` и `advPiiBenchmark` - явные задачи для отдельного
held-out external/non-gating evidence. Обычные `build`/`test` не запускают
benchmark и не скачивают корпус. JVM Parquet reader остаётся только в test
classpath; `piiProductionRuntimeClasspathCheck` проверяет изоляцию от production.
Recognizer behavior, taxonomy и release thresholds не изменяются.

~~~bash
./gradlew advPiiBenchmark
./gradlew --offline advPiiBenchmark \
  -PadvPiiCorpusDirectory=/absolute/path/to/advpii
./gradlew test -x processTest \
  --tests 'io.vigilant.detectors.pii.benchmark.advpii.*' \
  --tests 'io.vigilant.detectors.pii.benchmark.common.*' \
  --tests 'io.vigilant.detectors.pii.benchmark.hivetrace.*' \
  --tests 'io.vigilant.detectors.pii.quality.PiiQualityScorerTest'
~~~

Offline-каталог содержит `train-00000-of-00001.parquet`. Download/cache
находятся в `build/advpii/`; dataset не добавляется в Git. Оба способа ввода
используют общий bounded downloader/integrity contract с HiveTrace: exact size
и SHA-256 проверяются до parsing и перед каждым повторным использованием.
Временный файл публикуется только после проверки, через atomic rename при
поддержке filesystem. Обрыв HTTP, неверные bytes и cleanup failure не оставляют
принимаемого partial artifact. Неверный offline input не заменяется download.
HTTP connection, input/output streams и Parquet reader закрываются на success
и failure; connect/read timeouts ограничены.

[Metadata resource](../src/test/resources/io/vigilant/detectors/pii/benchmark/advpii/metadata.properties)
владеет revision `02741d9f99a91b8fdcf48f4316a2c73be7a7449a`, точным URL,
размером `4 258 476` bytes, SHA-256, upstream CC BY 4.0 declaration, attribution
Roei Arpaly and Yoni Birman (Reichman University), ссылками на pinned dataset
card/stats и ожидаемыми category/type/stage/attack counts. Declaration не
является юридической оценкой лицензии.

Adapter v1 проверяет точную Parquet schema: int32 `uid`/`input_id`, UTF-8
`category`/`llm_input`, `attack_target` со string lists `pii`/`context` и
`pii_spans` со string `type`/`value`/nullable `value_fuzzy`, int32 `start`/`end`.
Required nulls, duplicate `uid`, unknown categories/types/attacks, неверные
configurations или spans отклоняют весь corpus до scoring. Одинаковый текст
с разными `uid` сохраняется как отдельные attack configurations. Unicode
читается строго; NFC/NFD/NFKC/NFKD, trimming, case folding и удаление invisible
characters отсутствуют. Python code-point offsets переводятся в UTF-8 исходного
`llm_input`; точный срез обязан равняться `value_fuzzy or value`, включая SSN.

Mapping v1: `email -> EMAIL_ADDRESS`, `phone_number -> PHONE_NUMBER`,
`credit_card_number -> PAYMENT_CARD`, `iban -> IBAN`. `ssn` учитывается только
в source coverage. SSN-only positives остаются positive и не входят в negative
FPR. Detector получает исходный текст каждой записи, `stopOnFirst=false` и
явно эти четыре типа. Остальные пять типов обозначаются `not covered`.

Полная qualification до вызова detector требует `104 728` records:
`80 936` positive, `22 560` negative, `1 232` hard_negative; `114 101` source spans,
из них `99 696` mapped и `14 405` SSN. Positive stages: `1 208` baseline,
`7 248` PII-only, `72 480` combined. Каждая из шести PII families (`homoglyph`,
`chunking`, `emojify`, `char_to_word`, `invisible_chars`, `separators`) содержит
`13 288` rows. Все type/context counts сверяются с metadata; каждая из десяти
фиксированных combined context configurations содержит `7 248` rows.
Baseline уникален по `input_id`; каждая attack configuration сохраняет source
type multiplicities того же baseline. На каждый positive input приходятся
baseline и все `6 × (1 + 10)` distinct variants.

`PiiQualityScorer` переиспользует [единый matching contract](../spec/requirements/fast-pii.md#quality):
same-type exact boundaries или nonempty half-open overlap, deterministic
one-to-one maximum-cardinality pairing внутри записи. Source-aligned
`TP/FP/FN`, gold/prediction counts, precision/recall/F1 публикуются для каждого
типа и micro aggregate. Пустые denominators дают `null` в JSON и `N/A` в Markdown;
F1 denominator пуст только при отсутствии и gold, и predictions.

Baseline, PII-only, combined, negative и hard_negative - отдельные subsets.
PII-only и combined имеют family rollups; combined также имеет каждую context
configuration и её пересечение с каждой family. Rollups пересекаются, их
нельзя суммировать как независимые выборки. Context-label coverage явно
пересекается. Для каждой positive subset сопоставимый baseline содержит одну
исходную observation на каждую attacked row с тем же `input_id` и теми же
scored types. Это сохраняет веса исходных prompts при объединении variants.
Report показывает baseline counts/metrics и signed recall delta в процентных
пунктах: attack recall минус сопоставимый baseline recall. Gold независим от
predictions; не вводится positive document-level recall.

Document FPR вычисляется отдельно для `negative` и `hard_negative`: документы
хотя бы с одним finding / все документы source category. Числитель, знаменатель
и четыре enabled types опубликованы рядом. Entity FP остаются отдельными counts.
`pi_few_shot_safe` содержит неразмеченные вспомогательные примеры: посторонний
finding остаётся source-aligned FP и не повышает recall attacked gold.
Counts и precision сохраняются с явной оговоркой об ограниченной интерпретации FP.

Privacy floor детальных groups и type groups равен **5 distinct original
`input_id`**. Повторные variants одного prompt не увеличивают support. Type
support учитывает prompts с gold или prediction этого типа внутри subset.
При support 0-4 объект содержит только `suppressed=true`, без counts и derived
metrics; Markdown показывает только suppressed. Общая source coverage сохраняется.
Reports не содержат individual IDs, raw prompts, values/value_fuzzy, tokens,
candidates, individual spans или reversible fingerprints. Safe errors содержат
только allowlisted code без исходных parser causes и suppressed diagnostics;
[конфигурация benchmark logs](../src/test/resources/io/vigilant/detectors/pii/benchmark/advpii/logback.xml)
отключает библиотечные logs в явных benchmark processes.

JSON и Markdown строятся из одного безопасного aggregate snapshot:
`build/reports/pii/advpii/advpii-benchmark.json` и `advpii-benchmark.md`.
Они детерминированы для одинаковых detector/evaluator и corpus bytes, не содержат
времени запуска или offline path. В session ledger отдельно фиксируются source
revision/dirty state, команды, JDK/OS и run IDs. Corpus остаётся целиком held-out,
без tuning/evaluation split, training или настройки production rules по результатам.
Он не смешивается с canonical, RedMadRobot или HiveTrace evidence и не задаёт
новый release gate. English synthetic single-turn corpus не представляет
production traffic и не подтверждает HTTP extraction/enforcement или latency.

### PII contract checks

Обязательная matrix ниже задаёт проверяемые cases, а не утверждает наличие
каждого evidence. Неподтверждённые случаи перечислены в
[coverage](requirements-coverage.md#pii-и-windowing). Focused tests используют
public seam, synthetic fixtures, точные offsets и независимый oracle.

| Контракт | Полная обязательная matrix | Source/test owner |
|---|---|---|
| Public model | Kotlin и Java callers; immutable ALL/result; negative/empty/reversed span, blank metadata, confidence null/0/1/out-of-range/NaN/infinity; safe errors | `Pii*ContractTest`, `PiiJavaContractTest` |
| Preflight | Empty payload; empty types bypass invalid/oversized input; every nonempty set preflights even early match; exactly 1 MiB и +1 byte; lone high/low surrogate до/после size limit; INVALID_UNICODE priority; ASCII/Cyrillic/3-byte/supplementary offsets и invalid character boundary | `PayloadPreflightTest`, `FastPiiDetectorTest` |
| Pipeline | Все 512 type subsets, включая empty/full; canonical type order и source order; invalid then valid candidate; stop-first = first full; cross-type overlap и exact duplicate key; repeated/concurrent calls; immutable result | `FastPiiDetectorTest` |
| EMAIL | Весь dot-atom symbol set; min/max local/label/254 length; strict ASCII/IDN и IDN punctuation/dots; каждый gap 1/2/3 и 4+ rejection до/после @/dots; invalid dots/quoted/comments/literals/Unicode local/single-label; exact prose boundaries, long adversarial run | `EmailAddressRecognizerTest` |
| PHONE | +7 и 8; compact/separated/area parentheses; каждый из семи separators; 10-digit national и 7-prefixed contextual; все шесть keywords на обеих сторонах и exact 32-code-point boundary; weak/partial/distant words, extensions, timestamp/version/order/long-digit negatives; repeated/edge/unsupported separator и bad parentheses | `PhoneNumberRecognizerTest` |
| PAYMENT_CARD | Каждая длина 13..19, compact/space/hyphen; Luhn generation и checksum mutations, repeated digit/zero, longer run, edge/repeated separators | `PaymentCardRecognizerTest` |
| IP | IPv4 octets 0/255 и invalid/leading zero; full/unique-compressed/embedded IPv4 IPv6, all address classes, brackets/zone; terminal dot/colon; ports 1/443/65535 в EOF и перед space/tab/LF/CRLF с prose, widths 1/2/3/4; zero/out-of-range/signed/letter/colon port negatives; fifth octet/extra group/second ::/hex continuation; ambiguous IPv6 suffix; invalid then valid, Unicode spans | `IpAddressRecognizerTest`, `Ipv4PortRecognizerTest` |
| IBAN | Все страны pinned registry и lengths, compact/canonical groups/last group 1..4/lowercase; unknown country, mod97 mutation, repeated/unsupported separators и adjacent alphanumeric boundaries; no runtime registry I/O | `IbanRecognizerTest`, `IbanCountryLengthsTest` |
| RU_INN | Exactly 12 и отказ на 10; обе checksum formulas и mutations, no separators, immediate adjacent digits, start/end payload | `RuInnRecognizerTest` |
| RU_SNILS | Все separator triples; threshold и modulo101/100->00; valid с/без context; invalid с exact keyword на обеих сторонах, 32 code points и за пределом; standalone/repeated/partial/unsupported negatives; mixed contextual+valid и validated priority | `RuSnilsRecognizerTest` |
| RU_PASSPORT | Все четыре layouts, prefix паспорт и пара серия+номер, каждый одиночный keyword negative; 64-code-point context на обеих сторонах и beyond, supplementary offsets, no-context/unsupported forms | `RuPassportRecognizerTest` |
| RU_OMS | Compact и все шесть consistent separators, mixed separator negative; Mod10 generation/mutations; exact омс/полис sequence с обеих сторон на 48-code-point boundary и beyond; standalone/repeated/partial/weak negatives, contextual+valid и validated priority | `RuOmsRecognizerTest` |
| Detector lifecycle | Interrupt entry/between recognizers/at candidate validation, no flag clearing/partial result; deterministic concurrent reuse, no retained state; adversarial no-match и repeated contextual candidates linear | `FastPiiDetectorTest`, recognizer tests |
| Corpora/reports | Каждый type >=100 positive и >=100 negative; exact ordered metadata и rejection; parser rejection matrix; mixed overlaps, deterministic maximum one-to-one matching; safe output | `CanonicalCorpus*Test`, `PiiQualityScorerTest`, report writer tests |
| External | Size/hash/schema/BIO/array/ambiguity failures; exact repeated-token Unicode alignment; label mapping, all pinned counts/split disjointness; adjustment boundaries, no checksum exclusion; privacy suppression, safe JSON/Markdown | `RedMadRobotCorpusAdapterTest`, `RedMadRobotScorerTest`, `RedMadRobotReportWriterTest` |
| Windows | Каждый поддержанный type/format/evidence/context через ownership boundary, widths 1/2/3/4 bytes; first/last fragment edge; exact direct oracle с global sorting, preserved provenance, duplicate/overlap, immutable input snapshot/result | `WindowedFastPiiExecutorTest` |
| Generic core | Non-PII I/F/K seam, cross-window discovery/ownership/translation, contract-controlled order; direct и multi-window paths, finite/unbounded capability; blank/nonpositive/inconsistent/overflow-risk bounds и core progress; negative/reversed/empty/out-of-range/non-codepoint local offsets; metadata conflicts; invalid Unicode и first/later detector failure; no partial aggregate | `WindowedInspectionExecutorTest` |
| Window lifecycle | One submitted task/one active call/one materialized window; bounded caller CPU thread; cancellation entry/active future/between windows, no calls after cancellation/error; temporary state terminal release | Оба window executor tests |
| Policy adapter | CLEAN/DETECTED, exact all metadata/order, >1 MiB fragment, typed safe ERROR/execution failure, cancellation сохранена, caller interrupt cancels CPU future; real bounded executor | `FastPiiPolicyAdapterTest`, `PiiShadowProxyServiceTest`, `PiiShadowProxyProcessTest` |

Numeric property tests используют fixed seeds и независимо сгенерированные
checksums: compact и каждую разрешённую formatted surface, изменение check
digits/значимых позиций, longer-run boundaries. Найденный regression остаётся
permanent synthetic test; corpus/recognizer semantics обновляются вместе с
нормативным контрактом. Матрица не разрешает менять production code при
documentation-only переносе или заявлять непроведённый runtime run.
Генераторы используют test-only Kotlin `kotlin.random.Random(seed)` без
обязательной property-testing library. Seed и число iterations фиксируются
в имени/безопасном выводе теста для воспроизведения counterexample.

## Identity contract checks

Контракт: [identity/context](../spec/requirements/identity-and-context.md),
[identity telemetry](../spec/requirements/observability.md#identity).
Основной public seam - real HTTP client -> gateway -> trusted Bridge -> LLM
upstream. Controlled inbound publisher наблюдает первый body demand; Bridge
независимо фиксирует exact POST/path/query/headers/empty body и cancellation;
LLM upstream подтверждает exact Authorization/body. Policy outcome проверяет
действующие user/groups, включая изменение groups после expiry и разные
токены одного user. Каждый failure проверяет полный status/body/headers,
отсутствие demand и upstream call, без secret disclosure.

Focused suites запускаются по изменённому контракту; Gradle commands идут
последовательно через durable runner:

```bash
rtk proxy ./gradlew test -x processTest --tests 'io.vigilant.gateway.identity.*'
rtk proxy ./gradlew test -x processTest --tests 'io.vigilant.gateway.config.AppConfigLoadingTest' --tests 'io.vigilant.gateway.config.ExternalIdentityCacheConfigTest' --tests 'io.vigilant.gateway.AppComponentIdentityTest'
rtk proxy ./gradlew test -x processTest --tests 'io.vigilant.context.*' --tests 'io.vigilant.gateway.proxy.GatewayIdentityE2eTest'
rtk proxy ./gradlew processTest --tests 'io.vigilant.gateway.ExternalIdentityProcessTest'
```

Для полного identity contract suite обязательны все named cases, не только
representative happy path:

- Environment/mode cross-product; selected/mixed/unknown config, file/env
  precedence и independent TTL/size overrides; valid numeric endpoints и каждый
  invalid input из permanent matrices. Current gaps сохраняются в coverage.
- Dummy empty/non-empty credential и full Bearer rejection matrix; JWT каждый
  configured key/rotation, algorithm/signature/issuer/audience/time и каждая
  invalid identity shape; ни JWT identity I/O, ни Bearer reject body demand.
- Bridge каждый final status 201..599, success/media/JSON/identity shapes,
  standard aggregate overflow, premature close, reserved connect failure;
  deadline отдельно на acquisition/connect/write/headers/body и every permit
  terminal path. Dummy/JWT regression использует ту же gateway boundary.
- HMAC deterministic randomness с независимыми literal vectors: равные
  String contents, case/character differences, different secrets, concurrent
  mixed inputs. Review retained state/listeners/callbacks проверяет отсутствие
  raw token memoization и утечки Caffeine types за decorator.
- Real Caffeine с controlled monotonic ticker: TTL-1ns/exact TTL/TTL+1ns,
  lookup дольше TTL, frequent hits, idle; maintenance по test seam, без
  фиксирования eviction victim; pending key сохраняется при eviction.
- Shared success и каждый finite failure, independent keys, N same/different
  key waiters и N+1 overload, hit при полном лимите, повторное заполнение N
  slots после terminal. Both partial cancellations, single/final/repeated
  cancellation, late publication/generation, все race winners и защищённые
  completion APIs воспроизводятся causal barriers.
- Empty/filled/one-key/multi-key/repeated close; graceful и forced shutdown.
  Cleanup helper failures проверяются отдельно от static composition review;
  helper test не считается failure injection реального factory.
- Exact OTel before/after counters, actual removals, initiating span lineage
  после partial cancellation, no hit/join span; unique token/digest/secret/
  identity sentinels отсутствуют в logs/audit/metrics/traces/errors.
- Installed process: два одинаковых requests используют один Bridge call,
  restart вызывает fresh lookup, invalid TTL/size и setting в DUMMY/JWT дают
  safe exit 2; forced shutdown двух coalesced callers отменяет один exchange
  без upstream. `MainTest` сохраняет startup process regression.
- Pure URL normalization и normalized assembly, ANY/USER/GROUP anonymous
  matching, immutable collections; public handoff phase-only, response model
  isolation и concurrent scopes, completion/timeout/cancellation/error cleanup.

Используются `GatewayTestFixture.awaitUntil`, `loopbackHttpAddress`,
`GatewayProcessFixture`, `runAllCleanupActions` и `closeAllResources`.
Сигнал приходит после owning observation (Bridge cancellation, counter/span,
request log), не просто после client completion. Waits bounded, `sleep` и
повторное использование released ephemeral port не применяются. Никакого
реального ожидания cache TTL, автоматического snapshot approval или нового
performance qualification эти checks не подразумевают.

## OWASP dependency check

Сканируется только `runtimeClasspath`, то есть dependencies, которые попадают
в application distribution. Build ломается при Critical vulnerability с
CVSS `9.0` или выше.

NVD API key читается из:

- Gradle property `nvdApiKey`, например в
  `~/.gradle/gradle.properties`;
- environment variable `NVD_API_KEY`.

Ключ можно запросить на
[NVD developers portal](https://nvd.nist.gov/developers/request-an-api-key).
Первичная синхронизация NVD database может занять десятки минут. Report
сохраняется в `build/reports/dependency-check/`. False positives подавляются с
обоснованием в
[config/dependency-check/suppressions.xml](../config/dependency-check/suppressions.xml).

## Mutation testing

~~~bash
./gradlew pitest
~~~

PIT анализирует `io.vigilant.*` classes и сохраняет HTML/XML reports в
`build/reports/pitest/`. Mutation testing запускается по требованию и не входит
в `build`, `verifyAll` или CI.

## Performance

JMH baseline deterministic PII detector:

~~~bash
./gradlew piiJmhBaseline
~~~

Артефакты сохраняются в `build/reports/pii/jmh/`:

- `baseline.json`;
- `baseline.txt`;
- `environment.properties`.

### PII JMH methodology

Gradle JMH plugin `0.7.3`, JMH `1.37` изолированы в benchmark configuration;
`piiProductionRuntimeClasspathCheck` запрещает их в production runtime.
`SampleTime`, один thread, microseconds, p50/p95/p99. Текущие defaults:
JDK 25, `-Xms1g -Xmx1g`, 2 forks, 3 warmup iterations по 1 s и 5 measurement
iterations по 1 s. Warmup отделяет JIT/GC effects; report фиксирует CPU/model,
cores, RAM, OS, JVM/version/flags, forks, warmup, iterations и команды.

Matrix - Cartesian product ASCII/Russian/mixed-Unicode background и размеров
`1 KiB`, `64 KiB`, `1 MiB` с каждым scenario:

- early EMAIL stop-on-first;
- finding каждого последующего типа при включённых предшествующих recognizer-ах;
- worst-case no-match full scan с похожими, но invalid/checksum-invalid inputs;
- full scan с несколькими findings каждого типа.

Для RU_OMS stop-first PAYMENT_CARD отключён из-за неизбежного cross-type
overlap; full scan сохраняет оба. Background class описывает padding, positive
fragment сохраняет обязательный Unicode/context (в том числе русский паспорт).
Измеряется только синхронный `detect`: HTTP, DI, queue/executor handoff и
orchestration не входят. Trial setup валидирует размер, scenario и finding
count вне measurement. Payload/findings не печатаются. У baseline нет отдельного
numeric latency release gate; он не подтверждает current enforcement SLO.

### PII quality qualification

Явная команда использует заранее сохранённые и проверенные baseline artifacts:

~~~bash
./gradlew piiQualityQualification \
  -PpiiQualificationBaselineDirectory=/absolute/path/to/reviewed-baseline
~~~

Directory обязан содержать `redmadrobot-pii-benchmark.json`, `jmh.json`,
`environment.properties`, `revision.txt`; baseline detector нельзя выбирать заново
после просмотра evaluation ради улучшения результата. При смене reference
сохранить старые artifacts и создать новый baseline directory: скопировать
неизменные paired JMH/environment/revision, собрать production `jar` из этой
точной Git-ревизии в отдельном checkout и сохранить его как `detector.jar`.
Пересчитать старый detector текущим adapter/scorer:

~~~bash
./gradlew redMadRobotPiiBaselineBenchmark \
  -PpiiQualificationBaselineDirectory=/absolute/path/to/rescored-baseline
~~~

Task исключает current main output из runtime classpath и использует frozen
`detector.jar`. Reference должен совпадать с current по ID/digest, dataset,
split и partition coverage. Старые reports без nested reference не принимаются.
Qualification task запускает external и
canonical reports и 18 paired JMH cases: 3 backgrounds x 3 sizes x
`NO_MATCH_FULL_SCAN,FULL_SCAN`. Сравнение median p95/p99 использует одинаковые
environment/JVM/forks/warmup/iterations и
[согласованные floors](../spec/requirements/fast-pii.md#quality).
JSON/Markdown в `build/reports/pii/qualification/` фиксируют baseline/current
Git revision и dirty state, dataset revision/checksum, corpus version, JMH
environment, команды, reference ID/digest, added/normalized counts,
full/tuning/evaluation и отдельный
product-aligned view. Все aggregate, IP и PHONE floors и strict evaluation
improvement проверяются на общем nested reference; исходные source-aligned
metrics и их verdict публикуются отдельно как diagnostics.
Historical numbers не переносятся в normative contract и не являются свежим
enforcement evidence. Текущий статус зафиксирован в
[coverage](requirements-coverage.md#pii-и-windowing).

### Gateway performance

PERF-01 direct-vs-gateway load test:

~~~bash
./gradlew perfTest
~~~

Полный run не входит в обычные проверки. Быстрый contract test сценария:

~~~bash
./gradlew perfContractTest
~~~

Методика, output artifacts и параметры smoke profile находятся в
[perf-01-load-test.md](perf-01-load-test.md). Generated результаты относятся
только к своему snapshot и не коммитятся как current guarantee. Текущий
request/response enforcement target и evidence gap отражены в
[requirements coverage](requirements-coverage.md#нефункциональные-требования-mvp).

Production inspection phase и packaged load profile:

~~~bash
./gradlew inspectionPhaseBenchmark
./gradlew inspectionLoadTest
~~~

Первый task публикует p50/p95/p99 parsing, windowing, policy evaluation и total
inspection в `build/reports/inspection/phase/`. Второй запускает packaged
gateway и upstream отдельными JVM, выполняет полный профиль `2 000 RPS` с
request `64 KiB` и пишет safe summary в `build/reports/inspection/load/`.
Оба task запускаются явно и не входят в обычный `build`. Зафиксированный
historical run не подтверждает текущий request/response enforcement SLO;
current status находится в
[requirements coverage](requirements-coverage.md#нефункциональные-требования-mvp).

## Git hooks

~~~bash
./gradlew installGitHooks
~~~

Команда устанавливает versioned pre-push hook из `config/git/hooks/`. Hook
запускает `./gradlew build` перед push.

## Локальные pipeline scripts

~~~bash
./scripts/pipeline-verify --snapshot <issue-session> --tool <JDK-home> --tool <Gradle-distribution>
./scripts/pipeline-sonar
~~~

`pipeline-verify` публикует один durable run/evidence ID через `check-run`.
Внутри нового run сначала выполняется `detekt` как cheap feedback, затем ровно
один `verifyAll dependencyCheckAnalyze --rerun`: verifyAll уже владеет build
и OWASP, а task-specific `--rerun` принудительно выполняет только OWASP,
чтобы новая verification session не наследовала external-data report как
UP-TO-DATE от другой задачи. Отдельного полного build
перед ним нет. Wrapper требует snapshot и installed toolchain declarations;
автоматически добавляет user Gradle properties, init.d, init.gradle/init.gradle.kts
(включая их отсутствие) и digest NVD_API_KEY. Используется весь Git-visible
worktree, включая новые root configuration files:
`verifyAll` действительно читает documentation/catalog fixtures. Передать те JDK
и Gradle distributions, которые использует environment/toolchain configuration.
User Gradle settings могут менять JDK selection; reviewer проверяет этот выбор.
Не передавать секреты аргументами. Private `--execute` - только child command
runner, не consumer entry point. Dispatch exit 0 не равен terminal PASS.

Artifacts: JUnit XML и HTML обеих test lanes, XML work-item fixtures, detekt XML
и OWASP HTML. Прежние Gradle UP-TO-DATE outputs для source-derived tasks допустимы, когда
сам Gradle подтвердил актуальность; runner затем проверяет их integrity.
OWASP при новом run выполняется явно, reuse между consumers происходит только
через current evidence внутри одной session. `--wave` отмечает
remediation, повторный вызов с неизменным contract возвращает прежний run.
Sonar остаётся отдельным обязательным gate, когда требуется scope задачи.
`pipeline-sonar`
поднимает локальный SonarQube в Docker, запускает tests, JaCoCo и Sonar analysis,
а затем фильтрует blocking findings по текущему verification scope. Для него
нужны Docker, `curl`, `jq`, Git и локальный `.claude/sonar.env`; подробные
требования и exit codes приведены в комментариях самого script.

## CI

[GitHub Actions workflow](../.github/workflows/ci.yml) настроен на события
pull request и push в `main` и предусматривает:

- обязательный `build` job;
- `dependency-check` job, который выполняет OWASP scan только при доступном
  secret `NVD_API_KEY`.

Ключ передаётся через environment job; условия отдельных steps проверяют
`env.NVD_API_KEY`. При доступном ключе выполняются checkout, Java/Gradle setup,
NVD cache и `./gradlew dependencyCheckAnalyze`; CVE report загружается и при
ошибке scan. Действуют [runtimeClasspath scope и CVSS gate](#owasp-dependency-check).

Если ключ не предоставлен, включая fork PR или Dependabot run без доступного
secret, job публикует notice в журнале и `SKIPPED` в GitHub step summary.
Setup, cache, scan и upload CVE report пропускаются. Успех такого job означает
успешное сообщение о пропуске, а не пройденный security scan. Обязательный
`./gradlew build` выполняется независимо от доступности ключа; его reports
загружаются при failure. Значение ключа не выводится и не передаётся в argv.

Mutation testing, PII report/внешний benchmark, OCI smoke, JMH baseline,
PERF-01, inspection phase/load и SonarQube
pipeline в текущий CI не входят.

### Локальная приёмка workflow

Валидность workflow проверяется actionlint. Условия steps можно проверить
локально через `@actions/expressions`, используя пустое значение
`env.NVD_API_KEY` и непустую синтетическую строку, которая не является ключом.
Для пустого входа выбран только skip-step; для непустого выбраны checkout,
Java/Gradle setup, NVD cache, dependency scan и upload report.
Сам skip shell-step должен публиковать notice и summary без значения ключа.
Fork/Dependabot без предоставленного secret соответствуют пустому входу;
такая локальная проверка не является запуском этих событий в GitHub.

Полный локальный `./gradlew build` через [durable runner](#устойчивый-запуск-проверок)
подтверждает сборку, tests и detekt на записанном commit и toolchain. Он не
подтверждает GitHub runner execution или актуальный OWASP scan.
Недоступность внешнего CI записывается отдельно от результатов локальных checks.

### Наблюдения CI

2026-09-11 [GitHub run 34641245206](https://github.com/finnetrolle/vigilant/actions/runs/34641245206)
на commit `96a4e7be1854ab3a88296f70378fa44fc2410c2a` создал build и OWASP jobs,
но обе завершились до первого step: GitHub сообщил о блокировке аккаунта
из-за billing issue. Реальный hosted build, skip-step и scan не подтверждены.
Repository secret `NVD_API_KEY` отсутствует; key-enabled scan не объявляется PASS.

Actionlint `1.7.12` воспроизвёл недопустимый secrets context исходного workflow
и принял исправленный YAML. Локальная проверка через `@actions/expressions`
`0.3.61` и YAML parser `2.8.1` подтвердила выбор steps для пустого/непустого
синтетического env. Точный skip shell-step выполнился с exit 0 и ожидаемыми
notice/summary. Эти результаты ограничены локальной конфигурацией и shell;
они не являются выполнением GitHub jobs или проверкой vulnerability database.

2026-09-12 полный локальный build commit
`96a4e7be1854ab3a88296f70378fa44fc2410c2a` прошёл на macOS с JDK `25.0.2`
и Gradle `9.7.1`: `/usr/bin/caffeinate -i ./gradlew build`, run
`b56cb6ac5ac84b829f74f63b79afc3fb`, exit 0. Reports содержат 57 process tests,
1520 остальных tests и 55 work-item validator tests, без failures/errors/skips.
Detekt и проверка отсутствия JMH в production runtimeClasspath прошли.
Команда `caffeinate` ограничивает автоматический сон временем build;
закрытие крышки она не предотвращает.

Первый полный run получил один ответ 503 вместо 200 в process test во время
интервала со сном macOS. Точный тест затем прошёл на неизменённом коде, как и
полный повторный build. Причина конкретного 503 не установлена; временное
совпадение со сном не считается доказанной причиной.
Проверка build относится к указанному commit; последующие изменения только
документации и каталога проверяются отдельно. Hosted execution и актуальный
OWASP scan этими результатами не подтверждаются.

## Поддержка документации

Документация текущего продукта находится в [docs/README.md](README.md), а
нормативные требования и статусы рабочих элементов - в `spec/`.
Производственная задача, меняющая поведение, конфигурацию, протокол,
безопасность данных или жизненный цикл, должна в том же наборе изменений
обновлять спецификацию-владельца, документ исполнения и
[карту покрытия требований](requirements-coverage.md).

Новые архитектурные схемы создаются только в нотации UML 2.0. Доступные для
ревью исходники PlantUML хранятся в
[`docs/diagrams/`](diagrams/README.md). Для диаграмм компонентов используется
`skinparam componentStyle uml2`; диаграммы последовательностей, состояний и
деятельности должны сохранять реальные переходы владения, отказов и конечных
состояний. Неформальные блок-схемы ASCII или Mermaid не используются как
нормативные схемы.

Перед завершением изменения документации необходимо проверить:

- относительные ссылки и якоря;
- точные значения по умолчанию и коды ошибок по производственному коду и
  тестам;
- отсутствие будущего поведения среди текущих возможностей;
- отсутствие полезной нагрузки, учётных данных, идентификаторов пользователей
  и обратимо преобразованных значений в примерах;
- `./gradlew validateWorkItems`, если изменялся `spec/`;
- `./gradlew build`, когда документация изменялась вместе с производственным
  кодом.

## Завершение work item

Нормативное правило находится в
[canonical project guide](agent-workflow.md#work-item-completion), формат каталога - в
[реестре](../spec/WORK_ITEMS.md#как-закрывать-work-item), permanent owners - в
[индексе требований](../spec/requirements/README.md).

1. Зафиксировать base revision. Прочитать согласованную issue, parent epic,
   dependencies и non-goals. Проверить, что точная согласованная версия source
   уже доступна в Git (`git log --all -- <source>` и `git show <revision>:<source>`).
   Если единственная версия ещё не сохранена в Git, сначала сохранить её обычным
   project Git workflow. Не создавать archive или historical wrapper.
2. Построить временную матрицу `source clause -> disposition -> owner anchor ->
   evidence` вне tracked документации. Различать current requirement, runtime
   detail, verification method, obsolete contract и planning/history. Для
   quantified clauses перечислить каждый обязательный case и terminal path.
   Выполнить все проверки issue и применимых epic criteria; один green build
   не заменяет требуемые process, OCI, load или lifecycle observations.
3. Перенести clauses к owners, implementation details в runtime docs, применимые
   observations в evidence. Обновить coverage, incoming references и dependent
   issues. В `Зависит от` оставить открытые hard blockers; под
   `Выполненные предпосылки` дать прямые ссылки на реализованные capabilities.
4. Удалить completed issue, её row/checklist entry; для последнего child проверить
   весь parent outcome и удалить completed epic. Parent с remaining scope
   сохранить; без executable children перевести в `Draft` с причиной.
   Обновить registry progress и актуальный frontier, удалить временную matrix.
5. Проверить именно результирующее дерево после удаления. Для изменений только
   документации/work-item tooling достаточно следующих команд, если issue не
   требует дополнительных gates:

~~~bash
rtk proxy ./gradlew workItemValidatorTest --rerun validateWorkItems
rtk proxy git diff --check
rtk proxy git status --short
~~~

`--rerun` принудительно выполняет tooling fixture task после изменения только
Markdown: navigation tests читают working tree во время запуска. Сам
`validateWorkItems` каждый раз проверяет актуальный каталог.
Не начинать вторую Gradle invocation до подтверждённого завершения первой.
Для runtime changes сохраняются TDD и полный build из project guide.
Выделяя новый ID, проверять текущие titles и Git history, включая удалённые
пути и metadata (`git log --all --name-only -- spec/epics spec/issues`, затем
`git show` для прежних документов); максимум оставшихся filenames недостаточен.

### Проверка каталога и ссылок

`WorkItemValidator.validate(Path)` проверяет registry membership/status/progress,
epic checklist/backlinks, acceptance у legacy Done issues, dangling/self/cyclic
`Зависит от` edges и локальные documentation references. Wrapped dependency
metadata входит в проверку. Fulfilled capability links проверяются как обычные
ссылки, но validator не доказывает семантическую полноту переноса clauses.

Reference sweep охватывает root `README.md`, `CLAUDE.md`, `AGENTS.md`, Markdown
в `spec/` и `docs/`, UML `[[...]]` targets. Проверяются относительные и
root-relative files/directories, images, reference-style destinations, same-file
и cross-file anchors, Unicode/percent encoding, suffixes повторных headings
и explicit HTML IDs. Network links не проверяются; fenced/inline code evidence
не считается ссылкой. За пределы repository checker не читает. Diagnostics
упорядочены по файлу, номеру строки и причине; file-wide graph errors идут
перед line-specific errors того же файла.

Fixture suite воспроизводит standalone/child/last-child completion, замену
fulfilled prerequisites, пустые каталоги и ошибки ссылок/зависимостей, сохраняя
прежние graph regressions. Focused TDD run:

~~~bash
rtk proxy ./gradlew workItemValidatorTest --tests 'io.vigilant.spec.CompletionWorkflowTest'
rtk proxy ./gradlew workItemValidatorTest --tests 'io.vigilant.spec.DocumentationReferenceTest'
~~~


## Protocol contract checks

Нормативный [Chat Completions contract](../spec/requirements/chat-completions-protocol.md)
проверяется через public request/response parser seam над complete immutable
byte source и explicit versioned descriptor. Все expected fragments, order,
semantic kinds, roles, locators, model, coverage и gaps задаются независимо
от production calculation. Negative cases проверяют safe code и отсутствие
partial result/source preview. Правила KDoc и lifecycle tests действуют по
[project guide](agent-workflow.md#behavior-first-development-and-selective-tdd).

Обязательная matrix:

- Request: каждая строка recognized field map, все шесть roles, scalar/parts,
  text/media mixtures, modern/custom/deprecated tool forms, empty strings,
  invalid inner JSON, reasoning/opaque gaps, root/property order, unknown
  additive fields; каждый schema keyword/container, safe unknown scalar и
  rejected textual subtree, external/missing/cyclic local references.
- Routing: каждый descriptor dimension, media parameters/case, unsupported
  method/path/media/transport/version и no source access при mismatch.
  Invalid UTF-8/JSON, duplicate keys на каждом уровне, missing/invalid types,
  ambiguity, exact depth/fragment boundaries и cancellation.
- Ordinary response: content/refusal/reasoning_content/calls/audio, missing/null/empty branches,
  canonical order, gaps/coverage и every malformed/ambiguous shape.
- SSE: LF/CRLF, comments, empty и multi-line data, interleaved choices и tool
  calls, content/refusal/reasoning_content/modern/deprecated arguments; canonical concatenation,
  standalone DONE, EOF, incomplete/mixed/extra terminal, duplicate/missing
  indices, repeated incompatible shapes, unknown event/content, provider error,
  transport error и cancellation без partial state.
- Segmentation: весь source, одно-byte segments и split на каждом byte для
  JSON/SSE, включая UTF-8, JSON token, SSE field/separator; literal oracle
  проверяет значение, segmentation сравнивает устойчивость результата.
- Source maps: independent decoded UTF-8/raw positions для Unicode/escapes,
  multi-line data и cross-event spans; отсутствие source payload в metadata.
- HTTP: все пять [inspection errors](../spec/requirements/http-gateway.md#inspection-error-matrix)
  с literal status/body/Retry-After и privacy sentinels; request reject имеет
  нулевой upstream counter, JSON/SSE response reject не раскрывает upstream
  status, headers или partial body. Gap ALLOW replay byte-identical и audit
  outcome INSPECTION_GAP наблюдаются отдельно; audit publication ожидается на
  owning boundary, а не выводится из client completion.

Основные existing suites: `ChatCompletionsRequestParserTest`,
`ChatCompletionsResponseParserTest`, `RequestRewritePlannerTest`,
`JsonResponseRewriterTest`, `SseResponseRewriterTest`, `OpenAiErrorResponsesTest`,
`RequestInspectionE2eTest`, `JsonResponseEnforcementE2eTest`,
`SseResponseEnforcementE2eTest`, `GatewayIdentityE2eTest`.
Использовать canonical focused mode, например:

```bash
rtk proxy ./gradlew test -x processTest --tests 'io.vigilant.protocol.openai.ChatCompletions*ParserTest' --tests 'io.vigilant.gateway.proxy.OpenAiErrorResponsesTest'
```

Реальные gateway E2E дополняют encoder tests; static source/test review не
является новым dynamic run. Existing matrix completeness и несовпадения с
согласованным target указаны в [coverage](requirements-coverage.md#protocol-evidence).
Для documentation-only переноса требуются semantic clause review и
`rtk proxy ./gradlew workItemValidatorTest validateWorkItems`, затем
`rtk proxy git diff --check`; full build/OCI/load не подменяют semantic review
и не требуются без изменения production behavior.

## Response and gateway contract checks

Нормативные contracts: [RESPONSE enforcement](../spec/requirements/response-enforcement.md)
и [HTTP gateway](../spec/requirements/http-gateway.md). Response policy
проверяется на causal real-Armeria seam, а low-level wire `Connection` и
malformed HTTP cases используют raw HTTP/1 upstream, когда Armeria server сам
нормализует field раньше gateway. Все waits deadline-bounded; released
ephemeral port не резервируется повторно.

Обязательная matrix:

- Atomic response: ordinary EOF и standalone SSE `[DONE]`; отсутствие client
  status/header/body до upstream terminal и detector decision; `ALLOW`, `MASK`,
  `BLOCK`, invalid upstream и inspection failure без partial disclosure.
- Ordinary JSON: `200`/`429`/`500`; content, refusal, reasoning_content, modern/deprecated
  arguments и audio transcript; only-gap, clean+gap, detected+gap MASK/BLOCK;
  Unicode, escapes, unknown fields/order/formatting, source maps и invalid
  locators/boundaries.
- SSE: все пять logical field classes, interleaved choice/tool indexes,
  inside-event и cross-event spans, multiple/adjacent/overlapping masks,
  LF/CRLF, comments, multi-line data, empty covered values, Unicode/escapes,
  unknown metadata и deterministic repeat.
- Ownership: ingest success/failure/interruption, parser view, original/masked
  replay, rejection, detector timeout/failure, rewrite/handoff failure,
  repeated/closed/synchronous transfer, client cancellation before terminal,
  during analysis и at handoff, replay cancellation, normal drain и forced
  shutdown. Retained references наблюдаются на source owner.
- Transport: request/response streaming and bounded demand, ordered exact
  bytes, pre-response `502`/`504`, mid-response abort, cancellation propagation,
  sequential/concurrent/idle pooling, static and every `Connection`-named
  hop-by-hop field, end-to-end fields, upstream `4xx`/`5xx`, authority/base
  path/query/`Host`/`Content-Length`.
- Operations: local `/healthz` and `/readyz`, no upstream probe, readiness
  before close, new-traffic rejection, active/stuck exchange drain, cleanup
  ordering/failures, mandatory startup settings, installed distribution and
  non-root/read-only OCI cases when a task requires fresh packaging evidence.
- Privacy: client errors, logs, metrics and traces contain no payload, upstream
  body/header, credentials, identity or raw internal cause. HTTP outcomes use
  literal status/body/Retry-After oracles independent of production encoders.

Основные suites: `RetainedResponseSourceTest`, `ReplayReadyResponseTest`,
`JsonResponseRewriterTest`, `SseResponseRewriterTest`,
`JsonResponseEnforcementE2eTest`, `SseResponseEnforcementE2eTest`,
`BypassProxyServiceTest`, `BypassProxyRequestBackpressureTest`,
`BypassProxyResponseBackpressureTest`, `BypassProxyCancellationTest`,
`UpstreamConnectionPoolingTest`, `UpstreamTimeoutsTest`,
`HealthEndpointsTest`, `ShutdownLifecycleTest` и
`OpenAiErrorResponsesTest`.

Пример focused run без serial process lane:

```bash
rtk proxy ./gradlew test -x processTest --tests 'io.vigilant.source.RetainedResponseSourceTest' --tests 'io.vigilant.gateway.proxy.ReplayReadyResponseTest' --tests 'io.vigilant.gateway.proxy.JsonResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.proxy.SseResponseEnforcementE2eTest'
```

Process/OCI/load evidence выполняется только когда его требует current issue;
исторический PASS не переименовывается в новый. Текущие gaps и граница
source-review evidence находятся в
[coverage](requirements-coverage.md#response-and-gateway-evidence).

## Observability contract checks

Нормативный owner: [observability](../spec/requirements/observability.md).
Проверки разделяют application JSONL, analysis lifecycle, HTTP propagation,
in-memory SDK observations и packaged stdout. Один канал или test helper не
является evidence остальных каналов.

Обязательная matrix:

- Logging topology: единственный Logback provider/root `ASYNC_STDOUT`, один
  downstream `STDOUT`, exact queue `8192`, threshold `2048`, `neverBlock=true`,
  `includeCallerData=false`, `maxFlushTime=2000`, level default/override,
  one JSON object per line,
  queue-full producer completion, discard priority и bounded stop.
- Analysis pair: REQUEST, ordinary RESPONSE и SSE RESPONSE; causal started
  immediately before first detector, completed after terminal evaluation and
  before handoff/disclosure; `CLEAN`, `DETECTED`, `INSPECTION_GAP`, `ERROR`,
  `ALLOW`, `MASK`, `BLOCK`, exact aggregate fields и canonical ordering.
- Pair absence/lifecycle: unsupported/malformed, identity/context/source
  failure, empty selection и cancellation before detector; cancellation,
  deadline, detector/policy/rewrite failure after start produce at most one
  terminal ERROR; no second completion after ready handoff.
- Tracing: default/custom headers, valid/missing/malformed traceparent and
  tracestate, valid/empty/invalid session, UUIDv7 generation, client/upstream
  propagation, SERVER/request INTERNAL/upstream CLIENT/response INTERNAL tree,
  External cold miss parent retention and no hit/join span.
- Transport tracing: independently observe finished SDK spans, production OTLP
  stdout and installed Main stdout. Exercise bypass and retained JSON/SSE
  success, HTTP errors, policy blocks, refused connection, controlled DNS,
  pre/post-headers timeout, truncated body, cancellation before handoff/during
  upstream/during inspection/during replay and SERVER-only timeout. Check
  exact HTTP bytes/status/headers, span counts/IDs/tree/session/durations,
  finite category/status, empty description and no events/links/diagnostics.
  Type/wrapper cases include the 16/17 transition boundary and identity cycle;
  causal terminal barriers cover both timeout/cancel orders and a simultaneous
  race. See [transport evidence](transport-trace-evidence.md) for suite owners.
- Metrics: every proxy name/unit, 2xx/4xx/5xx, pre/mid-response timeout,
  transport failure, cancellation, active baseline and no-upstream duration;
  every External lookup/cache outcome and exact hit/miss/coalesced/removal
  lifecycle. Dedicated inspection instruments remain an explicit gap.
- Privacy: independent sentinels in body/preview, PII value/span, query,
  headers/cookies/credentials, identity/user/groups, session, propagation and
  exception message; assert each audit/log/metric/trace/client-error output
  against its own allowlist. A raw exception event is not accepted merely
  because client/log output is safe.
- OTLP stdout: enabled traces `resourceSpans`, metrics `resourceMetrics`,
  disabled export with internal collection retained, parallel line atomicity,
  provider flush/close after drain and no application network exporter.

Основные suites: `LoggingConfigurationTest`, `RequestInspectionE2eTest`,
`JsonResponseEnforcementE2eTest`, `SseResponseEnforcementE2eTest`,
`PiiShadowProxyProcessTest`, `TracingServiceTest`, `OtlpExportTest`,
`MetricsServiceTest`, `OtlpMetricsExportTest`, `BridgeIdentityClientTest` и
`CachingExternalIdentityLookupTest`.
Transport suites: `TransportTracePrivacyE2eTest`, `TransportTraceCauseE2eTest`,
`TransportTraceServerE2eTest`, `TransportTraceHttpE2eTest`,
`TransportTraceLifecycleE2eTest`, `TransportTraceRaceE2eTest` and installed
`TransportTracePrivacyProcessTest` (registered in `ProcessTestInventoryTest`).

Пример focused run без packaged/process lane:

```bash
./gradlew test -x processTest --tests 'io.vigilant.gateway.LoggingConfigurationTest' --tests 'io.vigilant.gateway.tracing.*' --tests 'io.vigilant.gateway.metrics.*'
```

Source review при documentation migration не становится новым runtime
evidence. Текущие mismatches, включая arbitrary metric error class и отсутствующие
inspection instruments, перечислены в
[coverage](requirements-coverage.md#observability-evidence).
