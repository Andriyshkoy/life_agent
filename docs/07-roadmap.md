# Android-first roadmap Life Agent

## Статус

Дата актуализации: 28 июля 2026 года.

Этот документ заменяет ранний Telegram-first и четырёхнедельный roadmap.
Календарного дедлайна нет: работа идёт milestone с проверяемыми gates. Физический
M0 availability gate пройден; M4 разблокирован для подтверждённых sleep sessions
и ordinary HR, а M1 остаётся ближайшим implementation milestone.

Life Agent MVP теперь является самостоятельным Android-приложением для одного
владельца. Telegram-бот, Telegram Mini App и BotFather token не входят в MVP.
Все ручные сценарии, справочники, Health Connect и состояние синхронизации
реализуются непосредственно в приложении.

Текущий server bootstrap уже развёрнут: отдельный сертификат
`life.andriyshkoy.ru` валиден, HTTP отвечает `308` на HTTPS, а
`GET /healthz` возвращает ровно
`{"status":"ok","service":"life-agent","phase":"bootstrap"}`; `HEAD /healthz`
также разрешён. `/api/v1` сейчас намеренно отвечает `404`; enrollment, sync API
и приём данных являются scope M2.

## Продуктовая цель

На OnePlus Open должно быть удобно за несколько предсказуемых действий записать:

- питание из личного каталога продуктов, блюд и порций;
- самочувствие из настраиваемых вариантов, с timestamp и комментарием;
- фактический приём лекарства или БАДа из личного списка;
- обычную текстовую заметку;
- сон и пульс, автоматически полученные через Health Connect.

Основной контур:

```text
ручной ввод ───────────────┐
                           ├→ Android app → Room/outbox → HTTPS sync (M2) → PostgreSQL
Watch 2 → OHealth          │
             → Health Connect ┘
```

Приложение работает local-first: подтверждённый ручной ввод сначала атомарно
попадает в Room и не зависит от наличия сети. После server acknowledgement в M2
PostgreSQL становится долговременным каноническим хранилищем и местом для
backup/export. Синхронизация идемпотентна и может безопасно повторяться.

MVP оптимизируется для сбора, а не для просмотра. Timeline, графики, dashboard,
аналитика и рекомендации не требуются. Пользователь видит текущий сценарий,
итог сохранения, возможность исправить/отменить последнее действие и техническое
состояние синхронизации.

## Принципы реализации

1. **Явный сценарий вместо угадывания.** Тип записи, количество, единица, время
   и доза выбираются в конкретной форме.
2. **Local-first без ложных подтверждений.** `Сохранено` появляется только после
   commit в Room; server sync отображается отдельно.
3. **Исторический факт отделён от справочника.** При записи создаётся snapshot,
   поэтому изменение продукта, рецепта или препарата не переписывает прошлое.
4. **Unknown не равен zero.** Неизвестные нутриенты и пропущенные значения
   остаются `null`/`unknown`.
5. **Исправление прослеживаемо.** Correction создаёт revision, удаление —
   tombstone; тихого destructive overwrite нет.
6. **Минимальные health permissions.** Приложение читает только подтверждённые
   типы Health Connect и ничего туда не записывает.
7. **Сеть является ненадёжной.** Outbox, backoff, idempotency keys и явный
   sync status входят в архитектуру с первого server slice.
8. **Секреты не живут в Git или документации.** Rotating refresh token хранится
   в Keystore-wrapped ciphertext, access token короткоживущий; сервер хранит
   только token hashes и rotation/reuse state.
9. **Медицинских выводов нет.** MVP собирает факты, но не диагностирует, не
   назначает и не оценивает дозировки.

## Milestone map

| Milestone | Проверяемый результат |
|---|---|
| M0 — Day 0 и device gate | Готовый read-only probe установлен на реальном OnePlus Open; фактический экспорт OHealth классифицирован |
| M1 — Android foundation + notes | Воспроизводимая сборка, design system, Room/outbox и полный локальный notes vertical slice |
| M2 — Secure notes sync | `life.andriyshkoy.ru` принимает notes через enrollment и idempotent HTTPS sync; baseline backup восстановлен |
| M3 — Ручные health-домены | Питание, самочувствие и лекарства/БАДы полностью работают через тот же local-first/sync контур |
| M4 — Health Connect | Подтверждённые сон/пульс импортируются в тот же event model с provenance и безопасным incremental sync |
| M5 — Reliability и ownership | Export, backup/restore, deletion semantics, observability и recovery проверены end-to-end |
| M6 — Product dogfood | Приложение прошло устойчивое личное использование без потерь и критической UX-фрикции |

## M0 — Day 0 и device gate

Инженерная часть уже выполнена:

- выбран путь `OHealth → Health Connect`;
- подготовлен foreground-only read-only APK;
- core scan проверяет сон, пульс и resting HR;
- optional scan исследует HRV, SpO₂, дыхание, упражнения, шаги, дистанцию,
  калории и скорость;
- probe не имеет `INTERNET`, write, background, history и route permissions.

28 июля 2026 года физический availability gate пройден на OnePlus Open:
получены core 48-hour и extended 30-day reports, а решение
`GO_WITH_REDUCED_SLEEP_DETAIL` зафиксировано в
[Day 0 decision record](12-day-0-oneplus-health-connect.md). Отдельный core
30-day scan остаётся неблокирующей проверкой backfill для M4.

### Gate M0

- Health Connect доступен на фактической сборке OxygenOS — подтверждено.
- Для сна и обычного пульса найдены OHealth records — подтверждено.
- Sleep stages не наблюдались; пустой список stages является корректным.
- RHR availability подтверждена; отдельная optional/P1 реализация ещё не
  выполнена.
- Respiratory rate, steps и total calories обнаружены как post-MVP; остальные
  optional types имеют статус `not_observed`.

Gate M0 закрыт. Это разблокирует M4 для подтверждённых типов, но не означает,
что production importer уже реализован.

## M1 — Android foundation + notes

### Приложение

- Kotlin, Jetpack Compose и Material 3.
- Одна activity и предсказуемая navigation graph.
- Ровно три top-level раздела: `Добавить`, `Справочники`, `Настройки`;
  enrollment, sync status и `Sync now` находятся в `Настройки → Синхронизация`.
- На первом запуске сервер можно `Пропустить` и `Настроить позже`; локальный
  capture доступен без enrollment.
- Общие компоненты: app bar, primary action, form field, picker, confirmation,
  empty/error/loading state и destructive confirmation.
- Light/dark theme, системные font scaling и минимально достаточная
  accessibility.
- Структура по feature-модулям без преждевременных микромодулей.

### Локальные данные

- Room как durable local store.
- Stable UUID для capture, event, revision и outbox operation.
- `effective_at`, `recorded_at`, IANA timezone, source и provenance.
- Catalog entities отделены от immutable event snapshots.
- Stable `revision_id`, `current_revision_id` pointer, ancestry через
  `revision_parent` и `record_status: active|retracted`; tombstone представляет
  удаление без silent overwrite.
- `revision_no` может совпадать на branches и не используется как identity или
  глобальный монотонный порядок.
- Room migrations и fixture database tests.
- Transactional outbox создаётся в той же транзакции, что и событие.
- Room/outbox зашифрованы, DEK обёрнут Android Keystore key.
- Room, outbox, keys, credentials и exports исключены из Auto Backup и
  device-to-device transfer.
- Полная локальная история events, revisions и tombstones хранится весь MVP и не
  удаляется после server ACK.

### Notes vertical slice

- Текст и effective timestamp.
- Local save без parser/LLM и без сети.
- Note event и outbox operation создаются одной Room transaction.
- После commit доступны подтверждение, correction и undo последнего действия.
- Notes JSON export проходит локальный round-trip.

### Gate M1

- Clean checkout собирает debug APK документированной командой.
- CI выполняет Android lint, unit tests и assemble.
- Полный notes flow работает в airplane mode.
- Kill/restart не теряет подтверждённую fixture-заметку или её outbox operation.
- Double tap/retry не создаёт вторую заметку.
- Migration сохраняет events, revisions и outbox.
- Instrumented test не находит plaintext fixture в database/WAL/SHM и
  подтверждает backup/data-extraction exclusions.
- Основные экраны корректно работают при увеличенном шрифте и без сети.

## M2 — secure notes sync

### API и сервер

- Уже работающий HTTPS bootstrap сохраняет точный content-free `/healthz`;
  текущий `404` под `/api/v1/` ожидаем до deploy M2.
- В M2 nginx проксирует только необходимые authenticated API paths по HTTPS.
- FastAPI валидирует версионированные batch schemas.
- PostgreSQL доступен только во внутренней Docker network.
- Сервер хранит device identity, token hashes и rotation/reuse state, но не
  исходные access/refresh tokens.
- Payload и health content не попадают в access/application logs.

### Android sync

- Enrollment запускается из `Настройки → Синхронизация → Настроить` и выдаёт
  short-lived access token и отдельный rotating refresh token.
- `Пропустить`/`Настроить позже` оставляют приложение полностью работоспособным
  локально; отсутствие enrollment не блокирует capture.
- Refresh token защищён Android Keystore-backed encryption; access token
  остаётся только в памяти.
- Каждая операция имеет idempotency key и stable client sequence.
- WorkManager отправляет bounded batches с exponential backoff.
- Cursor/ack продвигается только после подтверждения сервера.
- Приложение показывает pending count, last successful sync и `Sync now`.
- Server rejection остаётся видимым recoverable состоянием, а не silently
  discarded записью.

### Baseline backup

- Только после первой синхронизированной fixture-заметки выполняется encrypted
  PostgreSQL backup.
- Backup восстанавливается в отдельную чистую БД.
- Сверяются note event, revision, outbox acknowledgement и tombstone.
- Копия хранится вне единственного VPS; секреты в backup не входят.

### Gate M2

- До включения API и первой fixture/реальной загрузки server OS поддерживается и
  получает security updates, а firewall policy проверена снаружи.
- Офлайн-заметки доходят до сервера после восстановления сети.
- Повтор одного batch не создаёт дубли.
- Kill app/server в разных точках sync не теряет заметку.
- Unauthorized и malformed request не записывают данные.
- Сертификат, hostname verification и access/refresh rotation проверены.
- PostgreSQL и служебные endpoints не доступны из интернета.
- Encrypted baseline backup с заметками восстановлен в чистую БД.

## M3 — ручные health-домены

### Питание

- Products, aliases, basis и КБЖУ.
- Portion presets, favorites и recent.
- Recipes с immutable versions, ingredients и yield.
- Meal presets для повторяющихся комбинаций.
- Consumption event/group с nutrient snapshot.
- Граммы, миллилитры, штуки и порции без binary float.
- Создание, изменение, архивирование и локальный import/export каталога.

### Самочувствие

- Настраиваемые dimensions и ordered options.
- Любое подмножество dimensions без автозаполнения пропусков.
- `Сейчас` или выбранный effective timestamp.
- Необязательный комментарий.
- Snapshot отображаемого значения на момент события.

### Лекарства и БАДы

- Личный master list, aliases, тип, form, dose и unit.
- Быстрые dose presets и recent.
- Только подтверждённый actual intake со snapshot дозы/единицы.
- Никакого предложения дозы или автоматического создания intake.
- Schedule и medication reminders отложены после MVP.

### Gate M3

- Каждый домен можно использовать в airplane mode.
- Double tap/retry не создаёт второй event.
- Изменение карточки не меняет старый snapshot.
- Unknown не участвует в вычислениях как zero.
- Частый продукт, блюдо и препарат записываются за несколько содержательных
  нажатий.
- После восстановления сети все домены проходят уже проверенный M2 sync
  protocol без отдельного transport path.
- Ни один сценарий не требует Telegram или прямого доступа к БД.

## M4 — Health Connect

- Код Day 0 probe переносится в production feature без diagnostic report UI.
- P0 импортирует только подтверждённые sleep sessions и ordinary heart rate.
- Availability resting HR подтверждена; его import остаётся optional/P1 задачей
  M4 с отдельным permission flow.
- На каждом app-open автоматически запускается неблокирующий foreground
  incremental import.
- Foreground `Sync now` запускает тот же путь вручную и остаётся обязательным
  понятным fallback.
- Чтение использует pagination и source/origin filtering.
- Health records нормализуются в общий event model с source record key,
  provenance и imported timestamp.
- Повторный scan идемпотентен.
- Update/delete внешней записи корректно обновляет local projection.
- Background/history permissions не нужны для P0 и добавляются только условно
  после стабильного foreground path, runtime feature check и доказанной
  необходимости.
- HRV, SpO₂, respiration, exercise, steps, distance, calories, speed и другие
  types остаются post-MVP discovery, даже если M0 нашёл records.

### Gate M4

- Сон и ordinary HR в приложении сверены с тем же периодом в Health Connect.
- App-open автоматически запускает foreground incremental import, а `Sync now`
  успешно повторяет его вручную.
- Повторный импорт не создаёт дубли.
- Позднее уточнение sleep session обновляет соответствующую запись.
- Импортированные records синхронизируются через тот же M2 protocol.
- Отзыв permission приводит к понятному degraded state, не ломая ручной capture.
- У приложения нет Health Connect write permissions.

## M5 — reliability, export и backup

- Полный versioned JSON export со справочниками, snapshots, revisions,
  provenance и tombstones.
- Производные CSV по доменам без потери canonical JSON.
- Локальный Android export через системный document picker/share flow только по
  явному действию владельца.
- Server-side encrypted backup вне единственного VPS.
- Clean restore в отдельную БД с проверкой counts/checksums.
- Restore не воскрешает записи, покрытые tombstones.
- Content-free metrics для sync lag, outbox failures, backup age, disk и DB.
- Redaction test для Android и server logs.
- Документированные delete-period/delete-all semantics и судьба backup.
- Безопасные schema migrations и rollback приложения/API.

### Gate M5

- Export проходит schema validation и импортируется в чистое test environment.
- Backup → clean restore сохраняет все активные события и историю revisions.
- После process/device/server restart нет потерянных confirmed actions.
- Outbox debt и backup failure заметны без чтения health content.
- Пользователь может забрать свои данные без Telegram и ручного SQL.

## M6 — dogfood и release gate

Dogfood начинается после устойчивого local capture и продолжается до получения
репрезентативного набора обычных дней. Число календарных дней не является
заменой качественным критериям.

Измеряются локально:

- confirmed action loss;
- unexplained active duplicates;
- capture completion/cancel rate;
- число нажатий и время для частой еды/дозы;
- correction rate;
- crash-free sessions;
- outbox age и sync success;
- catalog/preset hit rate;
- субъективная фрикция каждого основного сценария.

### Release gate

- Потерянных подтверждённых действий нет.
- Необъяснимых активных дублей нет.
- Основные manual flows работают без admin access.
- Health import честно работает либо показывает документированный degraded
  status.
- Offline → online sync и credential rotation проверены.
- Export и clean restore успешно выполнены.
- Повторяющиеся P0/P1 UX-проблемы устранены.
- Life Agent data surface ограничена HTTPS; host SSH ограничен, HTTP оставлен
  только для redirect/ACME, PostgreSQL/admin endpoints не опубликованы.
- Серверная ОС поддерживается и получает security updates, firewall policy
  проверена, известные host risks закрыты или имеют явный release blocker, а
  backup находится вне VPS.

## После MVP

Отдельными фазами, после release gate:

- голосовой ввод и локальная/облачная транскрибация с отдельным privacy
  решением;
- deterministic summaries;
- тренировки как полноценный ручной домен;
- импорт дополнительных wearable records;
- timeline, графики и dashboard;
- RAG, embeddings и агентские рекомендации;
- proactive coaching и reminders;
- medication schedules/reminders;
- Health Connect types кроме sleep, ordinary HR и подтверждённого optional RHR;
- фото, OCR и barcode automation.

Ни голос, ни RAG не должны менять canonical event model. Они становятся лишь
новыми способами создавать или читать те же проверяемые данные.

## Что требуется от владельца

Не передавать секреты в чат, issue или commit:

1. Перед финальной настройкой M4 backfill выполнить core 30-day scan.
2. Подготовить первые частые продукты, блюда и обычные порции.
3. Определить стартовые wellbeing dimensions/options.
4. Подготовить список лекарств и БАДов с form/dose/unit без медицинских
   назначений.
5. Подтвердить enrollment production APK после готовности HTTPS API.

BotFather token и Telegram owner ID больше не нужны.
