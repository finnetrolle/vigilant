# Issue 01A: Нагрузочный benchmark и профиль логирования (PERF-01)

**ID:** `VIG-01A`  
**Тип:** Issue  
**Статус:** Ready for implementation  
**Примечание:** реализация не начата
**Приоритет:** High
**Связанные требования:** `PERF-01`, `PERF-06`, `CONC-01`, критерии приёмки v0 из `../MVP_NON_FUNCTIONAL_REQUIREMENTS.md`
**Зависит от:** [VIG-09-02](epic_09/issue_09_02_perf01_latency.md)

VIG-09-02 является жёсткой зависимостью: logging-specific slow-sink и profiling
scenarios нельзя интерпретировать, пока default gateway path не выдерживает
полный workload и не имеет подтверждённого PERF-01 baseline.

## Контекст

Реализация `issue_01_logging.md` выполнена: JSONL в stdout через bounded
`AsyncAppender (neverBlock=true)`, unit-тесты неблокирующего поведения
(stalled sink, порядок discarding, `maxFlushTime`) проходят. Не подтверждено
нагрузочное SLO и отсутствие logging I/O на event loop под реальной
нагрузкой. Эта проверка вынесена в отдельную задачу, чтобы не блокировать
закрытие `issue_01_logging.md`.

## Цель

Доказать нагрузочным тестом и профилированием, что логирование не нарушает
`PERF-01`: при 2 000 RPS gateway добавляет не более 2 мс к p99 относительно
прямого вызова того же upstream, а на потоках Armeria/Netty event loop нет
блокирующего logging I/O.

## Сценарии

Сравнить на одинаковом стенде:

1. прямой upstream без gateway (baseline);
2. gateway с default stdout logging (`INFO`);
3. gateway с тестовым медленным downstream sink и заполненной async queue.

## Требования

### Нагрузка

- При 2 000 RPS default-конфигурация выполняет `PERF-01`:
  `proxy_overhead = latency_through_vigilant - latency_direct_to_upstream`
  не более 2 мс к p99.
- В режиме медленного sink request latency не следует за latency sink; потеря
  логов допустима.
- Методика - по `PERF-06`: прогрев JVM до устойчивого состояния; длительность
  замера, характеристики оборудования, размеры запросов и ответов, число
  соединений и профиль streaming/non-streaming фиксируются вместе с
  результатом. Результат без этих данных не подтверждает SLO.

### Профиль

- JFR/async-profiler под нагрузкой не показывает на потоках Armeria/Netty
  event loop: `PrintStream.write`, file I/O, socket/OTLP export, ожидание
  logging queue.
- CPU-работа по проверке уровня и созданию logging event допустима и
  учитывается в benchmark.

## Критерии приёмки

- [ ] Измерены все три сценария на одном стенде, условия зафиксированы по `PERF-06`.
- [ ] Benchmark подтверждает `PERF-01` при 2 000 RPS.
- [ ] Профиль подтверждает отсутствие stdout/file/network I/O и ожидания
      logging queue на event loop.
- [ ] В режиме медленного sink latency запросов не коррелирует с latency sink.
- [ ] Результаты (числа + условия) зафиксированы в репозитории.

## Definition of Done

Задача завершена, когда benchmark на фиксированном стенде подтверждает
`PERF-01` для default-конфигурации, профиль не находит logging I/O на event
loop, поведение при медленном sink проверено, и результаты с условиями
измерения зафиксированы в репозитории.

## Риски

- Размер очереди 8192 нужно подтвердить фактическим размером событий и memory
  budget по результатам этого benchmark; при необходимости - пересмотреть (см.
  риски `issue_01_logging.md`).
- Разница стенда (CPU, network, JVM flags) делает результаты несопоставимыми;
  baseline и proxy-замеры обязаны выполняться на одной машине в одном прогоне.
