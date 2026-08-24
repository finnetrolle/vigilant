# SonarQube: список проблем проекта

- **Дата анализа:** 2026-08-24
- **Сервер:** SonarQube Community 26.8.0.126808 (локально, Docker)
- **Проект:** `io.vigilant:vigilant`
- **Quality gate:** OK (0 новых нарушений, CAYC compliant)
- **Итог:** открытых проблем нет. 0 bugs, 0 vulnerabilities, 0 security hotspots, 0 code smells.

## Метрики

| Метрика | Значение |
|---|---:|
| Bugs | 0 |
| Vulnerabilities | 0 |
| Security hotspots | 0 |
| Code smells | 0 |
| Coverage | 91.8% |
| Дублирование строк | 1.5% |
| NCLOC | 2826 |
| Reliability rating | A (1.0) |
| Security rating | A (1.0) |
| Maintainability rating | A (1.0) |

### Условия quality gate для нового кода

| Условие | Порог | Фактическое значение | Статус |
|---|---:|---:|---|
| New coverage | не ниже 80% | 92.4% | OK |
| New duplicated lines density | не выше 3% | 0.6079% | OK |
| New violations | 0 | 0 | OK |

## История исправлений

- 2026-08-24: `kotlin:S3776` в `RuPassportRecognizer.kt` - `contextFlagsInRange` упрощён выделением операций пропуска частичного слова, разделителей, русских букв и проверки целого слова в документированные приватные функции. Существующий однопроходный алгоритм и публичное поведение сохранены. Полный `./gradlew build` GREEN; повторный Sonar-анализ подтвердил 0 открытых issues и quality gate OK.
- 2026-08-24: полный повторный прогон текущего проекта - обнаружен 1 новый `CODE_SMELL` (`kotlin:S3776`), quality gate перешёл в ERROR. По сравнению с прогоном 2026-08-21 coverage вырос с 82.6% до 91.8%, дублирование выросло с 0% до 1.5%, NCLOC вырос с 252 до 2798; bugs, vulnerabilities и security hotspots остались на нуле, все рейтинги остались A.
- 2026-08-21 (повторный прогон): анализ без изменений в коде - подтверждено 0 открытых проблем, метрики не изменились (coverage 82.6%, все рейтинги A).
- 2026-08-21 (после VIG-05-01): повторный анализ после добавления стабильных proxy-ошибок upstream в `BypassProxyService.kt` - 0 новых нарушений, coverage вырос 80.6% -> 82.6%.
- 2026-08-21: `kotlin:S6474` (dependency verification отсутствует) - исправлено: добавлены `gradle/verification-metadata.xml`, `gradle/verification-keyring.gpg`, `gradle/verification-keyring.keys` (pgp + sha256, 21 ключ). Артефакты, чьи ключи недоступны с keyserver, проверяются контрольными суммами sha256. Проверено принудительным прогоном `./gradlew --refresh-dependencies build`.
- 2026-08-21: `kotlin:S6624` (захардкоженные версии, 5 вхождений в `build.gradle.kts:19-23`) - исправлено: версии вынесены в Version Catalog `gradle/libs.versions.toml`.
- 2026-08-21: повторный анализ подтвердил закрытие обеих находок (0 открытых issues).
