# План интеграции Xiaomi Body Composition Scale S400

Дата фиксации: 28 июля 2026 года.

Статус: **planning/discovery only**. Этот документ не расширяет текущий MVP,
не разрешает production-доступ к Xiaomi Cloud и не означает, что connector,
BLE reader, database schema или deployment уже реализованы.

## Подтверждённый контекст

- Устройство: Xiaomi Body Composition Scale S400.
- Cloud/device model в Xiaomi Home: `yunmai.scales.ms104`.
- Регион Xiaomi account: `sg` (Singapore).
- Основное приложение устройства: Mi Home/Xiaomi Home.
- Xiaomi Bluetooth gateway отсутствует.
- Life Agent остаётся single-user системой; чужие и нераспознанные измерения
  нельзя автоматически приписывать владельцу.

Секреты Xiaomi account, bind key, cloud tokens, реальные measurement values и
экспорты не должны попадать в этот публичный repository, issue, PR, CI log или
чат.

## Что это означает для синхронизации

Без Bluetooth gateway весы сначала сохраняют измерение локально. Оно становится
доступно cloud connector только после того, как телефон/Mi Home подключится к
весам и загрузит данные в Xiaomi Cloud:

```text
S400 → внутренняя память весов
     → Mi Home на телефоне
     → Xiaomi Cloud
     → будущий read-only server importer
     → Life Agent normalization/sync
```

По официальному FAQ весы могут хранить до 160 несинхронизированных измерений.
Gateway нужен не для измерения или будущего backfill, а для загрузки в cloud без
открытого Mi Home. Его отсутствие принимается как известное ограничение, а не
как причина покупать новое устройство до Day 0 проверки.

Источники:

- [официальная страница S400](https://www.mi.com/global/product/xiaomi-body-composition-scale-s400/);
- [официальный FAQ S400](https://www.mi.com/uk/support/faq/details/KA-107891/).

## Зафиксированное направление

### Первый кандидат: server-side Xiaomi Home importer

Для будущего read-only spike выбран
[SmartScaleConnect](https://github.com/AlexxIT/SmartScaleConnect) как
исследовательский кандидат:

- проект явно перечисляет S400 EU `yunmai.scales.ms104`;
- перечислены region endpoints, включая `sg`;
- поддерживаются Xiaomi Home, несколько scale users и JSON/CSV output;
- есть Docker distribution;
- лицензия проекта — MIT.

Connector использует недокументированный Xiaomi cloud endpoint, а не
официальный стабильный public API. Поэтому наличие рабочего open-source клиента
подтверждает техническую реализуемость, но не является production approval.
До реализации отдельно принимаются риски изменения endpoint/auth, проверяются
условия использования, retention и возможность безопасного disconnect.

Предварительный runtime-контур:

```text
bounded poll/reconciliation worker
→ Xiaomi Home cloud
→ validate and select owner records
→ idempotent normalization
→ PostgreSQL
```

Период опроса не фиксируется до измерения фактической задержки Mi Home → cloud.
Первоначальная гипотеза для spike — не чаще одного bounded запуска за 15 минут.

### Резерв: прямое BLE-чтение в Android

S400 передаёт encrypted MiBeacon advertisements. Open-source реализации
подтверждают возможность получить как минимум:

- вес;
- статический пульс;
- high-frequency impedance;
- low-frequency impedance;
- scale profile ID.

Для расшифровки нужен индивидуальный BLE bind key. В production он может
храниться только в Keystore-protected storage на телефоне. Постоянное background
BLE scanning не считается гарантированным Android flow. Если этот путь будет
выбран, сначала реализуется явный сценарий `Начать взвешивание`, а автоматизация
оценивается отдельно.

Технические references:

- [xiaomi-ble: S400 support](https://github.com/Bluetooth-Devices/xiaomi-ble/pull/163);
- [openScale: S400 Android support](https://github.com/oliexdev/openScale/releases/tag/v3.0.3).

Код openScale имеет GPLv3-лицензию и не копируется в Life Agent без отдельного
лицензионного решения. Apache-2.0 implementation/protocol evidence из
`xiaomi-ble` можно рассматривать как основу для независимого Kotlin reader с
сохранением требуемых notices.

### Health Connect

Нативный Android export `Xiaomi Home → Health Connect` для этой конфигурации не
считается подтверждённым. Перед любым cloud/BLE production connector выполняется
дешёвая проверка:

1. присутствует ли Xiaomi Home среди Health Connect apps;
2. есть ли Xiaomi-origin записи Weight/Body Fat за известный период;
3. какие body-measurement permissions Xiaomi Home реально имеет.

Если стандартный route обнаружится, он получает приоритет перед private cloud
API. Health Connect write из Life Agent остаётся вне текущего MVP.

## Что считать исходным измерением

Поля нельзя смешивать без provenance:

| Класс | Примеры | Хранение |
|---|---|---|
| Непосредственно измеренное/переданное | weight, два impedance, scale HR | значение, единица, device/source и timestamp |
| Vendor-derived | body fat, water, muscle, bone, visceral fat, BMR, body score, metabolic age | отдельные поля с `derivation=vendor` |
| Life Agent-derived | будущий trend, moving average, пересчёт формул | отдельная projection с algorithm/version |

Body-composition показатели BIA являются оценками и не получают статус
клинических измерений. Unknown не заменяется нулём, а значения из разных
алгоритмов не сшиваются в один непрерывный ряд без явной версии.

Минимальный будущий normalized record:

- stable Life Agent event/revision ID;
- source record ID, если Xiaomi его предоставляет;
- owner/vendor profile ID и attribution state;
- source measured time, timezone/offset и imported time;
- weight и доступные composition values с единицами;
- raw high/low impedance, только если пришли по BLE;
- source app/device/model/region;
- connector и mapping version;
- `measured|vendor_derived|life_agent_derived`;
- content hash/fingerprint только как fallback dedup key;
- correction/deletion state.

Raw cloud payload хранится только если Day 0 докажет необходимость для
воспроизводимости; иначе сохраняются минимальные source identifiers, mapping
version и нормализованные поля.

## Read-only Day 0 перед разработкой

Spike запускается только после отдельного явного решения реализовывать
интеграцию:

1. Создать отдельный Xiaomi account и расшарить на него весы, если shared account
   действительно видит нужную историю. Основной account password не является
   вариантом по умолчанию.
2. Передать credential непосредственно в server secret store, не через Git/чат.
3. Запустить pinned SmartScaleConnect container для `sg` и
   `yunmai.scales.ms104` с JSON output в приватный временный каталог.
4. Не писать результат в production database.
5. Проверить только приватно: число записей, доступные поля, source IDs,
   timezone, users, update/delete semantics и initial backfill.
6. Провести две контролируемые проверки появления новой записи:
   - Mi Home закрыт после взвешивания;
   - Mi Home открыт и подключился к весам.
7. Повторить import и доказать, что один source record не создаёт дубль.
8. Удалить временный export; сохранить только privacy-safe capability report и
   checksum приватного evidence.

### Gate результата

Возможные решения:

- `GO_CLOUD_IMPORTER` — owner attribution, history, IDs и reconciliation
  достаточны, а private API risk явно принят;
- `GO_DIRECT_BLE` — cloud route не проходит security/reliability gate, но live
  BLE capture воспроизводим;
- `GO_MANUAL_WEIGHT` — автоматический route ненадёжен, вес вводится кнопкой;
- `NO_GO` — источник нельзя безопасно и достоверно связать с владельцем.

До этого gate S400 остаётся подтверждённым post-MVP источником данных, но не
задачей текущих M0–M4 и не runtime-зависимостью Life Agent.
