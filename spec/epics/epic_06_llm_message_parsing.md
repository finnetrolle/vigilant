# Epic 06: OpenAI Responses protocol scope

**ID:** `EPIC-06`
**Тип:** Epic
**Статус:** Draft
**Приоритет:** High
**Оценка:** не оценено; implementation-ready leaves отсутствуют
**Связанные требования:** `OUT-12`

## Результат

Определить будущий OpenAI Responses protocol scope вне текущего MVP.
Chat Completions требования принадлежат
[постоянному protocol owner](../requirements/chat-completions-protocol.md),
HTTP outcomes - [gateway owner](../requirements/http-gateway.md).

## Оставшиеся решения

До декомпозиции необходимо согласовать точные request/response field maps,
versioned schema snapshot, payload boundaries, ordinary JSON/SSE terminal
semantics, canonical source и mismatch handling повторных final snapshots.
Существующие Chat Completions contracts не задают эти решения по умолчанию.

При декомпозиции сохраняются уже согласованные ограничения будущего scope:

- Schema-recognized reference на внешний textual context (conversation,
  previous response, hosted prompt) требует `UNRESOLVED_CONTEXT` до разрешения
  reference. Inline input или prompt variables не делают неизвестную history
  или template inspectable. Parser не делает network lookup: внешний resolver
  должен представить context теми же normalized fragments до обычного flow.
- Если future schema допускает structured tool arguments, object/array
  сохраняют types; отдельные string leaves дают independent fragments. Keys,
  numeric, boolean и null values не stringified; runtime argument keys не
  становятся payload. Textual arguments остаются единым decoded fragment
  без обязательного inner JSON parsing. Это не добавляет structured containers
  в текущий Chat Completions adapter.

## Дочерние issues

Исполняемых leaves пока нет. Epic остаётся Draft, поскольку Responses scope
не имеет согласованных contracts и implementation-ready decomposition.
Закрытие Chat Completions work не означает реализации Responses API.

## Границы

Responses API не входит в публичный MVP. Realtime и Batch остаются
неактивированными post-MVP placeholders без field maps, terminal semantics,
transport contracts и implementation issues. Другие providers, resolver
implementation, detector/policy execution и новые reactions здесь не
активируются. Переход к implementation требует отдельного согласования и
декомпозиции remaining scope.
