#!/usr/bin/env python3
"""
RFT Monitor Bot
Интерактивный Telegram-бот для мониторинга задач.

Команды:
  /status  — полный отчёт по всем статусам
  /overdue — только просроченные задачи
  /stats   — статистика по тестировщикам
"""

import os
import sys
import telebot
from dotenv import load_dotenv
from monitor import (
    get_issues, get_p70_historical, build_message,
    MONITORED_STATUSES, STATUS_EMOJI, THRESHOLD,
)

load_dotenv()

TELEGRAM_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN")
TELEGRAM_CHAT  = os.getenv("TELEGRAM_CHAT_ID")

bot = telebot.TeleBot(TELEGRAM_TOKEN, parse_mode="HTML")


def send(chat_id: int | str, text: str) -> None:
    bot.send_message(chat_id, text, parse_mode="HTML", disable_web_page_preview=True)


def fetch_all():
    """Получить задачи и перцентили (единая точка для всех команд)."""
    issues = get_issues()
    p70_by_status = {s: get_p70_historical(s) for s in MONITORED_STATUSES}
    return issues, p70_by_status


# ---------------------------------------------------------------------------
# /start, /help
# ---------------------------------------------------------------------------

@bot.message_handler(commands=["start", "help"])
def cmd_help(message):
    send(message.chat.id, (
        "<b>RFT Monitor</b> — мониторинг задач в тестировании\n\n"
        "/status — полный отчёт по всем статусам\n"
        "/overdue — только просроченные задачи\n"
        "/stats — статистика по тестировщикам"
    ))


# ---------------------------------------------------------------------------
# /status — полный отчёт
# ---------------------------------------------------------------------------

@bot.message_handler(commands=["status"])
def cmd_status(message):
    send(message.chat.id, "⏳ Собираю данные...")
    try:
        issues, p70_by_status = fetch_all()
        text = build_message(issues, p70_by_status)
        send(message.chat.id, text)
    except Exception as e:
        send(message.chat.id, f"❌ Ошибка: {e}")


# ---------------------------------------------------------------------------
# /overdue — только просроченные
# ---------------------------------------------------------------------------

@bot.message_handler(commands=["overdue"])
def cmd_overdue(message):
    send(message.chat.id, "⏳ Собираю данные...")
    try:
        issues, _ = fetch_all()
        overdue = [i for i in issues if i["overdue"]]

        if not overdue:
            send(message.chat.id, f"✅ Просроченных задач нет (порог: {THRESHOLD} раб. дней)")
            return

        lines = [f"<b>🔴 Просроченные задачи ({len(overdue)} шт.)</b>\n"]
        for status in MONITORED_STATUSES:
            group = [i for i in overdue if i["status"] == status]
            if not group:
                continue
            emoji = STATUS_EMOJI.get(status, "📋")
            lines.append(f"{emoji} <b>{status}</b>")
            for i in sorted(group, key=lambda x: x["bdays"] or 0, reverse=True):
                sp = f" | {i['sp']} SP" if i["sp"] else ""
                lines.append(
                    f'  • <a href="{i["url"]}">{i["key"]}</a> — <b>{i["bdays"]} дн.</b>'
                    f'  {i["assignee"]}{sp}'
                )
            lines.append("")

        send(message.chat.id, "\n".join(lines))
    except Exception as e:
        send(message.chat.id, f"❌ Ошибка: {e}")


# ---------------------------------------------------------------------------
# /stats — статистика по тестировщикам
# ---------------------------------------------------------------------------

@bot.message_handler(commands=["stats"])
def cmd_stats(message):
    send(message.chat.id, "⏳ Собираю данные...")
    try:
        issues, p70_by_status = fetch_all()

        # Статистика по тестировщикам
        devs: dict[str, dict] = {}
        for i in issues:
            dev = i["assignee"] or "—"
            if dev not in devs:
                devs[dev] = {"total": 0, "overdue": 0, "sp": 0.0}
            devs[dev]["total"]  += 1
            devs[dev]["sp"]     += i["sp"] or 0
            if i["overdue"]:
                devs[dev]["overdue"] += 1

        # Сортируем по количеству просроченных
        sorted_devs = sorted(devs.items(), key=lambda x: x[1]["overdue"], reverse=True)

        total   = len(issues)
        overdue = sum(1 for i in issues if i["overdue"])
        ok      = total - overdue
        pct     = round(ok / total * 100) if total else 0
        flag    = "✅" if pct >= 70 else "⚠️"

        lines = [f"<b>📊 Статистика по тестировщикам</b>\n"]
        lines.append(f"{flag} On-time: {ok}/{total} = {pct}%\n")

        for dev, stat in sorted_devs:
            bar    = "🔴" if stat["overdue"] > 0 else "🟢"
            sp_str = f" | {stat['sp']:.1f} SP" if stat["sp"] else ""
            lines.append(
                f"{bar} <b>{dev}</b>: {stat['total']} задач"
                f" ({stat['overdue']} просрочено){sp_str}"
            )

        lines.append("\n<b>P70 истории:</b>")
        for status in MONITORED_STATUSES:
            p70 = p70_by_status.get(status)
            emoji = STATUS_EMOJI.get(status, "📋")
            lines.append(f"  {emoji} {status}: {p70} раб. дней" if p70 else f"  {emoji} {status}: нет данных")

        send(message.chat.id, "\n".join(lines))
    except Exception as e:
        send(message.chat.id, f"❌ Ошибка: {e}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("RFT Monitor Bot запущен. Ctrl+C для остановки.")
    bot.infinity_polling(timeout=30, long_polling_timeout=30)
