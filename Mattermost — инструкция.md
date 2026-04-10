# Подключение к Mattermost

## Как это работает

Mattermost отправляет POST-запрос на HTTP-сервер бота при вводе slash-команды.
Бот обрабатывает запрос и возвращает ответ прямо в канал.

```
Пользователь → /rft-status → Mattermost → POST http://your-server:8080/mattermost → Bot → ответ в канал
```

Все slash-команды указывают на **один и тот же URL** — бот различает их по полю `command` в теле запроса.

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

Создать четыре команды (URL один и тот же для всех):

| Поле | Значение |
|------|----------|
| **Request URL** | `http://YOUR_SERVER:8080/mattermost` |
| **Request method** | POST |
| **Response username** | `RFT Monitor` |
| **Autocomplete** | ✅ |

| Command trigger word | Autocomplete description |
|----------------------|--------------------------|
| `rft-status` | Полный отчёт по всем статусам с P70 |
| `rft-review` | Отчёт: Ready for Review + Under Review |
| `rft-testing` | Отчёт: Ready for Testing + In Testing |
| `rft-stale` | Зависшие задачи по категориям |

Mattermost выдаёт **отдельный токен для каждой команды**. Скопировать все токены и перечислить через запятую в `.env`:

```env
MATTERMOST_TOKEN=токен1,токен2,токен3,токен4
```

## Шаг 4 — Перезапустить контейнер

```bash
docker compose up -d --build
```

## Проверка

```bash
curl -X POST http://localhost:8080/mattermost \
  -d "command=/rft-status&token=your_secret_token"
```

## Команды

| Slash-команда | Описание |
|---------------|----------|
| `/rft-status` | Полный отчёт по всем 4 статусам с P70 + зависшие задачи |
| `/rft-review` | Отчёт: Ready for Review + Under Review |
| `/rft-testing` | Отчёт: Ready for Testing + In Testing |
| `/rft-stale` | Зависшие задачи по категориям |

## Требования к серверу

Mattermost должен иметь сетевой доступ к серверу с ботом на порту `MATTERMOST_PORT`.
Если бот и Mattermost на одном сервере — использовать `http://localhost:8080/mattermost`.
