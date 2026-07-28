# Day 0: OnePlus Watch 2 → OHealth → Health Connect

## Статус

Дата решения: 28 июля 2026 года.

На реальном OnePlus Open с OxygenOS 16 выполнены core scan за 48 часов и
extended scan за 30 дней из probe `0.2.0`. Device gate доступности типов закрыт
решением `GO_WITH_REDUCED_SLEEP_DETAIL`:

- Health Connect доступен и все запрошенные чтения завершились без ошибок;
- OHealth подтверждён как origin для `SleepSessionRecord`,
  `HeartRateRecord` и `RestingHeartRateRecord`;
- M4 разблокирован для sleep sessions и ordinary heart rate;
- отдельный RHR подтверждён, но остаётся optional/P1 частью M4 с собственным
  permission flow;
- в проверенной sleep session стадии не наблюдались. Importer сохраняет пустой
  список и ничего не синтезирует; если source stages появятся в другой записи,
  они сохраняются как source data.

Отдельный core scan за 30 дней не получен. Это не блокирует решение о
доступности типов, но оставляет проверку 30-дневного backfill сна/пульса
неблокирующей задачей M4.

## Зафиксированный канал

```text
OnePlus Watch 2
→ OHealth на OnePlus Open
→ Health Connect на телефоне
→ Life Agent Android
→ HTTPS
→ Life Agent API
→ PostgreSQL
```

Health Connect является локальным Android-хранилищем и не имеет server API.
Публичный OHealth REST/OAuth API для этих данных не документирован. Поэтому
OHealth- или Google-токен для device probe не нужен.

Официальные источники:

- [Health Connect overview](https://developer.android.com/health-and-fitness/health-connect);
- [Health Connect availability](https://developer.android.com/health-and-fitness/health-connect/availability);
- [Health Connect data types](https://developer.android.com/health-and-fitness/health-connect/data-types);
- [Health Connect read restrictions and pagination](https://developer.android.com/health-and-fitness/health-connect/read-data);
- [stable Health Connect SDK 1.1.0](https://developer.android.com/jetpack/androidx/releases/health-connect);
- [OHealth](https://play.google.com/store/apps/details?id=com.heytap.health.international);
- [OnePlus Watch 2 specifications](https://www.oneplus.com/it/oneplus-watch-2/specs).

## Что проверяет core probe

| Метрика | Health Connect record | Read permission | Что доказывает отчёт |
|---|---|---|---|
| Сон | `SleepSessionRecord` | `READ_SLEEP` | наличие sessions, stages, их типов и OHealth origin |
| Пульс | `HeartRateRecord` | `READ_HEART_RATE` | наличие series, records/samples и OHealth origin |
| Пульс покоя | `RestingHeartRateRecord` | `READ_RESTING_HEART_RATE` | наличие отдельных RHR observations и OHealth origin |

Probe читает все страницы результата за 48 часов или 30 дней. Он показывает
counts, наличие ожидаемого OHealth origin, агрегированное число остальных
источников, округлённое покрытие, типы стадий сна и наличие metadata. Он не
выводит названия остальных source apps, BPM/RHR, record IDs, точные timestamps,
названия или заметки сна.

Исходники и инструкция находятся в
[`android/health-probe`](../android/health-probe/README.md).

Готовый локальный артефакт:

```text
android/health-probe/dist/life-agent-health-probe-day0-v0.2.0.apk
SHA-256: 763ce4298f85c8f81f65d76588ec9d7d94119e0715e456ea0cb656bc8c1237f9
```

## Что можно исследовать вторым проходом

Расширение добавляется только после успешного core test:

| Метрика | Record | Permission | Ожидание |
|---|---|---|---|
| HRV RMSSD | `HeartRateVariabilityRmssdRecord` | `READ_HEART_RATE_VARIABILITY` | Watch 2 использует HRV для производных оценок, raw export не подтверждён |
| SpO₂ | `OxygenSaturationRecord` | `READ_OXYGEN_SATURATION` | измерение поддерживается, экспорт не подтверждён |
| Дыхание | `RespiratoryRateRecord` | `READ_RESPIRATORY_RATE` | возможны отдельные samples вокруг сна |
| Тренировка | `ExerciseSessionRecord` | `READ_EXERCISE` | session, type, laps/segments; vendor-specific metrics могут потеряться |
| Шаги/cadence | `StepsRecord`, `StepsCadenceRecord` | `READ_STEPS` | нужно отделять OHealth от системного Android origin |
| Дистанция | `DistanceRecord` | `READ_DISTANCE` | запись и связь с exercise session |
| Калории | `ActiveCaloriesBurnedRecord`, `TotalCaloriesBurnedRecord` | соответствующие read permissions | наличие records, без предположения об алгоритме |
| Скорость | `SpeedRecord` | `READ_SPEED` | samples, если OHealth их публикует |

Exercise route требует отдельного разрешения/consent. Диагностика никогда не
выводит координаты. OHealth sleep score, stress score, snoring risk, baseline HR
и специализированные спортивные показатели нельзя считать стандартными
Health Connect records.

## Результаты device test

| Тип | Результат 28.07.2026 | Решение |
|---|---|---|
| `SleepSessionRecord` | OHealth records наблюдались; stages не наблюдались | M4 P0, stages могут быть пустыми |
| `HeartRateRecord` | OHealth records и samples наблюдались | M4 P0 |
| `RestingHeartRateRecord` | отдельная OHealth observation наблюдалась | optional/P1 M4, отдельное разрешение |
| `RespiratoryRateRecord` | OHealth records наблюдались | post-MVP |
| `StepsRecord` | наблюдались OHealth и второй анонимизированный origin | post-MVP; требуется origin-aware deduplication |
| `TotalCaloriesBurnedRecord` | OHealth records наблюдались; чтение прошло более одной страницы | post-MVP |
| HRV RMSSD, SpO₂, exercise, cadence, distance, active calories, speed | records в проверенном 30-дневном окне не наблюдались | `not_observed`, не `unsupported`; post-MVP |

Все секции обоих отчётов имели `status=ok`. Окна, coverage, record/sample/stage
counts, origin totals, recording-method totals и metadata counts внутренне
согласованы. Отчёт также подтверждает фактическое прохождение pagination.

Ограничения evidence:

- у наблюдавшихся OHealth records отсутствовала device metadata, а
  `recording_method` был `unknown`; доказан origin приложения OHealth, но нельзя
  приписывать каждую запись непосредственно OnePlus Watch 2 или маркировать её
  как automatic;
- privacy-minimized report не содержит значений, record IDs и change metadata,
  поэтому точность значений, стабильность source IDs, updates/deletes,
  change-token flow и sync lag проверяются в M4;
- отдельный core 30-day report ещё нужен перед финальной настройкой backfill и
  reconciliation, но не для повторного решения availability;
- в сообщении владельца были два очевидных transport-артефакта: отсутствующий
  перевод строки между отчётами и пробел внутри одного года. Они нормализованы
  только в приватной локальной evidence-копии;
- сырые отчёты не коммитятся в публичный Git: несмотря на минимизацию, они
  содержат датированные агрегаты личных health-данных. Публично хранится только
  эта capability-классификация; test fixtures должны быть синтетическими.

## Процедура device test

1. Обновить firmware часов и OHealth.
2. В OHealth включить автоматический сон, all-day heart rate и resting heart
   rate; затем вручную выполнить sync часов.
3. Найти `Health Connect` через поиск в настройках OxygenOS.
4. Убедиться, что OHealth разрешена запись сна и heart-rate данных.
5. Установить debug APK probe.
6. Открыть приложение, нажать `Grant core read permissions` и выдать три read
   permissions.
7. Выполнить `Scan last 48 hours`, сразу нажать `Share capability report` и
   сохранить или вернуть текст отчёта.
8. Выполнить `Scan last 30 days` и сразу поделиться вторым отчётом: следующий
   scan заменяет текущий результат в приложении.
9. Отдельно нажать `Grant optional discovery permissions`, выдать только
   желаемые дополнительные read-разрешения, выполнить optional 30-day scan и
   сразу поделиться третьим отчётом.

Если за последние 48 часов нет сна, сначала достаточно 30-дневного scan. Для
чистого теста желательно иметь хотя бы одну синхронизированную ночь и один
обычный день пульса.

## Go/no-go

Интеграция Health Connect для Life Agent Android подтверждена, если:

- `Health Connect: available`;
- у нужных sections `status=ok`;
- для core records присутствует
  `com.heytap.health.international`;
- наличие sleep stages и число HR samples зафиксированы для продуктовой оценки.

Ненулевые OHealth sleep/HR records подтверждают технический маршрут. Плотность
samples не является медицинским или бинарным техническим порогом: она
сравнивается с тем же днём в OHealth и определяет только доступную детализацию
будущих отчётов.

Фактический verdict — `GO_WITH_REDUCED_SLEEP_DETAIL`: OHealth sleep и ordinary
HR подтверждены, stages пока отсутствуют, а RHR availability подтверждена для
отдельной optional/P1 реализации. Google Health fallback для текущего маршрута
не нужен.

## Инфраструктура и secrets

- Для будущего Android sync выбран `life.andriyshkoy.ru`; его DNS A-record
  направлен на сервер.
- 27 июля 2026 года развёрнут отдельный nginx vhost и однодоменный сертификат
  Let's Encrypt. HTTP перенаправляется на HTTPS, а content-free
  `GET/HEAD /healthz` снаружи отвечает точным bootstrap-status.
- Authenticated `/api/v1/` намеренно отсутствует и отвечает `404` до M2.
  PostgreSQL и Docker socket наружу не публикуются.
- Контракт M2: backend выдаёт short-lived access token и rotating refresh token.
  На телефоне refresh token хранится в ciphertext, защищённом Keystore-backed
  ключом; access token остаётся только в памяти, а сервер хранит token hashes и
  rotation/reuse state.
- Сейчас не нужны Google OAuth credentials или OHealth token.
- Секреты не передаются в чат или Git и устанавливаются непосредственно в
  server-side secret files.

Probe не развивается как отдельный companion или bridge. Следующий технический
шаг — единое production-приложение Life Agent для Android: в него переносятся
Health Connect reader, encrypted outbox, idempotent batches, change tokens,
background/history feature checks и явный `Sync now`. В том же приложении
последовательно появляются ручной ввод, локальные справочники и все будущие
интеграции.
