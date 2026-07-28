# Работа с ветками и релизами

Life Agent использует две постоянные ветки:

- `develop` — default integration branch для всей текущей разработки;
- `main` — стабильная production/release history.

## Обычная разработка

1. Создать короткую ветку от актуальной `develop`, например
   `feature/notes-room` или `agent/notes-room`.
2. Открыть pull request в `develop`.
3. Дождаться обязательных проверок и разрешить обсуждения.
4. Объединить PR squash-мерджем.

Прямой push в `develop` не используется. Squash оставляет в integration history
один осмысленный commit на законченное изменение.

## Production promotion

1. Убедиться, что нужный snapshot `develop` зелёный и готов целиком.
2. Открыть отдельный pull request строго `develop → main`.
3. Дождаться обязательных проверок.
4. Объединить PR merge commit, сохранив границу релиза в истории.

Прямой push и feature PR в `main` запрещены. CI отклоняет PR в `main` из любой
ветки, кроме `develop`. Срочное изменение сначала проходит обычный путь через
`develop`; временное ослабление production policy не является штатным hotfix
процессом.

## CI/CD boundary

CI запускается для push и pull request в обе постоянные ветки. Production
environment принимает только `main`.

Полноценный CD появится в M2 после появления versioned backend image, Docker
Compose manifest, migrations, readiness probe и rollback procedure. До этого
production environment фиксирует границу доступа, но не выполняет фиктивный
deploy. Будущий workflow обязан разворачивать immutable image digest только
после успешного CI commit в `main`.
