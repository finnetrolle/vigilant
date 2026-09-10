# Docker operations evidence

## Граница наблюдения

Проверки 2026-09-10 относятся к текущим исходникам runtime на Git revision
`fb4956b` (production/build inputs совпадают с `4272b36`), application version
`0.1.0-SNAPSHOT`. Session durable runner: `vig33-20260910`.
Runtime, зависимости, Dockerfile и shutdown configuration не менялись.

Это локальные app/OCI observations с synthetic запросами, test environment и
DUMMY identity. Они не квалифицируют production identity integration,
multi-replica rollout, внешний probe, alert delivery или Fluentd/Collector/
OpenObserve. Эти сценарии выполняет администратор по
[отдельному checklist](operations.md#приёмка-на-стенде). Численный availability
SLO и production measurements отсутствуют. Known privacy/metrics gaps из
[coverage](requirements-coverage.md#observability-evidence) сохраняются.

## Проверки приложения

| Проверка | Наблюдение и независимая проверка | Результат / run |
|---|---|---|
| Logging, OTLP exporters, health | Production Logback topology и literal queue bounds; полный JSON log envelope; enabled/disabled trace/metric output с independently named SDK instruments; реальные HTTP probes с literal `200 ok`, `200 ready`, `503 draining`, zero upstream probes | 15 tests, 0 failures/errors/skips; `6d890123a2674301b119d71719f51a65` |
| JVM shutdown | SIGTERM после causal upstream-start barrier: 503 readiness, новый request отклонён без нового upstream path; сохранённый literal prefix+suffix полностью получен. Отдельный never-ending upstream закрывается по configured force bound, процесс завершается | 2 tests, 0 failures/errors/skips; `2eba7d4ed43e4a6dbb10d6af0ba162a9` |
| OCI smoke | Реальный non-root Docker с read-only policy: ALLOW exact digest, shortened IP MASK independently written expected digest, policy/structural BLOCK с exact 403/body и zero upstream count, старый REQUEST error=ALLOW отклонён с exit 2; safe audit pair и SIGTERM exit 0/143 | Все пять cases PASS, exit 0; `4dd6e754361640709eb084d260732333` |

В первом run `HealthEndpointsTest` содержит четыре non-process tests;
его отдельный tagged process method этой командой не запускался. Shutdown run
отдельно наблюдает readiness/admission/drain и forced close через production
child process. Force test использует 2s с 2.5s tolerance; это не измерение
production recovery и не новая гарантия точности default 30s.

Физическое выполнение этих XML состоялось 2026-09-10 в 08:58 UTC (`test`,
первый run `d505952e7a3d4302b18a070a0a81aba6`) и 08:59 UTC (`processTest`,
первый run `8417722de6434179a2e07e46712897f5`). Итоговые focused команды в таблице
получили Gradle `UP-TO-DATE`: runtime и эти test inputs не изменялись.
Это актуальные сохранённые результаты, а не повторное физическое исполнение
17 тестов. Catalog fixtures и Docker observations выполнялись отдельно.

Точные команды дочерних checks (запускать последовательно через
[durable runner](development.md#устойчивый-запуск-проверок)):

```bash
rtk proxy ./gradlew test -x processTest --tests io.vigilant.gateway.LoggingConfigurationTest --tests io.vigilant.gateway.tracing.OtlpExportTest --tests io.vigilant.gateway.metrics.OtlpMetricsExportTest --tests io.vigilant.gateway.health.HealthEndpointsTest
rtk proxy ./gradlew processTest --tests io.vigilant.gateway.ShutdownLifecycleTest
rtk proxy ./scripts/oci-smoke-test
```

Selected runtime inputs каждого run: `src`, `buildSrc`, `gradle`, `gradlew`,
`build.gradle.kts`, `settings.gradle.kts`, `config`. OCI дополнительно выбирает
`Dockerfile`, `.dockerignore`, `scripts/oci-smoke-test`,
`scripts/lib/packaged-smoke-helpers`, `politics.conf.example` и digests
`DOCKER_HOST`, `DOCKER_CONTEXT`, `VIGILANT_SKIP_OCI_ARTIFACT`. Runner автоматически
сохраняет tool/environment provenance и before/after snapshots. Reports:
`build/test-results/test`, `build/test-results/processTest` и distribution tar.
Перед reuse проверяется `rtk proxy ./scripts/check-run status <run-id>`:
требуется `applicability: current`, а не только прежний exit 0.

## Наблюдение stdout artifact

Run `470fddcb9bca4162a755329bb831da9d`: PASS, exit 0. Docker client/server
`29.1.5 / 29.2.0`, image platform `linux/arm64`.

- Image ID: `sha256:93e6c0ac2d656516e115b16b6a384b9d12033e0c7d133b68f1a03c1ebf7f06fd`.
- Packaged JAR: `lib/vigilant-0.1.0-SNAPSHOT.jar`, SHA-256
  `53e5d1fc2792a18a8be5625c9e25588244ca66a645af6b191d7dcc717f24ba04`.
- Фактически разобраны 11 application records, один trace ExportRequest
  с четырьмя spans и два metric ExportRequests. Все 14 строк - отдельные
  валидные JSON objects с однозначно распознанным envelope.
- Получен один `request_completed`; его MDC trace ID совпал с exported span.
  `vigilant.proxy.requests` имел unit `{request}` и value 1. Также наблюдались
  proxy responses, active_requests, gateway/upstream duration и SDK collection
  duration; это не проверка всех возможных instrument/outcome combinations.
- После `docker stop --timeout 35` process exit 143. Контейнер, временный
  image и fixture upstream удалены; результаты остались в
  `build/operations-stdout/output/`. Числа records относятся к этому run и
  не являются фиксированным batching contract или zero-loss guarantee.

OCI smoke с `VIGILANT_OTLP_ENABLED=false` проверяет HTTP/enforcement и audit,
но не заменяет эту отдельную проверку. При OTLP=true один полный synthetic
Chat Completions exchange должен дать application completion, непустые
`resourceSpans` и `resourceMetrics` из реального контейнера. JSON decoder
проверяет каждую stdout строку, trace ID completion сопоставляется с exported
span, а instrument `vigilant.proxy.requests` имеет unit `{request}` и observed
value 1. Ожидание production shutdown завершает flush; arbitrary sleep для
ожидания exporter не используется.

Для повторения из корня repository нужны Docker, curl, Python 3, Bash и JDK 25.
Сначала выполнить `rtk proxy ./gradlew compileGatlingJava installDist` через
runner либо использовать результат OCI smoke на тех же inputs. Сохранить
следующий shell block как `build/operations-stdout/observe.sh`, затем выполнить:

```bash
rtk proxy bash build/operations-stdout/observe.sh
```

Для durable run выбрать runtime/OCI inputs выше, добавить
`--tool build/operations-stdout/observe.sh`, `--tool build/classes/java/gatling`,
`--tool build/install/vigilant` и
`--artifact directory:build/operations-stdout/output`. Временный сценарий
использует canonical `packaged-smoke-helpers` для порта, запуска Armeria,
readiness, полного literal response и cleanup upstream. Это методика наблюдения,
не новый runtime launcher или конфигурация внешней telemetry chain.
`fixture-only-token` - публичное тестовое значение DUMMY, без реальных credentials.
Raw output остаётся локально; перед передачей evidence администратор проверяет
его на отсутствие секретов и клиентских данных.

```bash
#!/usr/bin/env bash
set -euo pipefail
source ./scripts/lib/packaged-smoke-helpers
PROJECT_DIR="$PWD"
OUTPUT_DIR="$PROJECT_DIR/build/operations-stdout/output"
TMP_DIR=""
IMAGE_TAG="vigilant:stdout-$$"
CONTAINER_NAME="vigilant-stdout-$$"
UPSTREAM_PID=""
IMAGE_ATTEMPTED=0
CONTAINER_ATTEMPTED=0

# Attempt every resource cleanup and preserve the original failure, if any.
cleanup() {
    local status=$?
    trap - EXIT
    if [[ "$CONTAINER_ATTEMPTED" == 1 ]]; then
        docker rm --force "$CONTAINER_NAME" >/dev/null || { [[ "$status" != 0 ]] || status=1; }
    fi
    if [[ -n "$UPSTREAM_PID" ]]; then
        smoke_stop_process "$UPSTREAM_PID" "stdout upstream" "$OUTPUT_DIR/upstream.log" || { [[ "$status" != 0 ]] || status=1; }
    fi
    if [[ "$IMAGE_ATTEMPTED" == 1 ]]; then
        docker image rm "$IMAGE_TAG" >/dev/null || { [[ "$status" != 0 ]] || status=1; }
    fi
    if [[ -n "$TMP_DIR" ]]; then
        rm -rf "$TMP_DIR" || { [[ "$status" != 0 ]] || status=1; }
    fi
    exit "$status"
}
trap cleanup EXIT

mkdir -p "$OUTPUT_DIR"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/vigilant-stdout.XXXXXX")"

smoke_require_available_non_ephemeral_port 18181 "stdout upstream"
cp politics.conf.example "$TMP_DIR/politics.conf"
chmod 444 "$TMP_DIR/politics.conf"
printf '%s' '{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}' > "$TMP_DIR/request.json"
IMAGE_ATTEMPTED=1
docker build --tag "$IMAGE_TAG" . > "$OUTPUT_DIR/build.log" 2>&1
docker image inspect --format '{{.Id}} {{.Os}}/{{.Architecture}}' "$IMAGE_TAG" > "$OUTPUT_DIR/image.txt"
docker run --rm --entrypoint /bin/sh "$IMAGE_TAG" -c 'sha256sum lib/vigilant-*.jar' > "$OUTPUT_DIR/jar.txt"
docker version --format '{{.Client.Version}} {{.Server.Version}}' > "$OUTPUT_DIR/docker.txt"
smoke_start_armeria_upstream "$PROJECT_DIR" 18181 "$OUTPUT_DIR/upstream.log"
UPSTREAM_PID="$SMOKE_STARTED_PROCESS_PID"
CONTAINER_ATTEMPTED=1
docker run --detach --name "$CONTAINER_NAME" --stop-timeout 35 \
    --add-host host.docker.internal:host-gateway \
    --env VIGILANT_UPSTREAM_URL=http://host.docker.internal:18181 \
    --env VIGILANT_ENVIRONMENT=test --env VIGILANT_IDENTITY_MODE=DUMMY \
    --env VIGILANT_IDENTITY_DUMMY_USER=stdout-fixture \
    --env VIGILANT_OTLP_ENABLED=true \
    --env VIGILANT_POLITICS_CONFIG=/etc/vigilant/politics.conf \
    --mount "type=bind,src=$TMP_DIR/politics.conf,dst=/etc/vigilant/politics.conf,readonly" \
    --publish 127.0.0.1::8080 "$IMAGE_TAG" > "$OUTPUT_DIR/container.txt"
GATEWAY_PORT="$(docker port "$CONTAINER_NAME" 8080/tcp | sed -E 's/.*:([0-9]+)$/\1/' | head -n 1)"
smoke_await_http "" "http://127.0.0.1:$GATEWAY_PORT/readyz" "" "stdout readiness"
smoke_proxy_exact_request "http://127.0.0.1:$GATEWAY_PORT" \
    "$TMP_DIR/request.json" "$TMP_DIR/response.json" \
    "fixture-only-token" "stdout-check" "stdout request"
# Wait for process termination: production shutdown flushes both OTel providers.
docker stop --timeout 35 "$CONTAINER_NAME" >/dev/null
docker inspect --format '{{.State.ExitCode}}' "$CONTAINER_NAME" > "$OUTPUT_DIR/exit.txt"
docker logs "$CONTAINER_NAME" > "$OUTPUT_DIR/stdout.jsonl" 2> "$OUTPUT_DIR/stderr.log"
python3 - "$OUTPUT_DIR" <<'PY'
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
assert root.joinpath('exit.txt').read_text().strip() in ('0', '143')
raw = root.joinpath('stdout.jsonl').read_bytes()
assert raw.endswith(b'\n'), 'truncated final stdout record'
logs, traces, metrics = [], [], []
for line in raw.splitlines():
    record = json.loads(line)
    assert isinstance(record, dict)
    kinds = [all(k in record for k in ('timestamp', 'level', 'loggerName', 'formattedMessage')),
             isinstance(record.get('resourceSpans'), list),
             isinstance(record.get('resourceMetrics'), list)]
    assert sum(kinds) == 1, 'unknown or ambiguous stdout envelope'
    (logs, traces, metrics)[kinds.index(True)].append(record)
assert logs and traces and metrics, 'missing stdout signal type'
completed = [r for r in logs if any(p.get('event.name') == 'request_completed' for p in r.get('kvpList', []))]
assert len(completed) == 1, 'expected the synthetic request completion'
spans = [s for r in traces for resource in r['resourceSpans']
         for scope in resource['scopeSpans'] for s in scope['spans']]
assert spans and completed[0]['mdc']['trace_id'] in {s['traceId'] for s in spans}
instruments = [m for r in metrics for resource in r['resourceMetrics']
               for scope in resource['scopeMetrics'] for m in scope['metrics']]
requests = [m for m in instruments if m['name'] == 'vigilant.proxy.requests']
assert requests and all(m['unit'] == '{request}' for m in requests)
request_counts = sorted({int(p['asInt']) for m in requests for p in m['sum']['dataPoints']})
assert request_counts and request_counts[-1] == 1
summary = {'application_records': len(logs), 'trace_records': len(traces),
           'metric_records': len(metrics), 'spans': len(spans),
           'metric_names': sorted({m['name'] for m in instruments}),
           'request_completed': len(completed), 'correlation': 'matched',
           'proxy_request_counts': request_counts, 'exit': root.joinpath('exit.txt').read_text().strip()}
root.joinpath('summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary))
PY
```
