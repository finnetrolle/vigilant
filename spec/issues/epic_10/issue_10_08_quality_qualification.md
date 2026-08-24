# VIG-10-08: Итоговая quality и performance qualification

**Статус:** Ready for implementation
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Qualification > quality and performance evidence
**Зависит от:** [VIG-10-02](issue_10_02_ip_candidate_boundaries.md), [VIG-10-03](issue_10_03_product_aligned_report.md), [VIG-10-04](issue_10_04_email_obfuscation.md), [VIG-10-05](issue_10_05_phone_surfaces.md), [VIG-10-06](issue_10_06_snils_contextual.md), [VIG-10-07](issue_10_07_oms_contextual.md), [VIG-02-15](../epic_02/issue_02_15_jmh_baseline.md)
**Блокирует:** нет
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 3-4 инженерных дня
**Уверенность:** Medium

## Результат

Одна документированная qualification sequence доказывает, что EPIC-10 достиг
source-aligned quality floors, улучшил frozen evaluation partition, сохранил
canonical contract и не внёс устойчивую performance regression в обязательные
JMH scenarios.

## Критерии готовности

- [ ] Полный pinned source-aligned RedMadRobot report показывает exact
      precision `>= 0.75`, exact recall `>= 0.30`, exact F1 `>= 0.42`, relaxed
      F1 `>= 0.45` и IP exact recall `>= 0.90`.
- [ ] Frozen evaluation partition показывает exact F1 выше baseline
      VIG-10-01 и exact precision `>= 0.75`; tuning и evaluation результаты
      публикуются рядом без смешивания.
- [ ] Per-type и per-evidence table объясняет TP/FP/FN contribution каждой
      production issue и не скрывает тип с ухудшившейся precision.
- [ ] Product-aligned report публикуется отдельно с counts каждого adjustment;
      source-aligned denominator и provenance сохранены.
- [ ] `./gradlew piiQualityReport` подтверждает 100% canonical positive exact
      match и 100% hard-negative rejection по обновлённой нормативной surface.
- [ ] Paired JMH runs используют одинаковые environment, JVM, forks, warmup и
      iterations VIG-02-15. Median p95/p99 обязательных worst-case no-match и
      full-scan scenarios не регрессирует более чем на 10% относительно
      сохранённого baseline.
- [ ] Устойчивая performance regression выше floor профилируется и исправляется
      отдельным RED -> GREEN slice; qualification не выдаёт waiver самой себе.
- [ ] Reports фиксируют Git revision, dataset revision/checksum, corpus version,
      JMH environment и команды воспроизведения.
- [ ] Reports, test output и diagnostics не содержат raw payload, findings,
      candidates, tokens, secrets или auth headers.
- [ ] `./gradlew build` и work-item validator проходят.

## Test/demo seam

`./gradlew redMadRobotPiiBenchmark`, `./gradlew piiQualityReport`, JMH command
VIG-02-15 и финальный `./gradlew build`. Issue не вводит новый production
behavior, кроме минимального profile-guided fix при доказанной регрессии,
который обязан пройти отдельный TDD cycle.

## Не входит

Новые recognizer capabilities, изменение quality floors после просмотра
финального результата, исключение неудобных cases, gateway integration или
HTTP performance.
