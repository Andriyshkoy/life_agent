# Life Agent

Self-hosted персональный журнал в виде Android-приложения с локальным
offline-first хранением и защищённой синхронизацией на собственный сервер.

## Текущий MVP

Первый продукт предназначен для одного владельца. Все основные сценарии
реализуются непосредственно в Android-приложении через явные кнопки и формы:

- локальный каталог продуктов, рецептов и обычных порций;
- запись фактически съеденного со snapshot КБЖУ;
- самочувствие из настраиваемых вариантов, с любым timestamp и комментарием;
- справочник лекарств/БАДов и запись фактического приёма с дозой;
- обычные текстовые заметки;
- чтение сна и обычного пульса (`HeartRateRecord`) с OnePlus Watch 2 через
  OHealth и Health Connect в том же APK; пульс покоя (RHR) добавляется только
  если Day 0 подтвердит OHealth `RestingHeartRateRecord`.

Другие wearable-типы Day 0 может обнаружить для будущего планирования, но это не
расширяет MVP: они остаются post-MVP до отдельного решения.

Голос, ASR, AI parsing, RAG, советы, dashboard и полноценный просмотр истории не
входят в MVP. После сохранения доступны подтверждение, немедленное исправление и
отмена. До server ACK Room является durable источником новой записи и хранит
outbox; после ACK PostgreSQL хранит каноническую synced history. В течение
текущего MVP Room также сохраняет полную локальную историю revisions/tombstones,
а не очищается после ACK. Полный export и backup остаются обязательными.

## Подтверждённый контекст

- Телефон: OnePlus Open, OxygenOS 16.
- Часы: OnePlus Watch 2, приложение OHealth.
- Runtime: личный Docker VPS; на нём уже работают другие Compose-проекты.
- Репозиторий: публичный `Andriyshkoy/life_agent`; первый Day 0/MVP-contract
  snapshot находится в этом репозитории.
- Целевой deployment: GitHub Actions → GHCR image digest → controlled Docker
  Compose deploy.
- Клиент: нативное single-user Android-приложение.
- Интеграция здоровья: Health Connect читается внутри основного APK.

Текущий личный сервер принят как достаточная база для Day 0, локальной разработки
и content-free HTTPS bootstrap. Обновление EOL ОС, swap и firewall остаются
эксплуатационным техническим долгом; поддерживаемая ОС и проверенный firewall
обязательны до M2 и первой загрузки реальных данных. Точные server coordinates
намеренно не записываются в публичный репозиторий.

## Зафиксированные решения

- Основной пользовательский интерфейс и первичная запись данных находятся в
  Android-приложении.
- Локальная Room DB обеспечивает мгновенную запись без сети; после ACK сервер
  хранит каноническую synced history и backups.
- Telegram-бот исключён из MVP и текущего roadmap; Telegram не используется как
  ingress/transport, отдельного Life Agent companion-приложения также нет.
- Свободный текст вне формы не интерпретируется как health fact.
- Изменяемый справочник и историческое событие разделены.
- Изменение продукта, рецепта или дозы не переписывает прошлые события.
- Unknown и подтверждённый zero — разные состояния.
- Прямой публичный OHealth API не предполагается.
- Целевой wearable flow:
  `Watch 2 → OHealth → Health Connect → Life Agent Android → server sync`.
- Google Health API рассматривается только как post-MVP fallback после реального
  теста и доказанного пробела.
- Голосовой/agentic flow станет отдельным post-MVP этапом.

## Актуальные документы

Нормативная основа:

1. [Product specification текущего MVP](docs/10-mvp-product-spec.md)
2. [Нормативный детальный план разработки, CI/CD и production delivery](docs/11-mvp-delivery-plan.md)

Текущие supporting-документы, которые поясняют тот же план, но не создают
отдельного расписания:

- [Milestone roadmap](docs/07-roadmap.md)
- [Стартовый checklist](docs/08-start-checklist.md)
- [Day 0: OnePlus Watch 2 → Health Connect](docs/12-day-0-oneplus-health-connect.md)
- [Локальный каталог питания и CSV-шаблоны](templates/README.md)
- [Модель данных](docs/04-data-model.md)
- [Безопасность и приватность](docs/06-security-privacy.md)
- [HTTPS bootstrap для life.andriyshkoy.ru](infra/nginx/README.md)

## Discovery и будущие этапы

- [Исходный архитектурный обзор](docs/00-executive-summary.md)
- [Android product и UX](docs/01-product-and-ux.md)
- [Какие данные о здоровье собирать](docs/02-health-data-catalog.md)
- [Расширенная техническая архитектура](docs/03-architecture.md)
- [Исследование health-интеграций](docs/05-integrations.md)
- [Будущий голосовой pipeline](docs/09-voice-and-structuring.md)

Машиночитаемые заготовки:

- [Роли схем и единая команда валидации](schemas/README.md)
- [capture-envelope.schema.json](schemas/capture-envelope.schema.json)
- [Пример local-first capture](examples/capture-note-local-pending.json)
- [life-event.schema.json](schemas/life-event.schema.json)
- [mvp-event-payloads.schema.json](schemas/mvp-event-payloads.schema.json)
- [sync-wire.schema.json](schemas/sync-wire.schema.json)
- [event-payloads.schema.json](schemas/event-payloads.schema.json)
- [extraction.schema.json](schemas/extraction.schema.json)
- [Пример локальной MVP-заметки](examples/mvp-note-local-pending.json)
- [Пример серверной MVP-заметки](examples/mvp-note-server-committed.json)
- [Пример sync batch](examples/sync-push-batch-request.json)
- [Пример будущего разбора голоса](examples/voice-extraction.json)

## Следующее действие

Пока владелец параллельно завершает Day 0 на реальном телефоне, ближайшая
инженерная работа ограничена M1 — локальным Notes vertical slice:

```text
Android shell
→ форма заметки и timestamp
→ atomic encrypted Room event/revision + local outbox
→ мгновенное подтверждение
→ локальные исправление / отмена
→ Notes JSON export + schema/round-trip tests
```

На этом ближайший slice заканчивается. M2 (HTTPS notes sync и baseline backup),
M3 (питание, самочувствие и лекарства/БАДы) и M4 (Health Connect reader в
основном APK) выполняются как отдельные последующие milestones по нормативному
[delivery plan](docs/11-mvp-delivery-plan.md).
