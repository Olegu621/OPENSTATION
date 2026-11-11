# OpenStation Max — Открытая ОС и ассистент для Яндекс Станции Макс (`yandexstation_2`)

Полностью автономная альтернатива лаунчеру и сервисам Яндекса для **Яндекс Станции Макс** (`yandexstation_2`, Amlogic S905X2 `g12a`, `arm64-v8a`, Android 9 / API 28), сохраняющая нативную работу всего железа и добавляющая голосового ассистента «Алиса» на базе **Gemini Live**, встроенный Hi-Fi плеер Яндекс Музыки, веб-пульт и интерфейс в стиле BORK × Duo Max.

## Возможности и компоненты

1. **[`daemon/`](daemon/)** — корневой нативный C++17 сервис `openstationd` (ARM64 static-friendly NDK binary):
   - **Голосовой ассистент «Алиса» (Gemini Live + `sherpa-onnx`):** локальный офлайн wake-word (`ru-vosk-small` + `silero_vad`) на слове «Алиса», живой стриминг аудио/голоса в Gemini Live API, стриминг транскрипции и уровня речи (`ttsRms`/`micRms`) в лаунчер по UDP `8086`, полноценный tool-calling (управление музыкой, громкостью, EQ, LED-кольцом, экраном, таймерами, HDMI-CEC, запуском приложений, поиском в интернете `web_search`/`get_weather`/`fetch_url`/`http_request` и `shell_exec`).
   - **LED-кольцо (`fb4`, 24 RGB светодиода):** `rainbow`, `solid`, `breathe`, `spinner`, `music` (живой 24-полосный анализатор спектра), `off`, автояркость по датчику освещённости `ltr308als01`, кольцевая индикация громкости при вращении энкодера, красная пульсация при Mute.
   - **Фронтальный LED-экран (`fb5`):** динамическое определение подключения (`YNDX LED screen`); вывод часов, бегущей кириллицы/ASCII, эквалайзера и баннеров ассистента. При отсоединённом шлейфе матрицы демон безопасно ждёт переподключения.
   - **Физическое управление и звук:** мониторинг `/dev/input/event*` (кольцо громкости `rotary-volume`, кнопки Mute/Action `gpio_keypad`, датчик 3.5 мм джека), управление усилителями SY6045S и ЦАП ES8156, режимы микрофонов `aaudio` (`libyandexvqe`) и `alsa_16ch` (7 PDM-микрофонов + TDM loopback AEC).
   - **Умный дом и сеть:** Home Assistant Wyoming Satellite (`TCP :10700`), MQTT Auto-Discovery, mDNS `station-max.local` (`UDP :5353`), виртуальный `/dev/uinput` пульт, root HDMI-CEC (`/sys/class/cec/cmd`), DNS helper для Gemini (`dns.comss.one`).
   - **Веб-пульт (`http://<IP>:8080`):** адаптивная панель с управлением музыкой, ассистентом, кольцом, эквалайзером, матрицей, пультом, вводом текста на ТВ и логами по WebSocket.

2. **[`launcher/`](launcher/)** — Android TV лаунчер `OpenStationLauncher` (`com.openstation.launcher`):
   - **3-экранный интерфейс с морфингом живой сферы (`AliceOrbView`):**
     - **«Сфера» (главный экран):** крупная 60fps сфера на базе 188-кадровых WebP-спрайтов, переливающаяся, ускоряющаяся и пульсирующая в такт музыке и голосу, с карточкой живого ответа ассистента под ней.
     - **«Музыка и ТВ» + «Станция»:** при перелистывании экранов (пультом `Влево`/`Вправо`, вкладками или свайпом) сфера плавно уменьшается и переходит в нижний док-бар, справа от неё остаётся текст ответа ассистента.
     - **Глобальный оверлей (`AliceOverlayManager`):** при вызове «Алисы» поверх любых других приложений (YouTube, плееры, настройки) плавно выезжает нижний бар со сферой и текстом ответа, а после завершения плавно скрывается.
   - **Нативный плеер Яндекс Музыки (`YandexMusicPlayer` / `YandexMusicActivity`):** «Моя волна» с выбором настроения, поиск, лайки, винил-режим, обложки, Media3 ExoPlayer (включая FLAC/AAC и AES-CTR прокси), вход по OAuth/QR.
   - **Фоновый сервис `StationAudioService`:** системный эквалайзер (`DynamicsProcessing` API 28 с откатом на `Equalizer` + `BassBoost`), 24-полосный `Visualizer(0)` мост и синхронизация громкости с LED-кольцом.
   - **Сетка приложений, QR веб-пульта и быстрые аппаратные/системные настройки TV.**

3. **[`ime/`](ime/)** — офлайн Android TV клавиатура `OpenStationIME` (`com.openstation.ime`) с раскладками RU / EN / 123 и приёмом UTF-8 текста с веб-пульта (`UDP :8085`).

4. **[`scripts/`](scripts/)** — сценарии автоматической сборки, деплоя ([`deploy.ps1`](scripts/deploy.ps1)), сборки веб-ресурсов музыки ([`prepare_ym_app.py`](scripts/prepare_ym_app.py)) и полного отката к стоку ([`rollback.ps1`](scripts/rollback.ps1)).

## Быстрая установка

Требуется подключённый ADB с правами root (`uid=0`), Android NDK, CMake, Ninja, JDK 17 и Gradle:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy.ps1 -GeminiApiKey "ВАШ_GEMINI_API_KEY"
```

Либо задайте ключ через переменную окружения `$env:GEMINI_API_KEY`:

Дополнительные флаги `deploy.ps1`:
- `-SkipBuild` — пропустить пересборку и задеплоить уже собранные бинарники/APK;
- `-NoReboot` — не перезагружать автоматически по завершении;
- `-DeviceSerial <id>` — указать конкретный ADB serial.

## Полный откат к стоку Яндекса

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\rollback.ps1
```

Добавьте флаг `-UninstallApps`, чтобы удалить `OpenStationLauncher`, `OpenStationIME` и `/data/local/openstation`.

## Аппаратные особенности

- **ИК-передатчик отсутствует** — управление телевизором работает по **HDMI-CEC** (`/sys/class/cec/cmd`).
- **Фронтальный LED-экран (`fb5`):** включается автоматически при подключённом шлейфе матрицы.
- **BLE-пульт:** стандартные Bluetooth-пульты работают через нативный стек Android TV.
