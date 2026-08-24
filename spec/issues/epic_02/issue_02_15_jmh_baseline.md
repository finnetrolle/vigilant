# VIG-02-15: JMH performance baseline

**Статус:** Done

**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Evidence > performance baseline  
**Зависит от:** [VIG-02-16](issue_02_16_canonical_quality_corpora.md)  

**Оценка:** 3-4 инженерных дня

## Результат

JMH benchmark воспроизводимо измеряет V1 detector на обязательных datasets,
sizes и modes, а baseline report сохраняется как build artifact вместе с
описанием среды.

## Scope

- Gradle JMH plugin `0.7.3` и JMH `1.37` только в benchmark configuration.
- `SampleTime` с p50, p95 и p99.
- ASCII, русский и mixed Unicode background datasets на `1 KiB`, `64 KiB`,
  `1 MiB`; positive fragment сохраняет обязательные символы своего scenario.
- Worst-case no-match, early email, finding каждого позднего типа и full scan;
  `RU_OMS` stop-on-first отключает неизбежно пересекающийся более ранний
  `PAYMENT_CARD`, а full scan измеряет оба finding.
- CPU/JVM/OS, warmup, forks и iterations рядом с результатом.

## Критерии готовности

- [x] Все обязательные scenarios epic запускаются одной документированной
  Gradle-командой.
- [x] Benchmark не печатает payload или findings.
- [x] JMH отсутствует в production runtime classpath.
- [x] Baseline output и metadata сохранены как build artifact.
- [x] Числовой release gate не придуман без production baseline.
- [x] `./gradlew test` и production runtime classpath check проходят.

## Не входит

HTTP/executor overhead, оптимизация под заранее выбранный threshold и release
blocking по latency.
