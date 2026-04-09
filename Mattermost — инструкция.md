# Подключение к Mattermost

## Как это работает

Mattermost отправляет POST-запрос на HTTP-сервер бота при вводе slash-команды.
Бот обрабатывает запрос и возвращает ответ прямо в канал.

```
Пользователь → /rft-status → Mattermost → POST http://your-server:8080/mattermost → Bot → ответ в канал
```

## Шаг 1 — Включить в .env

```env
MATTERMOST_ENABLED=true
MATTERMOST_PORT=8080
MATTERMOST_TOKEN=your_secret_token   # скопировать из настроек команды в Mattermost
```

## Шаг 2 — Открыть порт в docker-compose.yml

Раскомментировать или добавить:

```yaml
services:
  bot:
    ...
    ports:
      - "8080:8080"
```

## Шаг 3 — Зарегистрировать slash-команды в Mattermost

Открыть: **Main Menu → Integrations → Slash Commands → Add Slash Command**

Создать три команды:

| Поле | /rft-status | /rft-overdue | /rft-stats |
|------|-------------|--------------|------------|
| **Command trigger word** | `rft-status` | `rft-overdue` | `rft-stats` |
| **Request URL** | `http://YOUR_SERVER:8080/mattermost` | ← то же | ← то же |
| **Request method** | POST | POST | POST |
| **Response username** | `RFT Monitor` | ← то же | ← то же |
| **Autocomplete** | ✅ | ✅ | ✅ |
| **Autocomplete description** | Полный отчёт | Просроченные задачи | Статистика по тестировщикам |

После создания каждой команды скопировать **Token** и вставить в `.env` как `MATTERMOST_TOKEN`.

> Если токены у команд разные — используй токен от `/rft-status` (или отключи проверку, оставив `MATTERMOST_TOKEN` пустым).

## Шаг 4 — Перезапустить контейнер

```bash
docker compose up -d --build
```

## Проверка

```bash
# Проверить что сервер отвечает
curl -X POST http://localhost:8080/mattermost \
  -d "command=/rft-status&token=your_secret_token"
```

## Команды

| Slash-команда | Описание |
|---------------|----------|
| `/rft-status` | Полный отчёт по всем статусам |
| `/rft-overdue` | Только просроченные задачи |
| `/rft-stats` | Статистика по тестировщикам |

## Требования к серверу

Mattermost должен иметь сетевой доступ к серверу с ботом на порту `MATTERMOST_PORT`.
Если бот и Mattermost на одном сервере — использовать `http://localhost:8080/mattermost`.
