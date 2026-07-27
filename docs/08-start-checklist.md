# Что подготовить к старту

> Статус: стартовый checklist для Android-first personal MVP. Основные устройства,
> сервер, домен и repository уже известны. Секреты не нужно присылать в чат:
> Android signing material устанавливается в protected CI environment, а
> production credentials создаются непосредственно на сервере или телефоне.

## 1. Зафиксированные исходные данные

Уже согласовано:

```text
Владелец: один пользователь
Телефон: OnePlus Open
ОС: OxygenOS 16
Часы: OnePlus Watch 2
Wearable app: OHealth
Health flow: Watch 2 → OHealth → Health Connect → Life Agent Android
Основной интерфейс: нативное Android-приложение
Timezone: Asia/Novosibirsk
API domain: https://life.andriyshkoy.ru
Repository: https://github.com/Andriyshkoy/life_agent
Deployment: Docker Compose на личном сервере
Server storage: PostgreSQL
Voice/AI parsing: не входят в MVP
```

Telegram-бот, отдельный Android companion и vendor cloud API не нужны. Один APK
содержит ручные формы, локальные справочники, Health Connect reader, Room/outbox
и синхронизацию.

До первой release-сборки зафиксировать как необратимые или дорогие для смены
идентификаторы:

```text
Application name: Life Agent
Production applicationId: ru.andriyshkoy.lifeagent
Release key alias:
Первый versionCode/versionName:
Целевой M2 API base URL: https://life.andriyshkoy.ru/api/v1/
```

`applicationId` и release signing key нельзя случайно заменить после начала
dogfooding: Android воспримет APK как другое приложение либо откажется обновлять
его поверх установленной версии.

Текущий HTTPS bootstrap уже работает: отдельный сертификат валиден, HTTP
отвечает `308` на HTTPS, а `GET /healthz` возвращает ровно
`{"status":"ok","service":"life-agent","phase":"bootstrap"}`; `HEAD /healthz`
также разрешён. `/api/v1` намеренно отвечает `404` до реализации enrollment/sync
API в M2.

## 2. Day 0 на телефоне

До реализации production Health Connect reader завершить физический capability
test из [Day 0 report](12-day-0-oneplus-health-connect.md):

- [ ] Обновить firmware часов и OHealth.
- [ ] Включить sleep, all-day heart rate и resting heart rate.
- [ ] Синхронизировать часы с OHealth.
- [ ] Проверить, что OHealth разрешена запись нужных типов в Health Connect.
- [ ] Установить подготовленный read-only probe APK.
- [ ] Вернуть capability report за 48 часов.
- [ ] Вернуть capability report за 30 дней.
- [ ] Отдельно выполнить optional scan для HRV, SpO₂, дыхания, тренировок,
  шагов, дистанции, калорий и скорости.

Нужны только обезличенные counts/capabilities из probe. Значения BPM, SpO₂,
точные timestamps и record IDs присылать не требуется.

По результатам зафиксировать:

```text
Health Connect доступен:
OHealth origin для сна:
Есть sleep stages:
OHealth origin для heart rate:
Resting heart rate:
Дополнительные типы:
Задержка после OHealth sync:
Доступное окно истории:
```

Отсутствующий тип остаётся `unavailable/unknown`; его нельзя восстанавливать
догадкой. Production MVP импортирует sleep и ordinary HR; resting HR может
добавиться в M4 только при подтверждении Day 0 и отдельном permission flow.
Остальной optional scan является discovery для post-MVP решений.

## 3. Android-проект

### Toolchain

- [ ] Production app находится в отдельном Gradle module, а Day 0 probe остаётся
  диагностическим артефактом.
- [ ] JDK, Gradle, Android Gradle Plugin, Kotlin, compile/target SDK и Health
  Connect SDK закреплены версиями.
- [ ] Сборка воспроизводится локально в Docker, как и CI job.
- [ ] Debug и release имеют разные application ID suffix/API config.
- [ ] `minSdk` соответствует OnePlus Open и не расширяется без причины.
- [ ] Включены Android Lint, unit tests и dependency verification.
- [ ] Release manifest не имеет `debuggable`, cleartext traffic или лишних
  permissions.
- [ ] Top-level navigation содержит ровно `Добавить`, `Справочники`,
  `Настройки`; sync/enrollment находятся в `Настройки → Синхронизация`.
- [ ] Первый запуск предлагает `Пропустить`/`Настроить позже` для server
  enrollment и сразу допускает локальный capture.
- [ ] Note form содержит только текст и effective timestamp, без отдельного
  title.

Текущий probe уже подтверждает работоспособность Android toolchain, но его debug
signing key не переносится в production.

### Release signing

Один раз создать production release-signing keystore на доверенной машине:

```text
Keystore file:
Key alias:
Certificate SHA-256:
Срок действия:
Зашифрованная offline backup-копия:
```

Правила:

- keystore и пароли отсутствуют в Git, Docker image и build logs;
- зашифрованная offline-копия проверена до первого release;
- CI получает signing material только в protected production job;
- APK artifact публикуется с SHA-256, versionCode/versionName и commit SHA;
- установка обновления поверх предыдущей release-сборки проверяется на телефоне.

### Permissions

Начальный набор:

- `INTERNET` только для собственного HTTPS API M2;
- read permissions для sleep и ordinary HR только после Day 0; resting HR —
  отдельно и только если Day 0 подтвердил availability;
- notifications — только если они реально нужны для понятного sync status;
- без contacts, SMS, phone, location, accessibility и broad storage.

Health Connect types кроме sleep, ordinary HR и условного resting HR остаются
post-MVP discovery и не получают production permissions в MVP. Никаких health
write/route permissions;
background/history permissions возможны только условно после стабильного
foreground P0, runtime capability check и доказанной необходимости.

## 4. Локальная модель данных

До первой ручной формы подготовить:

- [ ] Room schema и migration test.
- [ ] Client-generated UUID для event, revision и outbox command.
- [ ] `current_revision_id` явно выбирает текущую revision; ancestry задаётся
  `revision_parent`, а `record_status` ограничен `active|retracted`.
- [ ] `revision_no` не используется как identity или глобально монотонный
  порядок: на branches значения могут совпадать.
- [ ] Одна транзакция `domain event + outbox`.
- [ ] WorkManager с constraints, exponential retry и ручным `Sync now`.
- [ ] Sync states: `saved locally`, `waiting`, `synced`, `failed`.
- [ ] Server idempotency contract и unique constraint.
- [ ] Offline correction и deletion tombstone.
- [ ] UTC, исходный offset, IANA timezone и provenance.
- [ ] Local validation единиц и диапазонов.

Рекомендуемый retention:

```text
Локальные canonical events/revisions/tombstones: сохранять весь MVP
Outbox: до server ACK + короткий технический TTL
Sync diagnostics без content: 30 дней
Temporary import files: удалить сразу после обработки
```

Eviction подтверждённой local history можно проектировать позже только после
проверенного paginated bootstrap/restore с сервера; в текущем MVP она не
удаляется после ACK.

Нужно проверить три сценария до dogfooding:

1. запись в airplane mode появляется локально;
2. после восстановления сети она доходит на сервер;
3. повторный WorkManager run создаёт ровно одно логическое событие.

## 5. Android security и backup policy

До хранения реальных health data:

- [ ] Room/outbox зашифрованы поддерживаемым encrypted SQLite driver либо
  application-level AEAD.
- [ ] Случайный DEK хранится только wrapped; wrapping key создан в Android
  Keystore.
- [ ] Rotating refresh token хранится Keystore-wrapped и никогда не попадает в
  APK; access token имеет короткий TTL.
- [ ] Room, outbox, credentials, keys и exports исключены из Android Auto Backup
  и device-to-device transfer.
- [ ] Production `data-extraction-rules` и manifest backup policy проверены
  инструментально.
- [ ] Private data не пишутся во внешнее/shared storage.
- [ ] Sensitive HTTP/Room data не появляются в Logcat.
- [ ] На телефоне настроен надёжный PIN/password.
- [ ] Biometric/app lock либо реализован как optional setting, либо явно
  отложен с указанным риском.
- [ ] Полный local wipe и повторный enrollment протестированы.

При восстановлении на новом телефоне не переносится старая credential chain.
Новый install проходит новый device enrollment и получает данные с личного
сервера через аутентифицированный versioned bootstrap:

- events, revisions и tombstones читаются постранично;
- opaque cursor сохраняется только после атомарного применения Room-страницы;
- повтор страницы идемпотентен;
- несовместимая schema version останавливает restore до изменения данных;
- старый pending outbox не восстанавливается с сервера;
- после bootstrap продолжается обычный incremental pull/push.

## 6. Сервер и DNS/TLS

### Известно

- DNS A-record `life.andriyshkoy.ru` уже направлен на личный сервер.
- На сервере используется Docker/Compose и системный reverse proxy.
- Сервис personal/single-user; публичный signup не требуется.
- Dedicated HTTPS bootstrap установлен и проверен: валидный certificate, HTTP
  `308` redirect и точный content-free ответ
  `{"status":"ok","service":"life-agent","phase":"bootstrap"}` на
  `GET /healthz`; `HEAD /healthz` также разрешён.
- `/api/v1` сейчас намеренно возвращает `404`; это не sync API и он не принимает
  пользовательские данные до M2.

### Проверить и подготовить

```text
ACME contact email:
Production SSH user:
Compose directory:
PostgreSQL volume:
Encrypted off-host backup target:
Backup public recipient / key location:
Принятые RPO/RTO:
```

Checklist:

- [x] DNS резолвится в ожидаемый IPv4 с внешней сети.
- [x] Получен отдельный TLS certificate для `life.andriyshkoy.ru`.
- [ ] Автоматическое renewal и reload nginx проверены.
- [x] HTTP перенаправляется кодом `308` на HTTPS без приёма credentials.
- [x] HTTPS `/healthz` возвращает точный bootstrap JSON без content.
- [x] `/api/v1` не опубликован преждевременно и ожидаемо отвечает `404`.
- [x] Life Agent nginx vhost установлен отдельным файлом; существующие публичные
  vhost прошли внешний smoke-test после reload.
- [ ] Наружу открыт только необходимый SSH/HTTP/HTTPS; PostgreSQL не
  опубликован.
- [ ] Docker network отделяет API и PostgreSQL.
- [ ] Приложение работает от непривилегированного пользователя.
- [ ] PostgreSQL role не superuser и ограничена своей схемой.
- [ ] DB migrations выполняются контролируемым job.
- [ ] DB password, backup key и API secrets создаются на сервере и находятся вне
  Compose/Git.
- [ ] Ограничены request body, timeout, rate и Docker/nginx log size.
- [ ] Access/error logs не содержат `Authorization`, query с content и body.
- [ ] Аутентифицированный sync/event endpoint и content-free `/healthz`
  разделены.
- [ ] Свободное место, certificate expiry и свежесть backup мониторятся без
  health content.

До включения M2 API и первой fixture/реальной загрузки серверная ОС должна быть
поддерживаемой и получать security updates, а firewall policy должна быть
проверена с внешней сети. Текущая EOL ОС или неподтверждённый firewall являются
жёстким gate для M2 real data, но не блокируют Day 0, локальную M1 и уже
развёрнутый content-free HTTPS bootstrap.

## 7. API и device enrollment

Начальный production contract:

```text
Target M2 base: https://life.andriyshkoy.ru/api/v1/
Auth: short-lived per-device access token
Refresh: rotating opaque token
Server storage: credential hashes + rotation state only
Owner model: fixed single subject
Delivery: idempotent event/revision batches
```

Перед первым sync:

- [ ] Локальный app работает без enrollment; первый запуск предлагает
  `Пропустить`, а `Настроить позже` доступно в
  `Настройки → Синхронизация`.
- [ ] Server CLI создаёт одноразовый enrollment code с TTL не более 10 минут.
- [ ] Code хранится server-side только в hash-виде и атомарно погашается.
- [ ] Сервер выдаёт `device_id`, short-lived access token и rotating refresh
  token минимум 256 бит по TLS.
- [ ] Приложение сохраняет refresh token с Android Keystore; access token не
  переживает свой короткий TTL.
- [ ] Сервер хранит `device_id`, token hashes, rotation/reuse state, status и
  audit timestamps без health content.
- [ ] Enrollment endpoint закрывается без активного одноразового code.
- [ ] Если уже есть active device, новый enrollment отклоняется по умолчанию.
- [ ] Explicit replacement атомарно отзывает старую credential chain и
  активирует новую.
- [ ] Auth и enrollment имеют rate limit.
- [ ] Refresh выполняется single-flight; reuse уже rotated token отзывает всю
  credential chain.
- [ ] Отозванная chain не влияет на DB/deploy/backup credentials.
- [ ] Проверены revoke потерянного устройства, reinstall и новый enrollment.
- [ ] API игнорирует попытку клиента выбрать другого `subject_id`.
- [ ] Authenticated bootstrap постранично восстанавливает events, revisions и
  tombstones в чистый Room, проверяя schema compatibility.
- [ ] Отложенный/неуспешный enrollment не блокирует локальные формы и не меняет
  уже подтверждённые local events.

Access/refresh tokens, enrollment code и production DB credential не являются
CI secrets и никогда не передаются через GitHub Actions.

## 8. PostgreSQL, backup и восстановление

До dogfooding:

- [ ] Canonical schema поддерживает events, revisions, provenance, source
  records, idempotency и tombstones.
- [ ] Есть уникальные ограничения для client event/revision/outbox IDs.
- [ ] Первая fixture-заметка успешно синхронизирована и подтверждена сервером;
  до этого baseline backup не создаётся.
- [ ] После этой fixture-заметки создан encrypted baseline PostgreSQL backup и
  восстановлен в чистую БД.
- [ ] Daily PostgreSQL backup зашифрован.
- [ ] Минимум одна зашифрованная копия находится вне основного сервера.
- [ ] Ключ backup хранится отдельно от сервера и самой копии.
- [ ] Retention задан явно, например 7 daily + 4 weekly.
- [ ] Success/failure backup виден в content-free monitoring.
- [ ] Выполнен реальный restore в чистое изолированное окружение.
- [ ] Restore проверяет migrations, revisions, idempotency и export.
- [ ] Restore применяет deletion tombstones до запуска API.
- [ ] Новый Android install восстановлен через authenticated bootstrap и затем
  успешно продолжил incremental sync.

Backup без проверенного восстановления не считается backup. Android Auto Backup
не заменяет server backup и в MVP выключен для sensitive app data.

## 9. GitHub и CI/CD

Repository:

```text
https://github.com/Andriyshkoy/life_agent
```

Подготовить:

- [ ] Default branch и protected production environment.
- [ ] Workflow permissions по умолчанию read-only.
- [ ] PR/push checks: format, unit tests, Android Lint, assemble, backend tests,
  Compose validation и image scan.
- [ ] Android build выполняется в закреплённом container/toolchain.
- [ ] Backend image публикуется в GHCR по immutable digest.
- [ ] Production deploy разрешён только из protected environment.
- [ ] SSH host key хранится явно; `StrictHostKeyChecking=no` запрещён.
- [ ] Deploy проверяет healthcheck и имеет документированный rollback на
  предыдущий image digest.
- [ ] Secrets и generated APK/DB artifacts покрыты `.gitignore`.

Production release job подписывает APK в protected GitHub Environment. Для него
нужны secrets:

```text
ANDROID_KEYSTORE_B64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Для server deploy:

```text
PROD_SSH_HOST
PROD_SSH_USER
PROD_SSH_PRIVATE_KEY
PROD_SSH_HOST_KEY
```

Названия являются договорённым интерфейсом workflow, а не приглашением
передавать значения в чат. Если registry package закрыт, pull credential
устанавливается непосредственно на сервере с минимальным `read:packages`.

CI не получает:

- PostgreSQL dump или health fixtures из production;
- device access/refresh token или enrollment code;
- DB password, encryption DEK или backup private key;
- OHealth/Google credentials, которых нет в текущем flow.

Для tests используются synthetic fixtures без реальных заметок, лекарств,
пульса и timestamps владельца.

## 10. Личные настройки MVP

Зафиксировать на первом запуске:

```text
Основная timezone: Asia/Novosibirsk
Язык: русский
Вес: kg/g
Объём: ml
Дистанция: km/m
Decimal input: запятая и точка
Формат времени: 24 часа
App lock: выключен / после background / при каждом открытии
Строгий режим sync только после unlock: да/нет
```

Для MVP нет обязательных check-ins, streaks, medication schedules или
медицинских reminders. У лекарств может быть обычная доза/dose preset, но
приложение хранит только отдельно подтверждённый actual intake, не даёт
назначений и не меняет дозировку.

## 11. Что отслеживаем в MVP

Зафиксированный scope:

- [x] Питание и snapshot КБЖУ.
- [x] Локальные продукты, рецепты и порции.
- [x] Самочувствие с выбранным значением, timestamp и комментарием.
- [x] Справочник лекарств/БАДов и фактический приём.
- [x] Текстовые заметки.
- [x] Сон и пульс через подтверждённый Health Connect route.
- [x] Для sleep и ordinary HR задан автоматический foreground incremental import
  при app-open и ручной `Sync now` fallback.
- [ ] Resting HR — только если Day 0 подтвердит availability и отдельный
  permission flow в M4.
- [ ] HRV/SpO₂/дыхание/тренировки/steps/distance/calories/speed — только
  post-MVP discovery, не production permissions текущего MVP.
- [ ] Голос, фото, OCR, AI parsing и RAG — после стабильного MVP.

Для каждой ручной формы проверить:

- типичный capture занимает несколько нажатий;
- `Сейчас` можно заменить фактическим временем;
- preview не подменяет `unknown` нулём;
- после сохранения доступны `Исправить` и `Отменить`;
- server outage не блокирует ввод.

## 12. Локальный каталог еды

Для полезного старта подготовить:

- 20–50 часто используемых продуктов;
- 10–20 повторяющихся блюд;
- 5–10 обычных порций или meal presets;
- личные aliases.

Шаблоны:

- [foods.csv](../templates/foods.csv);
- [recipes.csv](../templates/recipes.csv);
- [recipe_ingredients.csv](../templates/recipe_ingredients.csv);
- [portion_presets.csv](../templates/portion_presets.csv).

Для продукта достаточно:

- точного названия и бренда;
- основы: 100 г, 100 мл или serving;
- энергии, белка, жира и углеводов;
- массы упаковки/порции, если она используется;
- заметки об источнике значений.

Неизвестные клетчатка, натрий или сахар остаются пустыми, не `0`. Для домашнего
блюда один раз фиксируются ингредиенты и готовый выход; изменение рецепта
создаёт новую version и не пересчитывает прошлые события.

## 13. Порядок фактического запуска

```text
1. Параллельно выполнить OnePlus/OHealth/Health Connect Day 0; его gate
   блокирует только M4, а не ручную разработку
2. Зафиксировать applicationId и создать release signing key
3. Собрать Android shell: navigation, encrypted Room, outbox, WorkManager и
   локальный notes vertical slice
4. Сохранить уже работающий TLS bootstrap: HTTP 308, точный /healthz и
   намеренный /api/v1 404 до M2
5. Перевести server на поддерживаемую ОС и проверить firewall policy снаружи
6. Развернуть PostgreSQL schema и M2 /api/v1, реализовать enrollment,
   access/refresh rotation, token hashes и revoke
7. Провести notes offline/idempotency/bootstrap/end-to-end sync tests и получить
   ACK первой fixture-заметки
8. Только после этого создать baseline encrypted backup и выполнить clean restore
9. Добавить справочники и ручные формы: еда, самочувствие и фактический приём
10. После M0 перенести подтверждённый Health Connect reader в production APK
11. Закрыть export/delete/full restore и production hardening
12. Подписать release APK, развернуть backend image digest и начать dogfooding
13. После репрезентативного цикла исправить friction, sync gaps и import
    duplicates; только затем планировать voice, OCR, RAG и советы
```

## 14. Go/no-go перед реальными данными

- [ ] Release APK обновляется поверх предыдущей версии и подписан ожидаемым
  certificate.
- [ ] Room/outbox encryption и Android backup exclusion проверены.
- [ ] DNS/TLS и cleartext rejection проверены с телефона.
- [ ] Server OS поддерживается и получает security updates; firewall policy
  проверена с внешней сети до M2 API/real data.
- [ ] Enrollment, sync, retry, idempotency и revoke прошли end-to-end.
- [ ] Второй active device отклонён; explicit replacement и bootstrap на новый
  телефон прошли end-to-end.
- [ ] PostgreSQL не доступен из интернета.
- [ ] В Logcat/nginx/API/CI нет content и credentials.
- [ ] Health permissions совпадают с фактически реализованными типами.
- [ ] После первой synced fixture note encrypted baseline backup восстановлен в
  чистое окружение.
- [ ] Export и full delete/tombstone проверены.
- [ ] На сервере есть свободное место и поддерживаемая security baseline.
- [ ] Обрабатываются только данные владельца; доступ третьим лицам отсутствует.
- [ ] Для каждого off-host backup/processor зафиксированы провайдер, страна и
  передаваемые данные; граница personal use не нарушена.

### Что ещё потребуется от владельца

Без секретов можно сообщить:

```text
Результаты трёх Day 0 capability reports:
Выбранный production applicationId:
ACME contact email:
Production SSH user:
Compose directory:
Encrypted off-host backup target:
RPO/RTO:
App lock preference:
```

Signing passwords, SSH private key, database password, device access/refresh
tokens, enrollment code и backup private key устанавливаются в их целевые
secret stores и не передаются сообщением.
