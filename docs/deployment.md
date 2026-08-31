# Deployment

## Versioned artifact

Формат приложения зафиксирован как reproducible Gradle application
distribution:

~~~bash
./gradlew ociArtifact
~~~

Команда создаёт `build/distributions/vigilant-<version>.tar` с versioned
application JAR, runtime dependencies и start scripts. Для локального запуска
без container можно использовать:

~~~bash
./gradlew installDist
./build/install/vigilant/bin/vigilant
~~~

Перед запуском необходимо настроить upstream, предоставить валидный
`politics.conf` и создать persistent audit directory. Полный список настроек находится в
[configuration reference](configuration.md).

## OCI image

Multi-stage [Dockerfile](../Dockerfile) собирает `ociArtifact` в digest-pinned
JDK 25 builder и переносит distribution в digest-pinned JRE 25 image.

~~~bash
docker build --tag vigilant:0.1.0-SNAPSHOT .
~~~

Container:

- запускается от non-root UID/GID `10001`;
- использует `/opt/vigilant/bin/vigilant` как entrypoint;
- объявляет port `8080`;
- использует `STOPSIGNAL SIGTERM`;
- объявляет `/var/lib/vigilant/audit` как writable audit volume;
- не содержит встроенной upstream или policy configuration.

## Запуск через environment

~~~bash
docker run --rm --name vigilant \
  --publish 8080:8080 \
  --stop-timeout 35 \
  --env VIGILANT_UPSTREAM_URL=https://api.openai.com \
  --env VIGILANT_POLITICS_CONFIG=/etc/vigilant/politics.conf \
  --mount type=bind,src="$PWD/politics.conf",dst=/etc/vigilant/politics.conf,readonly \
  --mount type=volume,src=vigilant-audit,dst=/var/lib/vigilant/audit \
  vigilant:0.1.0-SNAPSHOT
~~~

## Запуск с HOCON

~~~bash
docker run --rm --name vigilant \
  --publish 8080:8080 \
  --stop-timeout 35 \
  --mount type=bind,src="$PWD/vigilant.conf",dst=/etc/vigilant/vigilant.conf,readonly \
  --env VIGILANT_POLITICS_CONFIG=/etc/vigilant/politics.conf \
  --mount type=bind,src="$PWD/politics.conf",dst=/etc/vigilant/politics.conf,readonly \
  --mount type=volume,src=vigilant-audit,dst=/var/lib/vigilant/audit \
  vigilant:0.1.0-SNAPSHOT
~~~

Configuration files монтируются read-only. Audit volume обязан сохраняться при
container restart, быть доступен только UID/GID `10001` и использовать
подходящее deployment encryption at rest. Для production deployment secrets
должны передаваться через предназначенный для них secret mechanism, а не
записываться в image или config committed в repository.

## Health и lifecycle

Orchestrator probes:

- `GET /healthz` - liveness;
- `GET /readyz` - readiness.

`SIGTERM` запускает JVM shutdown hook. Readiness переключается на `503`, новые
audit admissions запрещаются, active exchanges получают bounded drain, затем
WAL force-ится и seal-ится до закрытия inspection, upstream и OpenTelemetry
resources.

Default graceful shutdown force timeout равен 30 seconds. Container stop
timeout 35 seconds сохраняет этот budget и снижает риск последующего
`SIGKILL`.

## STDOUT telemetry

Container пишет только в stdout. Там находятся два логических потока:
application logs в JSON Lines и OpenTelemetry traces/metrics в OTLP JSON Lines.
Runtime обязан использовать non-blocking delivery, ротацию и bounded local
storage. Collector должен разделять записи по top-level `resourceSpans` и
`resourceMetrics`; остальные JSON records являются application logs. Требования
и пример pipeline приведены в [observability reference](observability.md).

## OCI smoke test

~~~bash
./scripts/oci-smoke-test
~~~

Требования:

- `cmp`;
- `curl`;
- Docker;
- Python 3.

Скрипт собирает временный image и проверяет:

- env-only и HOCON configuration;
- read-only policy mount;
- non-root user;
- `/healthz` и `/readyz`;
- реальный PII Chat Completions request;
- byte-identical request replay;
- safe JSONL shadow audit;
- exit code `2` для невалидной application/policy configuration;
- graceful SIGTERM shutdown.

Smoke test удаляет созданные container/image resources при завершении. Он
запускается явно и не входит в `./gradlew build` или CI.

## Не входит в поставку

Текущий repository не предоставляет:

- registry publication;
- multi-arch images;
- Kubernetes manifests или Helm charts;
- autoscaling policy;
- централизованный Collector, log storage, dashboards или alerts.
