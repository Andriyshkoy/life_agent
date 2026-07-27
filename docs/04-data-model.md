# Модель данных

## Зачем она устроена именно так

Дневник должен отвечать не только на вопрос «что сейчас считается правдой», но и
на вопросы:

- что именно прислал пользователь или источник;
- что пользователь исправил;
- из какого устройства пришло измерение;
- какой версией продукта или рецепта рассчитаны КБЖУ;
- можно ли безопасно сделать export и восстановить историю.

Поэтому capture, canonical revision и перестраиваемая проекция — разные
сущности. Полный health-профиль не складывается в одно изменяемое JSON-поле.

## Четыре уровня

| Уровень | Пример | Можно менять |
|---|---|---|
| Capture | Android form command, Health Connect record | нет; можно только удалить по purge policy |
| Artifact (после MVP) | media/import и результат их обработки | нет; новая обработка создаёт новую версию |
| Canonical event | приём пищи, самочувствие, лекарство, заметка, сон, пульс | исправление создаёт revision |
| Projection | bootstrap/pull, export; после MVP — агрегаты и RAG | полностью перестраивается |

`raw` здесь означает точное представление полезного входа, а не обязательное
вечное хранение всего сетевого пакета. Например, HTTP envelope минимизируется до
нужных полей. Media retention появится только вместе с отдельной post-MVP
функцией импорта или голоса.

## Две durable-копии, одна identity

Android-приложение должно работать без сети, поэтому Room и PostgreSQL используют
одинаковые client-generated IDs и versioned payload contracts:

- Room — canonical local store и единственный источник новой записи до первого
  серверного ACK;
- PostgreSQL — canonical history всех принятых revisions, backup source и база
  будущих агрегатов/RAG;
- после ACK Room весь текущий MVP сохраняет полную offline history
  revisions со статусами `active|retracted`, но серверные
  `server_sequence` и `life_event.current_revision_id` определяют synced state;
- синхронизация передаёт immutable operations, а не «текущее состояние всей
  таблицы».

ACK означает, что конкретная operation вместе с canonical revision (`active`
либо `retracted`), receipt и `server_sequence` закоммичена в PostgreSQL.
Сохранение только raw metadata или background job не является ACK и не
продвигает server cursor.

Нельзя создавать новый event ID при retry. `capture_id`, `event_id`,
`revision_id` и `operation_id` генерируются один раз на телефоне и сохраняются
вместе в одной Room-транзакции.

Идентификаторы имеют разные роли: `capture_id` — provenance envelope,
`operation_id` — идемпотентная команда доставки, `event_id` — logical event,
`revision_id` — immutable версия. `capture_id` сохраняется end-to-end в local
capture/revision/outbox, transport operation, raw ingest, receipt и canonical
provenance. Ни один из IDs не заменяется другим при retry.

Security principal `subject_id` соответствует внутреннему PostgreSQL
`person_id` отношением 1:1. Capture schema не принимает ни `subject_id`, ни
`person_id`: backend получает `person_id` только из аутентифицированной
device-session. До enrollment первый запуск создаёт локальные opaque
`installation_id` и `local_owner_id`; все локальные rows принадлежат им и могут
создаваться без сети. `server_device_id` и `server_person_id` в Room nullable:
они появляются как server-issued enrichment после enrollment/sync, а не как
выбор пользователя или условие локального capture.

## Основные сущности PostgreSQL

### Приём и обработка

| Таблица | Назначение |
|---|---|
| `device` | зарегистрированный Android device, статус и время revoke |
| `device_session` | отзываемая `session_family_id`, статус и revoke metadata |
| `device_session_token` | hashes opaque access/refresh tokens, kind, generation, expiry, spent/reuse state |
| `sync_operation_receipt` | canonical-committed `operation_id`, result, content hash и server sequence |
| `raw_ingest` | `capture_id`, transport operation, hash, время приёма и минимальный raw payload |
| `sync_batch` | transport batch identity и hash всего неизменяемого batch body |
| `sync_batch_operation` | упорядоченное membership нескольких operations в одном batch |
| `outbox_job` | export/backup/purge работа после canonical commit; на ACK не влияет |
| `purge_ledger` / `purge_watermark` | durable generation ledger удаления и его применение ко всем storage/restore targets |

`blob`, `artifact`, `processing_run` и `candidate_fact` зарезервированы для
отдельного post-MVP media/import pipeline и не являются текущими компонентами.

Имена ключевых transport/job columns, на которые ниже ссылаются constraints и
индексы:

```text
device_session
  device_session_id, session_family_id, device_id
  status, created_at, revoked_at, revoke_reason

device_session_token
  token_id, session_family_id, token_kind
  token_hash, generation, expires_at
  spent_at, replaced_by_token_id, reuse_detected_at, revoked_at

sync_batch
  device_id, batch_id, content_sha256, received_at

sync_batch_operation
  device_id, batch_id, operation_id, ordinal, operation_content_sha256

sync_operation_receipt
  person_id, device_id, operation_id, batch_id
  capture_id, event_id, revision_id, content_sha256
  result_code (applied / conflict)
  server_sequence, committed_at

raw_ingest
  raw_ingest_id, capture_id, person_id, device_id
  operation_id, batch_id, idempotency_key (= batch_id)
  content_sha256, received_at, payload_inline

source_record_version
  source_record_version_id, connector_account_id, stream_key
  external_id, external_version, operation, payload_hash

outbox_job
  outbox_job_id, job_type, status, available_at, locked_until
  attempt_count, payload

purge_ledger
  purge_generation, purge_id, scope_encrypted, scope_digest
  requested_at, completed_at

purge_watermark
  target_kind, target_id, applied_generation, applied_at

backup_manifest
  backup_id, snapshot_id, purge_generation, manifest_sha256, created_at
```

Opaque access и refresh credentials никогда не хранятся server-side в plaintext.
Rotation атомарно помечает refresh-token row как `spent`, создаёт successor
следующей generation в той же family и заменяет access token. Попытка повторно
использовать spent refresh hash фиксирует `reuse_detected_at` и отзывает всю
family; проверка access token учитывает expiry и family revocation, поэтому
отзыв немедленно закрывает все её tokens. Spent refresh hashes сохраняются как
минимум до family/reuse-window expiry, иначе повторное предъявление нельзя
обнаружить.

### Канонические факты

| Таблица | Назначение |
|---|---|
| `life_event` | стабильный `event_id`, владелец, тип и authoritative `current_revision_id` |
| `event_revision` | неизменяемая версия содержания события |
| `observation_value` | типизированное числовое/категориальное измерение |
| `assertion_evidence` | provenance конкретного поля до исходного fragment/payload path |
| `revision_parent` | одна parent revision для correction, две branches для conflict resolution |
| `event_relation` | `part_of`, `derived_from`, `same_as`; другие relations только с будущим доменом |
| `concept` | локальный словарь еды, самочувствия, лекарств и показателей |
| `concept_mapping` | соответствие локального понятия к коду внешнего источника |

### Интеграции и проекции

| Таблица | Назначение |
|---|---|
| `connector_account` | провайдер, scopes, статус и ссылка на зашифрованные credentials |
| `source_record_version` | external record ID/version, upsert/delete и исходный provenance |
| `sync_stream` | cursor/change token/anchor отдельно для каждого data type |
| `sync_run` | окно синхронизации, числа полученных/изменённых/удалённых записей, ошибки |
| `projection_checkpoint` | версия и позиция перестроения read model |
| `rag_document` / `embedding` | будущая удаляемая и перестраиваемая семантическая проекция |

Для MVP часть узких таблиц допустимо реализовать одним versioned JSONB payload,
но границы уровней и идентификаторы нельзя смешивать.

## Локальная Room-модель

Room не обязана буквально повторять все аналитические PostgreSQL-таблицы. Ей
нужен минимальный набор для быстрого UI и безотказной репликации:

| Entity | Назначение |
|---|---|
| `local_installation` | opaque `installation_id`, созданный до enrollment, и nullable server device enrichment |
| `local_owner` | opaque `local_owner_id` и nullable server person enrichment |
| `local_capture` | неизменяемый `capture_id`, owner/installation, source и payload hash |
| `local_life_event` | identity, `local_owner_id`, current local/server revision pointer, sync status |
| `local_event_revision` | immutable payload, `capture_id`, base revision и content hash |
| `local_observation_value` | typed значения для capture confirmation и offline working set |
| `local_source_record_version` | Health Connect UPSERT/DELETE source version и provenance |
| `local_food`, `local_recipe_version`, `local_portion_preset` | offline справочник и быстрый ввод питания |
| `sync_outbox` | immutable encrypted operation до per-operation ACK |
| `local_sync_batch` | durable `batch_id`, точные canonical body bytes и body hash |
| `local_sync_batch_operation` | неизменяемые ordered membership и operation content hashes |
| `sync_state` | server pull cursor, Health Connect token по stream, last success/error |
| `sync_conflict` | canonical-committed non-current branch, server current revision и resolution status |

`local_installation.server_device_id` и `local_owner.server_person_id` nullable;
локальные domain/capture rows ссылаются на `installation_id/local_owner_id`, а не
на ещё не выданные серверные ID. Enrollment заполняет enrichment и не переписывает
уже созданные capture/event/revision/operation IDs.

Рекомендуемые поля `sync_outbox`:

```text
operation_id, capture_id, installation_id, local_owner_id
operation_kind, entity_id, revision_id, base_revision_id
schema_version, payload_ciphertext, content_sha256
created_at, attempt_count, next_attempt_at
state: pending | sending | acked | conflict | permanent_failure
server_sequence, acked_at, last_error_code
```

Sensitive outbox payload шифруется data key, защищённым Android Keystore.
Перед первой отправкой WorkManager атомарно сохраняет `local_sync_batch` и
`local_sync_batch_operation`: `batch_id`, exact canonical body bytes,
`content_sha256`, ordered membership, `operation_id` и hash каждой operation.
Сохранённые batch bytes шифруются тем же локальным data key.
HTTP `Idempotency-Key` в точности равен `batch_id`. Byte-identical retry читает
сохранённое тело; после частичного ACK новый batch может быть создан только из
непринятых rows. Identity и receipt существуют на уровне
`operation_id + content_sha256`, поэтому новый batch не превращает уже принятую
operation в новую.

Health Connect page, её source UPSERT/DELETE versions, соответствующие
active/retracted revisions, outbox operations и новый changes token записываются
одной Room-транзакцией. Поэтому токен можно продвинуть после локального commit,
не дожидаясь сети.

## Каноническое событие

`life_event` задаёт identity, а содержание живёт в `event_revision`:

```text
life_event:
  event_id, person_id, event_kind, current_revision_id
  event_kind: meal | sleep | wellbeing | medication_intake |
              supplement_intake | measurement | note

event_revision:
  revision_id, event_id, person_id, capture_id, revision_no, schema_version
  assertion_status: observed | uncertain
  lifecycle: NULL
  record_status: active | retracted
  verification_status: source_recorded | user_confirmed | machine_inferred |
                       needs_review

  effective_start_utc, effective_end_utc
  original_local_start, original_local_end
  timezone_id, start_offset_seconds, end_offset_seconds
  temporal_precision, local_date

  payload, quality_flags
  recorded_at, created_at, actor_type, correction_reason

revision_parent:
  child_revision_id, parent_revision_id
  relation: supersedes | resolves
```

Разделение обязательно:

- `assertion_status` отвечает, наблюдавшийся факт определён или uncertain;
- `lifecycle` для всех текущих MVP event types равен `NULL`;
- `verification_status` отвечает, насколько запись проверена;
- `record_status` исключает revision из текущих итогов, не уничтожая историю.

Текущая версия никогда не вычисляется как revision с максимальным номером или
временем: её единственный authoritative pointer —
`life_event.current_revision_id`. Термин `tombstone revision` означает обычную
immutable `event_revision` с `record_status = retracted`; после подтверждённого
delete current pointer указывает именно на неё, а current projection скрывает
логическое событие. Отдельного tombstone status/table для canonical event нет.

Workouts и plans не входят в текущую схему продукта. Если планы будут отдельно
одобрены после MVP, новая schema version одновременно добавит
`assertion_status = planned | negated`, lifecycle
`scheduled | completed | cancelled` и будущий event type. Выполненный факт
останется отдельным событием, а не изменением planned-факта. MVP constraint
требует `lifecycle IS NULL`.
Correction содержит одну связь `revision_parent(relation = supersedes)`.
Conflict resolution содержит две связи `revision_parent(relation = resolves)` —
по одной на каждую branch.
`revision_no` удобен для отображения, но не уникален внутри branched event:
порядок и ancestry задаются `revision_parent`, а identity — `revision_id`.
Constraints/service validation требуют, чтобы child и все parents принадлежали
одному `event_id`, parents были различны, а resolution имела ровно две branch
links.

## Время

Для каждого события сохраняются:

- `effective_start_utc` и, для interval, `effective_end_utc`;
- `original_local_start` и, для interval, `original_local_end`;
- `start_offset_seconds` и отдельный `end_offset_seconds` для interval;
- IANA timezone, например `Asia/Novosibirsk`;
- точность: `exact`, `minute`, `hour`, `part_of_day`, `date`, `approximate`,
  `unknown`;
- отдельно `source_created_at`, `source_modified_at`, `recorded_at` и
  `ingested_at`.

Для point event все `end_*` поля равны `NULL`. Для interval
`original_local_end`, `effective_end_utc` и `end_offset_seconds` обязательны;
end offset приходит из source отдельно и не копируется из start, поскольку сон
может пересечь DST или смену timezone.

Если пользователь в явной форме выбрал только дату или часть дня, система не
подставляет фиктивные `00:00`: хранит исходную точность и nullable instant.

Для travel и переходов DST одного UTC недостаточно: original local и timezone
нужны для честного дневного отчёта. Формат времени на границах API —
[RFC 3339](https://www.rfc-editor.org/rfc/rfc3339.html), timezone — база
[IANA](https://www.iana.org/time-zones).

## Значения и единицы

У измерения хранятся:

```text
observation_value_id, person_id, event_revision_id, concept_id
raw_value_numeric, raw_unit_text
canonical_value_numeric, ucum_code
unit_display, conversion_version
method, device_id, body_site, aggregation_period
```

Для веса и нутриентов нельзя использовать binary floating point. Подходящий
вариант — PostgreSQL `numeric`, округление только при показе. Канонические единицы
по возможности используют [UCUM](https://ucum.org/ucum), но исходная единица
никогда не теряется.

`unknown`, `not_measured`, `not_applicable` и реальный `0` различаются.

## Локальная модель питания

### Справочник

`canonical_food`:

- стабильный `food_id`;
- название, бренд и описание;
- basis: `per_100g`, `per_100ml` или `per_serving`;
- масса/объём serving, если известны;
- энергия, белок, жир, углеводы и опциональные нутриенты;
- источник значения: этикетка, ручной ввод, рецепт, внешний справочник;
- дата/версия этикетки, статус проверки и заметка;
- `density_g_per_ml`, только если она реально известна.

`food_alias` хранит личные названия и приоритет совпадения: «тот творог»,
«моя овсянка», сокращение бренда. Alias не дублирует КБЖУ.

`portion_preset` задаёт удобную порцию:

- `food_id` или `recipe_version_id`;
- label/aliases;
- quantity и unit;
- пересчёт в граммы/миллилитры или долю serving;
- источник и дата проверки.

### Рецепт

Рецепт имеет стабильный `recipe_id` и неизменяемые `recipe_version`:

- список ингредиентов с количеством и выбранной версией продукта;
- масса сырой смеси при наличии;
- масса готового блюда (`yield_g`) после приготовления;
- число порций и/или обычный вес порции;
- retention factor для микронутриентов только если позже появится обоснованный
  справочник; в MVP его не угадывать.

Если выход готового блюда известен:

```text
nutrient_per_100g =
    sum(nutrient_in_each_ingredient) / cooked_yield_g × 100
```

Вода, потерянная при готовке, меняет массу и концентрацию, но не создаёт и не
удаляет макронутриенты сама по себе. Масло, соус и жидкость, оставшаяся в
кастрюле, должны быть отражены фактическими ингредиентами или пометкой
неопределённости.

### Факт еды

`consumption_event` ссылается на продукт или точную `recipe_version`, хранит
фактическое количество и `nutrient_snapshot`:

```json
{
  "basis_quantity": "320",
  "basis_unit": "g",
  "energy_kcal": "518.4",
  "protein_g": "31.7",
  "fat_g": "16.2",
  "carbohydrate_g": "59.1",
  "calculation_version": "nutrition-v1",
  "input_refs": ["recipe_version:..."]
}
```

Snapshot нужен, чтобы изменение рецепта завтра не переписало прошлый обед.
Исправление количества создаёт revision факта и новый snapshot.

Шаблоны начального справочника находятся в
[templates](../templates/README.md).

Явный JSON/CSV seed в M3 относится только к versioned справочнику питания. Он не
является generic file import, не проходит через media/artifact pipeline и сам по
себе не создаёт `meal` event.

## Provenance и уверенность

Одного поля `source = android` мало. Для каждого значимого утверждения нужны:

- origin: пользователь, часы, приложение или лаборатория;
- collector: Life Agent Android и Health Connect; source app сохраняется
  отдельно;
- device/app и их версии;
- locator: form field, JSON Pointer или source-record field;
- validation state, time confidence и unit confidence — отдельно;
- transform/model/config version;
- был ли факт подтверждён человеком.

Post-MVP media/import pipeline сможет добавить transcript/audio/CSV/PDF locators
и отдельные ASR/extractor confidence без изменения canonical identity.

## Исправления и удаление

- Исправление всегда добавляет `event_revision`.
- Текущим является revision, на который указывает
  `life_event.current_revision_id`, а не «последний активный»; history
  экспортируется.
- Удаление записи источником создаёт revision с
  `record_status = retracted` (tombstone) и при допустимом переходе передвигает
  current pointer на неё; ручное исправление пользователя нельзя молча удалить
  при следующем sync.
- User purge физически удаляет связанные revisions, evidence, projections,
  backup references и, если post-MVP BlobStore уже добавлен, blobs.
- Каждому purge атомарно назначается монотонная `purge_generation`; encrypted
  durable `purge_ledger` хранит минимальный scope, а `purge_watermark` — последнюю
  применённую generation для каждого primary/projection/blob/export/restore
  target.
- Каждый backup manifest связан со snapshot и его `purge_generation`.
  Восстановление обязано проверить binding и применить ledger entries после этой
  generation до актуального watermark, прежде чем открыть данные, чтобы
  удалённое не «воскресло».

Authoritative `purge_ledger` дополнительно реплицируется как encrypted
hash-chained off-host ledger и не откатывается вместе со старым PostgreSQL
snapshot. Иначе restore не смог бы узнать о purge, выполненном после создания
восстанавливаемого backup.

Append-only — техническая модель исправлений, а не запрет пользователю удалить
свои данные.

## Дедупликация

Это три разные задачи:

1. **Transport retry** — `UNIQUE(person_id, operation_id)` и
   отдельная `sync_batch` с `UNIQUE(device_id, batch_id)` плюс membership
   `sync_batch_operation` с `UNIQUE(device_id, batch_id, operation_id)`;
   HTTP `Idempotency-Key = batch_id`, а Android durable хранит body hash/bytes и
   membership.
2. **Source version** — `UNIQUE(connector_account_id, stream_key, external_id,
   external_version)` when the source supplies a version; otherwise the final
   key part is `payload_hash`.
3. **Semantic overlap** — похожие события из нескольких источников.

Первые две решаются детерминированно. Третья не должна автоматически уничтожать
данные: создаётся relation `same_as` или preferred source rule с объяснением.

Особенно важно не суммировать одновременно:

- дневные steps aggregate и все step deltas;
- active calories нескольких приложений;
- один и тот же сон после vendor → Health Connect → Google Health.

## Идемпотентность и конфликты sync

Серверные constraints обеспечивают детерминированный replay:

```text
UNIQUE sync_operation_receipt(person_id, operation_id)
UNIQUE event_revision(revision_id)
UNIQUE sync_batch(device_id, batch_id)
UNIQUE sync_batch_operation(device_id, batch_id, operation_id)
```

- повтор с тем же `operation_id + content_sha256` возвращает сохранённый ACK;
- тот же ID с другим hash отклоняется как protocol/security error;
- create с тем же `event_id` не создаёт второй event;
- сервер присваивает принятой операции монотонный `server_sequence`, который
  используется только как pull cursor, а не как время жизненного события;
- delete является revision с `record_status = retracted`, на которую при
  допустимом переходе указывает current pointer, и синхронизируется тем же
  протоколом.

Correction несёт `base_revision_id`. Если base уже не current, backend одной
транзакцией сохраняет входящую revision как non-current canonical branch, её
parent к stale base и receipt с `result_code = conflict`. Только после commit он
возвращает terminal conflict acknowledgement и текущую серверную revision; этот
result не меняет `life_event.current_revision_id`. Приложение показывает
сравнение и создаёт новую resolution revision с двумя строками в
`revision_parent(relation = resolves)` — по одной на client и server branch.
Молчаливый last-write-wins запрещён для питания, самочувствия, лекарств и health
data.

Такой conflict не rejected: revision, parents, receipt и sequence уже
canonical-committed. `sync_conflict` — локальное состояние ожидающего resolution
branch, а не сигнал повторять ту же operation.

Каждая terminal operation атомарно коммитит canonical revision (`active` либо
`retracted`), revision parents, допустимое изменение
`life_event.current_revision_id`, нужную source version,
`sync_operation_receipt` и `server_sequence`. Raw/job persistence без этого
commit не даёт ACK.

Schema/auth/protocol-invalid operation не создаёт receipt и получает error, а не
ACK. Canonical-committed conflict получает terminal ACK с
`result_code = conflict` и остаётся non-current branch до явного resolution.

Автоматические updates одного Health Connect record разрешаются по его source
version; при её отсутствии используется `source_modified_at + payload_hash`.
Semantic overlap между разными origins остаётся отдельной задачей дедупликации.

## Проекции и будущий RAG

Для bootstrap/pull, подтверждения capture и export нужны минимальные
локальные/серверные read models:

- текущие подтверждённые revisions по локальной дате;
- блюда и nutrient snapshots;
- sleep intervals;
- daily wellbeing;
- measurements с source priority и dedup state.

Полная timeline, dashboard и графики не входят в MVP. Будущий RAG не должен
отвечать на числовые вопросы по embeddings. Запрос
«сколько белка было за неделю» идёт в SQL; semantic memory ищет заметки и контекст.
Каждый фрагмент RAG ссылается на `event_revision_id` и evidence. При исправлении
или purge документ и embedding перестраиваются.

FHIR полезен на import/export boundary — например, `Observation`,
`QuestionnaireResponse`, `Device` и `Provenance`, — но не как внутренняя OLTP
схема. Ориентиры:
[FHIR R4 Observation](https://hl7.org/fhir/R4/observation.html) и
[Open mHealth schemas](https://github.com/openmhealth/schemas).

## Индексы для первого релиза

Достаточный старт:

```text
UNIQUE sync_batch(device_id, batch_id)
UNIQUE sync_batch_operation(device_id, batch_id, operation_id)
UNIQUE sync_batch_operation(device_id, batch_id, ordinal)
UNIQUE sync_operation_receipt(person_id, operation_id)
UNIQUE raw_ingest(capture_id, operation_id)
UNIQUE device_session_token(token_hash)
UNIQUE life_event(event_id)
UNIQUE event_revision(revision_id)
UNIQUE revision_parent(child_revision_id, parent_revision_id, relation)
UNIQUE source_record_version(connector_account_id, stream_key, external_id,
                             external_version) WHERE external_version IS NOT NULL
UNIQUE source_record_version(connector_account_id, stream_key, external_id,
                             payload_hash) WHERE external_version IS NULL

INDEX event_revision(event_id, revision_no)
INDEX event_revision(person_id, effective_start_utc DESC)
INDEX event_revision(person_id, local_date, effective_start_utc DESC)
INDEX life_event(person_id, event_kind, current_revision_id)
INDEX observation_value(person_id, concept_id, event_revision_id)
INDEX outbox_job(status, available_at)
INDEX sync_operation_receipt(person_id, server_sequence)
INDEX purge_ledger(purge_generation)
UNIQUE purge_watermark(target_kind, target_id)
```

`JSONB` удобен как versioned extension bucket
([PostgreSQL documentation](https://www.postgresql.org/docs/current/datatype-json.html)),
но широкий GIN-индекс и универсальная EAV-модель до появления реальных запросов
не нужны. TimescaleDB и `pgvector` также откладываются: PostgreSQL достаточно для
событий одного пользователя, а embeddings являются будущей производной.
