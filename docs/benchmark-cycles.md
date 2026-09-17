# Полные benchmark cycles

Из корня repository:

```bash
./scripts/benchmark load
./scripts/benchmark pii-quality
```

Требуются Python 3.11+, Git, JDK 25 и возможность читать список локальных
процессов. Launcher выбирает JDK из `JAVA_HOME`, иначе из `java` в PATH;
этот же JDK используется Gradle и benchmark toolchains. Gradle Wrapper
подготавливает закреплённый distribution при первом online запуске.

Команда ждёт завершения, показывает progress в stderr и возвращает компактный
TOON result в stdout. Exit `0` означает завершённый цикл с пройденными
обязательными gates и current evidence; `1` - failed/incomplete/stale cycle;
`2` - ошибка аргументов или подготовки запуска. Низкие external PII scores
сами по себе не являются failure. `MEASURED` означает успешное измерение без
числового release gate, а не подтверждение качества production traffic.

## Состав

`load` выполняет последовательно:

1. `installDist`, `perfContractTest`, `inspectionResourceContractTest`, `jmhJar`.
2. `piiJmhBaseline`: полная матрица `3 backgrounds x 3 sizes x 12 scenarios`.
3. `inspectionPhaseBenchmark`: `4 phases x 2 sizes`.
4. `perfTest`: direct/default gateway/slow-sink, включая logging/JFR evidence.
5. `inspectionLoadTest`: packaged inspection profile.
6. `inspectionResourceQualification`: fixed max-shape, capacity, cancellation
   и shutdown/resource checks.

Измерения не выполняются параллельно. Нужен спокойный стенд без конкурирующей
нагрузки; launcher проверяет активные Gradle clients и фиксированные порты
`18080..18082`, `19080..19085`. Это preflight, не резервирование портов:
владельцы fixture повторяют проверку непосредственно перед запуском.
Проверка процессов не заменяет договорённость не запускать сторонний Gradle
или нагрузку во время цикла. Сам `check-run` сериализует свои commands в worktree.

`pii-quality` подготавливает `testClasses`, затем запускает `piiQualityReport`,
`redMadRobotPiiBenchmark`, `hiveTracePiiBenchmark` и `advPiiBenchmark`.
Canonical остаётся release gate; три внешних корпуса остаются non-gating.
Source/product/reference views, exact/relaxed scores, denominators и privacy
ограничения сохраняют контракты своих runners. Единого F1 по корпусам нет.

Дополнительная qualification требует заранее сохранённый reviewed baseline:

```bash
./scripts/benchmark pii-quality --baseline /absolute/path/to/reviewed-baseline
```

После четырёх корпусов выполняется существующая `piiQualityQualification`,
включая paired JMH. Directory содержит `redmadrobot-pii-benchmark.json`,
`jmh.json`, `environment.properties`, `revision.txt`. Совместимость reference,
окружения и floors проверяет существующая qualification; launcher не выбирает,
не пересчитывает и не заменяет baseline. Методика находится в
[development guide](development.md#pii-quality-qualification).

## Данные и offline

Online режим использует существующие preparers: проверенный cache либо загрузка
pinned bytes. Можно задать локальные inputs:

```bash
./scripts/benchmark pii-quality --offline \
  --redmadrobot /absolute/path/to/test.csv \
  --hivetrace /absolute/path/to/hivetrace \
  --advpii /absolute/path/to/advpii
```

Без указанных путей `--offline` явно передаёт preparers их локальные caches:
`build/redmadrobot-pii/test.csv`, `build/hivetrace-pii/`, `build/advpii/`.
Это запрещает незаметную загрузку dataset при пропущенном кеше. Integrity и
coverage проверяются теми же preparers/evaluators, что и online. Offline также
требует уже установленный Wrapper distribution и кеш Gradle dependencies.

## Lifecycle и результаты

```bash
./scripts/benchmark load --detach
./scripts/check-run status <run-id>
./scripts/check-run wait <run-id> --seconds 60
./scripts/check-run cancel <run-id>
```

`--detach` возвращает подтверждение запуска, не PASS. Единственный supervisor -
`scripts/check-run`; launcher не создаёт второй supervisor или execution lock.
В обычном foreground режиме Ctrl-C запрашивает cancellation и ждёт cleanup.
Закрытие терминала не теряет detached worker. Cycle timeout по умолчанию
`14400` секунд, меняется через `--timeout <seconds>`. Отмена/timeout сохраняют
partial summary; точный terminal state и завершение process group принадлежат
`check-run status`. При принудительном KILL summary может остаться running,
поэтому один summary без supervisor verdict не является доказательством PASS.

Каждая команда создаёт новый cycle ID и snapshot. Измерения всегда свежие;
компиляция и подготовительные tests могут использовать валидный Gradle cache.
JMH producer явно rerun; shortened/smoke profile, неполная JMH matrix, missing
reports и `DEVIATION` не принимаются как полный успешный цикл.
Ошибка одного измерителя сохраняется, после чего выполняются независимые
этапы. Ошибка подготовки или занятые ресурсы останавливают зависимую работу;
неисполненные этапы перечислены как `not_run`.

Cycle ID и supervisor run ID различаются; launcher печатает оба. Результаты:

```text
build/reports/benchmark/<cycle-id>/
  config.json
  summary.md
  summary.json
  prepare.log
  <stage>/command.log
  <stage>/reports/...
  <stage>/*-processes/...
```

Summary содержит состав цикла, timestamps/duration, Git revision/dirty state,
окружение, команды, отдельные verdicts и метрики, прямые ссылки на исходные
JSON/Markdown/Gatling HTML. Process logs и JFR также изолированы по этапу.
`-PbenchmarkOutputRoot=<directory>` - используемый launcher-ом Gradle input
для маршрутизации benchmark artifacts; без него standalone tasks сохраняют
обычные output paths.

Supervisor хранит durable records в `<git-dir>/check-runs/<run-id>/`: input и
tool digests до/после, exit, логи и artifact integrity. Launcher декларирует
JDK, Gradle, user Gradle configuration, cycle configuration и явные external
inputs; дополнительные влияющие tools/configuration задаются повторяемым
`--tool <path>`. До reuse обязательно проверить `check-run status`: изменение
исходников, окружения или артефактов исключает current evidence. Не редактировать
inputs во время измерений. Reports под `build/` удаляются `clean` и не коммитятся.

Ambient `VIGILANT_*` исключены из benchmark environment. Launcher задаёт собственный
пустой `vigilant.conf`, который входит в input digest; packaged fixtures добавляют
свои фиксированные параметры. Локальный `vigilant.conf`, `/etc/vigilant/vigilant.conf`
и пользовательские overrides не меняют профиль цикла. Команда без аргументов
показывает лишь `measurement_state` последнего summary с `applicability: unverified`;
terminal verdict всегда проверяется через supervisor.

## Граница выводов и проверка launcher

Цикл автоматизирует существующую матрицу. Он не добавляет отсутствующий профиль
актуального request/response enforcement SLO из `PERF-01/02` и не меняет их
[статус покрытия](requirements-coverage.md#нефункциональные-требования-mvp).
JMH измеряет синхронные операции; existing gateway profiles измеряют собственные
request-policy scenarios. Ни успешный запуск, ни их numeric results не устраняют
этот evidence gap. Heavy cycles не входят в `build`, `check`, `verifyAll` или CI.

Gateway fixtures используют валидные Chat Completions JSON/SSE responses с
сохранением wire-byte budgets и задержек chunks; resource requests включают
синтетический Bearer credential для DUMMY identity. Audit reader связывает
`policy.analysis_completed` фазы REQUEST с transport `request_completed` по
trace ID и SERVER span, поскольку session отсутствует в analysis event.
Для parser/capacity rejection и cancellation до analysis отсутствие пары
проверяется вместе с HTTP, отсутствием upstream handoff и серверными quota
barriers. Эти изменения совместимости не снижают latency, delivery, memory
или completeness gates и не делают старые результаты сопоставимым baseline.

Focused checks:

```bash
python3 -m unittest discover -s scripts/tests -p test_benchmark.py
./gradlew detekt perfContractTest
```

CLI tests используют настоящий supervisor и процессы на внешней Gradle boundary:
порядок, fresh outputs, non-gating scores, failure/missing reports, smoke/partial
matrix rejection, concurrency, cancellation, timeout и artifact/input integrity.
Java contracts проверяют согласованность text/JSON вердиктов на независимых
известных measurement inputs. Полные dynamic cycles выполняются отдельно.
