# VIG-10-08: Итоговая quality и performance qualification

**Статус:** Done
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

- [x] Полный pinned source-aligned RedMadRobot report показывает exact
      precision `>= 0.75`, exact recall `>= 0.30`, exact F1 `>= 0.42`, relaxed
      F1 `>= 0.45` и IP exact recall `>= 0.90`.
- [x] Frozen evaluation partition показывает exact F1 выше baseline
      VIG-10-01 и exact precision `>= 0.75`; tuning и evaluation результаты
      публикуются рядом без смешивания.
- [x] Per-type и per-evidence table объясняет TP/FP/FN contribution каждой
      production issue и не скрывает тип с ухудшившейся precision.
- [x] Product-aligned report публикуется отдельно с counts каждого adjustment;
      source-aligned denominator и provenance сохранены.
- [x] `./gradlew piiQualityReport` подтверждает 100% canonical positive exact
      match и 100% hard-negative rejection по обновлённой нормативной surface.
- [x] Paired JMH runs используют одинаковые environment, JVM, forks, warmup и
      iterations VIG-02-15. Median p95/p99 обязательных worst-case no-match и
      full-scan scenarios не регрессирует более чем на 10% относительно
      сохранённого baseline.
- [x] Устойчивая performance regression выше floor профилируется и исправляется
      отдельным RED -> GREEN slice; qualification не выдаёт waiver самой себе.
- [x] Reports фиксируют Git revision, dataset revision/checksum, corpus version,
      JMH environment и команды воспроизведения.
- [x] Reports, test output и diagnostics не содержат raw payload, findings,
      candidates, tokens, secrets или auth headers.
- [x] `./gradlew build` и work-item validator проходят.

## Qualification evidence

- `./gradlew piiQualityQualification
  -PpiiQualificationBaselineDirectory=build/reports/pii/qualification-baseline`
  завершён успешно на JDK 25, JMH 1.37, 2 forks, 3 x 1 s warmup и 5 x 1 s
  measurement для 18 paired cases.
- Source-aligned exact precision `0.819760`, recall `0.323158`, F1 `0.463571`;
  relaxed F1 `0.493016`, IP exact recall `0.909677`.
- Frozen evaluation exact F1 вырос с `0.388316` до `0.450658`, current exact
  precision равен `0.801170`.
- Product-aligned exact precision `0.842457`, recall `0.373594`, F1 `0.517637`;
  report отдельно сохранил counts `107` и `104` для двух versioned
  adjustments.
- Canonical corpus подтвердил 100% exact match для 900 positive и 100%
  rejection для 900 hard-negative cases; 3 mixed cases также прошли.
- Median regression для `NO_MATCH_FULL_SCAN`: p95 `+8.02%`, p99 `+8.87%`;
  для `FULL_SCAN`: p95 `+2.78%`, p99 `+2.48%`. Environment baseline/current
  совпадает.
- Доказанная квадратичная деградация contextual fallback получила отдельный
  RED test `repeated contextual fallbacks remain linear on a large unicode
  payload`; bounded traversal и profile-guided marker scan перевели его в
  GREEN без изменения accepted surface.
- JSON и Markdown evidence находятся в
  `build/reports/pii/qualification/`; provenance фиксирует baseline/current
  Git revision, dirty-state current worktree, dataset revision и SHA-256,
  corpus version, полное JMH environment и команды воспроизведения.

## Test/demo seam

`./gradlew redMadRobotPiiBenchmark`, `./gradlew piiQualityReport`, JMH command
VIG-02-15 и финальный `./gradlew build`. Issue не вводит новый production
behavior, кроме минимального profile-guided fix при доказанной регрессии,
который обязан пройти отдельный TDD cycle.

## Не входит

Новые recognizer capabilities, изменение quality floors после просмотра
финального результата, исключение неудобных cases, gateway integration или
HTTP performance.
