# Голос и структурирование

> Статус: post-MVP research. Голос, ASR и agentic extraction намеренно не входят
> в текущий Android-релиз и не блокируют
> [button-first MVP](10-mvp-product-spec.md). MVP не запрашивает
> `RECORD_AUDIO`, не принимает audio intents и не содержит ASR runtime.

## Решение для будущего голосового этапа

Голос становится дополнительным способом ввода внутри единого Android-приложения
Life Agent. Отдельный бот, companion или voice-agent не нужен. Для одного
говорящего и короткой завершённой записи также не нужны realtime streaming и
diarization.

После MVP исследуются три явных Android-сценария:

1. Нажать и удерживать кнопку записи в Life Agent. Приложение запрашивает доступ
   к микрофону только при первом использовании функции и пишет ограниченную по
   длительности запись в app-private storage.
2. Поделиться готовым аудиофайлом в Life Agent через Android Sharesheet
   (`ACTION_SEND`, `audio/*`) или выбрать его системным file picker
   (`ACTION_OPEN_DOCUMENT`). Входящий `content://` URI копируется в приватное
   хранилище до завершения временного разрешения.
3. Использовать системное on-device распознавание только когда
   `SpeechRecognizer.isOnDeviceRecognitionAvailable()` подтверждает его
   наличие. Обычный системный recognizer может обращаться к внешнему сервису и
   не считается local-first автоматически.

Официальные Android-источники:

- [MediaRecorder и разрешение на микрофон](https://developer.android.com/media/platform/mediarecorder);
- [приём данных через Android Sharesheet](https://developer.android.com/training/sharing/receive);
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files);
- [on-device SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).

Основной local-first pipeline:

```text
[явная запись в приложении | ACTION_SEND | ACTION_OPEN_DOCUMENT]
→ проверка URI, размера, формата и длительности
→ durable encrypted app-private blob
→ media validation/normalization
→ device-local ASR или явно выбранный self-hosted ASR
→ immutable transcript artifact
→ extraction в строгую schema
→ независимая validation
→ Android preview/confirmation
→ canonical event revisions
→ TTL удаления аудио
```

Сбой на любом этапе оставляет запись в разделе `Черновики`; исходник можно
обработать повторно, не прося пользователя заново надиктовать событие. Запись и
импорт всегда запускаются явным действием пользователя, скрытое фоновое
прослушивание не допускается.

## Выбор ASR

Для первого benchmark:

| Кандидат | Когда использовать | Особенности |
|---|---|---|
| Android on-device `SpeechRecognizer` | быстрый UX experiment на поддерживаемом телефоне | не требует собственной модели, но доступность, языки и качество зависят от устройства |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | основной device-local candidate | native runtime и квантованные модели; нужно измерить размер APK/model, RAM, нагрев и батарею |
| [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | self-hosted fallback на личном сервере | CTranslate2, CPU/GPU, INT8, word timestamps, Silero VAD; аудио покидает телефон по HTTPS |
| [OpenAI Whisper](https://github.com/openai/whisper) | reference baseline | оригинальная MIT-реализация, обычно тяжелее оптимизированных портов |

Термин `local` фиксируется явно:

- `device-local`: аудио и расшифровка не покидают телефон;
- `self-hosted`: обработка на личном сервере, но аудио передаётся по сети;
- `cloud`: сторонний провайдер, только отдельный opt-in.

Стартовая конфигурация self-hosted `faster-whisper`: `language=ru`,
`vad_filter=true`, `word_timestamps=true`, `compute_type=int8` на CPU. Это не
финальный выбор checkpoint. Размер модели, beam size и runtime определяются
сравнением с device-local вариантом на фактическом телефоне и сервере. VAD
важен: длинные паузы и тишина могут провоцировать ложный текст.

Перед ASR проверяются реальный container format, codec, duration и size; имя и
заявленный MIME от отправившего приложения или content provider не считаются
достаточной проверкой. Поддерживаемые входные контейнеры задаются allowlist.
Если runtime требует нормализации, она создаёт отдельный производный artifact;
оригинал остаётся временным immutable artifact до TTL.

## Личный benchmark

Нужны 50–100 вручную расшифрованных записей:

- комната, улица, машина, ветер и музыка;
- 5–10 секунд и длинный рассказ;
- время, граммы, калории, пульс, вес с десятичной частью;
- личные бренды и названия блюд;
- упражнения, подходы, повторы и RIR/RPE;
- лекарства и дозы, только если домен включён;
- русско-английские вкрапления;
- «планирую», «не делал», «кажется» и исправления самого себя.

Измерять:

- normalized WER;
- точность критичных чисел и единиц;
- пропуски и hallucinated text;
- p50/p95 latency;
- RAM/VRAM и CPU/GPU load;
- процент записей, потребовавших correction;
- размер очереди при серии записей или импортов.

Главная продуктовая метрика — не WER сам по себе, а доля правильно сохранённых
фактов без ручного исправления. ASR может ошибиться в пунктуации без вреда и
одновременно опасно перепутать `15` и `50`.

Полезно вести персональный словарь aliases продуктов и упражнений. Он помогает
сопоставлению после ASR; нельзя молча заменять transcript «наиболее похожим»
словом. Оригинальная расшифровка и нормализованное concept mapping хранятся
раздельно.

## Artifact chain

Для каждой попытки фиксируются:

```text
audio artifact ID и sha256
engine, exact model/checkpoint и runtime version
language, VAD/beam/temperature/compute settings
raw ASR response
transcript segments, word timestamps/probabilities
duration и processing latency
extractor model/rules/schema/config hash
validation report
```

Повтор с новой моделью создаёт новый transcript artifact. Подтверждённый
canonical event не меняется автоматически: новый результат можно сравнить и
предложить как correction.

## Извлечение фактов

Один transcript может породить несколько candidate facts:

```text
«Спал шесть с половиной часов, энергия семь.
 На завтрак моя овсянка. Вечером планирую бег».

→ sleep, observed
→ wellbeing, observed
→ meal, observed
→ workout, planned
```

Обязательные правила:

- статус каждого кандидата: `observed`, `planned`, `negated` или `uncertain`;
- отсутствующее значение — `null`, не догадка;
- каждое значимое поле имеет evidence excerpt и span;
- relative time вычисляется детерминированно относительно timestamp записи или
  импорта и timezone, а исходная фраза сохраняется;
- автоматическое дополнение из профиля запрещено;
- вычисление КБЖУ из локального рецепта — отдельный versioned transform;
- schema validation не считается проверкой истинности.

Машиночитаемая форма находится в
[extraction.schema.json](../schemas/extraction.schema.json), а multi-domain
пример — в [voice-extraction.json](../examples/voice-extraction.json).

Если применяется LLM с strict structured output:

- `additionalProperties: false` у закрытых объектов;
- все поля required, optional значения nullable;
- enum для статусов и единиц;
- bounds там, где они являются технически невозможными, а не медицинской
  «нормой»;
- один controlled retry после schema error;
- затем `needs_confirmation`, без свободного recovery JSON.

Официальная документация OpenAI подчёркивает, что соответствие
[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
схеме не гарантирует истинность содержания. Требования strict mode описаны в
[Function calling guide](https://developers.openai.com/api/docs/guides/function-calling#strict-mode).

## Независимый validator

До canonical layer:

1. Проверяет JSON Schema.
2. Проверяет, что каждый `evidence.quote` действительно есть в transcript и span
   не выходит за границы.
3. Разбирает числа и units без binary float.
4. Помечает невозможные комбинации warning, но не исправляет молча.
5. Проверяет temporal status: будущее событие не становится observed workout.
6. Разрешает aliases только к versioned local concepts.
7. Ставит `requires_confirmation` по критичности и ASR quality.
8. Проверяет idempotency группы по source capture/import/artifact.

`asr_confidence`, `extractor_uncertainty` и `validation_state` — три разных
показателя. Confidence разных ASR нельзя сравнивать как одну универсальную
вероятность.

## Политика подтверждения

В начале все voice groups показывают preview. После калибровки:

- обычная еда, заметки и тренировки с высокой уверенностью могут сохраняться
  сразу с доступным действием `Отменить`;
- неоднозначные количества уточняются одним вопросом;
- точные дозы лекарств всегда подтверждаются;
- low-confidence критичные числа подтверждаются;
- симптом сохраняется как сообщённый факт, но из него не строится диагноз;
- несколько событий можно отменить одной групповой кнопкой.

Пример:

```text
✅ Из голоса подготовлены 3 записи
• Завтрак: овсянка, обычная порция
• Самочувствие: энергия 7/10
• План: бег вечером
[Сохранить] [Исправить] [Только заметка]
```

## Облачный fallback

Cloud ASR/LLM — только явная настройка, не скрытый fallback. Перед включением
фиксируются provider, регион обработки, retention, training policy, endpoint,
стоимость и перечень передаваемых полей.

Варианты:

- [OpenAI Speech-to-text](https://developers.openai.com/api/docs/guides/speech-to-text)
  поддерживает русский и файлы до 25 MB;
- Yandex SpeechKit v3 поддерживает `ru-RU` и OGG Opus
  ([модели и языки](https://aistudio.yandex.ru/docs/en/speechkit/stt/models.html));
- другие провайдеры допускаются только после такого же privacy и quality audit.

Согласно актуальной официальной таблице
[OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data),
`/v1/audio/transcriptions` не используется для обучения по умолчанию, не имеет
application-state retention и совместим с Zero Data Retention. Это свойство
конкретного endpoint и текущей политики, его нужно перепроверять перед
включением. Для LLM extraction правила endpoint могут отличаться; local parser
остаётся предпочтительным.

Внешнему extractor передаётся минимально нужный transcript, без старой истории,
Android account, advertising ID и других device identifiers. Raw audio не
отправляется LLM повторно, если текст уже получен.

## Ошибки и fallback UX

| Ошибка | Поведение |
|---|---|
| входящий URI больше недоступен | показать ошибку импорта; при следующей попытке сразу копировать файл в app-private storage |
| ASR упал | retry с bounded backoff; затем ввод текстом/заметка |
| transcript пуст | не создавать факты, предложить прослушать/переписать |
| extraction invalid | один controlled retry, затем review |
| ambiguity | сохранить partial fact или задать один вопрос |
| пользователь исправил | новая event revision |
| audio TTL истёк | transcript и события остаются, replay audio больше невозможен |

Ни одна ошибка не должна отвечать пользователю «ничего не произошло», если
durable capture уже выполнен.

## Критерии готовности

- 100% принятых записей и audio imports имеют durable capture record;
- нет события без transcript/artifact provenance;
- планы и отрицания не превращаются в факты;
- критичные числа в gold corpus проходят выбранный порог;
- typical voice обрабатывается меньше 30 секунд;
- Android-действие `Отменить` отменяет всю группу без orphan facts;
- retry не создаёт дубли;
- TTL действительно удаляет blob и backup policy соответствует ему;
- модель/конфигурация закреплены версией и воспроизводимы.
