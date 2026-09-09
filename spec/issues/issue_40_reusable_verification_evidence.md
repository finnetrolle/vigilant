# VIG-40: Один snapshot и переиспользуемая verification evidence

- **ID:** `VIG-40`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** [VIG-39](issue_39_compact_task_context.md)
- **Блокирует:** нет
- **Оценка:** 3-5 инженерных дней
- **Уверенность:** Medium

## Результат

Каждый дорогой mechanical gate выполняется не более одного раза для одного
неизменного verification snapshot. Implementation, `verify-changes` и delivery
consumers получают один компактный evidence record с run ID, inputs, exit и
проверяемыми artifacts; изменение релевантного input делает только затронутую
evidence stale и не разрешает ложный PASS.

## Базовая модель

- Во время behavior-first slice выполняются declared affected tests и дешёвый
  lint/contract feedback.
- После последней code remediation выполняется один current full local gate.
- `verifyAll` уже владеет `build` и OWASP dependency check; final pipeline не
  запускает отдельный полный `build` перед тем же `verifyAll`.
- Standards и Spec получают один полный review snapshot. После исправлений те
  же оси проверяют delta и affected contracts; неизменный verdict не строится
  заново.
- Input hashes являются необходимым, но не достаточным условием reuse: должны
  совпадать command, selected inputs, tracked toolchain/environment identity и
  integrity объявленных artifacts.

## Контракт evidence

Расширить repository `scripts/check-run`, не создавая второй process supervisor.
Успешный reusable record содержит:

- run ID, label, exact argv, repository/worktree и Git head;
- timestamps, duration, exit и terminal state;
- selected input fingerprint до и после запуска;
- digests влияющих environment keys без сохранения значений;
- declared artifacts с type/path/digest либо явное `none`;
- compact applicability status: `current`, `stale`, `corrupt`,
  `infrastructure_failure` или фактический failed/cancelled/timeout state.

Проверяющий consumer перед reuse обязан запросить current status. Missing или
изменённый artifact, изменившийся input, другая команда, незавершённый process
или повреждённый record не могут быть приняты как PASS.

External-data gates, включая OWASP и Sonar, не переиспользуются между задачами
только по source hash. В первой версии их evidence действует лишь внутри одного
явно идентифицированного verification snapshot; отдельная freshness policy не
придумывается.

## Изменения

- Добавить в `check-run` declaration и integrity verification artifacts, а также
  compact lookup/status для current passed record по exact label/command/input
  contract.
- Сохранить один worktree execution lock и bounded process-group cleanup.
- Изменить `scripts/pipeline-verify`: ранний detekt остаётся отдельным cheap
  feedback, final gate вызывает `verifyAll` один раз и публикует единственный
  reusable run/evidence ID вместо последовательности `build` + `verifyAll`.
- Описать передачу run IDs из pre-verification closure в verification consumers.
- Добавить метрики цикла: количество full gates, причины invalidation, reused
  evidence, tool duration и remediation waves. Не обещать процент экономии до
  наблюдений на 3-5 сопоставимых issues.

## Обязательные cases

1. Passed run, неизменные exact inputs/command/environment и целые artifacts
   возвращается как `current`.
2. Изменённый, добавленный или удалённый selected input возвращает `stale`.
3. Изменённый environment digest или command не совпадает с reusable contract.
4. Missing, modified или symlink-swapped artifact возвращает `corrupt`.
5. Failed, timeout, cancelled и infrastructure failure сохраняют собственный
   terminal verdict и никогда не становятся reusable.
6. Одновременный второй runner в том же worktree отклоняется существующим lock;
   чтение status остаётся bounded и не запускает команду повторно.
7. Docs-only remediation инвалидирует Spec/docs evidence, но не runtime run,
   если docs действительно не входили в его declared inputs; shared build или
   contract input инвалидирует соответствующий full gate.
8. Final pipeline не содержит отдельный `build` перед `verifyAll`, а один реальный
   запуск доказывает build/tests/detekt и OWASP artifacts на одном snapshot.

## Критерии готовности

- [ ] Все восемь cases покрыты независимыми Python/shell fixtures; expected
  state и digests не вычисляются проверяемой production функцией.
- [ ] Повреждённая, частичная или stale evidence fail-closed и выдаёт короткую
  диагностику с точным следующим действием, не печатая полный log.
- [ ] Один реальный final pipeline через durable runner заканчивается terminal
  exit и публикует проверяемые test/dependency artifacts для текущих inputs.
- [ ] Pre-verification closure и development guide однозначно различают новый
  run, reused evidence и NOT RUN; semantic applicability остаётся ответственностью
  reviewer, а не выводится только из hashes.
- [ ] На 3-5 последующих сопоставимых issues ledger позволяет посчитать число
  устранённых full reruns и wall-clock экономию без смешения tool time с паузами.
- [ ] `rtk proxy python3 -m unittest discover -s scripts/tests -p 'test_*.py'`,
  `rtk proxy ./gradlew validateWorkItems` и `rtk proxy git diff --check` GREEN;
  обязательный final pipeline выполнен без перекрывающего Gradle process.

## Не входит

- Distributed или remote build cache, cross-worktree evidence reuse и CI artifact service.
- Автоматическое определение affected tests по эвристике или LLM.
- Автоматический semantic PASS, snapshot approval или изменение human-approved fixtures.
- Ослабление process/OCI/load/lifecycle evidence, OWASP, Sonar или review axes.
- Pitest в обязательном pipeline.

## Ambiguity Report

```text
Goals:        0.0   one-snapshot reuse и fail-closed outcome явны
Acceptance:   0.1   восемь обязательных cases перечислены
Boundaries:   0.05  mechanical evidence отделена от semantic applicability
Alternatives: 0.1   remote cache и heuristic test selection отклонены
Assumptions:  0.2   3-5 issue ledger даст численный эффект после внедрения
Aggregate:    0.09  Ready for implementation.
```
