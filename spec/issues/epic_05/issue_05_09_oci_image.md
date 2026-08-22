# VIG-05-09: OCI-образ поставки

**Статус:** Done
**Epic:** [EPIC-05](../../epics/epic_05_v0_hardening.md)
**Ветка:** Эксплуатация > OCI-образ поставки
**Зависит от:** нет
**Предпочтительный порядок (не блокировка):** после issue health endpoints - smoke-тест использует health endpoint
**Блокирует:** нет
**Оценка:** 2-3 инженерных дня
**Уверенность:** High

## Результат

Поставка v0 соответствует разделу «Поставка» спецификации: один versioned
артефакт gateway и один OCI-контейнер, собираемый воспроизводимо локально и
работающий с конфигурацией через env/файл.

## Требования

Раздел «Поставка»: один versioned JAR и один OCI-контейнер; конфигурация через
env и/или read-only config file; graceful shutdown.

## Критерии готовности

- [x] Сборка versioned артефакта (рекомендованный baseline: existing
  `installDist` layout или fat/installer-JAR - зафиксировать выбор) одним
  Gradle-таргетом.
- [x] Dockerfile (multi-stage) собирает образ из этого артефакта; базовый образ
  JRE 25 зафиксирован.
- [x] Образ собирается локально одной документированной командой.
- [x] Smoke-тест: контейнер стартует с `VIGILANT_UPSTREAM_URL`, health
  endpoint отвечает, запрос проксируется upstream, невалидная конфигурация
  даёт exit code 2.
- [x] Graceful shutdown в контейнере: `docker stop` (SIGTERM) завершает
  процесс корректно через shutdown hook, без `SIGKILL` в пределах таймаута
  остановки.
- [x] Не-root пользователь в образе; конфигурационный файл монтируется
  read-only.
- [x] README задокументировывает сборку и запуск образа.
- [x] `./gradlew build` проходит.

## Результат реализации

Формат артефакта зафиксирован как воспроизводимый Gradle `distTar`:
`./gradlew ociArtifact` создаёт один versioned архив
`build/distributions/vigilant-<version>.tar` с application distribution.
Multi-stage [Dockerfile](../../../Dockerfile) собирает этот target в чистом JDK
25 builder и переносит результат в digest-pinned JRE 25 image с UID/GID
`10001` и `STOPSIGNAL SIGTERM`.

[Smoke-тест](../../../scripts/oci-smoke-test) проверяет env и read-only file
configuration, health endpoint, реальное проксирование, non-root user, exit
code 2 при невалидной конфигурации и graceful shutdown без `SIGKILL`. Команды
сборки, запуска и проверки зафиксированы в [README](../../../README.md).

## Не входит

Публикация образа в registry, CI/CD пайплайн сборки, multi-arch, Helm-чарты.

## Рекомендуемые решения внутри issue

- Базовый образ: `eclipse-temurin:25-jre` (или аналогичный JRE 25);
  non-root user; `ENTRYPOINT` на запуск из `installDist`.
- Smoke-тест - скрипт или тестовая задача, запускающая docker локально и не
  входящая в `./gradlew build`.
