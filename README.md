# RFT Monitor

Мониторинг задач в статусах тестирования Jira. Отправляет алерты в Telegram и отвечает на интерактивные команды.

## Фильтрация задач

Все запросы к Jira ограничены:
- **Проект:** `EN` (настраивается через `JIRA_PROJECT`)
- **Спринт:** только активный (`openSprints()`)

## Отслеживаемые статусы

| Статус | Эмодзи |
|--------|--------|
| Ready for Review | 👀 |
| Under Review | 🔍 |
| Ready for Testing | 🧪 |
| In Testing | ⚙️ |

Задача считается **просроченной**, если находится в статусе дольше `THRESHOLD_BUSINESS_DAYS` рабочих дней (по умолчанию 4).

## Зависшие задачи

Отдельный отчёт (`/stale`) выявляет задачи с застрявшим статусом:

| Категория | Статусы | Порог |
|-----------|---------|-------|
| В работе | In Progress, In Review, Under Review, In Testing | > 2 раб. дн. |
| Долгая очередь | Ready for Review, Ready for Testing | > 4 раб. дн. |
| Покинутые | Все нетерминальные | > 8 раб. дн. |

## Стек

| Компонент | Технология |
|-----------|------------|
| Bot | Java 21 + Maven + telegrambots 6.9.7.1 |
| База данных | MySQL (зеркало Jira) |
| Jira API | REST API v3: поиск — `POST /rest/api/3/search/jql`, changelog — `GET /rest/api/3/issue/{key}/changelog` |
| Контейнеризация | Docker + Docker Compose |
| Дашборд | Looker Studio (Custom SQL) |

## Структура проекта

```
rft-monitor/
├── src/
│   ├── main/java/ru/entera/rftmonitor/
│   │   ├── Main.java                          — точка входа
│   │   ├── config/AppConfig.java              — конфигурация из .env
│   │   ├── model/
│   │   │   ├── Issue.java                     — задача с просрочкой
│   │   │   ├── StaleIssue.java                — зависшая задача
│   │   │   └── StaleReport.java               — отчёт по зависшим
│   │   ├── client/
│   │   │   ├── JiraApiClient.java             — Jira REST API v3
│   │   │   └── MySqlRepository.java           — запросы к MySQL
│   │   ├── service/
│   │   │   ├── IssueService.java              — бизнес-логика
│   │   │   ├── MessageBuilder.java            — HTML для Telegram
│   │   │   └── MattermostMessageBuilder.java  — Markdown для Mattermost
│   │   └── bot/
│   │       ├── MonitorBot.java                — Telegram бот
│   │       └── MattermostBot.java             — Mattermost slash-команды
│   └── test/java/ru/entera/rftmonitor/service/
│       ├── IssueServiceGetIssuesTest.java
│       ├── IssueServiceStaleReportTest.java
│       ├── MessageBuilderStaleReportTest.java
│       └── MattermostMessageBuilderStaleReportTest.java
├── sql/
│   ├── looker_current.sql                     — текущие задачи для Looker Studio
│   └── looker_history.sql                     — история для Looker Studio
├── Dockerfile
├── docker-compose.yml
└── .env                                       — конфигурация (не коммитится)
```

## Настройка

### 1. Переменные окружения

Скопируй `.env.example` в `.env` и заполни:

```env
# Jira
JIRA_URL=https://entera.atlassian.net
JIRA_PROJECT=EN
JIRA_EMAIL=your@email.com
JIRA_API_TOKEN=your_api_token

# Telegram
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_CHAT_ID=your_chat_id

# MySQL (зеркало Jira, обновляется раз в сутки ночью)
MYSQL_SERVER=mysql80.hostland.ru
MYSQL_PORT=3306
MYSQL_DBNAME=host1850712_jira
MYSQL_USERNAME=host1850712
MYSQL_PASSWORD=your_password

# Параметры мониторинга
THRESHOLD_BUSINESS_DAYS=4
TARGET_PERCENT=70

# Mattermost (опционально)
MATTERMOST_ENABLED=false
MATTERMOST_URL=http://localhost:8065
MATTERMOST_PORT=8080
MATTERMOST_TOKEN=your_slash_command_token
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

## Команды бота

| Команда | Описание |
|---------|----------|
| `/start`, `/help` | Список команд |
| `/status` | Полный отчёт по всем 4 статусам с P70 + зависшие задачи |
| `/review` | Отчёт: Ready for Review + Under Review |
| `/testing` | Отчёт: Ready for Testing + In Testing |
| `/stale` | Зависшие задачи по категориям |

## Метрики

**On-time %** — доля задач в статусе ≤ `THRESHOLD_BUSINESS_DAYS` рабочих дней.

**P70** — 70-й перцентиль времени в статусе по задачам проекта EN, которые покинули статус за последние 3 месяца. Источник: `IssueStatusDurations` + `DetailedIssuesChangelog`. Конвертация: `часы / 24 × 5/7`. Рассчитывается для каждого из 4 статусов.

## Схема БД (MySQL)

| Таблица | Назначение |
|---------|-----------|
| `IssuesInfo` | Основные поля задач |
| `DetailedIssuesChangelog` | История переходов между статусами |
| `IssueStatusDurations` | Время (в часах) в каждом статусе |
| `IssueSprints` | Принадлежность задач к спринтам |
| `Developer`, `Tester` | Назначенные люди |

> Данные обновляются раз в сутки ночью. Если задача перешла в статус после последней синхронизации, бот автоматически получает дату перехода через Jira API (`/issue/{key}/changelog`) в реальном времени.

## Mattermost (опционально)

Установи `MATTERMOST_ENABLED=true` и настрой slash-команды в Mattermost → Integrations → Slash Commands:

| Команда | URL |
|---------|-----|
| `/rft-status` | `http://<host>:<MATTERMOST_PORT>/mattermost` |
| `/rft-review` | `http://<host>:<MATTERMOST_PORT>/mattermost` |
| `/rft-testing` | `http://<host>:<MATTERMOST_PORT>/mattermost` |
| `/rft-stale` | `http://<host>:<MATTERMOST_PORT>/mattermost` |

Подробная инструкция: `Mattermost — инструкция.md`.

## Looker Studio

SQL-запросы для подключения дашборда находятся в `sql/`.  
Подробная инструкция: `Looker Studio — инструкция.md`.

**Подключение:** Looker Studio → Создать → MySQL → Custom Query → вставить содержимое файла.

| Файл | Использование |
|------|--------------|
| `sql/looker_current.sql` | Текущие задачи в статусах мониторинга |
| `sql/looker_history.sql` | История закрытых задач, P70, тренды |

## Разработка

```bash
# Сборка и тесты
mvn package

# Только сборка без тестов
mvn package -DskipTests

# Пересборка Docker-образа
docker compose up -d --build
```

Ветки:
- `master` — стабильная версия
- `develop` — текущая разработка
