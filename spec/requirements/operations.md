# Эксплуатационная готовность Docker deployment

Нормативный owner эксплуатационных обязанностей, request-based availability
SLI, внешнего probe и production review. Численный availability SLO и его
отсутствие до review принадлежат [MVP NFR](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#deployment-и-stack).
Практическая приёмка описана в [operator reference](../../docs/operations.md),
наблюдения приложения - в [evidence](../../docs/operations-evidence.md).

## Deployment и ответственность

Первый production deployment допускает одну Docker-реплику. Поддерживаются
несколько stateless replicas за внешним балансировщиком. Каждая replica владеет
своими identity cache, bounded request source и retained in-memory response
sources; shared runtime state не требуется. Остановка единственной реплики
прерывает обслуживание. Общий host и балансировщик остаются отдельными failure
domains; численная HA-гарантия и zero-error rollout не заявлены.

| Владелец | Обязанности |
|---|---|
| Vigilant | Docker artifact, локальные health/readiness, bounded shutdown по [HTTP contract](http-gateway.md#health-admission-and-shutdown), JSONL stdout по [observability contract](observability.md#stdout-topology-and-ownership) |
| Администратор deployment | Запуск, restart/rollout/recovery, балансировка, external probe, сбор/хранение telemetry, уведомления и реакция на инциденты |
| Владелец сервиса | Review production evidence и последующий выбор численного SLO, постоянного окна и error-budget policy |

Администратор проверяет на стенде одну реплику (startup, полный запрос, stop,
restart), graceful shutdown, несколько реплик (rotation, planned stop и crash),
три stdout signal types и внешний probe по
[checklist](../../docs/operations.md#приёмка-на-стенде). Для production используются
совместимые identity settings; DUMMY допустим только в development/test.
Отдельного численного recovery target нет; завершение долгого запроса при crash
контейнера не гарантируется. Ошибки restart/rollout входят в общий SLI.

Подготовка выпуска не требует ожидания production measurements. Расширение
runtime или инфраструктуры требует отдельного согласованного scope. В текущую
поставку не входят конфигурации telemetry chain, развёртывание Fluentd,
Collector, OpenObserve, балансировщика или мониторинга, dashboards, alert
delivery, autoscaling, Kubernetes/Helm, shared runtime state, durable audit,
direct application network exporters и новые runtime SLI instruments.
Enforcement, retries и shutdown semantics остаются у существующих owners.
Несвязанные conformance/evidence gaps не считаются закрытыми.

## Request-based availability SLI

За явно указанный период SLI равен доле штатно завершённых подходящих
клиентских попыток среди всех таких попыток. Подходящая попытка - корректный
аутентифицированный запрос в поддерживаемом контракте. HTTP status class сам
по себе не определяет штатность результата.

| Наблюдаемый результат | Учёт |
|---|---|
| Полностью завершённый ALLOW/MASK или корректный policy BLOCK | Штатный результат, числитель и знаменатель |
| Технический отказ Vigilant, identity provider, upstream LLM, балансировщика или инфраструктуры | Неуспех, только знаменатель; причина учитывается отдельно |
| HTTP 200 с последующим техническим обрывом ответа | Неуспех; получение headers не доказывает завершение |
| Явно некорректный запрос или отказ аутентификации по вине клиента | Вне знаменателя; отдельно от недоступности identity provider |
| Подтверждённая добровольная отмена клиентом до технического сбоя | Вне знаменателя; cancellation после timeout/failure не скрывает неуспех |
| Технический сбой с неизвестной причиной | Неуспех с неизвестной причиной, без произвольной атрибуции |
| Нет подходящих запросов | SLI не определён, а не 100% |
| Пропущенные terminal observations или неполные данные о попытках | Явный evidence gap; отсутствие записи не считается успехом |

Одна попытка учитывается один раз: её logs, spans и metrics не суммируются как
разные запросы. Retry клиента - отдельная попытка. Плановый restart не исключает
технические неуспехи. Классификация не изменяет HTTP/enforcement contract.

Независимый пример: два ALLOW, один MASK, один policy BLOCK и по одному
техническому отказу Vigilant, identity provider, upstream и инфраструктуры
дают `4 / 8 = 50%`. Дополнительный invalid client request не меняет результат.
Потеря terminal observation обозначает неполноту данных.

Нынешние proxy counters отражают только дошедший до приложения трафик и HTTP
status classes. Они не видят попытки к упавшему контейнеру и не обеспечивают
полную атрибуцию. Формула `1 - 5xx / requests` не является готовым end-to-end
SLI. Администратор выбирает ingress/client observations, сверяет полноту
попыток и terminal outcomes, отдельно фиксирует доступные данные и gaps.
При невозможности определить eligibility из-за технического отказа identity
provider нельзя объявлять попытку клиентской ошибкой без подтверждения.

## Внешний probe

Probe работает вне контейнера Vigilant и обращается к `/readyz` каждые
30 секунд. Каждая попытка ограничена пятью секундами. Timeout, connection error
или HTTP response, отличный от 200, считаются неудачей. Три последовательные
неудачи формируют сигнал; 200 сбрасывает последовательность.

От падения реплики до формирования сигнала должно проходить не более
120 секунд. Это требование к администраторскому probe, а не доказательство
настроенного мониторинга или срок доставки уведомления. Администратор владеет
каналом/доставкой, проверяет расписание и сохраняет timestamp падения,
результат каждой попытки и timestamp сигнала. Интервал задаётся между началами
попыток; даже при падении сразу после 200 номинальный bound составляет
`30 + 30 + 30 + 5 = 95` секунд, оставляя 25 секунд на scheduling задержки.
Фактический end-to-end bound проверяется на стенде, а не выводится только из
конфигурации.

`/readyz` не проверяет identity provider или upstream и не заменяет request-based
SLI. Сбой самого probe или telemetry chain не подтверждает доступность
приложения. Recovery 200 сбрасывает счётчик, но не стирает историю инцидента.

## Первый production review

Администратор фиксирует timestamp первого production запуска и через
14 календарных дней предоставляет владельцу сервиса отчёт: период и объём
трафика, результаты запросов, причины технических ошибок, интервалы
недоступности, restart/rollout/recovery observations и полноту telemetry.
14 дней задают первый review, а не постоянное окно SLO.

Владелец сервиса выбирает численный SLO, постоянное окно и error-budget policy
по результатам review. При недостаточном трафике или gaps фиксируются причина,
необходимые наблюдения и следующая дата review; период продлевается без
выдуманного SLO. Основания пересмотра контракта: этот review, нехватка данных
для SLI/атрибуции, новые требования к доступности, масштабу или delivery.
Процедура не создаёт календарное напоминание/автоматизацию и не требует ждать
14 дней внутри реализации.
