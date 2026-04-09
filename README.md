# RFT Monitor

Мониторинг задач в статусах тестирования Jira. Отправляет алерты в Telegram и отвечает на интерактивные команды.

## Отслеживаемые статусы

| Статус | Эмодзи |
|--------|--------|
| Ready for Testing | 🧪 |
| Ready for Review | 👀 |
| In Testing | ⚙️ |

Задача считается **просроченной**, если находится в статусе дольше `THRESHOLD_BUSINESS_DAYS` рабочих дней (по умолчанию 4).

## Стек

| Компонент | Технология |
|-----------|------------|
| Bot (основной) | Java 21 + Maven + telegrambots 6.9.7.1 |
| Bot (legacy) | Python 3.12 + pyTelegramBotAPI |
| База данных | MySQL (зеркало Jira) |
| Jira API | REST API v3 |
| Контейнеризация | Docker + Docker Compose |
| Дашборд | Looker Studio (Custom SQL) |

## Структура проекта

```
rft-monitor/
├── src/main/java/ru/entera/rftmonitor/
│   ├── Main.java                  — точка входа
│   ├── config/AppConfig.java      — конфигурация из .env
│   ├── model/Issue.java           — модель задачи
│   ├── client/
│   │   ├── JiraApiClient.java     — Jira REST API v3
│   │   └── MySqlRepository.java   — запросы к MySQL
│   ├── service/
│   │   ├── IssueService.java      — бизнес-логика
│   │   └── MessageBuilder.java    — форматирование сообщений
│   └── bot/MonitorBot.java        — Telegram бот
├── sql/
│   ├── looker_current.sql         — текущие задачи для Looker Studio
│   └── looker_history.sql         — история для Looker Studio
├── monitor.py                     — Python: cron-скрипт (плановый отчёт)
├── bot.py                         — Python: интерактивный бот (legacy)
├── Dockerfile                     — multi-stage сборка Java
├── docker-compose.yml
└── .env                           — конфигурация (не коммитится)
```

## Настройка

### 1. Переменные окружения

Скопируй `.env.example` в `.env` и заполни:

```env
# Jira
JIRA_URL=https://entera.atlassian.net
JIRA_EMAIL=your@email.com
JIRA_API_TOKEN=your_api_token

# Telegram
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_CHAT_ID=your_chat_id

# MySQL (зеркало Jira)
MYSQL_SERVER=mysql80.hostland.ru
MYSQL_PORT=3306
MYSQL_DBNAME=host1850712_jira
MYSQL_USERNAME=host1850712
MYSQL_PASSWORD=your_password

# Параметры мониторинга
THRESHOLD_BUSINESS_DAYS=4
TARGET_PERCENT=70
```

**Где получить:**
- Jira API token: https://id.atlassian.com/manage-profile/security/api-tokens
- Telegram bot token: [@BotFather](https://t.me/BotFather) → `/newbot`
- Telegram chat ID: напиши боту `/start`, затем `https://api.telegram.org/bot<TOKEN>/getUpdates`

### 2. Запуск

```bash
docker compose up -d
docker compose logs -f
```

Бот автоматически перезапускается при падении (`restart: unless-stopped`).

### 3. Плановые отчёты (опционально)

Для ежедневной рассылки через Python-скрипт:

```bash
pip install -r requirements.txt
bash setup_cron.sh   # добавляет в cron
```

## Команды бота

| Команда | Описание |
|---------|----------|
| `/start`, `/help` | Список команд |
| `/status` | Полный отчёт по всем 3 статусам с P70 |
| `/overdue` | Только просроченные задачи |
| `/stats` | Статистика по тестировщикам |

## Метрики

**On-time %** — доля задач, которые находятся в статусе ≤ `THRESHOLD_BUSINESS_DAYS` рабочих дней.

**P70** — 70-й перцентиль времени в статусе по закрытым задачам из `IssueStatusDurations`. Показывает, за сколько дней проходит 70% задач исторически.

## Схема БД (MySQL)

| Таблица | Назначение |
|---------|-----------|
| `IssuesInfo` | Основные поля задач |
| `DetailedIssuesChangelog` | История переходов между статусами |
| `IssueStatusDurations` | Время (в часах) в каждом статусе |
| `IssueSprints` | Принадлежность задач к спринтам |
| `Developer`, `Tester` | Назначенные люди |

> Данные обновляются раз в сутки ночью.

## Looker Studio

SQL-запросы для подключения дашборда находятся в `sql/`.  
Подробная инструкция по настройке: `Looker Studio — инструкция.md`.

**Подключение:** Looker Studio → Создать → MySQL → Custom Query → вставить содержимое файла.

| Файл | Использование |
|------|--------------|
| `sql/looker_current.sql` | Текущие задачи в статусах мониторинга |
| `sql/looker_history.sql` | История закрытых задач, P70, тренды |

## Разработка

```bash
# Сборка Java
mvn package -DskipTests

# Пересборка Docker-образа
docker compose up -d --build
```

Ветки:
- `master` — стабильная версия
- `develop` — текущая разработка
