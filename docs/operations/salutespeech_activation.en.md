# Voicing answers with SaluteSpeech (Sber)

[Русский](salutespeech_activation.md) · **English**

## What the code already does

The voice that reads Vedalina's answers in `POST /api/assistant/v1/voice/synthesize`
is chosen by a single variable — `VEDAL_TTS_PROVIDER`: `yandex` (Yandex
SpeechKit, as before) or `salute` (Sber SaluteSpeech). The controller sits
behind the `TextToSpeech` port; the implementations are `SpeechKitTextToSpeech`
(an adapter over the unchanged `SpeechKit`) and `SaluteSpeechTextToSpeech`.
The API contract does not change: whichever provider is selected, the endpoint
returns `audio/wav` (16-bit PCM, mono), so the frontend never notices.

The default is `yandex`. A stand without the variable sounds the way it did;
switching is a deliberate edit in `.env`.

**Speech recognition** (`/voice/recognize`) is not affected: it stays on
SpeechKit and `VEDAL_SPEECHKIT_API_KEY`. Voicing with Sber while recognising
with Yandex is fine; `GET /voice` reports `available: true` when at least one
of them is configured.

Differences visible from the outside:

| | SpeechKit | SaluteSpeech |
| --- | --- | --- |
| Access | static Api-Key | authorization key → 30-minute OAuth token (refreshed by the portal, `GigaChatAuth`) |
| Synthesis endpoint | `tts.api.cloud.yandex.net/speech/v1/tts:synthesize` | `smartspeech.sber.ru/rest/v1/text:synthesize` |
| Voice | `alena`, 16 kHz | `Nec_24000` (Natalia), `Bys_24000` (Boris), `May_24000`, `Tur_24000`, `Ost_24000`, `Pon_24000`; `_8000` variants exist |
| Per-request limit | ~5,000 UTF-8 bytes | 4,000 characters including spaces |
| TLS | stock JVM roots | needs the Russian Trusted Root CA — the same file as for GigaChat |

Answers longer than the limit are split by words, synthesised part by part
and the PCM is joined into one WAV — the same trick as with Yandex.

## Where to get the key

1. Sber developer studio: <https://developers.sber.ru/studio> → sign in with
   Sber ID → create a **SaluteSpeech API** project (not GigaChat API — it is a
   separate product with its own project and key).
2. In the project — "Authorization key": shown once, it is base64 of
   `client_id:client_secret`. Put it into `SALUTE_AUTH_KEY`. A GigaChat key
   does not work here: the OAuth endpoint is shared, the projects and scopes
   are not.
3. Scope (`SALUTE_SCOPE`) follows the contract type: `SALUTE_SPEECH_PERS`
   (individuals, for trials), `SALUTE_SPEECH_B2B` (prepaid), `SALUTE_SPEECH_CORP`
   (postpaid). A wrong scope is a `401` on the key exchange — the first thing
   to check on failure.

Never store the key in Jira, GitHub, e-mail or the repository. Pass it over a
secure channel only and put it into `backend/.env` on the VM or CI secrets —
exactly like the Yandex and GigaChat keys.

**Billing and limits.** PERS is for individuals, capped and not for commercial
use; B2B/CORP are billed per character. Sber's docs allow up to 10 parallel
synthesis streams for legal entities and 5 for individuals; the portal itself
holds at most three concurrent voice requests (`VoiceController`). Current
prices live on the SaluteSpeech page in the studio and are not copied here.

## Ministry of Digital Development certificate

`ngw.devices.sberbank.ru` and `smartspeech.sber.ru` are signed by the same
"Russian Trusted Root CA" chain as GigaChat. One file —
`backend/certs/russian_trusted_root_ca.pem` (how to obtain it:
`backend/certs/README.md`), shipped in the image at `/app/certs/`. An empty
`SALUTE_CA_BUNDLE` inherits `GIGACHAT_CA_BUNDLE`, so a stand where GigaChat is
already on needs nothing extra. The root is **added** to the stock roots, not
substituted; TLS verification is never disabled.

## Environment variables

```env
VEDAL_TTS_PROVIDER=salute
SALUTE_AUTH_KEY=<authorization key of the SaluteSpeech API project>
SALUTE_SCOPE=SALUTE_SPEECH_PERS       # or SALUTE_SPEECH_B2B / SALUTE_SPEECH_CORP
SALUTE_TTS_VOICE=Nec_24000            # Bys_24000, May_24000, … — no rebuild
SALUTE_TTS_FORMAT=wav16               # or pcm16; the endpoint returns audio/wav either way
SALUTE_CA_BUNDLE=                     # empty — same file as GIGACHAT_CA_BUNDLE
```

With `salute` the SpeechKit key is only needed for recognition. A typo in
`VEDAL_TTS_PROVIDER`, an empty `SALUTE_AUTH_KEY`, a scope outside the list or
the `opus` format fail startup with a message naming the variable — instead of
a 503 on the first synthesis request.

Variables reach the container through `backend/compose.yaml` (the voicing
block in the `portal` service) and the `compose.prod.yaml` /
`compose.stand-prod.yaml` overlays — a value in `.env` alone does not get in.

## Switching and rolling back

Two lines in `backend/.env` (`VEDAL_TTS_PROVIDER=salute`, `SALUTE_AUTH_KEY`;
`GIGACHAT_CA_BUNDLE` is already set if GigaChat is on), then:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml --profile app up -d
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml logs -f portal | grep -i -E "озвучивает|SaluteSpeech|GigaChat"
```

The startup log should say "Ведалина озвучивает ответы голосом SaluteSpeech
Nec_24000 (wav16)"; the first synthesis logs "Токен GigaChat получен …" (the
token exchanger is shared and the log line carries its class name).

Rollback — `VEDAL_TTS_PROVIDER=yandex` (or drop the variable) and the same `up -d`.

## Smoke test

```bash
curl -sS https://<domain>/api/assistant/v1/voice/synthesize \
  -H 'Content-Type: application/json' -H 'X-Voice-Consent: true' \
  -d '{"text":"Здравствуйте, я Ведалина."}' -o /tmp/vedalina.wav
file /tmp/vedalina.wav    # RIFF (little-endian) data, WAVE audio, mono 24000 Hz
```

A `502 Голос временно недоступен` instead of audio points to the portal log:
`OAuth-дверь GigaChat ответила 401` — key or scope (`SALUTE_SPEECH_*`, not
`GIGACHAT_API_*`); `PKIX` — certificate; `SaluteSpeech ответил 4xx/5xx` —
format, voice or package limit.

## Verified against the docs vs. assumptions

Checked at <https://developers.sber.ru/docs/ru/salutespeech/> (October 2026):
OAuth `POST https://ngw.devices.sberbank.ru:9443/api/v2/oauth` with
`Authorization: Basic`, `RqUID` (uuid4), `scope` from `SALUTE_SPEECH_PERS|B2B|CORP`,
30-minute token with `access_token`/`expires_at`; synthesis
`POST https://smartspeech.sber.ru/rest/v1/text:synthesize`, 4,000-character
limit, binary audio response; voice names; WAV16/PCM16/OPUS formats;
parallel-stream limits.

Assumptions (the reference page renders its request section client-side and
does not expose it): the query parameter names `format` and `voice`, and
`Content-Type: application/text` for plain text (`application/ssml` for
markup) come from Sber's examples and SDKs. If the endpoint answers `400`,
start there — all three live in `SaluteSpeechTextToSpeech`.

## Limitations

- `opus` is not enabled: `/voice/synthesize` is declared as `audio/wav`, and
  changing the response type is an API and frontend contract change.
- No SSML is sent: assistant answers are plain text.
- The token exchanger is the `GigaChatAuth` class; its log lines say
  "GigaChat" even when serving SaluteSpeech.
