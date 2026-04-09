#!/usr/bin/env python3
"""
RFT Monitor
Следит за задачами в статусах "Ready for Testing", "Ready for Review", "In Testing".
Алертит в Telegram если задача висит дольше THRESHOLD_BUSINESS_DAYS рабочих дней.
Считает 70-й перцентиль по истории и текущий % on-time.
"""

import os
import sys
import requests
import mysql.connector
import numpy as np
from datetime import datetime, timedelta
from requests.auth import HTTPBasicAuth
from dotenv import load_dotenv

load_dotenv()

JIRA_URL        = os.getenv("JIRA_URL", "https://entera.atlassian.net")
JIRA_EMAIL      = os.getenv("JIRA_EMAIL")
JIRA_API_TOKEN  = os.getenv("JIRA_API_TOKEN")

TELEGRAM_TOKEN  = os.getenv("TELEGRAM_BOT_TOKEN")
TELEGRAM_CHAT   = os.getenv("TELEGRAM_CHAT_ID")

MYSQL_HOST      = os.getenv("MYSQL_SERVER")
MYSQL_PORT      = int(os.getenv("MYSQL_PORT", "3306"))
MYSQL_DB        = os.getenv("MYSQL_DBNAME")
MYSQL_USER      = os.getenv("MYSQL_USERNAME")
MYSQL_PASS      = os.getenv("MYSQL_PASSWORD")

THRESHOLD       = int(os.getenv("THRESHOLD_BUSINESS_DAYS", "4"))
TARGET_PCT      = int(os.getenv("TARGET_PERCENT", "70"))

MONITORED_STATUSES = ["Ready for Testing", "Ready for Review", "In Testing"]

STATUS_EMOJI = {
    "Ready for Testing": "🧪",
    "Ready for Review":  "👀",
    "In Testing":        "⚙️",
}


# ---------------------------------------------------------------------------
# Утилиты
# ---------------------------------------------------------------------------

def business_days(start: datetime, end: datetime) -> int:
    """Количество рабочих дней (пн–пт) между двумя датами."""
    days = 0
    cur = start.date()
    end_d = end.date()
    while cur < end_d:
        if cur.weekday() < 5:
            days += 1
        cur += timedelta(days=1)
    return days


def calendar_hours_to_bdays(hours: float) -> float:
    """Перевод календарных часов в рабочие дни (приближённо, 5/7 недели)."""
    return hours / 24 * (5 / 7)


# ---------------------------------------------------------------------------
# Jira
# ---------------------------------------------------------------------------

def get_issues_from_jira() -> list[dict]:
    """Базовые поля задач из Jira API (без changelog)."""
    auth = HTTPBasicAuth(JIRA_EMAIL, JIRA_API_TOKEN)
    url  = f"{JIRA_URL}/rest/api/3/search/jql"

    jql_statuses = ", ".join(f'"{s}"' for s in MONITORED_STATUSES)
    payload = {
        "jql":        f'status in ({jql_statuses}) ORDER BY created ASC',
        "maxResults": 200,
        "fields":     ["summary", "assignee", "issuetype", "customfield_10022", "created", "status"],
    }

    resp = requests.post(url, auth=auth, json=payload, timeout=30)
    resp.raise_for_status()
    return resp.json().get("issues", [])


def get_entered_dates(issue_keys: list[str]) -> dict[str, datetime]:
    """
    Возвращает {issue_key: last_entered_at} из MySQL DetailedIssuesChangelog.
    Ищет последний переход в любой из MONITORED_STATUSES для каждой задачи.
    """
    if not issue_keys:
        return {}
    try:
        conn = mysql.connector.connect(
            host=MYSQL_HOST, port=MYSQL_PORT,
            database=MYSQL_DB, user=MYSQL_USER, password=MYSQL_PASS,
            connection_timeout=10,
        )
        cursor = conn.cursor()
        placeholders = ", ".join(["%s"] * len(MONITORED_STATUSES))
        key_placeholders = ", ".join(["%s"] * len(issue_keys))
        cursor.execute(f"""
            SELECT i.IssueKey, c.New, MAX(c.CreatedDate) AS entered_at
            FROM DetailedIssuesChangelog c
            JOIN IssuesInfo i ON i.IssueId = c.IssueId
            WHERE c.Field = 'status'
              AND c.New IN ({placeholders})
              AND i.IssueKey IN ({key_placeholders})
            GROUP BY i.IssueKey, c.New
        """, MONITORED_STATUSES + issue_keys)
        rows = cursor.fetchall()
        cursor.close()
        conn.close()
    except Exception as e:
        print(f"[MySQL] Ошибка get_entered_dates: {e}", file=sys.stderr)
        return {}

    # Для каждой задачи берём дату перехода в её текущий статус
    result = {}
    for key, status, entered_at in rows:
        result[(key, status)] = entered_at
    return result


def get_issues() -> list[dict]:
    """
    Возвращает задачи из Jira с датой входа в текущий статус из MySQL.
    """
    raw_issues = get_issues_from_jira()
    issue_keys = [i["key"] for i in raw_issues]
    entered_map = get_entered_dates(issue_keys)

    now    = datetime.utcnow()
    result = []

    for issue in raw_issues:
        key    = issue["key"]
        fields = issue["fields"]

        summary    = fields.get("summary", "")
        assignee   = fields.get("assignee") or {}
        name       = assignee.get("displayName", "—")
        sp         = fields.get("customfield_10022")
        itype      = fields["issuetype"]["name"]
        cur_status = fields["status"]["name"]

        # Дата последнего перехода в текущий статус из MySQL
        entered = entered_map.get((key, cur_status))

        # Фолбэк — дата создания задачи из Jira
        if entered is None:
            created_str = fields.get("created", "")
            if created_str:
                entered = datetime.strptime(created_str[:19], "%Y-%m-%dT%H:%M:%S")

        bdays   = business_days(entered, now) if entered else None
        overdue = bdays is not None and bdays > THRESHOLD

        result.append({
            "key":        key,
            "summary":    summary,
            "assignee":   name,
            "sp":         sp,
            "type":       itype,
            "status":     cur_status,
            "entered_at": entered,
            "bdays":      bdays,
            "overdue":    overdue,
            "url":        f"{JIRA_URL}/browse/{key}",
        })

    return result


# ---------------------------------------------------------------------------
# MySQL — исторический перцентиль
# ---------------------------------------------------------------------------

def get_p70_historical(status: str) -> float | None:
    """
    70-й перцентиль времени в указанном статусе по ЗАКРЫТЫМ задачам (в рабочих днях).
    Использует IssueStatusDurations.<status> (календарные часы).
    """
    try:
        conn = mysql.connector.connect(
            host=MYSQL_HOST, port=MYSQL_PORT,
            database=MYSQL_DB, user=MYSQL_USER, password=MYSQL_PASS,
            connection_timeout=10,
        )
        cursor = conn.cursor()
        cursor.execute(f"""
            SELECT `{status}`
            FROM IssueStatusDurations
            WHERE `{status}` > 0
              AND current_status NOT IN ('{status}')
        """)
        rows = cursor.fetchall()
        cursor.close()
        conn.close()
    except Exception as e:
        print(f"[MySQL] Ошибка для статуса '{status}': {e}", file=sys.stderr)
        return None

    if not rows:
        return None

    bdays_hist = [calendar_hours_to_bdays(float(r[0])) for r in rows]
    return round(float(np.percentile(bdays_hist, 70)), 1)


# ---------------------------------------------------------------------------
# Telegram
# ---------------------------------------------------------------------------

def send_telegram(text: str) -> None:
    url  = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage"
    resp = requests.post(url, json={
        "chat_id":                  TELEGRAM_CHAT,
        "text":                     text,
        "parse_mode":               "HTML",
        "disable_web_page_preview": True,
    }, timeout=15)
    resp.raise_for_status()


# ---------------------------------------------------------------------------
# Формирование сообщения
# ---------------------------------------------------------------------------

def build_status_section(status: str, issues: list[dict], p70: float | None) -> list[str]:
    """Секция для одного статуса."""
    emoji   = STATUS_EMOJI.get(status, "📋")
    total   = len(issues)
    overdue = [i for i in issues if i["overdue"]]
    ok      = [i for i in issues if not i["overdue"]]
    pct     = round(len(ok) / total * 100) if total else 0
    flag    = "✅" if pct >= TARGET_PCT else "⚠️"

    lines = [f"{emoji} <b>{status}</b> — {total} задач  {flag} on-time: {len(ok)}/{total} = {pct}%"]
    if p70 is not None:
        lines.append(f"   📈 P70 истории: {p70} раб. дней")

    if overdue:
        lines.append(f"   <b>🔴 Просрочены (&gt; {THRESHOLD} дн.):</b>")
        for i in sorted(overdue, key=lambda x: x["bdays"] or 0, reverse=True):
            sp = f" | {i['sp']} SP" if i["sp"] else ""
            lines.append(
                f'      • <a href="{i["url"]}">{i["key"]}</a> — <b>{i["bdays"]} дн.</b>'
                f'  {i["assignee"]}{sp}'
            )

    if ok:
        lines.append(f"   <b>🟢 В норме:</b>")
        for i in sorted(ok, key=lambda x: x["bdays"] or 0, reverse=True):
            days = i["bdays"] if i["bdays"] is not None else "?"
            sp   = f" | {i['sp']} SP" if i["sp"] else ""
            lines.append(
                f'      • <a href="{i["url"]}">{i["key"]}</a> — {days} дн.'
                f'  {i["assignee"]}{sp}'
            )

    return lines


def build_message(issues: list[dict], p70_by_status: dict[str, float | None]) -> str:
    today = datetime.utcnow().strftime("%d.%m.%Y")
    lines = [f"<b>📋 Мониторинг тестирования — {today}</b>\n"]

    for status in MONITORED_STATUSES:
        status_issues = [i for i in issues if i["status"] == status]
        if not status_issues:
            lines.append(f"{STATUS_EMOJI.get(status, '📋')} <b>{status}</b> — нет задач")
            continue
        lines.extend(build_status_section(status, status_issues, p70_by_status.get(status)))
        lines.append("")

    total   = len(issues)
    overdue = sum(1 for i in issues if i["overdue"])
    ok      = total - overdue
    pct     = round(ok / total * 100) if total else 0
    flag    = "✅" if pct >= TARGET_PCT else "⚠️"
    lines.append(f"{flag} <b>Итого: on-time {ok}/{total} = {pct}%</b>  (цель ≥ {TARGET_PCT}%)")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    print("Получаю задачи из Jira...")
    issues = get_issues()
    print(f"  Найдено: {len(issues)} задач")
    for status in MONITORED_STATUSES:
        cnt = sum(1 for i in issues if i["status"] == status)
        print(f"    {status}: {cnt}")

    print("Считаю исторические перцентили из MySQL...")
    p70_by_status = {}
    for status in MONITORED_STATUSES:
        p70 = get_p70_historical(status)
        p70_by_status[status] = p70
        print(f"    P70 [{status}]: {p70} раб. дней")

    message = build_message(issues, p70_by_status)
    print("\n--- Сообщение ---")
    print(message)
    print("-----------------\n")

    print("Отправляю в Telegram...")
    send_telegram(message)
    print("Готово.")


if __name__ == "__main__":
    main()
