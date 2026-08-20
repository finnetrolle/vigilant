# VIG-02-07: Recognizer `IP_ADDRESS`

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > IP_ADDRESS  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Fast detector локально и без DNS распознаёт строгие IPv4 и IPv6 forms,
включая compressed IPv6 и embedded IPv4 tail, с metadata
`fast.ip_address`, `1.0.0`, `VALIDATED`.

## Критерии готовности

- [ ] IPv4 принимает только четыре octets `0..255` с правилами leading zero.
- [ ] IPv6 поддерживает full, единственный `::` и embedded IPv4 tail.
- [ ] Brackets не входят в span; zone identifiers отклоняются.
- [ ] IPv4 tail внутри IPv6 не создаёт отдельный finding.
- [ ] Private, loopback и link-local адреса распознаются.
- [ ] Есть exhaustive boundary, malformed compression и mixed-text tests.
- [ ] Parser не выполняет DNS или сетевые вызовы.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Hostname resolution, CIDR ranges и zone identifiers.
