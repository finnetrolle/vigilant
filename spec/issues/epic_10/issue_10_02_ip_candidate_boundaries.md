# VIG-10-02: IP перед завершающей пунктуацией

**Статус:** Done
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Boundary robustness > terminal punctuation
**Зависит от:** нет
**Блокирует:** [VIG-10-08](issue_10_08_quality_qualification.md)
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

`PiiDetector.detect` находит строгий IPv4/IPv6 literal, когда сразу после него
идёт завершающая пунктуация, а для IPv4 также отделяет decimal port. Finding
точно покрывает адрес и не поглощает delimiter, сохраняя отказ на malformed
address continuation.

## Критерии готовности

- [x] RED regression через `PiiDetector.detect` воспроизводит пропуск валидного
      IPv4 и IPv6 перед завершающей точкой или двоеточием.
- [x] Валидный IPv4 перед `:1..65535` возвращается без port в finding span.
- [x] Валидный IPv4/IPv6 перед одиночной завершающей точкой возвращается без
      точки в span; смешанные Unicode offsets остаются точными.
- [x] Malformed continuations вроде пятого IPv4 octet, лишней IPv6 group,
      второго `::` или следующего hex/digit token не превращаются в finding
      через усечение до удобного валидного prefix.
- [x] Unbracketed IPv6 с неоднозначным suffix не интерпретируется как
      host-and-port; bracket semantics EPIC-02 сохраняются.
- [x] Existing IPv4/IPv6, boundary, zone identifier и embedded IPv4 tests
      остаются GREEN.
- [x] `IP_ADDRESS` exact recall полного pinned RedMadRobot report достигает не
      менее `0.90`, exact precision не становится ниже baseline.
- [x] Scanner остаётся bounded/linear, вызывает cancellation checkpoint и не
      выполняет DNS/network calls.
- [x] `recognizerVersion` повышен, KDoc и canonical fixtures отражают новую
      surface.
- [x] Focused tests и `./gradlew build` проходят.

## Test/demo seam

Публичный `PiiDetector.detect` с exact type/span/evidence assertions, затем
per-type section команды `./gradlew redMadRobotPiiBenchmark`.

## Не входит

Hostname resolution, IPv6 zone IDs, port как часть finding, URL parsing,
ослабление strict IPv4/IPv6 parser или изменение `PiiType`.
