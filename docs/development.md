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
./gradlew test --tests "io.vigilant.gateway.proxy.BypassProxyServiceTest"
./gradlew run
./gradlew installDist
./gradlew ociArtifact
~~~

`run` компилирует и запускает `io.vigilant.gateway.MainKt` прямо из Gradle;
ему нужны те же environment variables и `politics.conf`, что packaged
application. `installDist` создаёт локальный runnable distribution в
`build/install/vigilant/`. `ociArtifact` создаёт reproducible versioned tar в
`build/distributions/`, который использует Dockerfile.

`./gradlew build` включает:

- Kotlin/JVM compilation с warnings-as-errors;
- unit, integration и E2E tests;
- detekt с project configuration
  [config/detekt/detekt.yml](../config/detekt/detekt.yml);
- fixture tests work-item validator;
- validation graph в `spec/`;
- проверку, что JMH dependencies отсутствуют в production runtime classpath.

Proxy behavior tests используют реальные Armeria servers на ephemeral ports.

## Дополнительные проверки

~~~bash
./gradlew detekt
./gradlew validateWorkItems
./gradlew piiProductionRuntimeClasspathCheck
./gradlew dependencyCheckAnalyze
./gradlew verifyAll
~~~

`verifyAll` объединяет `build` и OWASP dependency check.

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

Полная matrix и принятые scenario semantics зафиксированы в
[VIG-02-15](../spec/issues/epic_02/issue_02_15_jmh_baseline.md). Числового
release gate у baseline нет.

PERF-01 direct-vs-gateway load test:

~~~bash
./gradlew perfTest
~~~

Полный run не входит в обычные проверки. Быстрый contract test сценария:

~~~bash
./gradlew perfContractTest
~~~

Методика, output artifacts и параметры smoke profile находятся в
[perf-01-load-test.md](perf-01-load-test.md), зафиксированные runs - в
[perf-01-result.md](perf-01-result.md).

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
production run опубликован в
[inspection-load-result.md](inspection-load-result.md).

## Git hooks

~~~bash
./gradlew installGitHooks
~~~

Команда устанавливает versioned pre-push hook из `config/git/hooks/`. Hook
запускает `./gradlew build` перед push.

## Локальные pipeline scripts

~~~bash
./scripts/pipeline-verify
./scripts/pipeline-sonar
~~~

`pipeline-verify` последовательно выполняет `build` и `verifyAll`; второй шаг
повторно использует результаты Gradle и добавляет OWASP scan. `pipeline-sonar`
поднимает локальный SonarQube в Docker, запускает tests, JaCoCo и Sonar analysis,
а затем фильтрует blocking findings по текущему verification scope. Для него
нужны Docker, `curl`, `jq`, Git и локальный `.claude/sonar.env`; подробные
требования и exit codes приведены в комментариях самого script.

## CI

[GitHub Actions workflow](../.github/workflows/ci.yml) запускается для каждого
pull request и push в `main`:

- обязательный `build` job;
- OWASP dependency-check job при наличии repository secret `NVD_API_KEY`.

Mutation testing, PII report/внешний benchmark, OCI smoke, JMH baseline,
PERF-01, inspection phase/load и SonarQube pipeline в текущий CI не входят.
