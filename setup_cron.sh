#!/bin/bash
# Установка и настройка cron для RFT Monitor

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Установка зависимостей
pip install -r "$SCRIPT_DIR/requirements.txt"

# Добавление в cron: каждый рабочий день в 10:00
CRON_LINE="0 10 * * 1-5 cd $SCRIPT_DIR && python3 monitor.py >> $SCRIPT_DIR/monitor.log 2>&1"

# Проверяем, нет ли уже такой записи
if crontab -l 2>/dev/null | grep -q "rft-monitor"; then
    echo "Cron уже настроен."
else
    (crontab -l 2>/dev/null; echo "$CRON_LINE") | crontab -
    echo "Cron добавлен: каждый рабочий день в 10:00"
fi

echo ""
echo "Тест запуска:"
python3 "$SCRIPT_DIR/monitor.py"
