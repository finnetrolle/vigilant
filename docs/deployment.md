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

Перед запуском необходимо настроить upstream и предоставить валидный
`politics.conf`. Полный список настроек находится в
[configuration reference](configuration.md).

После `installDist` повторяемый smoke-test запускает generated start script с
env-only и HOCON configuration без audit settings против live upstream:

~~~bash
./scripts/installed-distribution-smoke-test
~~~

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
- не объявляет persistent directory или volume;
- не содержит встроенной upstream или policy configuration.

## Запуск через environment

~~~bash
docker run --rm --name vigilant \
  --publish 8080:8080 \
  --stop-timeout 35 \
  --env VIGILANT_UPSTREAM_URL=https://api.openai.com \
  --env VIGILANT_ENVIRONMENT=development \
  --env VIGILANT_IDENTITY_MODE=DUMMY \
  --env VIGILANT_IDENTITY_DUMMY_USER=local-user \
  --env VIGILANT_POLITICS_CONFIG=/etc/vigilant/politics.conf \
  --mount type=bind,src="$PWD/politics.conf",dst=/etc/vigilant/politics.conf,readonly \
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
  vigilant:0.1.0-SNAPSHOT
~~~

Configuration files монтируются read-only. Для production deployment secrets
должны передаваться через предназначенный для них secret mechanism, а не
записываться в image или config committed в repository.

`DUMMY` mode разрешает только development/test deployment, поэтому первая
команда остаётся local example. Production выбирает `identity-mode=JWT` с exact
issuer/audience и pinned RSA public JWK set либо `identity-mode=EXTERNAL` с
trusted absolute Bridge URL и whole-exchange timeout. Plain HTTP Bridge
разрешён contract-ом, поэтому network protection принадлежит deployment.
Startup не выполняет Bridge health check. JWT rotation выполняется явным
configuration/deployment update с overlap старого и нового public key.

## Health и lifecycle

Orchestrator probes:

- `GET /healthz` - liveness;
- `GET /readyz` - readiness.

`SIGTERM` запускает JVM shutdown hook. Readiness переключается на `503`, новые
proxy exchanges запрещаются, active exchanges получают bounded drain, затем
закрываются inspection, upstream и OpenTelemetry resources.

Default graceful shutdown force timeout равен 30 seconds. Container stop
timeout 35 seconds сохраняет этот budget и снижает риск последующего
`SIGKILL`.

## STDOUT telemetry

Container пишет только в stdout. Там находятся два логических потока:
application logs в JSON Lines и OpenTelemetry traces/metrics в OTLP JSON Lines.
Runtime обязан использовать non-blocking delivery, ротацию и bounded local
storage. Started request analysis публикует safe best-effort
`policy.analysis_started`/`policy.analysis_completed` через existing Logback
`AsyncAppender` с `neverBlock=true`; request не ждёт queue delivery или stdout write.
Collector должен разделять записи по top-level `resourceSpans` и
`resourceMetrics`; остальные JSON records являются application logs. Требования
и пример pipeline приведены в [observability reference](observability.md).

## OCI smoke test

~~~bash
./scripts/oci-smoke-test
~~~

Требования:

- `curl`;
- Docker;
- Python 3.

Скрипт собирает временный image и проверяет:

- env-only configuration без durable-audit settings;
- read-only policy mount;
- non-root user;
- отсутствие persistent audit directory, image volume и audit environment;
- `/healthz` и `/readyz`;
- реальный PII Chat Completions request;
- byte-identical request replay;
- safe JSONL stdout request-audit pair;
- graceful SIGTERM shutdown.

Smoke test удаляет созданные container/image resources при завершении. Он
запускается явно и не входит в `./gradlew build` или CI.

## Не входит в поставку

Текущий repository не предоставляет:

- registry publication;
- multi-arch images;
- Kubernetes manifests или Helm charts;
- autoscaling policy;
- Collector distribution, external audit/log storage, dashboards или alerts.
