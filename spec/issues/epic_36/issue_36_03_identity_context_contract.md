# VIG-36-03: Постоянный контракт identity и context

**Статус:** Ready for implementation
**Epic:** [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md)
**Ветка:** migrate по identity/context contract
**Зависит от:** [VIG-36-01](issue_36_01_completion_workflow.md)
**Блокирует:** [VIG-36-08](issue_36_08_current_catalog_closure.md)
**Связанные требования:** `MVP-04`, `MVP-05`, `CONC-03`, `CONC-04`, `OBS-01`, `OBS-02`
**Оценка:** 1-2 инженерных дня
**Уверенность:** Medium

## Результат

DUMMY/JWT/EXTERNAL, External cache и PolicyContext доступны как постоянный identity/context contract, без completed identity tasks.

## Обязательный контекст

До редактирования прочитать [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md), permanent owners, если они
уже опубликованы, current top-level requirements и **весь source batch ниже**.
Baseline - Git `ca8ec90`; полный текст текущего source сравнить с baseline,
чтобы не удалить новую незавершённую работу. Source titles и Done сами по себе
не доказывают актуальность содержащихся в них правил.

Protocol body-derived attributes принадлежат VIG-36-04; здесь определяется сборка и перенос context, а не повторный parsing.

## Source batch: 12 issues и 1 epic

Paths ниже относительно repository root. Каждая строка назначена этому leaf
как единственному removal owner; это конечный список, а не glob на все файлы
с подходящим именем. Current clauses из shared sources других leaves можно
читать, но их файлы здесь не удаляются.

- `spec/issues/epic_03/issue_03_01_context_contract.md`
- `spec/issues/epic_03/issue_03_02_url_normalization.md`
- `spec/issues/epic_03/issue_03_03_identity_extraction.md`
- `spec/issues/epic_03/issue_03_04_model_extraction.md`
- `spec/issues/epic_03/issue_03_05_response_handoff.md`
- `spec/issues/epic_03/issue_03_06_security_e2e.md`
- `spec/issues/epic_03/issue_03_07_anonymous_request_context.md`
- `spec/issues/issue_27_dummy_identity_extractor.md`
- `spec/issues/issue_28_keycloak_jwt_identity_extractor.md`
- `spec/issues/issue_30_external_identity_extractor.md`
- `spec/issues/issue_31_identity_lookup_cache.md`
- `spec/issues/issue_35_production_identity_mode.md`
- `spec/epics/epic_03_policy_context_extraction.md`

## Destination и current consumers

Permanent destinations:

- `spec/requirements/identity-and-context.md`

Current documents для синхронизации:

- `docs/runtime-contract.md`
- `docs/configuration.md`
- `docs/architecture.md`
- `docs/policies.md`
- `docs/observability.md`
- `docs/requirements-coverage.md`
- `README.md`
- `spec/MVP_FUNCTIONS.md`

Также обновить requirements index, свой registry/checklist и **все входящие
ссылки на удаляемый batch** во всём tracked root/spec/docs. Другие epics/issues
можно менять только для affected references или перенесённых shared clauses.
Нельзя оставить broken links ради соблюдения списка основных consumers.
Source consumers для фактической сверки: BearerIdentityExtractor, DummyIdentityExtractor, OfflineJwtIdentityExtractor, ExternalIdentityExtractor, BridgeIdentityClient, CachingExternalIdentityLookup, ExternalIdentityCacheKeyHasher, PolicyContextHandoff и existing HTTP/process tests.

## Обязательные contract cases

1. Матрица environment × identity mode: development/test допускают DUMMY, JWT и EXTERNAL; production - JWT и EXTERNAL. Exact selector, mixed/unknown config rejection, no fallback/runtime switching.

2. Single Bearer parsing, missing/malformed/duplicate authorization, normalized user/groups, unsupported identity shapes и unchanged upstream Authorization; каждый current failure остаётся safe.

3. Offline JWT: pinned RS256 trust configuration, issuer/audience/time/claims validation и отсутствие runtime identity I/O; не переносить старый Keycloak-only label как ограничение продукта.

4. External: exact one-attempt Bridge exchange, success/protocol/status/transport/timeout/overload, original whole-exchange timeout, event-loop isolation, token/privacy constraints.

5. Cache: independent TTL/size defaults, HMAC keys/no raw token retention, hit/miss/join, expiry/eviction, no stale fallback/idle refresh, bounded waiters, cancellation одного/последнего caller, late completion, close/shutdown и cleanup failure ordering.

6. Context: canonical URL match key, schema-derived model attributes, immutable user/groups, independent REQUEST/RESPONSE selection и handoff. Current telemetry hit/miss/coalescing/removal counters и initiating span lineage сохраняются.

Для каждого named case переносить полную уже согласованную матрицу source,
а не representative examples. Если current target ещё не подтверждён,
сохранить target и явно назвать gap в coverage. Не менять семантику code или
requirements, чтобы сделать документацию внешне согласованной.

## Особые границы

Исправить README, где cache ещё ошибочно исключён из supported capabilities. Identity-specific metrics/span clauses поместить в named identity section permanent observability owner или перенаправить на уже перенесённый section VIG-36-07; общий stdout/privacy contract не дублировать. AppConfig values и tests служат проверкой current facts, но не поводом менять approved targets.

Узнать actual source/test ownership перед переносом ссылки. Не переименовывать
production symbols и не добавлять будущие reactions/schema/config extensions.
Новый owner получает current contract, а не историю изменений или полный plan
исходной issue. Если соседний detailed file ещё не существует, использовать
существующий top-level/current reference и обновить его при публикации owner;
пустые placeholders и dangling future links запрещены.

## Public seam и evidence contract

- **Stimulus:** прочитать весь конечный batch, применить согласованный
  current-only transfer и удалить его completed files после переноса.
- **Public seam:** путь от root README/requirements index к конкретному rule,
  current runtime description и coverage; tracked filesystem и repository
  validation для removal/references.
- **Observable result:** каждый current clause найден у одного permanent
  owner; implementation detail или verification method находится в своём
  document; obsolete/planning/history не сохранились как requirements; batch
  отсутствует, ссылки и remaining work-item graph разрешаются.
- **Independent oracle:** original source clause из baseline, approved
  superseding contract и existing source/test observations. Новые headings,
  количество deleted files или green checker не заменяют semantic review.

Временная review matrix:
`source section/clause -> disposition -> destination anchor -> evidence`.
Использовать dispositions из EPIC-36, проверить каждую строку и отсутствие
unsupported current clauses. Matrix хранится для review вне final working
tree; historical mirror/archive в repository не добавляется.

## UML ownership

При найденном расхождении синхронно менять source и owning text document:

- `runtime-components.puml`, `runtime-classes.puml`,
  `request-inspection-sequence.puml` -> `docs/architecture.md`;
- `policy-selection-activity.puml` -> `docs/policies.md`;
- `tracing-sequence.puml` -> `docs/observability.md`.

Все sources находятся в `docs/diagrams/`. Менять только относящиеся к этому
leaf claims, сохранять UML 2.0; новые diagrams ради migration не нужны.
Для изменённого UML выполнить syntax/render check и просмотреть результат,
не добавляя generated SVG/PNG в Git.

## Критерии готовности

- [ ] Весь source batch прочитан и clause matrix полна; каждый current rule
  перенесён ровно к одному owner, каждая obsolete disposition обоснована.
- [ ] Все numbered contract cases выше и все их source subcases сохранены;
  нет label-only coverage, потерянных terminal paths или обобщений вместо
  exhaustive matrices.
- [ ] Permanent destinations, relevant root/spec/docs и affected UML описывают
  один согласованный contract; target/runtime/evidence gaps явно отражены,
  никакой coverage status не повышен без applicable evidence.
- [ ] Удалён exact source batch; все входящие paths/anchors/task dependencies
  удалены или перенаправлены к actual owner. Новая незавершённая работа,
  возникшая после baseline, не удалена автоматически.
- [ ] Registry, remaining epic checklists/backlinks/progress и fulfilled
  prerequisites обновлены одним consistency change по VIG-36-01.
- [ ] `rtk proxy ./gradlew workItemValidatorTest validateWorkItems` и
  `rtk proxy git diff --check` GREEN; modified UML проверен. Поиск stale
  claims и removed references имеет полный scope и объяснённые совпадения.
- [ ] Production behavior/config/tasks не изменены. Эта migration issue
  завершена и удалена по новому completion protocol, без historical record.

## Проверки и граница evidence

Сначала semantic review source/destination; затем actual catalog checks через
существующие commands VIG-36-01. После terminal removal выполнить их снова,
если removal был сделан после предыдущего прогона. Не запускать перекрывающиеся
Gradle processes. Full production build, OCI/load/process qualification не
требуется для documentation-only переноса и не заявляется выполненной.

## Не входит

Новый identity mode/provider, cache behavior/config, JWT trust model, network retries, new metrics или benchmark cache.

Не менять generated reports, CHANGELOG или .papercuts.jsonl. Не публиковать
новые независимые product issues и не объявлять неподтверждённые capabilities
готовыми в рамках этого leaf.

## Ambiguity Report

```text
Goals:        0.0   independently reviewable migration outcome explicit
Acceptance:   0.25  finite cases and public evidence; semantic review required
Boundaries:   0.0   current-only transfer, no new runtime behavior
Alternatives: 0.0   lifecycle and permanent owners agreed
Assumptions:  0.25   baseline verified; source drift must be checked
Aggregate:    0.10  Ready for implementation.
```
