# VIG-03-02: Нормализация policy URL

**Статус:** Done
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** URL normalization  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md)  
**Оценка:** 2-3 инженерных дня

## Результат

Pure deterministic component преобразует destination URI в единственный
нормативный policy match key, согласованный с EPIC-04.

## Нормативный contract

- Input является effective absolute upstream HTTP(S) URI после объединения
  configured base path и inbound request path.
- Key имеет форму `scheme://authority/path`: scheme и IDNA ASCII host
  lowercase, terminal DNS dot удалён, IPv6 literal валидируется без DNS lookup
  и записывается в единственной lowercase compressed форме, default
  `http:80`/`https:443` port удалён, другой port сохранён.
- Path обязан начинаться с `/`; empty path становится `/`. Dot segments
  удаляются, repeated slash и trailing slash сохраняются как семантически
  значимые, path case сохраняется. EPIC-04 затем сравнивает полный key
  case-insensitive по своему существующему contract.
- Percent escapes используют uppercase hex; percent-encoded unreserved ASCII
  декодируется, reserved characters, включая encoded slash, остаются encoded.
- Query, fragment и user-info полностью исключены и никогда не попадают в key
  или safe error.
- Malformed escape, unsupported scheme, absent host или invalid IDNA/port даёт
  typed `INVALID_POLICY_URL` без partial key.

## Тестовый seam

Table-driven pure unit tests без Armeria и сетевых вызовов.

## Критерии приёмки

- [x] Эквивалентные разрешённые URI дают один key.
- [x] Различия, значимые для policy matching, не схлопываются.
- [x] Query, credentials и secrets не попадают в context или errors, если они
  исключены принятым контрактом.
- [x] Unsupported inputs дают typed safe error либо явно определённый result.
- [x] Locale пользователя не влияет на normalization.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Glob/regex matching, redirects, DNS resolution и policy selection.
