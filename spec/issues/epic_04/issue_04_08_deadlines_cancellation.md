# VIG-04-08: Per-policy deadlines и shared cancellation

**Статус:** Ready for implementation  
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Detector execution > policy deadlines  
**Зависит от:** [VIG-04-07](issue_04_07_parallel_execution.md)  
**Блокирует:** [VIG-04-09](issue_04_09_fail_fast.md), [VIG-04-11](issue_04_11_decision_observability.md)  
**Оценка:** 5-6 инженерных дней

## Результат

Каждая applied policy независимо ожидает общий набор detector executions до
своего deadline. Общий execution отменяется только после исчезновения
последнего consumer.

## Наблюдаемое поведение

- Deadline начинается после snapshot/matching, в момент запуска detectors.
- Timeout policy создаёт `ERROR/POLICY_DEADLINE_EXCEEDED` только для неё.
- Более длинная policy продолжает ждать общий detector после timeout короткой.
- Execution отменяется после completion/timeout/cancellation всех consumers.
- `PolicyResult.deadlineExceeded` и unfinished detector IDs точны.

## Тестовый seam

Controllable clock/test scheduler и cancellable fake detectors. Не использовать
широкие wall-clock sleeps как доказательство semantics.

## Критерии готовности

- [ ] Пример `20ms/100ms` из epic воспроизведён deterministic test-ом.
- [ ] Timeout одного consumer не отменяет execution, нужный другому.
- [ ] Последний ушедший consumer отменяет unfinished execution.
- [ ] External cancellation отменяет все ожидания и executions.
- [ ] Partial detector results не выдаются как successful clean result.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew build` проходят.

## Не входит

Provider timeout, retry и sequential detector dependencies.
