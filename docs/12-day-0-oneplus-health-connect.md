# Day 0: OnePlus Watch 2 → OHealth → Health Connect

## Статус

Дата: 27 июля 2026 года.

Выполнена вся часть Day 0, не требующая физического доступа к телефону:

- подтверждён поддерживаемый канал интеграции;
- сверены актуальные API и разрешения Health Connect;
- подготовлен foreground-only read-only probe;
- определён безопасный HTTPS-контур будущей синхронизации Android-приложения;
- определены проверяемые go/no-go критерии.

Фактический экспорт OHealth остаётся device gate: владелец устанавливает probe
на OnePlus Open, выдаёт локальные разрешения и возвращает сформированный
capability report. Наличие датчика или метрики в OHealth само по себе не
доказывает экспорт в Health Connect.

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

## Device test

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

Отсутствие только RHR не блокирует MVP: его можно честно оставить unavailable
либо позже вычислять только после отдельного продуктового решения. Отсутствие
OHealth одновременно для sleep и HR блокирует прямой Health Connect route; тогда
проверяется Google Health fallback или ручной/file import.

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

После успешного capability report probe не развивается как отдельный companion
или bridge. Следующий технический шаг — единое production-приложение Life Agent
для Android: в него переносятся Health Connect reader, encrypted outbox,
idempotent batches, change tokens, background/history feature checks и явный
`Sync now`. В том же приложении последовательно появляются ручной ввод,
локальные справочники и все будущие интеграции.
