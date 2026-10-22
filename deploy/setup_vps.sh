#!/usr/bin/env bash
# One-time setup on a fresh Ubuntu 22.04/24.04 or Oracle Linux 9 VPS (e.g. Oracle Cloud Always Free).
# Run as a sudo user:  bash deploy/setup_vps.sh
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if command -v apt-get >/dev/null; then
  sudo apt-get update && sudo apt-get install -y python3 python3-venv sqlite3
else
  sudo dnf install -y python3 python3-pip sqlite
fi

id smt >/dev/null 2>&1 || sudo useradd --system --create-home --shell /usr/sbin/nologin smt
sudo mkdir -p /opt/smt
sudo rsync -a --exclude .git --exclude '*.db' "$REPO_DIR"/ /opt/smt/
sudo python3 -m venv /opt/smt/.venv
sudo /opt/smt/.venv/bin/pip install --quiet anthropic        # optional: Claude copywriting
[ -f /opt/smt/.env ] || sudo cp /opt/smt/deploy/.env.example /opt/smt/.env
sudo chown -R smt:smt /opt/smt
sudo chmod 600 /opt/smt/.env

sudo cp /opt/smt/deploy/smt-agent.service /opt/smt/deploy/smt-bot.service /etc/systemd/system/
sudo systemctl daemon-reload
echo
echo "Next:"
echo "  1. sudo -u smt nano /opt/smt/.env      (Telegram token + owner chat id at minimum)"
echo "  2. sudo systemctl enable --now smt-agent smt-bot"
echo "  3. journalctl -u smt-agent -f          (logs)"
echo "  4. Nightly backup: echo '15 3 * * * smt sqlite3 /opt/smt/smt.db \".backup /opt/smt/backup-\$(date +\\%a).db\"' | sudo tee /etc/cron.d/smt-backup"
echo "No inbound ports are needed: the bot uses long polling, so keep the firewall closed except SSH."
