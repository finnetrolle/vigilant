# VIG-02-07: Recognizer `IP_ADDRESS`

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > IP_ADDRESS  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Fast detector локально и без DNS распознаёт строгие IPv4 и IPv6 forms,
включая compressed IPv6 и embedded IPv4 tail, с metadata
`fast.ip_address`, `1.0.0`, `VALIDATED`.

## Критерии готовности

- [x] IPv4 принимает только четыре octets `0..255` с правилами leading zero.
- [x] IPv6 поддерживает full, единственный `::` и embedded IPv4 tail.
- [x] Brackets не входят в span; zone identifiers отклоняются.
- [x] IPv4 tail внутри IPv6 не создаёт отдельный finding.
- [x] Private, loopback и link-local адреса распознаются.
- [x] Есть exhaustive boundary, malformed compression и mixed-text tests.
- [x] Parser не выполняет DNS или сетевые вызовы.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Hostname resolution, CIDR ranges и zone identifiers.
