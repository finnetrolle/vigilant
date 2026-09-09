# Agent context baseline

## Методика

Baseline сравнивает repository context для трёх issues: operational discovery
VIG-33, context tooling VIG-39 и verification tooling VIG-40. Это выборка
docs/tooling workflow, не production-performance qualification. Исходный guide
snapshot: `9a8ada9`; точный source VIG-39 сохранён в Git commit `891044d`.
Точный source VIG-40 сохранён в `70ba478`; этот commit также содержит source
VIG-39. После удаления samples measurement восстанавливает их из явно
выбранного source revision только во временном Git fixture, не создавая
archive/planning record в рабочем дереве.

`before_input` считает UTF-8 bytes и whitespace-separated words для прежних
AGENTS, CLAUDE, полного каталога и исходной issue из baseline revision, плюс
одинаковый выбранный external context. Для двух tooling samples добавляется
полный исходный journal: это явно обозначенный сценарий диагностики с прежней
широкой рекомендацией. Для VIG-33 journal не добавляется. Broad journal не
считается безусловным обязательством каждой задачи.

`routed_input` считает текущие AGENTS, CLAUDE и весь обязательный project
workflow: перенос процедур не выдаётся за их исчезновение из контекста.
`packet_output` считает реальные stdout bytes и whitespace-separated words
в сериализованном TOON. `after_total` равен routed_input плюс
packet_output. Экономия равна before_input минус after_total, без округления
bytes. Внутренний полный результат papercuts учитывается отдельно в
`internal_tool_output`; он не попадает в контекст агента и не вычитается из
стоимости запуска инструмента. Global skills, cached/uncached tokens,
стоимость модели и wall-clock недоступны и не оцениваются.

Reproduce against the current working tree:

```bash
rtk proxy python3 scripts/tests/measure_task_context.py 9a8ada9 70ba478
```

## Измерение 2026-09-09

Состояние: migrated guides и terminal catalog; source checkpoint `891044d`.
Таблица воспроизведена указанной командой, exit 0. Bytes и words указаны
раздельно; negative saving при будущих изменениях также сохраняется как есть.

| Sample | Before input bytes / words | Routed input bytes / words | Packet output bytes / words | After total bytes / words | Saved bytes / words |
|---|---:|---:|---:|---:|---:|
| VIG-33 | 53,700 / 6,241 | 22,099 / 2,891 | 3,702 / 282 | 25,801 / 3,173 | 27,899 / 3,068 |
| VIG-39 | 113,709 / 11,598 | 22,099 / 2,891 | 21,275 / 1,760 | 43,374 / 4,651 | 70,335 / 6,947 |
| VIG-40 | 111,589 / 11,494 | 22,099 / 2,891 | 19,228 / 1,681 | 41,327 / 4,572 | 70,262 / 6,922 |

Internal papercuts tool output для VIG-39/VIG-40: 46,398 bytes / 4,028 words
каждый; для VIG-33: 0 / 0. Temporary fixture-root metadata нормализуется в
`<fixture>` при этом измерении, journal content сохраняется. Это цена внутреннего
retrieval, отдельно от emitted packet; runtime duration не измерялся.

В записанном измерении после закрытия VIG-39 её источник восстанавливался
из checkpoint во временный fixture, а VIG-40 ссылалась на published prerequisite.
При новом запуске после закрытия VIG-40 оба удалённых samples восстанавливаются
из указанного source revision; прежняя таблица остаётся историческим измерением. В baseline revision
VIG-39 ещё ссылалась на VIG-38; выполненная prerequisite отражена capability link
в checkpoint, её требования не удалены. Позднейшие изменения context sources
или journal требуют нового измерения, а не переименования этой таблицы в current.

## Rule destinations

Сверка выполнена по полным исходным AGENTS/CLAUDE и development sections из
`9a8ada9`, с отдельным просмотром каждой строки удаления. Ни одно обязательство
не признано устаревшим; описания runtime перенаправлены к существующим owners.

| Removed source block / obligations | Canonical destination |
|---|---|
| AGENTS startup, TDD loading, RTK, navigation, inline implementation, independent verification | [CLAUDE mandatory routing](../CLAUDE.md#mandatory-routing), [navigation](../CLAUDE.md#agent-code-navigation) |
| AGENTS criteria/non-goals, quantified cases, KDoc, reuse, scope, deterministic fixtures, closure summary | [Defect prevention](agent-workflow.md#pre-verification-defect-prevention) |
| CLAUDE testing mode: pre-agreed seam, strict/regression TDD, independent examples, old consumers, early feedback, full build, Approved Scenarios | [Project testing mode](agent-workflow.md#behavior-first-development-and-selective-tdd) |
| CLAUDE pre-verification criteria, causal independent oracles, cleanup failures, scope, library defaults, ownership, KDoc, canonical reuse, concurrency/process fixtures, delta reviews and exact closure summary | [Defect prevention](agent-workflow.md#pre-verification-defect-prevention), transferred as complete clauses |
| CLAUDE completion steps 1-6: exact Git source, all evidence, permanent owners, dependencies, removal, parent/frontier, no reused IDs | [Work-item completion](agent-workflow.md#work-item-completion), transferred as complete clauses |
| CLAUDE command list, process/non-process tests, packaging, config/startup example and error exit | [Development commands](development.md#основные-команды), [extra checks](development.md#дополнительные-проверки), [configuration](configuration.md#ошибки-startup), [deployment](deployment.md#запуск-через-environment) |
| CLAUDE runtime status, key source files, policies/transport/identity/response boundaries | [Architecture and source map](architecture.md#карта-исходного-кода), [runtime contract](runtime-contract.md), [policy requirements](../spec/requirements/policy-engine.md) |
| CLAUDE docs/coverage, UML, source ownership diagrams, Sonar debt, YAGNI/SOLID, Javadoc, blocking/streaming, headers/errors/privacy | [Stable engineering invariants](../CLAUDE.md#stable-engineering-invariants); detailed protocol invariant remains in CLAUDE |
| CLAUDE papercut recording/resolution, scope, continue-unless-blocked, durable fix, recheck old context, privacy and doctor | [Agent papercuts](development.md#agent-papercuts); full-journal lookup becomes explicit unknown-symptom fallback |
| CLAUDE durable runner command/result/inputs, non-overlap, bounded waits and completed evidence | [Durable runner](development.md#устойчивый-запуск-проверок) |
| Development duplicated mode/old consumers/approved fixtures and review freshness paragraph | Project testing mode and defect prevention above; metrics collection stays in [development mode](development.md#режим-разработки) |

All startup triggers remain in CLAUDE and are reachable without task ID,
papercuts, Git discovery or successful packet extraction. No installed global
skill, runtime code, build topology or security gate is changed.

## Manual obligation audit

| Sample | Independently inspected content / obligations | Lost obligations |
|---|---|---:|
| VIG-33 | Complete goal, existing deployment context, every open SLI/SLO decision, exclusions and Draft readiness condition; exact health/lifecycle section; startup invariants remain mandatory | 0 |
| VIG-39 | Complete public packet/error/source/order contract, all seven acceptance criteria and five non-goals; exact authoring/completion/papercut sections; all moved guide clauses from the table above | 0 |
| VIG-40 | Complete eight evidence cases, six criteria, five non-goals and unchanged review/security gates; exact runner/pipeline/closure sections; dependency completion represented explicitly, without claiming the implementation of VIG-40 | 0 |

These are clause-by-clause source reviews, not flags calculated by the extractor.
The measurement utility only measures text and verifies literal issue round-trip;
it cannot establish lost obligations or a semantic PASS. No release threshold
is assigned before the baseline, and these observations introduce none.
