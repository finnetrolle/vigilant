# Документация Vigilant

Этот каталог описывает текущее поведение продукта. Нормативные требования,
границы будущих этапов и статусы рабочих элементов остаются в
[`spec/`](../spec/). Если документация расходится с готовой к реализации
задачей или нормативным контрактом, сначала исправляется источник истины в
`spec/`, а затем синхронно обновляется документация.

## Начало работы

- [README проекта](../README.md) - быстрый запуск и текущие возможности.
- [Конфигурация](configuration.md) - все настройки приложения и правила их
  проверки.
- [Политики](policies.md) - формат `politics.conf`, сопоставление политик и
  REQUEST enforcement и startup migration.
- [Реализация Chat Completions](openai-chat-completions.md) -
  проверяемые поля и причины отклонения запросов.
- [Обнаружение PII](pii-detection.md) - поддерживаемые типы, срабатывания,
  оконная обработка и ограничения качества.

## Исполнение и эксплуатация

- [Архитектура](architecture.md) - компоненты, владение ресурсами и жизненный
  цикл.
- [Контракт исполнения](runtime-contract.md) - результаты HTTP-запросов,
  тайм-ауты, проверки состояния и завершение работы.
- [RESPONSE enforcement](../spec/requirements/response-enforcement.md) -
  атомарная JSON/SSE проверка, masking и lifecycle ownership.
- [HTTP gateway](../spec/requirements/http-gateway.md) - routing, headers,
  stable errors, probes и shutdown.
- [Observability contract](../spec/requirements/observability.md) - stdout,
  analysis pairs, tracing, metrics, privacy и delivery ownership.
- [Развёртывание](deployment.md) - дистрибутив, stateless OCI и lifecycle.
- [Наблюдаемость](observability.md) - JSON Lines, трассировка, метрики и
  безопасный аудит.
- [Разработка](development.md) - сборка, тесты, контроль качества и CI.

## Требования и архитектурные схемы

- [Покрытие требований](requirements-coverage.md) - связь всех требований MVP,
  NFR, Stage 1 и требований вне области продукта с текущей реализацией и
  документацией.
- [Evidence request enforcement](request-enforcement-evidence.md) - критерии
  [REQUEST contract](../spec/requirements/request-enforcement.md), независимые
  byte/lifecycle oracles и точные команды RED/GREEN.
- [UML-диаграммы](diagrams/README.md) - исходники диаграмм компонентов,
  последовательностей, состояний и деятельности в нотации UML 2.0 и формате
  PlantUML.
- [План развития](../spec/ROADMAP.md) - достигнутый этап и граница будущей
  работы.
- [Реестр рабочих элементов](../spec/WORK_ITEMS.md) - формальные статусы эпиков
  и задач.

## Проверки и evidence

- [Методика PERF-01](perf-01-load-test.md) описывает воспроизводимый benchmark,
  но не объявляет historical run текущей гарантией.
- [Development guide](development.md) владеет current test, qualification,
  performance и work-item verification methods.
- [Карта покрытия](requirements-coverage.md) отделяет target, runtime и
  применимое evidence для всех 55 stable IDs.

## Правила поддержки

- Не описывать будущее поведение как доступное сейчас.
- Для каждой настройки указывать источник, значение по умолчанию, правила
  проверки и влияние на исполнение.
- Для каждого результата fail-closed указывать статус и тело ответа, момент
  отказа и наличие побочных эффектов для вышестоящего сервера и аудита.
- Не помещать полезную нагрузку, учётные данные, идентификаторы пользователей
  или обратимо преобразованные значения в примеры аудита, журналов и ошибок.
- Новые схемы создавать только в нотации UML 2.0 и хранить их исходники в
  `docs/diagrams/*.puml`.
