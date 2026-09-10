# Эксплуатация первого Docker deployment

Этот operator reference готовит deployment до накопления production telemetry.
Контракт и SLI-классификация принадлежат
[operations requirements](../spec/requirements/operations.md); выбранного
численного availability SLO пока нет. [Evidence приложения](operations-evidence.md)
отделено от приведённого ниже checklist администратора. Незаполненный checklist
не означает выполненный rollout, настроенный alert или доставку в OpenObserve.

## Подготовка

1. Собрать или выбрать проверенный [Docker artifact](deployment.md#oci-image).
   В журнале приёмки записать Git revision, version, image ID/digest,
   OS/architecture, дату и версии Docker/JRE. Использовать один выбранный
   artifact на всех проверяемых replicas.
2. Подготовить upstream, read-only `politics.conf` и identity configuration по
   [configuration reference](configuration.md). Для production выбрать JWT
   (issuer, audience, public JWK set) либо EXTERNAL (trusted Bridge URL и
   whole-exchange timeout). Пример DUMMY из deployment guide предназначен для
   local/test; smoke evidence с ним не проверяет production identity integration.
3. Передать secrets через механизм deployment, отдельно от image, Git и
   публикуемого evidence. Согласовать защиту сети до Bridge/upstream и
   synthetic запрос без реальных клиентских данных.
4. Начать с одной реплики либо подготовить внешнюю балансировку для нескольких.
   У каждой реплики собственные cache и in-memory sources. Учитывать общий host
   и балансировщик как отдельные failure domains; запас доступности при одной
   реплике отсутствует на время её остановки.
5. Настроить захват stdout, bounded non-blocking delivery, rotation и retention.
   Целевая chain и правила распознавания signal types описаны в
   [observability reference](observability.md#ответственность-deployment).
   Администратор предоставляет Fluentd, Collector, OpenObserve, их configuration,
   endpoints и secrets; приложение не подключается к ним по сети.
6. Настроить внешний probe по
   [30s/5s/3 failures/120s contract](../spec/requirements/operations.md#внешний-probe)
   и выбрать канал уведомления и ответственного за инцидент. Для нескольких
   replicas проверять каждую отдельно; балансировочный адрес может оставаться
   ready при падении одной replica и скрыть её отказ.

Default force timeout приложения - 30 секунд; `docker stop --timeout 35`
оставляет время для текущего shutdown budget. При переопределении таймаутов
согласовать Docker stop timeout с ними. Readiness и admission принадлежат
[HTTP lifecycle](../spec/requirements/http-gateway.md#health-admission-and-shutdown);
probe не проверяет работоспособность identity/upstream.

## Приёмка на стенде

Все строки ниже выполняет администратор на своём стенде. Для каждой сохраняются
дата, artifact/config revision без secrets, действие, фактическое наблюдение,
PASS/FAIL/GAP и ссылка на безопасное evidence. Не публиковать bodies, bearer,
identity values или raw dumps окружения.

| Сценарий | Действие и ожидаемое наблюдение | Что сохранить |
|---|---|---|
| Одна реплика: startup | Запустить с production-compatible identity, проверить `/healthz` = `200 ok`, `/readyz` = `200 ready`, выполнить synthetic запрос до полного ответа с ожидаемой policy reaction | Статусы/body probes, terminal outcome запроса, artifact и config revision |
| Одна реплика: stop/restart | Остановить реплику, подтвердить недоступность обслуживания; выполнить restart, дождаться readiness и повторить полный запрос | Интервал недоступности, результат после restart; отдельного recovery target нет |
| Graceful drain | Удержать upstream ответ незавершённым, начать SIGTERM, наблюдать readiness 503 и отказ нового admission; завершить ранее admitted ответ до deadline | `503 draining`, отсутствие нового upstream handoff, полный активный ответ и exit |
| Force timeout | Удерживать active exchange дольше настроенного deadline, выполнить SIGTERM; наблюдать закрытие exchange и завершение процесса | Настроенный bound, terminal event и exit; default force 30s, Docker stop 35s |
| Две или более replicas: rotation | По безопасным ingress/backend observations подтвердить, что запросы доходят до каждой replica. Снять одну с балансировки, drain/stop; новые запросы должны идти в оставшуюся | Backend для каждой попытки, rotation timestamps и terminal outcomes |
| Возврат replica | Запустить остановленную replica, дождаться её readiness, вернуть в rotation, подтвердить новый полный запрос к ней | Readiness перед rotation и последующий terminal outcome |
| Crash replica | Аварийно остановить одну replica. После исключения backend балансировщиком новые запросы доходят до оставшейся | Время crash/exclusion, успехи и технические ошибки перехода; долгий запрос к упавшей replica не гарантирован |
| Stdout приложения | Включить OTLP, выполнить synthetic запрос, получить отдельные JSONL application logs, `resourceSpans`, `resourceMetrics` | Image ID, commands и распознанные схемы; [повторяемая методика](operations-evidence.md#наблюдение-stdout-artifact) |
| Telemetry chain | На своей chain проследить каждый из трёх signals от stdout до соответствующего logs/traces/metrics представления в OpenObserve, проверить correlation | Версии компонентов, безопасные IDs проверки и результаты каждого signal; просто строка с OTLP JSON в log storage не доказывает сохранение metrics/traces |
| Внешний probe | Уронить replica сразу после успешной проверки и в других фазах расписания. Зафиксировать три последовательные неудачи и сигнал не позже 120s от crash | Время crash, начала/конца/результаты попыток и формирования сигнала, отдельно время доставки уведомления |
| Probe recovery/failure | После восстановления получить 200, подтвердить сброс счётчика; отдельно проверить наблюдаемость сбоя самого probe/chain | Сброс последовательности и видимый monitoring gap, без ложного подтверждения доступности |

Балансировщик и single host не квалифицируются этими сценариями как отказоустойчивые.
Ошибки planned restart и переходного периода crash сохраняются в SLI. Checklist
не обещает zero-error rollout, durable audit или завершение запросов после crash.

## Сбор availability observations

До запуска администратор выбирает ingress/client источник попыток и источник
их полного terminal результата, включая недоступный контейнер, балансировщик,
identity provider и upstream. Proxy counters и `/readyz` сами по себе этого
набора не дают. Для выбранного периода применить
[полную классификацию](../spec/requirements/operations.md#request-based-availability-sli),
сопоставить одну попытку с её observations без суммирования log/span/metric
и сохранить причины технических неуспехов отдельно от общего SLI.

Для review достаточно безопасного реестра: период, источник, число подходящих
попыток, штатные completions, failures по причинам, client exclusions и
подтверждение их оснований, неизвестные причины, пропущенные terminal outcomes,
интервалы monitoring gaps. Не трактовать cancellation после timeout как
добровольную отмену. HTTP 200 без завершённого ответа не считать успехом.
Если источник не видит часть попыток или их завершение, явно отметить
неполноту; при нулевом знаменателе SLI не определён.

## Первый production review

Администратор записывает timestamp первого production запуска с timezone и
дату review через 14 календарных дней. Владелец сервиса получает отчёт по
[review contract](../spec/requirements/operations.md#первый-production-review):
период/трафик, классифицированные результаты и причины, недоступность,
restart/rollout/recovery, полноту telemetry. По этим данным он выбирает
численный SLO, постоянное окно и error-budget policy. При недостатке данных
записываются причина, необходимые наблюдения и следующая дата review.
Этот документ не запускает календарное напоминание или автоматизацию.
