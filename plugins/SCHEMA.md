# Маркетплейс плагинов OpenStation Max

Любой разработчик и пользователь может создать собственный плагин для Яндекс Станции Макс на OpenStation Max, протестировать его локально в веб-пульте (`Плагины → Добавить / Выложить`) и выложить в общий каталог.

## Быстрая публикация в каталог

### Способ 1 — Pull Request (для разработчиков)
1. Сделайте форк репозитория `Olegu621/OPENSTATION`.
2. Создайте папку `plugins/<plugin-id>/` (только строчные латинские буквы, цифры, `-`, `_`, от 2 до 48 символов).
3. Положите внутрь файл `plugin.json` (и при необходимости скрипты `.sh` / `README.md`).
4. Запустите генерацию индекса и проверку схемы:
   ```bash
   python scripts/build_plugins_index.py
   python -m unittest plugins/tests/test_plugins_catalog.py -v
   ```
5. Откройте Pull Request в ветку `main`. После принятия PR плагин сразу появится в каталоге **на всех станциях пользователей** без перепрошивки!

### Способ 2 — Через GitHub Issue (без git)
Если не хотите работать с Pull Request, откройте Issue по шаблону **«Публикация плагина»** (прямая ссылка есть в веб-пульте станции в разделе `Плагины → Выложить плагин`) и вставьте свой `plugin.json`.

---

## Формат `plugin.json` (v1)

```json
{
  "schemaVersion": 1,
  "id": "my-awesome-plugin",
  "name": "Мой плагин",
  "version": "1.0.0",
  "author": "YourGitHub",
  "category": "Утилиты",
  "icon": "🧩",
  "description": "Короткое описание для карточки в маркетплейсе.",
  "homepage": "https://github.com/Olegu621/OPENSTATION/tree/main/plugins/my-awesome-plugin",
  "assistantHint": "Когда пользователь спрашивает ..., вызови plugin_my_tool.",
  "settingsSchema": [
    {
      "key": "api_key",
      "label": "API ключ",
      "type": "secret",
      "default": "",
      "placeholder": "sk-...",
      "help": "Хранится только локально на станции и скрывается в API"
    }
  ],
  "tools": [
    {
      "name": "plugin_my_tool",
      "description": "Описание инструмента для голосового ассистента",
      "parameters": {
        "query": { "type": "STRING", "description": "Параметр запроса" }
      },
      "required": ["query"],
      "runner": {
        "type": "http",
        "method": "GET",
        "url": "https://api.example.com/q?text={{args.query}}&key={{settings.api_key}}"
      }
    }
  ],
  "actions": [
    {
      "id": "test",
      "label": "Проверить работу",
      "runner": {
        "type": "http",
        "method": "GET",
        "url": "https://api.example.com/health"
      }
    }
  ]
}
```

### Поддерживаемые `runner.type`
- **`http`**: HTTP/HTTPS-запросы (`method`, `url`, `headers`, `body`, `insecure`, `timeoutMs`). Поддерживает шаблоны `{{args.key|default}}` и `{{settings.key|default}}`.
- **`wol`**: Встроенная отправка Wake-on-LAN Magic Packet (`mac`, `broadcast`, `port`).
- **`mqtt`**: Публикация в подключённый MQTT-брокер (`topic`, `payload`, `retain`).
- **`station`**: Цепочка встроенных действий станции (`steps`: `set_led_ring`, `set_led_screen`, `show_screen_banner`, `set_volume`, `set_eq_preset`, `manage_apps`, `music_control`, `tv_hdmi_cec`, и др.).
- **`shell`**: Запуск shell-команды или приложенного скрипта `.sh` из `files[]` с таймаутом (`OPENSTATION_PLUGIN_ID`, `OPENSTATION_ARGS_JSON`, `OPENSTATION_SETTINGS_JSON`).

### Поддерживаемые поля
- **Категории:** `Алиса`, `Умный дом`, `Медиа`, `Подсветка`, `Утилиты`
- **Типы в `settingsSchema`:** `string`, `secret`, `number`, `bool`, `select` (с массивом `options`)
- **Хуки жизненного цикла (`hooks`):** `onEnable`, `onDisable`
- **Фоновое расписание (`schedule`):** `{ "intervalSec": 300, "runner": { ... } }`
