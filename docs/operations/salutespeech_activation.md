# Озвучивание ответов через SaluteSpeech (Сбер)

**Русский** · [English](salutespeech_activation.en.md)

## Что уже делает код

Голос, которым Ведалина читает ответы в `POST /api/assistant/v1/voice/synthesize`,
выбирается одной переменной — `VEDAL_TTS_PROVIDER`: `yandex` (Yandex SpeechKit,
как было) или `salute` (SaluteSpeech Сбера). Контроллер стоит за портом
`TextToSpeech`, реализации — `SpeechKitTextToSpeech` (переходник к прежнему
`SpeechKit`, который не менялся) и `SaluteSpeechTextToSpeech`. Контракт двери
не меняется: при любом провайдере наружу уходит `audio/wav` (PCM 16 бит,
моно), и фронту переключение не видно.

Умолчание — `yandex`. Стенд, где переменная не задана, звучит тем же,
чем звучал; переключение — осознанное действие в `.env`.

**Распознавание речи** (`/voice/recognize`) эта настройка не затрагивает:
оно остаётся на SpeechKit и ключе `VEDAL_SPEECHKIT_API_KEY`. Можно озвучивать
Сбером, а распознавать Яндексом; `GET /voice` отвечает `available: true`,
если настроен хотя бы один из них.

Отличия, которые видны снаружи:

| | SpeechKit | SaluteSpeech |
| --- | --- | --- |
| Доступ | статический Api-Key | ключ авторизации → OAuth-токен на 30 минут (портал обновляет сам, `GigaChatAuth`) |
| Дверь синтеза | `tts.api.cloud.yandex.net/speech/v1/tts:synthesize` | `smartspeech.sber.ru/rest/v1/text:synthesize` |
| Голос | `alena`, 16 кГц | `Nec_24000` (Наталья), `Bys_24000` (Борис), `May_24000`, `Tur_24000`, `Ost_24000`, `Pon_24000`; есть варианты `_8000` |
| Предел на запрос | ~5 000 байт UTF-8 | 4 000 символов, включая пробелы |
| TLS | штатные корни JVM | нужен корень Минцифры «Russian Trusted Root CA» — тот же файл, что для GigaChat |

Ответы длиннее предела портал режет по словам на части, озвучивает по
очереди и склеивает PCM в один WAV — тот же приём, что у Яндекса.

## Где взять ключ

1. Кабинет разработчика Сбера: <https://developers.sber.ru/studio> → войти
   по Сбер ID → создать проект **SaluteSpeech API** (не GigaChat API — это
   отдельный продукт со своим проектом и своим ключом).
2. В проекте — «Ключ авторизации» (Authorization key): кабинет показывает его
   один раз, это base64 пары `client_id:client_secret`. Его и кладём
   в `SALUTE_AUTH_KEY`. Ключ GigaChat сюда не подходит: OAuth-дверь у двух
   сервисов одна, но проекты и области доступа разные.
3. Область доступа (`SALUTE_SCOPE`) — по типу договора: `SALUTE_SPEECH_PERS`
   (физлицо, для пробы), `SALUTE_SPEECH_B2B` (ИП и юрлицо, предоплата),
   `SALUTE_SPEECH_CORP` (юрлицо, постоплата). Не та область — `401` на обмене
   ключа; это первая вещь, которую стоит проверить при отказе.

Ключ не хранить в Jira, GitHub, почте и репозитории. Передавать только
защищённым каналом и класть в `backend/.env` на ВМ или в секреты CI —
ровно как ключи Яндекса и GigaChat.

**Про оплату и лимиты.** Пакет PERS — для физлиц, с ограничениями на объём
и не для коммерческого использования; B2B/CORP тарифицируются по символам.
Параллельных потоков синтеза у юрлица до 10, у физлица до 5 (документация
Сбера); портал и так держит не больше трёх одновременных голосовых запросов
(`VoiceController`). Актуальные тарифы — на странице SaluteSpeech в кабинете,
здесь они не переписываются, чтобы не устареть.

## Сертификат Минцифры

Двери `ngw.devices.sberbank.ru` и `smartspeech.sber.ru` подписаны той же
цепочкой «Russian Trusted Root CA», что и GigaChat. Файл один —
`backend/certs/russian_trusted_root_ca.pem` (как его получить —
`backend/certs/README.md`), в образе он лежит в `/app/certs/`.
`SALUTE_CA_BUNDLE` пустой наследует `GIGACHAT_CA_BUNDLE`, поэтому на стенде,
где GigaChat уже включён, отдельно задавать ничего не нужно. Корень
**добавляется** к штатным, а не заменяет их; проверка TLS не выключается.

## Переменные окружения

```env
VEDAL_TTS_PROVIDER=salute
SALUTE_AUTH_KEY=<ключ авторизации проекта SaluteSpeech API>
SALUTE_SCOPE=SALUTE_SPEECH_PERS       # или SALUTE_SPEECH_B2B / SALUTE_SPEECH_CORP
SALUTE_TTS_VOICE=Nec_24000            # Bys_24000, May_24000, … — без пересборки
SALUTE_TTS_FORMAT=wav16               # или pcm16; наружу всё равно audio/wav
SALUTE_CA_BUNDLE=                     # пусто — тот же файл, что GIGACHAT_CA_BUNDLE
```

Ключ SpeechKit при `salute` нужен только для распознавания. Опечатка
в `VEDAL_TTS_PROVIDER`, пустой `SALUTE_AUTH_KEY`, область не из списка
или формат `opus` роняют старт с текстом, называющим переменную, — а не
отдают посетителю 503 при первой просьбе озвучить ответ.

Переменные доезжают до контейнера через `backend/compose.yaml` (блок
«Озвучивание» в сервисе `portal`) и наложения `compose.prod.yaml`,
`compose.stand-prod.yaml` — значение в `.env` само по себе внутрь не попадает.

## Как переключить и как откатить

Переключение — две строки в `backend/.env` (`VEDAL_TTS_PROVIDER=salute`,
`SALUTE_AUTH_KEY`; `GIGACHAT_CA_BUNDLE` уже задан, если включён GigaChat), затем:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml --profile app up -d
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml logs -f portal | grep -i -E "озвучивает|SaluteSpeech|GigaChat"
```

В журнале старта должно быть «Ведалина озвучивает ответы голосом SaluteSpeech
Nec_24000 (wav16)», при первом озвучивании — «Токен GigaChat получен,
действует до …» (обменник токена общий, и строка журнала называет его
по имени класса).

Откат — `VEDAL_TTS_PROVIDER=yandex` (или убрать переменную) и тот же `up -d`.

## Smoke-test после включения

```bash
curl -sS https://<домен>/api/assistant/v1/voice/synthesize \
  -H 'Content-Type: application/json' -H 'X-Voice-Consent: true' \
  -d '{"text":"Здравствуйте, я Ведалина."}' -o /tmp/vedalina.wav
file /tmp/vedalina.wav    # RIFF (little-endian) data, WAVE audio, mono 24000 Hz
```

Если вместо звука пришёл `502 Голос временно недоступен`, причина в журнале
портала: `OAuth-дверь GigaChat ответила 401` — ключ или область доступа
(`SALUTE_SPEECH_*`, не `GIGACHAT_API_*`); `PKIX` — сертификат;
`SaluteSpeech ответил 4xx/5xx` — формат, голос или лимит пакета.

## Что проверено по документации, а что — допущение

Сверено с <https://developers.sber.ru/docs/ru/salutespeech/> (октябрь 2026):
OAuth-дверь `POST https://ngw.devices.sberbank.ru:9443/api/v2/oauth`
с `Authorization: Basic`, `RqUID` (uuid4), `scope` из `SALUTE_SPEECH_PERS|B2B|CORP`,
токен на 30 минут и поля `access_token`/`expires_at`; дверь
`POST https://smartspeech.sber.ru/rest/v1/text:synthesize`, предел 4 000
символов, ответ — байты звука; имена голосов; форматы WAV16/PCM16/OPUS;
лимиты параллельных потоков.

Допущения (в справочнике страница запроса рендерится скриптом и наружу
не отдаётся): имена query-параметров `format` и `voice` и значение
`Content-Type: application/text` для простого текста (`application/ssml`
для разметки) взяты из примеров и SDK Сбера. Если дверь ответит `400`,
начинать разбор стоит с них — все три задаются в `SaluteSpeechTextToSpeech`.

## Ограничения

- `opus` не включён: дверь `/voice/synthesize` объявлена как `audio/wav`,
  и смена типа ответа — это изменение контракта API и фронта.
- SSML портал не шлёт: ответы ассистента — обычный текст.
- Обменник токена — класс `GigaChatAuth`; его строки журнала говорят «GigaChat»
  и при работе SaluteSpeech.
