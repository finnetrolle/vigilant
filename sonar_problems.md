# SonarQube: список проблем проекта

- **Дата анализа:** 2026-08-21 (повторный прогон после исправлений)
- **Сервер:** SonarQube Community 26.8.0.126808 (локально, Docker)
- **Проект:** `io.vigilant:vigilant`
- **Quality gate:** OK (0 новых нарушений, CAYC compliant)
- **Итог:** открытых проблем нет. Метрики: 0 bugs, 0 vulnerabilities, 0 security hotspots, 0 code smells, coverage 80.6%, дубликации 0%, рейтинги reliability/security/SQAL - A.

## История исправлений

- 2026-08-21: `kotlin:S6474` (dependency verification отсутствует) - исправлено: добавлены `gradle/verification-metadata.xml`, `gradle/verification-keyring.gpg`, `gradle/verification-keyring.keys` (pgp + sha256, 21 ключ). Артефакты, чьи ключи недоступны с keyserver, проверяются контрольными суммами sha256. Проверено принудительным прогоном `./gradlew --refresh-dependencies build`.
- 2026-08-21: `kotlin:S6624` (захардкоженные версии, 5 вхождений в `build.gradle.kts:19-23`) - исправлено: версии вынесены в Version Catalog `gradle/libs.versions.toml`.
- 2026-08-21: повторный анализ подтвердил закрытие обеих находок (0 открытых issues).
