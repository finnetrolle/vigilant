# VIG-16: Packaged shadow proxy evidence

- **ID:** `VIG-16`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-14](issue_14_strict_protocol_gap_outcomes.md), [VIG-15](issue_15_capacity_cancellation_outcomes.md), [VIG-09-08](epic_09/issue_09_08_shutdown_lifecycle.md)
- **Блокирует:** Production PII shadow proxy milestone
- **Оценка:** 3-5 инженерных дней
- **Уверенность:** Medium

## Результат

Packaged `MainKt` distribution и OCI container проходят production-process E2E
с real upstream stub, mounted `politics.conf`, exact request replay, health and
readiness lifecycle и JSONL safe shadow audit.

## Public seam

Child-process test запускает installed application distribution. OCI smoke
строит Dockerfile, запускает non-root container и проверяет его только через
published port, stdout logs and container lifecycle.

## Критерии готовности

- [x] `MainKt` process forwarding-ит PII Chat Completions request unchanged и
  пишет expected safe `DETECTED` event.
- [x] OCI smoke использует mounted read-only valid shadow policy.
- [x] Container health/readiness, exact forwarding, JSONL audit и SIGTERM проходят.
- [x] Invalid app/policy config даёт exit code `2` без sensitive values.
- [x] README документирует production shadow request, limits и smoke command.
- [x] `./gradlew build`, `./gradlew validateWorkItems` и OCI smoke проходят.

## Не входит

Registry publication, multi-arch, Kubernetes/Helm и advisory load baseline.
