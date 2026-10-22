"""Telegram Bot API interface: alerts, the morning briefing and the approval queue.

Only the chat whose id equals SMT_OWNER_CHAT_ID can act. Everyone else is
ignored (and logged). Buttons: ✅ Approve / ❌ Reject / ✏️ Edit.

Commands
  /queue                 one card per pending item, with buttons
  /approve <id>          /reject <id> [reason]
  /edit <id> caption|text|hashtags <new text>     (returns the item to PENDING)
  /approve_replies       approve every pending reply/DM draft (not posts)
  /leads                 open qualified leads
  /won <lead id> <value> mark a lead as a sale (feeds revenue attribution)
  /brief                 send the morning briefing now
"""
import json
import os
import urllib.error
import urllib.request

from . import analytics, db, gate
from .resilience import PermanentError, RateLimited, TransientError, with_retry

API = "https://api.telegram.org/bot{token}/{method}"
MAX_LEN = 4000


class TelegramClient:
    def __init__(self, token):
        self.token = token

    def call(self, method, http_timeout=40, **params):
        req = urllib.request.Request(API.format(token=self.token, method=method),
                                     data=json.dumps(params).encode(), headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=http_timeout) as r:
                res = json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                raise RateLimited("telegram rate limit", json.loads(e.read() or b"{}").get("parameters", {}).get("retry_after"))
            if e.code >= 500:
                raise TransientError(f"telegram HTTP {e.code}")
            raise PermanentError(f"telegram HTTP {e.code}")
        except (urllib.error.URLError, TimeoutError) as e:
            raise TransientError(str(e))
        if not res.get("ok"):
            raise PermanentError(res.get("description", "telegram error"))
        return res["result"]


class ConsoleNotifier:
    """Offline stand-in: prints messages and keeps them for tests."""

    def __init__(self, echo=True):
        self.sent, self.echo = [], echo

    def __call__(self, text, buttons=None):
        self.sent.append((text, buttons))
        if self.echo:
            print(f"[to owner] {text}" + (f"\n  buttons: {[b['text'] for row in buttons for b in row]}" if buttons else "") + "\n")


class TelegramNotifier:
    def __init__(self, client, chat_id, conn=None):
        self.client, self.chat_id, self.conn = client, chat_id, conn

    def __call__(self, text, buttons=None):
        for i in range(0, len(text), MAX_LEN):
            params = {"chat_id": self.chat_id, "text": text[i:i + MAX_LEN], "disable_web_page_preview": True}
            if buttons and i + MAX_LEN >= len(text):
                params["reply_markup"] = {"inline_keyboard": buttons}
            with_retry(lambda: self.client.call("sendMessage", **params), where="telegram.send", conn=self.conn)


def notifier_from_env(conn=None):
    token, chat = os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("SMT_OWNER_CHAT_ID")
    if token and chat:
        return TelegramNotifier(TelegramClient(token), chat, conn)
    return ConsoleNotifier()


def buttons_for(item_id):
    return [[{"text": "✅ Approve", "callback_data": f"a:{item_id}"},
             {"text": "❌ Reject", "callback_data": f"r:{item_id}"},
             {"text": "✏️ Edit", "callback_data": f"e:{item_id}"}]]


def card(row):
    body = json.loads(row["body"]) if row["body"].startswith("{") else {"text": row["body"]}
    head = f"#{row['id']} · {row['kind'].upper()} · {row['platform']}" + (f" · {row['format']}" if row["format"] else "")
    if row["scheduled_for"]:
        head += f" · {row['scheduled_for'].replace('T', ' ')}"
    lines = [head]
    if row["hook"]:
        lines.append(f"Hook ({row['hook_trigger']}): {row['hook']}")
    if "slides" in body:
        lines += [f"  {i + 1}. {s}" for i, s in enumerate(body["slides"])]
    elif "body" in body:
        lines += [f"  {b}" for b in body["body"]] + [f"  CTA: {body['cta']}"]
    else:
        lines.append(body.get("text", ""))
    if row["caption"]:
        lines.append(f"Caption: {row['caption']}")
    if row["hashtags"]:
        lines.append(row["hashtags"])
    return "\n".join(lines)


class Bot:
    def __init__(self, conn, brand, notify, owner=None):
        self.conn, self.brand, self.notify = conn, brand, notify
        self.owner = str(owner or gate.owner_id())

    def handle(self, update):
        """Process one Telegram update. Returns the reply text (also sent to the owner)."""
        if "callback_query" in update:
            cq = update["callback_query"]
            sender, text = str(cq["from"]["id"]), None
            action, _, item = cq.get("data", "").partition(":")
            text = {"a": f"/approve {item}", "r": f"/reject {item}", "e": f"/editinfo {item}"}.get(action, "")
        elif "message" in update:
            msg = update["message"]
            sender, text = str(msg["chat"]["id"]), msg.get("text", "")
        else:
            return None
        if sender != self.owner:
            db.audit(self.conn, f"telegram:{sender}", "ignored_non_owner", (text or "")[:80])
            return None
        try:
            reply = self.command(text.strip())
        except (KeyError, ValueError, gate.GateError) as e:
            reply = f"! {e}"
        if reply:
            self.notify(reply)
        return reply

    def command(self, text):
        cmd, _, rest = text.partition(" ")
        cmd = cmd.split("@")[0].lower()
        if cmd == "/queue":
            items = gate.pending(self.conn)
            for row in items[:20]:
                self.notify(card(row), buttons_for(row["id"]))
            return f"{len(items)} items pending." + (" Showing the first 20." if len(items) > 20 else "")
        if cmd == "/approve":
            row = gate.approve(self.conn, int(rest), self.owner, "telegram")
            return f"✅ #{row['id']} approved - goes out {row['scheduled_for'] or 'at the next dispatch'}."
        if cmd == "/reject":
            item, _, reason = rest.partition(" ")
            gate.reject(self.conn, int(item), self.owner, "telegram", reason)
            return f"❌ #{item} rejected."
        if cmd == "/editinfo":
            return (f"Send: /edit {rest} caption <new caption>\n or /edit {rest} text <new post text>\n"
                    f" or /edit {rest} hashtags <#a #b>\nThe item then waits for approval again.")
        if cmd == "/edit":
            item, _, rest2 = rest.partition(" ")
            field, _, value = rest2.partition(" ")
            if not value:
                raise ValueError("usage: /edit <id> caption|text|hashtags <new value>")
            row = self.conn.execute("SELECT body FROM queue_item WHERE id=?", (int(item),)).fetchone()
            if row is None:
                raise KeyError(f"#{item} not found")
            if field == "caption":
                gate.edit(self.conn, int(item), self.owner, caption=value)
            elif field == "hashtags":
                gate.edit(self.conn, int(item), self.owner, hashtags=value)
            elif field == "text":
                body = json.loads(row["body"])
                key = "text" if "text" in body else "hook_0_3s" if "hook_0_3s" in body else None
                if key is None:
                    raise ValueError("carousels: edit the caption here, or the slides with the CLI")
                body[key] = value
                gate.edit(self.conn, int(item), self.owner, body=json.dumps(body, ensure_ascii=False))
            else:
                raise ValueError("field must be caption, text or hashtags")
            self.notify(card(self.conn.execute("SELECT * FROM queue_item WHERE id=?", (int(item),)).fetchone()),
                        buttons_for(int(item)))
            return f"✏️ #{item} updated and back in the queue."
        if cmd == "/approve_replies":
            ids = [r["id"] for r in gate.pending(self.conn) if r["kind"] in ("reply", "dm")]
            for i in ids:
                gate.approve(self.conn, i, self.owner, "telegram")
            return f"✅ {len(ids)} reply/DM drafts approved."
        if cmd == "/leads":
            rows = self.conn.execute("SELECT * FROM lead WHERE status='NEW' ORDER BY score DESC").fetchall()
            return "\n".join([f"{len(rows)} open leads"] + [f"#{r['id']} {r['platform']} {r['author']}: {r['reason']}"
                                                            for r in rows])
        if cmd == "/won":
            lead, _, value = rest.partition(" ")
            analytics.close_lead(self.conn, int(lead), float(value))
            return f"💰 Lead #{lead} marked won (${float(value):,.0f}) and attributed to its post."
        if cmd == "/brief":
            return analytics.morning_briefing(self.conn, self.brand)
        return __doc__.split("Commands", 1)[1].strip()

    def poll_forever(self, client, stop=lambda: False):
        offset = int(db.get_kv(self.conn, "tg_offset", "0"))
        while not stop():
            try:
                updates = with_retry(lambda: client.call("getUpdates", offset=offset, timeout=30),
                                     where="telegram.getUpdates", conn=self.conn)
            except (TransientError, PermanentError):
                continue
            for u in updates:
                offset = u["update_id"] + 1
                db.set_kv(self.conn, "tg_offset", str(offset))
                if "callback_query" in u:
                    try:
                        client.call("answerCallbackQuery", callback_query_id=u["callback_query"]["id"])
                    except (TransientError, PermanentError):
                        pass
                self.handle(u)
