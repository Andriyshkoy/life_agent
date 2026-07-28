# План разработки и доставки Android-first MVP

## Статус

Дата актуализации: 28 июля 2026 года.

Основной продуктовый интерфейс изменён с Telegram-бота на самостоятельное
Android-приложение. Telegram shell, long polling, BotFather token, команды и
chat-формы удалены из MVP.

План не привязан к неделям или фиксированной дате. Milestone закрывается только
после выполнения gate; незакрытая надёжность не компенсируется новой функцией.
Этот документ является нормативным для порядка работ, scope milestone и gates.
Если краткий roadmap, checklist или product spec допускает другое толкование,
приоритет имеет этот план.

## Что должно получиться

Один владелец использует Life Agent на OnePlus Open для быстрого сбора:

- питания из локального каталога продуктов, блюд и preset-порций;
- timestamped самочувствия из собственных dimensions/options;
- фактического приёма лекарств и БАДов из личного списка;
- текстовых заметок;
- сна и пульса из `OnePlus Watch 2 → OHealth → Health Connect`.

Приложение сохраняет действие локально даже без сети, а затем надёжно
синхронизирует его с личным сервером:

```text
┌──────────────────────────── OnePlus Open ────────────────────────────┐
│                                                                     │
│  Compose UI → domain validation → Room events + transactional outbox│
│                                          │                          │
│  Watch 2 → OHealth → Health Connect ─────┘                          │
└──────────────────────────────────────┬──────────────────────────────┘
                                       │ authenticated HTTPS sync (M2)
                                       ▼
                         life.andriyshkoy.ru
                         nginx → API (M2) → PostgreSQL
                                       │
                                       └→ encrypted off-host backup
```

Room является durable local-first хранилищем и очередью доставки. PostgreSQL
является долговременным каноническим хранилищем после server acknowledgement.
Обе стороны используют одинаковые стабильные IDs, revisions, timestamps,
provenance и tombstones. Room хранит полную локальную историю events, revisions
и tombstones весь MVP; server ACK не является основанием для её eviction.

Просмотр истории, дневной dashboard и графики не нужны для MVP. В приложении
остаются только capture UI, управление справочниками, подтверждение последнего
действия, correction/undo, Health Connect status, sync status, export/privacy и
технические настройки.

## Уже подтверждено

### Устройства и Health Connect

- Телефон: OnePlus Open, OxygenOS 16.
- Часы: OnePlus Watch 2.
- Health Connect на Android не имеет server API; чтение выполняет приложение на
  телефоне.
- Публичный OHealth REST/OAuth API для требуемых данных не документирован.
- Подготовлен и собран foreground-only read-only Day 0 probe.
- Probe проверяет sleep, heart rate и resting heart rate, а отдельным optional
  scan — HRV, SpO₂, respiration, exercise, steps, distance, calories и speed.
- Probe не имеет network, write, background, history или exercise-route
  permissions.

Физический availability gate пройден с verdict
`GO_WITH_REDUCED_SLEEP_DETAIL`: OHealth sleep sessions и ordinary HR
разблокируют M4 P0, а подтверждённый RHR остаётся optional/P1. Полный decision
record находится в
[Day 0 документе](12-day-0-oneplus-health-connect.md).

### Сервер

- Персональный x86_64 Linux VPS имеет Docker Engine и Compose v2.
- На host уже работают другие Compose-проекты и системный nginx.
- Ресурсов достаточно для компактного API и PostgreSQL.
- `life.andriyshkoy.ru` выбран как единственный публичный hostname Life Agent.
- HTTPS bootstrap уже развёрнут: отдельный сертификат валиден, HTTP отвечает
  `308` на HTTPS, а `GET /healthz` возвращает ровно
  `{"status":"ok","service":"life-agent","phase":"bootstrap"}`;
  `HEAD /healthz` также разрешён.
- `/api/v1` сейчас намеренно отвечает `404`: enrollment, sync API и приём
  пользовательских данных появляются только в M2.
- PostgreSQL, Docker socket и внутренние service ports нельзя публиковать.
- Life Agent разворачивается отдельным Compose project и отдельной network.

Известные ограничения host — EOL release ОС, swap pressure и отсутствие
подтверждённого firewall policy — не блокируют только Day 0, локальную M1 и
content-free HTTPS bootstrap. M2 и первая загрузка реальных данных заблокированы
до перехода на поддерживаемую ОС с security updates и проверки firewall policy.
Целевой M2 data surface будет доступен только по HTTPS; host сохраняет
ограниченный SSH, а HTTP — только redirect/ACME. Upgrade ОС и firewall
maintenance выполняются отдельной контролируемой операцией, не смешанной с
application deploy.

### Репозиторий

- Публичный GitHub repository создан и доступен для push.
- GitHub Actions доступны.
- Android Day 0 source и discovery-документация уже находятся в workspace.
- Секреты, signing keys, реальные exports, backup и health fixtures не должны
  попадать в публичный Git.

## Зафиксированный технический контур

### Android

- Kotlin.
- Jetpack Compose + Material 3.
- Navigation Compose.
- Room для local store, migrations и transactional outbox.
- WorkManager для устойчивой фоновой доставки.
- Health Connect SDK для read-only импорта.
- Android Keystore-wrapped rotating refresh token; short-lived access token
  хранится только в памяти.
- Версионированные DTO и строгая domain validation.
- Один app module на старте; feature packages выделяются по доменам без
  преждевременной многомодульности.

Product app и Day 0 probe — разные application IDs. Диагностический APK остаётся
воспроизводимым инструментом, а нужный read/scanner code переносится в product
app после device gate.

### Backend

Компактный Python modular monolith:

- `api` — enrollment, versioned sync, readiness и export;
- `worker` — backup/export и bounded background jobs, когда они действительно
  нужны;
- `migrate` — одноразовое применение Alembic migrations;
- `postgres` — canonical server database.

Redis, Kafka, Celery, Kubernetes, vector database и микросервисы для одного
владельца не нужны. Durable operations и outbox на обеих сторонах достаточно.

### Доменная модель

Общий event envelope содержит как минимум:

- stable `event_id` и `capture_id`;
- stable `revision_id` и `life_event.current_revision_id` как явный указатель на
  выбранную текущую revision;
- ancestry через `revision_parent` с relation `supersedes|resolves`;
- `revision_no` только как display hint: на branches значения могут совпадать,
  поэтому это не identity и не глобальный монотонный порядок;
- `effective_at`, `recorded_at` и IANA timezone;
- source: manual, Health Connect и точный source type;
- provenance/source key;
- `record_status: active|retracted`; удаление представлено tombstone revision, а
  текущая projection определяется `current_revision_id`;
- schema version;
- device operation/idempotency key;
- server received timestamp после sync.

Справочники и факты разделены:

- product/recipe/dose option изменяются;
- food consumption, wellbeing event и actual medication intake хранят snapshot;
- sleep/HR сохраняют Health Connect provenance;
- старое событие не пересчитывается из текущей карточки.

## Definition of Done для любого ручного capture

Сценарий считается готовым, только если:

1. Он начинается явным действием пользователя и не полагается на NLP.
2. Числа, единицы и обязательные поля проверяются до commit.
3. Событие и outbox operation создаются одной Room transaction.
4. `Сохранено на устройстве` показывается только после успешного local commit.
5. Double tap/recomposition/retry не создают второе активное событие.
6. Хранятся effective time, recorded time, timezone, source и snapshot.
7. Неизвестное значение остаётся unknown, а не нулём или догадкой.
8. Correction создаёт revision, undo/delete создаёт tombstone.
9. Offline capture доступен и позже синхронизируется без действий с БД.
10. Event входит в JSON export и проходит round-trip test.
11. Есть unit test успешного пути, validation failure и replay.
12. Sensitive content отсутствует в Android/server logs и crash breadcrumbs.

## Milestone 0 — Day 0 и Android-first baseline

### Выполнено

- Зафиксирован Health Connect route.
- Собран read-only диагностический APK.
- Проверены SDK types, permissions, pagination и privacy-safe capability report.
- Зафиксирован минимальный HTTPS-контур будущей синхронизации.
- Принято решение сделать Android app единственным интерфейсом MVP.

### Device test владельца

28 июля 2026 года получены core 48-hour и extended 30-day reports с реального
OnePlus Open/OxygenOS 16. Отдельный core 30-day report остаётся неблокирующей
проверкой M4 backfill.

### Gate M0

- Health Connect доступен на фактическом устройстве — подтверждено.
- Сон и ordinary HR имеют OHealth records — подтверждено.
- Sleep stages не наблюдались; пустой список stages поддерживается контрактом.
- RHR availability подтверждена для optional/P1.
- Respiratory rate, steps и total calories подтверждены только как post-MVP;
  остальные optional types классифицированы `not_observed`.
- Production permission list основан на результате, а не на предположении.

Gate M0 пройден с verdict `GO_WITH_REDUCED_SLEEP_DETAIL`. M4 разблокирован для
sleep sessions и ordinary HR, но production importer ещё не реализован.

Если сон/HR не экспортируются, ручной MVP продолжается, а автоматический импорт
получает отдельный fallback spike. Google/cloud fallback не включается молча.

## Milestone 1 — Android foundation и local notes vertical slice

### Bootstrap

- Инициализировать Git repository с branch `main` и подключить GitHub remote.
- Перенести/создать product app рядом с изолированным Day 0 probe.
- Зафиксировать Gradle wrapper, Java/Kotlin/AGP versions и reproducible build.
- Добавить `.gitignore` для:
  - local properties и IDE state;
  - signing keys/passphrases;
  - `.env`, access/refresh token artifacts и enrollment codes;
  - Room databases;
  - exports/backups и реальные health fixtures;
  - generated APK/build directories.

### Application shell

- App icon, splash и single-activity Compose shell.
- Ровно три раздела нижней или компактной top-level navigation:
  - `Добавить`;
  - `Справочники`;
  - `Настройки`.
- `Синхронизация`, enrollment и `Sync now` находятся внутри `Настройки`, а не
  образуют четвёртый top-level раздел.
- Главный экран с четырьмя явными действиями:
  - питание;
  - самочувствие;
  - приём;
  - заметка.
- Единый timestamp picker: `Сейчас` и конкретные дата/время.
- Общие loading, empty, validation, offline, retry и destructive states.
- Системный back, cancel draft и защита от повторной отправки.
- Первый запуск сразу допускает локальную работу: настройка сервера имеет
  `Пропустить` и остаётся доступной как `Настроить позже` в
  `Настройки → Синхронизация`.

### Design system

- Material 3 color/typography/shape tokens.
- Небольшой набор переиспользуемых components:
  - primary/secondary/destructive action;
  - search/list item;
  - amount/unit field;
  - option chips;
  - timestamp field;
  - confirmation card;
  - sync/status banner.
- Light/dark theme.
- Touch targets, contrast, TalkBack labels и large-font smoke test.
- Ошибка объясняет, что осталось несохранённым и какое действие доступно.

### Room foundation

- Database, DAOs, schema export и migration test harness.
- Event envelope, revision и tombstone entities.
- Transactional outbox.
- Room и outbox зашифрованы; DEK обёрнут ключом Android Keystore.
- Room, outbox, keys, credentials и exports исключены из Auto Backup и
  device-to-device transfer.
- Events, все revisions и tombstones сохраняются локально весь MVP и не
  удаляются после server ACK.
- Catalog base entities.
- Repository/use-case boundary между UI и Room.
- `SavedStateHandle` только для UI draft; подтверждённые данные не зависят от
  process memory.

### Notes vertical slice

- Только текст и `Сейчас`/chosen timestamp; отдельного заголовка у заметки нет.
- Atomic Room event + outbox.
- Confirmation, исправление и undo последнего действия.
- Draft не превращается в событие после cancel.
- Notes JSON export проходит локальный schema-validation и round-trip.

### CI

Pull request workflow выполняет:

- Gradle dependency/setup verification;
- formatting/static analysis;
- Android lint;
- unit и Room migration tests;
- debug APK assemble;
- JSON Schema/fixture checks с Draft 2020-12 format assertions для
  `uuid`/`date`/`date-time`, а не только format annotations;
- отсутствие известных secret file patterns.

Workflow использует минимальные permissions, не получает production secrets и
не запускает untrusted PR code на production runner.

### Gate M1

- Clean checkout собирается документированной Docker/local командой.
- CI зелёный без production secrets.
- Navigation и все design-system states доступны из fixture/demo mode.
- Полный notes flow работает в airplane mode.
- Fixture note и её outbox operation переживают process kill и database reopen.
- Double tap/retry не создаёт вторую заметку.
- Room migration сохраняет event/revision/outbox invariants.
- Encryption at rest проверено instrumented-тестом: plaintext fixture не
  обнаруживается в database/WAL/SHM после закрытия приложения.
- Backup/data-extraction rules instrumentally подтверждают, что Room, outbox,
  keys, credentials и exports не попадают в Auto Backup и device transfer.
- App usable offline и при увеличенном системном шрифте.

## Milestone 2 — secure HTTPS notes sync и baseline backup

### Server foundation

- Отдельные production directory, Compose project и Docker network.
- Уже работающий nginx bootstrap и отдельный TLS certificate не переустанавливаются
  без причины; M2 сохраняет точный content-free `/healthz`.
- M2 добавляет только необходимые authenticated HTTPS API paths под `/api/v1/`;
  до их deploy текущий `404` является ожидаемым.
- API/PostgreSQL bind/network rules исключают прямой публичный доступ к БД.
- Контейнеры запускаются от non-root user с memory limits и healthchecks.
- Secrets читаются из server secret files, не из image/Git.
- JSON logs не содержат payload, notes, nutrients, doses, health samples,
  authorization header или raw tokens.

### Enrollment

Для одного владельца не нужен внешний identity provider:

1. Локальный app и capture доступны без сервера; на первом запуске можно нажать
   `Пропустить`.
2. Когда владелец выбирает `Настройки → Синхронизация → Настроить`, backend
   генерирует одноразовый enrollment code с коротким сроком.
3. Владелец вводит/сканирует code в установленном APK.
4. Сервер выдаёт device ID, short-lived access token и случайный rotating
   refresh token.
5. На Android refresh token хранится в ciphertext, защищённом Keystore-backed
   ключом; access token остаётся только в памяти.
6. На сервере хранятся только token hashes, rotation/reuse state и metadata
   устройства.
7. Revoke/rotate выполняются без удаления уже сохранённых событий.

Enrollment code и credentials не передаются в Git или публичные issue. Утечка
Android credential не даёт SSH, Docker или database access. Отсутствующий или
отложенный enrollment не блокирует локальные формы и не ставит их в error state.

### Sync contract

Каждый batch содержит:

- protocol/schema version;
- device ID;
- batch ID;
- упорядоченный набор operations;
- stable operation/event ID;
- revision и entity type;
- payload либо tombstone;
- client effective/recorded timestamps.

Сервер:

- проверяет auth, content type, size и schema;
- применяет operation один раз по idempotency key;
- отклоняет revision conflict явно;
- возвращает per-operation ack/error;
- продвигает cursor только после commit;
- предоставляет authenticated paginated bootstrap для нового устройства;
- предоставляет incremental pull по opaque server cursor;
- не угадывает и не исправляет health values.

Android:

- WorkManager запускается только при доступной сети;
- отправляет bounded batches;
- использует exponential backoff с верхней границей;
- удаляет outbox item только после ack;
- сохраняет permanent validation failure для понятного ручного восстановления;
- атомарно и идемпотентно применяет каждую bootstrap/pull page в Room;
- сохраняет новый cursor только в той же Room transaction после успешного
  применения всей страницы;
- показывает pending count, last success и кнопку `Sync now`.

### CI/CD

Backend CI:

- formatting, lint, types и tests;
- migration и JSON Schema checks с теми же включёнными format assertions, что
  использует API;
- Docker build и smoke test;
- image publication в GHCR по immutable commit tag/digest.

Production deploy:

- использует GitHub Environment/manual approval;
- передаёт серверу только разрешённый image digest;
- не устанавливает self-hosted runner с Docker access;
- выполняет pre-deploy DB dump, migration и health-gated Compose update;
- сохраняет current/previous digest;
- откатывает image при failed readiness, но не откатывает БД destructive restore.

Android release:

- сначала распространяется owner-only signed APK;
- release signing key и passphrase не хранятся в репозитории;
- versionCode монотонен;
- checksum артефакта фиксируется рядом с release metadata;
- production base URL задаётся release configuration, cleartext traffic
  запрещён.

### Baseline notes backup

- Только после первой синхронизированной fixture-заметки создаётся encrypted
  PostgreSQL backup.
- Backup восстанавливается в отдельную чистую БД.
- Сверяются note event, revision, tombstone и server acknowledgement.
- Backup target находится вне единственного VPS; secrets в dump не входят.

### Gate M2

- До включения M2 API и первой fixture/реальной загрузки сервер работает на
  поддерживаемой ОС с security updates, а firewall policy проверена снаружи.
- TLS hostname/certificate validation проходит на телефоне.
- Offline notes backlog синхронизируется после восстановления сети.
- 100 повторов batch создают одну server note/revision.
- Kill app/API/PostgreSQL в контрольных точках не теряет confirmed note.
- Invalid/revoked credential не записывает данные.
- Credential rotation проверен.
- После явной замены устройства новая установка проходит enrollment и
  paginated bootstrap без дублей.
- Старый pending outbox не восстанавливается через Android backup/device
  transfer и не выдаётся серверным bootstrap.
- Note content отсутствует в nginx, API и CI logs.
- PostgreSQL, Docker socket и admin endpoints не доступны из интернета.
- Failed deploy возвращает предыдущий работоспособный image.
- Encrypted baseline backup с заметкой восстановлен в чистую БД.

## Milestone 3 — ручные health-домены

Notes из M1 уже доказали полный local-first loop, а M2 — transport, enrollment,
server idempotency и baseline restore. Nutrition, wellbeing и
medication/supplements расширяют те же contracts без отдельного transport path.

### Nutrition

#### Catalog

- `product`, aliases и источник значений.
- Basis: 100 г, 100 мл или одна штука/порция.
- Energy, protein, fat и carbohydrates с nullable значениями.
- Portion presets, package size, favorite и recent.
- `recipe`, immutable `recipe_version`, ingredients и yield.
- Meal preset для повторяющегося набора.
- JSON/CSV seed/import для начального наполнения.

#### Capture

1. Выбрать продукт, блюдо или preset.
2. Выбрать количество и совместимую unit.
3. При необходимости добавить другие позиции в одну meal group.
4. Выбрать effective time.
5. Добавить необязательный comment.
6. Сохранить snapshot КБЖУ и source catalog version.

Decimal values хранятся без binary float. `null` nutrient не участвует в сумме
как zero и явно остаётся неизвестным.

### Wellbeing

- CRUD dimensions и ordered options.
- Начальный набор задаёт владелец; значения не навязывают медицинскую шкалу.
- Событие может содержать любое подмножество dimensions.
- Пропущенная dimension не наследуется от предыдущего события.
- Effective timestamp и необязательный comment.
- Исторический snapshot label/version.

### Medication and supplements

- Master list: name, aliases, medication/supplement, form.
- Dose value + compatible unit и optional concentration.
- Dose presets, favorite и recent.
- Actual intake с effective timestamp, comment и snapshot.
- UI не рекомендует дозу и не оценивает безопасность назначения.

Schedule и medication reminders не входят в M3/MVP. Они проектируются после MVP
как отдельные planned-сущности и никогда не подменяют actual intake.

### Correction и local ownership

- Confirmation последнего результата содержит `Исправить` и `Отменить`.
- Correction создаёт новую revision с тем же logical event ID.
- Undo идемпотентно создаёт tombstone.
- Catalog archive не удаляет исторические snapshots.
- Полный JSON export уже на этом этапе покрывает локальные записи.

### Gate M3

- Все три health-домена работают в airplane mode.
- 100-кратный replay UI action создаёт один logical event.
- Process kill после commit не теряет event/outbox.
- Старое food/dose событие не меняется после редактирования карточки.
- Unknown и zero различаются в storage, calculations и export.
- Частый food/dose flow требует только несколько содержательных действий.
- Correction/undo не требуют timeline, Telegram или SQL.
- После восстановления сети каждый домен синхронизируется через M2 protocol.
- Fixture export проходит schema validation и round-trip.

## Milestone 4 — production Health Connect feature

Milestone начинается после пройденного Gate M0 для P0-типов: sleep sessions и
ordinary heart rate. Resting HR availability подтверждена, но import остаётся
optional/P1 задачей с отдельным permission flow. Результат M0 не означает, что
production import уже реализован.

### Реализация

- Перенести проверенный scanner/query code из Day 0 probe.
- Запрашивать read permissions для подтверждённых sleep и ordinary HR; resting
  HR — отдельно в optional/P1 flow.
- На каждом открытии приложения автоматически запускать foreground incremental
  import; локальный capture и открытие UI не ждут его завершения.
- Показывать:
  - Health Connect availability;
  - permission status;
  - last successful import;
  - импортируемые типы;
  - `Sync now`;
  - degraded/error state с конкретным восстановимым действием.
- Читать все pages выбранного диапазона.
- Фильтровать/маркировать origin, не приписывая системные records OHealth.
- Сохранять stable source key, record version/time range и provenance.
- Идемпотентно импортировать create/update/delete.
- Не выводить сырые HR samples или sleep details в operational logs.

### Foreground и background

Автоматический foreground import при открытии приложения является штатным P0
путём, а `Sync now` — обязательным рабочим fallback. Change token используется
для incremental import; истечение/инвалидация token обрабатывается bounded
reconciliation scan с идемпотентной дедупликацией. Background read и historical
read не требуются для P0 и добавляются только условно: после стабильного
foreground path, при наличии runtime capability, доказанной необходимости и
отдельно проверенном permission/revocation flow.

Respiration, steps и total calories наблюдались, но вместе с HRV, SpO₂,
exercise, distance, active calories, cadence, speed и любыми другими Health
Connect types остаются только post-MVP discovery. Положительный Day 0 report не
добавляет их permissions в MVP; resting HR является единственным подтверждённым
optional/P1 исключением текущего M4.

Health Connect write и exercise route permissions не входят в MVP.

### Gate M4

- Сон/ordinary HR за один и тот же период сверены с Health Connect/OHealth.
- App-open автоматически запускает foreground incremental import, а `Sync now`
  восстанавливает тот же путь вручную.
- Повторный полный scan и incremental scan не создают дубли.
- Позднее изменение sleep session обновляет запись, не создавая вторую активную.
- Импортированные records синхронизируются через M2 protocol.
- Permission revoke/regrant протестирован.
- Manual domains продолжают работать при недоступном Health Connect.
- Приложение не имеет write permissions.

## Milestone 5 — reliability, export, delete и backup

### Export

- Версионированный canonical JSON:
  - catalogs и immutable versions;
  - active events и старые revisions;
  - nutrient/dose/wellbeing snapshots;
  - Health Connect provenance;
  - tombstones;
  - schema/app/server versions.
- Удобные CSV views по доменам как производный формат.
- Android создаёт export только по явному действию через system document
  picker/share sheet.
- Временный server export имеет короткую retention и не попадает в обычный
  backup как отдельная копия.

### Backup и restore

- Автоматический encrypted `pg_dump`.
- Backup target находится вне единственного VPS.
- Retention и key ownership документированы.
- Restore выполняется в отдельную чистую БД.
- Counts и checksums canonical records сверяются.
- Tombstones повторно применяются и не дают старой копии воскресить удалённые
  события.
- Room export можно импортировать в clean fixture app для device recovery.

### Delete/privacy

- В приложении описано:
  - что хранится на телефоне и сервере;
  - что Health Connect остаётся отдельным источником;
  - как работает sync, export и backup retention;
  - что удаление Life Agent event не удаляет исходную OHealth запись.
- Delete period и delete all имеют preview и повторное подтверждение.
- Delete создаёт tombstone и синхронизируется тем же надёжным протоколом.
- Disconnect/revoke прекращает будущий upload без удаления серверной истории.

### Observability и recovery

- Content-free метрики: crash/start, pending outbox age, sync failures,
  import cursor age, DB readiness, disk и backup age.
- Android error state различает:
  - локальную validation error;
  - нет сети;
  - auth revoked;
  - server retryable;
  - permanent schema conflict;
  - Health Connect permission revoked.
- Runbooks для server restore, device re-enrollment, credential revoke,
  migration failure и full disk.
- Dependency/security update process для Android и server.

### Gate M5

- JSON export валиден и проходит clean round-trip.
- Encrypted backup восстановлен в чистую БД.
- Active/revision/tombstone counts и checksums совпадают.
- Восстановление старой копии не возвращает удалённые события.
- Device re-enrollment не создаёт active duplicates.
- Критическая sync/import/backup ошибка видна без чтения payload или SQL.
- Проверено отсутствие секретов и health content в логах и crash reports.

## Milestone 6 — личный dogfood и product release

Dogfood не имеет искусственного календарного дедлайна. Он продолжается, пока
набор обычных дней покрывает разные capture paths, offline periods, sync,
Health Connect updates, corrections и хотя бы один recovery drill.

### Измеряемые сигналы

| Сигнал | Release gate |
|---|---:|
| Потерянное подтверждённое действие | 0 |
| Необъяснимый active duplicate | 0 |
| Event без effective time/source/provenance | 0 |
| Silent permanent sync failure | 0 |
| Частый food/dose capture | несколько содержательных действий |
| Unknown, ошибочно превращённый в zero | 0 |
| Crash в основном capture path | 0 нерешённых воспроизводимых |
| Outbox после возвращения сети | полностью доставляется |
| Export → clean restore | pass |
| Backup → clean restore | pass |

### Dogfood log

Для каждого повторяющегося затруднения фиксируются:

1. какой сценарий был открыт;
2. сколько действий потребовалось;
3. где ожидание UI не совпало с поведением;
4. пришлось ли исправлять event;
5. был ли pending/error state понятен;
6. можно ли устранить проблему без расширения scope.

Приоритет имеют потеря, дубль, неверные units/timestamps/snapshots, sync
uncertainty и повторяющаяся capture friction. Графики, AI и новые integrations
не вытесняют эти проблемы.

### Final release gate

- Все основные сценарии выполняются из APK без Telegram, SQL и SSH.
- Confirmed local action не теряется при process kill или отсутствии сети.
- Серверная копия воспроизводима из backup.
- Nutrition, wellbeing, medication/supplement и notes schemas стабильны.
- Health Connect работает для подтверждённых типов либо честно показывает
  degraded status.
- Permissions минимальны и могут быть отозваны без поломки ручного capture.
- Export/delete/privacy flows проверены владельцем.
- Нет нерешённых P0/P1 дефектов ежедневного использования.

## Приоритизированный backlog

### P0 — обязательный Android-first MVP

| ID | Milestone | Задача | Готово, когда |
|---|---|---|---|
| AND-010 | M0 | Day 0 device gate — done 2026-07-28 | Verdict `GO_WITH_REDUCED_SLEEP_DETAIL` зафиксирован |
| AND-001 | M1 | Product app skeleton | Compose shell, navigation, theme и release configuration собираются |
| AND-002 | M1 | Design system | Все capture screens используют единые accessible components/states |
| AND-003 | M1 | Room event model | Event, revision, tombstone и migration invariants покрыты тестами |
| AND-004 | M1 | Transactional outbox | Event и pending operation создаются атомарно |
| AND-005 | M1 | Notes vertical slice | Offline save/correct/undo/export работает end-to-end |
| OPS-001 | M1/M2 | CI/CD | Android/server checks и health-gated digest deploy работают |
| AND-012 | M2 | Enrollment/Keystore | Short-lived access + rotating refresh chain выдаётся, хранится, ротируется и отзывается безопасно |
| SRV-001 | M2 | HTTPS API | Versioned authenticated sync доступен только через TLS hostname |
| SRV-002 | M2 | Idempotent server ingestion | Replay/retry не создаёт дубли и даёт per-operation ack |
| SRV-003 | M2 | PostgreSQL schema | Notes, revisions и tombstones воспроизводимы |
| OPS-003 | M2/M5 | Backup/restore | Baseline notes restore и затем полный off-host restore проходят |
| AND-006 | M3 | Nutrition catalog | Products, aliases, portions, recipes и immutable versions готовы |
| AND-007 | M3 | Nutrition capture | Meal/group и immutable nutrient snapshot работают |
| AND-008 | M3 | Wellbeing capture | Configurable dimensions/options, timestamp и comment работают |
| AND-009 | M3 | Medication catalog/capture | Dose/unit validation и actual intake snapshot работают |
| AND-011 | M4 | Health Connect P0 import | Sleep и ordinary HR автоматически читаются foreground при app-open; `Sync now` работает как fallback |
| OPS-002 | M5 | Export | Versioned JSON/CSV и Android ownership flow проверены |
| OPS-004 | M5 | Privacy/delete | Delete semantics, token revoke и log redaction проверены |
| QA-001 | M6 | Dogfood release gate | Нет loss/duplicate и пройден full recovery drill |

### P1 — улучшения после устойчивого P0

| ID | Задача | Условие |
|---|---|---|
| AND-101 | Repeat previous/favorite meal | Основной nutrition flow уже корректен |
| AND-102 | Faster catalog seeding | Начальное наполнение остаётся измеримым bottleneck |
| AND-103 | Background Health Connect sync | Foreground import стабилен и runtime capability подтверждена |
| AND-104 | Domain CSV import UI | JSON ownership уже работает |
| AND-107 | Optional resting HR | Availability подтверждена; отдельный permission/import flow реализован и проверен |

### После MVP и отдельного решения

| ID | Возможность | Предварительный gate |
|---|---|---|
| NEXT-201 | Голос и транскрибация | Стабильные manual schemas + privacy/model benchmark |
| NEXT-202 | LLM parsing | Fixture evaluation, evidence mapping и confirmation UX |
| NEXT-203 | Timeline/dashboard | Сформулирована конкретная задача просмотра |
| NEXT-204 | Deterministic summaries | Накоплен качественный dataset |
| NEXT-205 | RAG/semantic retrieval | Отдельный evaluation corpus и privacy review |
| NEXT-206 | Advice/coaching | RAG gate, safety policy и explicit opt-in |
| NEXT-207 | Фото/OCR/barcode | Локальный catalog loop стабилен, bottleneck измерен |
| NEXT-208 | Vendor/cloud health fallback | Health Connect route доказанно недостаточен и принята cloud-копия |
| NEXT-209 | Medication schedules/reminders | Отдельная planned-модель, явный opt-in и доказанная польза |
| NEXT-210 | Additional Health Connect records кроме resting HR | Отдельное post-MVP discovery и permission решение для каждого type |
| NEXT-211 | Manual workout domain | Core capture/reliability gates закрыты и принят отдельный post-MVP scope |
| NEXT-212 | Xiaomi S400 (`sg`, `yunmai.scales.ms104`) | Read-only cloud/BLE gate, owner attribution и явное принятие private API risk по [planning record](13-xiaomi-s400-integration.md) |

## Явные non-goals MVP

- Telegram-бот, Telegram Mini App и BotFather integration.
- Голосовой ввод, ASR и agentic voice flow.
- LLM parsing свободного текста.
- RAG, embeddings, советы и autonomous coaching.
- Диагностика, лечение, emergency monitoring и предложение дозировок.
- Timeline, графики и аналитический dashboard.
- Multi-user, публичная регистрация, sharing и SaaS.
- Health Connect write, exercise routes и realtime sensor stream.
- Medication schedules/reminders и автоматическое создание intake.
- Health Connect types кроме sleep, ordinary HR и подтверждённого optional RHR.
- Обязательный внешний food catalog.
- Фото-calorie estimation, OCR и barcode automation.
- Микросервисы, Kubernetes и vector database.

## Что нужно от владельца

Сейчас внешние токены не нужны. BotFather token и Telegram owner ID больше не
требуются.

По ходу milestone владелец предоставляет:

1. Перед финальной настройкой M4 backfill — отдельный core 30-day report.
2. Первые частые продукты, блюда, рецепты и порции.
3. Стартовые wellbeing dimensions/options.
4. Список лекарств/БАДов с form, dose и unit.
5. По желанию настроить enrollment production APK после готовности M2 API;
   локальный capture доступен и до этого.
6. Решение о хранении Android release signing key; key/passphrase не
   пересылаются в чат и не коммитятся.
7. Проверку обычных capture flows на реальном OnePlus Open.

Enrollment credential генерируется нашей системой. Google/OHealth OAuth не
нужен, пока отдельный fallback gate не докажет обратное.

## Актуальные первичные источники

- [Health Connect overview](https://developer.android.com/health-and-fitness/health-connect)
- [Health Connect data types](https://developer.android.com/health-and-fitness/health-connect/data-types)
- [Health Connect read data](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Health Connect synchronization](https://developer.android.com/health-and-fitness/health-connect/sync-data)
- [Android app architecture](https://developer.android.com/topic/architecture)
- [Room](https://developer.android.com/training/data-storage/room)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Jetpack Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)
- [GitHub secure use of Actions](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub deployment environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [Docker image digests](https://docs.docker.com/dhi/explore/security-concepts/digests/)
