# Z7 OS — оболочка для XGIMI Z7X

Своя ОС-оболочка для проектора XGIMI Z7X (китайская GMUI, Android 9, MediaTek MT9669): без рекламы и китайских сервисов, на русском и английском, с AirPlay, полными настройками проектора и собственными автообновлениями.

Прошивка, загрузчик и ядро **не модифицируются**: все изменения обратимы.

## Сборка ОС

Монорепо на Kotlin/Gradle. Модули — в `app/*` (сейчас `agent`). APK собираются в GitHub Actions (локально Android SDK не нужен), забираются и ставятся на проектор по ADB.

- `app/agent` — ядро-агент (`os.z7.agent`): фоновый сервис, локальный ADB-мост (dadb → 127.0.0.1:5555), держит каст живым после загрузки. Дальше: обновлятор, лаунчер, настройки.
- CI: [.github/workflows/android.yml](.github/workflows/android.yml) → артефакт `agent-apk`.
- Установка на проектор: `adb -s <ip>:5555 install -r agent-release.apk`.

## Документы

1. [Исследование](docs/01-research.md) — что за устройство, что известно, почему такой подход
2. [Безопасность / анти-кирпич](docs/02-safety.md) — обязательные правила и лестница восстановления
3. [Вводные и план](docs/03-inputs-and-plan.md) — что нужно от владельца, фазы работ
4. [Техническое задание](docs/04-TZ.md)

## Скрипты

- `scripts/find-projector.ps1` — найти IP проектора в сети по MAC и проверить порт ADB.
- `scripts/collect-device-info.ps1 -Ip <IP> [-PullApks]` — слепок состояния устройства по ADB (только чтение).
- `scripts/cast-on.ps1` — поднять AirPlay после ребута; `-Miracast` — включить режим Windows-трансляции.
- `scripts/restore.ps1` — аварийный откат к стоку.

Каст работает (AirPlay iOS 27 + Miracast Win+K) — см. [docs/cast-setup.md](docs/cast-setup.md).

adb: `D:\tools\platform-tools\adb.exe`.
