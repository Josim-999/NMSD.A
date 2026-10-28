"""Tests for the WhatsApp Business MCP server.

Nothing here touches the network: a fake WhatsApp Cloud API runs on localhost
and the client is pointed at it, so a send is exercised the whole way from a
JSON-RPC tools/call to the HTTP request Meta would receive.
"""
import hashlib
import hmac
import io
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from wamcp import config as config_mod                                  # noqa: E402
from wamcp import graph, install, mcp, messages, phones, policy, store, tools, webhook  # noqa: E402

TOKEN = "EAAtest-token-value"
PHONE_ID = "100000000000001"
WABA_ID = "200000000000002"
APP_SECRET = "app-secret-value"
CUSTOMER = "966501234567"


# --------------------------------------------------------------------------- #
#  A fake Cloud API
# --------------------------------------------------------------------------- #
class FakeGraphHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth_ok(self):
        if self.headers.get("Authorization") == f"Bearer {TOKEN}":
            return True
        self._json(401, {"error": {"message": "Invalid OAuth access token.", "type":
                                   "OAuthException", "code": 190, "fbtrace_id": "trace"}})
        return False

    def do_GET(self):
        if not self._auth_ok():
            return
        state = self.server.state
        state["requests"].append(("GET", self.path, None))
        if forced := state.get("force_error"):
            self._json(forced[0], {"error": forced[1]})
            return
        if f"/{PHONE_ID}/whatsapp_business_profile" in self.path:
            self._json(200, {"data": [{"about": "We deliver medicine", "vertical": "HEALTH"}]})
        elif f"/{WABA_ID}/message_templates" in self.path:
            self._json(200, {"data": [
                {"name": "order_update", "language": "en_US", "status": "APPROVED",
                 "category": "UTILITY",
                 "components": [{"type": "BODY", "text": "Order {{1}} arrives {{2}}."}]},
                {"name": "promo_week", "language": "en_US", "status": "REJECTED",
                 "category": "MARKETING", "rejected_reason": "INCORRECT_CATEGORY",
                 "components": [{"type": "BODY", "text": "Sale!"}]},
            ]})
        elif self.path.rstrip("/").endswith(f"/{PHONE_ID}") or f"/{PHONE_ID}?" in self.path:
            self._json(200, {"id": PHONE_ID, "display_phone_number": "+1 555 000 0001",
                             "verified_name": "Test Pharmacy", "quality_rating": "GREEN",
                             "code_verification_status": "VERIFIED", "platform_type": "CLOUD_API",
                             "throughput": {"level": "STANDARD"}})
        elif "MEDIA-ID" in self.path and "/download" in self.path:
            payload = b"\x89PNG-bytes"
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        elif "MEDIA-ID" in self.path:
            base = f"http://{self.server.server_address[0]}:{self.server.server_address[1]}"
            self._json(200, {"id": "MEDIA-ID", "mime_type": "image/png", "sha256": "abc",
                             "file_size": 10, "url": f"{base}/v24.0/MEDIA-ID/download"})
        else:
            self._json(404, {"error": {"message": "Unknown path", "code": 33}})

    def do_POST(self):
        if not self._auth_ok():
            return
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        state = self.server.state
        is_json = "application/json" in (self.headers.get("Content-Type") or "")
        body = json.loads(raw) if (raw and is_json) else {"_raw": len(raw)}
        state["requests"].append(("POST", self.path, body))

        if forced := state.get("force_error"):
            self._json(forced[0], {"error": forced[1]})
            return
        if self.path.endswith("/messages"):
            if body.get("status") == "read":
                self._json(200, {"success": True})
                return
            state["sent"].append(body)
            self._json(200, {
                "messaging_product": "whatsapp",
                "contacts": [{"input": body.get("to"), "wa_id": body.get("to")}],
                "messages": [{"id": f"wamid.FAKE{len(state['sent'])}",
                              "message_status": "accepted"}],
            })
        elif self.path.endswith("/media"):
            self._json(200, {"id": "UPLOADED-MEDIA-ID"})
        elif "message_templates" in self.path:
            state["templates"].append(body)
            self._json(200, {"id": "300000000000003", "status": "PENDING",
                             "category": body.get("category")})
        elif "whatsapp_business_profile" in self.path:
            self._json(200, {"success": True})
        else:
            self._json(404, {"error": {"message": "Unknown path", "code": 33}})

    def do_DELETE(self):
        if not self._auth_ok():
            return
        self.server.state["requests"].append(("DELETE", self.path, None))
        self._json(200, {"success": True})


class FakeGraph:
    """Runs the fake API in a thread for the length of a test."""

    def __init__(self):
        self.httpd = HTTPServer(("127.0.0.1", 0), FakeGraphHandler)
        self.httpd.state = {"sent": [], "requests": [], "templates": [], "force_error": None}
        self.thread = threading.Thread(target=self.httpd.serve_forever,
                                       kwargs={"poll_interval": 0.02}, daemon=True)
        self.thread.start()

    @property
    def base(self):
        host, port = self.httpd.server_address
        return f"http://{host}:{port}"

    @property
    def state(self):
        return self.httpd.state

    def fail_next(self, status, error):
        self.httpd.state["force_error"] = (status, error)

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def local_opener():
    """An opener that ignores any proxy settings, so localhost is reachable."""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Base(unittest.TestCase):
    """A configured server talking to a fake Cloud API, in a temp directory."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="wamcp-test-")
        self.api = FakeGraph()
        self.addCleanup(self.api.stop)
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.config = config_mod.Config(
            access_token=TOKEN, phone_number_id=PHONE_ID, business_account_id=WABA_ID,
            app_secret=APP_SECRET, verify_token="verify-me", api_base=self.api.base,
            db_path=os.path.join(self.dir, "wamcp.db"),
            media_dir=os.path.join(self.dir, "media"),
        )
        self.store = store.Store(self.config.db_path)
        self.client = graph.GraphClient(self.config, opener=local_opener(), sleep=lambda s: None)
        self.ctx = tools.Context(self.config, client=self.client, store_=self.store)

    def open_window(self, wa_id=CUSTOMER, seconds_ago=60, name="Josim"):
        """Pretend this person messaged us, which opens the 24-hour window."""
        self.store.set_contact_name(wa_id, name)
        self.store.record_message(f"wamid.IN{wa_id}{seconds_ago}", wa_id, "in",
                                  ts=time.time() - seconds_ago, type="text", body="hello")

    def call(self, _tool, **arguments):
        return tools.call(self.ctx, _tool, arguments)

    def text_of(self, result):
        return "\n".join(part["text"] for part in result["content"])


# --------------------------------------------------------------------------- #
#  Phone numbers
# --------------------------------------------------------------------------- #
class TestPhones(unittest.TestCase):
    def test_accepts_the_ways_people_write_numbers(self):
        for raw, country, want in [
            ("+966 50 123 4567", "", "966501234567"),
            ("00966501234567", "", "966501234567"),
            ("966-50-123-4567", "", "966501234567"),
            ("050 123 4567", "966", "966501234567"),
            ("501234567", "966", "966501234567"),
            ("(555) 010-9999", "1", "15550109999"),
            ("966501234567@c.us", "", "966501234567"),
            ("٠٥٠١٢٣٤٥٦٧", "966", "966501234567"),
        ]:
            self.assertEqual(phones.normalise(raw, country), want, raw)

    def test_rejects_what_is_not_a_number(self):
        for raw in ["", None, "   ", "hello", "12345", "1" * 16]:
            with self.assertRaises(phones.PhoneError):
                phones.normalise(raw)

    def test_local_number_without_a_country_is_refused_clearly(self):
        with self.assertRaises(phones.PhoneError) as caught:
            phones.normalise("0501234567")
        self.assertIn("international", str(caught.exception))

    def test_pretty_adds_one_plus(self):
        self.assertEqual(phones.pretty("966501234567"), "+966501234567")
        self.assertEqual(phones.pretty("+966501234567"), "+966501234567")


# --------------------------------------------------------------------------- #
#  Message builders
# --------------------------------------------------------------------------- #
class TestMessages(unittest.TestCase):
    def test_text_payload_matches_the_cloud_api(self):
        payload = messages.text("+966501234567", "hi", preview_url=True)
        self.assertEqual(payload["messaging_product"], "whatsapp")
        self.assertEqual(payload["to"], "966501234567")
        self.assertEqual(payload["type"], "text")
        self.assertEqual(payload["text"], {"body": "hi", "preview_url": True})

    def test_reply_sets_context(self):
        payload = messages.text("+966501234567", "hi", reply_to="wamid.X")
        self.assertEqual(payload["context"], {"message_id": "wamid.X"})

    def test_template_components_are_ordered_header_body_button(self):
        payload = messages.template(
            "+966501234567", "order_update", "en_US",
            body_params=["A12", "today"], header_media={"kind": "image", "link": "https://x/y.png"},
            button_params=["track/1"],
        )
        kinds = [c["type"] for c in payload["template"]["components"]]
        self.assertEqual(kinds, ["header", "body", "button"])
        body = payload["template"]["components"][1]["parameters"]
        self.assertEqual([p["text"] for p in body], ["A12", "today"])
        self.assertEqual(payload["template"]["language"], {"code": "en_US"})

    def test_template_accepts_a_verbatim_components_array(self):
        payload = messages.template("+966501234567", "x", components=[{"type": "body"}])
        self.assertEqual(payload["template"]["components"], [{"type": "body"}])

    def test_media_needs_exactly_one_source(self):
        with self.assertRaises(messages.MessageError):
            messages.media("+966501234567", "image")
        with self.assertRaises(messages.MessageError):
            messages.media("+966501234567", "image", link="https://a", media_id="1")

    def test_document_carries_filename_and_caption(self):
        payload = messages.media("+966501234567", "document", link="https://a/i.pdf",
                                 caption="Invoice", filename="inv.pdf")
        self.assertEqual(payload["document"],
                         {"link": "https://a/i.pdf", "caption": "Invoice", "filename": "inv.pdf"})

    def test_whatsapp_limits_are_enforced_before_sending(self):
        with self.assertRaises(messages.MessageError):
            messages.text("+966501234567", "x" * (messages.TEXT_MAX + 1))
        with self.assertRaises(messages.MessageError):
            messages.buttons("+966501234567", "b", ["a", "b", "c", "d"])
        with self.assertRaises(messages.MessageError):
            messages.buttons("+966501234567", "b", ["x" * 21])
        with self.assertRaises(messages.MessageError):
            messages.option_list("+966501234567", "b", "go",
                                 [{"title": "s", "rows": [f"r{i}" for i in range(11)]}])
        with self.assertRaises(messages.MessageError):
            messages.media("+966501234567", "sticker", link="https://a", caption="no")
        with self.assertRaises(messages.MessageError):
            messages.location("+966501234567", 200, 0)

    def test_duplicate_button_ids_are_refused(self):
        with self.assertRaises(messages.MessageError):
            messages.buttons("+966501234567", "b",
                             [{"id": "same", "title": "A"}, {"id": "same", "title": "B"}])

    def test_flat_row_list_becomes_one_section(self):
        payload = messages.option_list("+966501234567", "pick", "Menu", ["Pizza", "Pasta"])
        sections = payload["interactive"]["action"]["sections"]
        self.assertEqual(len(sections), 1)
        self.assertEqual([r["title"] for r in sections[0]["rows"]], ["Pizza", "Pasta"])

    def test_reaction_can_be_removed_with_an_empty_emoji(self):
        payload = messages.reaction("+966501234567", "wamid.X", "")
        self.assertEqual(payload["reaction"]["emoji"], "")


# --------------------------------------------------------------------------- #
#  Store
# --------------------------------------------------------------------------- #
class TestStore(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="wamcp-store-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.store = store.Store(os.path.join(self.dir, "s.db"))

    def test_conversation_shows_the_last_message_and_who_sent_it(self):
        self.store.set_contact_name(CUSTOMER, "Josim")
        self.store.record_message("in1", CUSTOMER, "in", ts=100, body="Is it ready?")
        self.store.record_message("out1", CUSTOMER, "out", ts=200, body="Yes")
        chat = self.store.conversations()[0]
        self.assertEqual(chat["name"], "Josim")
        self.assertEqual(chat["last_body"], "Yes")
        self.assertEqual(chat["last_direction"], "out")
        self.assertEqual(chat["messages"], 2)

    def test_messages_come_back_oldest_first(self):
        for i in range(5):
            self.store.record_message(f"m{i}", CUSTOMER, "in", ts=100 + i, body=str(i))
        bodies = [m["body"] for m in self.store.messages(CUSTOMER, limit=3)]
        self.assertEqual(bodies, ["2", "3", "4"])

    def test_status_updates_land_on_the_message(self):
        self.store.record_message("out1", CUSTOMER, "out", body="hi", status="accepted")
        self.store.record_status("out1", "delivered")
        self.store.record_status("out1", "read")
        row = self.store.message("out1")
        self.assertEqual(row["status"], "read")
        self.assertEqual([h["status"] for h in row["status_history"]], ["delivered", "read"])

    def test_recording_the_same_id_twice_does_not_duplicate(self):
        self.store.record_message("same", CUSTOMER, "in", body="first")
        self.store.record_message("same", CUSTOMER, "in", body="second")
        self.assertEqual(len(self.store.messages(CUSTOMER)), 1)

    def test_search_escapes_sql_wildcards(self):
        self.store.record_message("m1", CUSTOMER, "in", body="100% cotton")
        self.store.record_message("m2", CUSTOMER, "in", body="plain")
        self.assertEqual([m["wamid"] for m in self.store.search("100%")], ["m1"])

    def test_opt_out_round_trip(self):
        self.store.opt_out(CUSTOMER, "said STOP")
        self.assertEqual(self.store.is_opted_out(CUSTOMER)["reason"], "said STOP")
        self.store.opt_in(CUSTOMER)
        self.assertIsNone(self.store.is_opted_out(CUSTOMER))

    def test_keyword_classification(self):
        for word in ["STOP", "stop", " Unsubscribe ", "remove me", "stop."]:
            self.assertEqual(store.classify_keyword(word), "opt_out", word)
        for word in ["START", "subscribe"]:
            self.assertEqual(store.classify_keyword(word), "opt_in", word)
        for word in ["stop by tomorrow", "hello", ""]:
            self.assertIsNone(store.classify_keyword(word), word)

    def test_database_is_private_to_the_user(self):
        mode = os.stat(self.store.path).st_mode & 0o777
        self.assertEqual(mode, 0o600)


# --------------------------------------------------------------------------- #
#  Webhook
# --------------------------------------------------------------------------- #
def signed(secret, body):
    return "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()


class TestWebhook(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="wamcp-hook-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.store = store.Store(os.path.join(self.dir, "h.db"))

    def test_signature_must_match_the_exact_body(self):
        body = b'{"a":1}'
        self.assertTrue(webhook.verify_signature(APP_SECRET, body, signed(APP_SECRET, body)))
        self.assertFalse(webhook.verify_signature(APP_SECRET, body + b" ", signed(APP_SECRET, body)))
        self.assertFalse(webhook.verify_signature(APP_SECRET, body, signed("other", body)))
        self.assertFalse(webhook.verify_signature("", body, signed(APP_SECRET, body)))
        self.assertFalse(webhook.verify_signature(APP_SECRET, body, ""))

    def test_signature_accepts_the_header_without_its_prefix(self):
        body = b"{}"
        bare = signed(APP_SECRET, body)[7:]
        self.assertTrue(webhook.verify_signature(APP_SECRET, body, bare))

    def _payload(self, messages_list, statuses=None):
        return {"object": "whatsapp_business_account", "entry": [{"id": WABA_ID, "changes": [{
            "field": "messages",
            "value": {"messaging_product": "whatsapp",
                      "metadata": {"phone_number_id": PHONE_ID},
                      "contacts": [{"profile": {"name": "Josim"}, "wa_id": CUSTOMER}],
                      "messages": messages_list, "statuses": statuses or []}}]}]}

    def test_every_inbound_type_is_stored_readably(self):
        payload = self._payload([
            {"from": CUSTOMER, "id": "w1", "timestamp": "100", "type": "text",
             "text": {"body": "Do you deliver?"}},
            {"from": CUSTOMER, "id": "w2", "timestamp": "101", "type": "image",
             "image": {"id": "MEDIA-ID", "mime_type": "image/jpeg", "caption": "this one"}},
            {"from": CUSTOMER, "id": "w3", "timestamp": "102", "type": "interactive",
             "interactive": {"type": "button_reply",
                             "button_reply": {"id": "yes_ship", "title": "Yes please"}}},
            {"from": CUSTOMER, "id": "w4", "timestamp": "103", "type": "location",
             "location": {"latitude": 24.7, "longitude": 46.7, "name": "Home"}},
            {"from": CUSTOMER, "id": "w5", "timestamp": "104", "type": "button",
             "button": {"payload": "CONFIRM", "text": "Confirm"}},
            {"from": CUSTOMER, "id": "w6", "timestamp": "105", "type": "reaction",
             "reaction": {"message_id": "wamid.OUT", "emoji": "\U0001f44d"}},
            {"from": CUSTOMER, "id": "w7", "timestamp": "106", "type": "document",
             "document": {"id": "DOC", "mime_type": "application/pdf", "filename": "id.pdf"}},
        ])
        result = webhook.ingest(self.store, payload)
        self.assertEqual(result["messages"], 7)
        rows = {m["wamid"]: m for m in self.store.messages(CUSTOMER, limit=20)}
        self.assertEqual(rows["w1"]["body"], "Do you deliver?")
        self.assertEqual(rows["w2"]["media_id"], "MEDIA-ID")
        self.assertIn("this one", rows["w2"]["body"])
        self.assertEqual(rows["w3"]["choice_id"], "yes_ship")
        self.assertEqual(rows["w3"]["body"], "Yes please")
        self.assertIn("24.7", rows["w4"]["body"])
        self.assertEqual(rows["w5"]["choice_id"], "CONFIRM")
        self.assertIn("reacted", rows["w6"]["body"])
        self.assertEqual(rows["w7"]["filename"], "id.pdf")
        self.assertEqual(self.store.contact(CUSTOMER)["name"], "Josim")

    def test_stop_opts_the_sender_out_immediately(self):
        payload = self._payload([{"from": CUSTOMER, "id": "s1", "timestamp": "100",
                                  "type": "text", "text": {"body": "STOP"}}])
        result = webhook.ingest(self.store, payload)
        self.assertEqual(result["opt_outs"], [CUSTOMER])
        self.assertIsNotNone(self.store.is_opted_out(CUSTOMER))

    def test_start_opts_back_in(self):
        self.store.opt_out(CUSTOMER)
        webhook.ingest(self.store, self._payload(
            [{"from": CUSTOMER, "id": "s2", "timestamp": "100", "type": "text",
              "text": {"body": "START"}}]))
        self.assertIsNone(self.store.is_opted_out(CUSTOMER))

    def test_the_word_stop_inside_a_sentence_is_not_an_opt_out(self):
        webhook.ingest(self.store, self._payload(
            [{"from": CUSTOMER, "id": "s3", "timestamp": "100", "type": "text",
              "text": {"body": "please stop by the shop tomorrow"}}]))
        self.assertIsNone(self.store.is_opted_out(CUSTOMER))

    def test_failed_delivery_status_is_recorded_with_its_reason(self):
        webhook.ingest(self.store, self._payload([], statuses=[{
            "id": "wamid.OUT1", "status": "failed", "timestamp": "200",
            "recipient_id": CUSTOMER,
            "errors": [{"code": 131026, "title": "Message undeliverable"}]}]))
        self.store.record_message("wamid.OUT1", CUSTOMER, "out", body="hi")
        row = self.store.message("wamid.OUT1")
        self.assertEqual(row["status_history"][0]["error_code"], 131026)

    def test_an_unknown_payload_shape_is_ignored_quietly(self):
        self.assertEqual(webhook.ingest(self.store, {"object": "other"})["messages"], 0)
        self.assertEqual(webhook.ingest(self.store, "not a dict")["messages"], 0)

    def test_receiver_rejects_unsigned_posts_and_verifies_subscriptions(self):
        config = config_mod.Config(verify_token="verify-me", app_secret=APP_SECRET,
                                   db_path=os.path.join(self.dir, "h.db"))
        server = webhook.make_server(config, host="127.0.0.1", port=0, say=lambda m: None,
                                     store=self.store)
        thread = threading.Thread(target=server.serve_forever,
                                  kwargs={"poll_interval": 0.02}, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        host, port = server.server_address
        base = f"http://{host}:{port}"
        opener = local_opener()

        with opener.open(base + "/webhook?hub.mode=subscribe&hub.verify_token=verify-me"
                                "&hub.challenge=12345") as resp:
            self.assertEqual(resp.read().decode(), "12345")

        try:
            opener.open(base + "/webhook?hub.mode=subscribe&hub.verify_token=wrong"
                               "&hub.challenge=1")
            self.fail("a wrong verify token must be rejected")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 403)

        body = json.dumps(self._payload([{"from": CUSTOMER, "id": "p1", "timestamp": "100",
                                          "type": "text", "text": {"body": "signed hello"}}])
                          ).encode()
        request = urllib.request.Request(base + "/webhook", data=body, method="POST")
        request.add_header("Content-Type", "application/json")
        request.add_header("X-Hub-Signature-256", signed(APP_SECRET, body))
        with opener.open(request) as resp:
            self.assertEqual(resp.status, 200)
        self.assertEqual(self.store.message("p1")["body"], "signed hello")

        bad = urllib.request.Request(base + "/webhook", data=body, method="POST")
        bad.add_header("Content-Type", "application/json")
        bad.add_header("X-Hub-Signature-256", "sha256=deadbeef")
        try:
            opener.open(bad)
            self.fail("an invalid signature must be rejected")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)


# --------------------------------------------------------------------------- #
#  Policy
# --------------------------------------------------------------------------- #
class TestPolicy(Base):
    def gate(self, **overrides):
        values = self.config.as_dict()
        values.update(overrides)
        config = config_mod.Config(**values)
        return policy.Policy(config, self.store)

    def test_free_form_is_allowed_inside_the_window(self):
        self.open_window(seconds_ago=60)
        decision = self.gate().check(messages.text(CUSTOMER, "hi"))
        self.assertEqual(decision.warnings, [])

    def test_free_form_is_refused_outside_the_window(self):
        self.open_window(seconds_ago=25 * 3600)
        with self.assertRaises(policy.PolicyError) as caught:
            self.gate().check(messages.text(CUSTOMER, "hi"))
        self.assertEqual(caught.exception.kind, "window_closed")
        self.assertIn("whatsapp_send_template", caught.exception.hint)

    def test_an_unknown_window_warns_but_proceeds(self):
        decision = self.gate().check(messages.text(CUSTOMER, "hi"))
        self.assertTrue(any("window" in w for w in decision.warnings))

    def test_a_closing_window_warns(self):
        self.open_window(seconds_ago=24 * 3600 - 600)
        decision = self.gate().check(messages.text(CUSTOMER, "hi"))
        self.assertTrue(any("closes in" in w for w in decision.warnings))

    def test_templates_ignore_the_window(self):
        self.open_window(seconds_ago=40 * 3600)
        payload = messages.template(CUSTOMER, "order_update")
        self.gate().check(payload, requires_window=False)

    def test_opted_out_contacts_are_never_messaged(self):
        self.open_window()
        self.store.opt_out(CUSTOMER, "said STOP")
        with self.assertRaises(policy.PolicyError) as caught:
            self.gate().check(messages.text(CUSTOMER, "hi"))
        self.assertEqual(caught.exception.kind, "opted_out")

    def test_opt_out_beats_a_template_too(self):
        self.store.opt_out(CUSTOMER)
        with self.assertRaises(policy.PolicyError):
            self.gate().check(messages.template(CUSTOMER, "order_update"), requires_window=False)

    def test_allowlist_blocks_everyone_else(self):
        self.open_window()
        gate = self.gate(allowlist=["+966500000000"])
        with self.assertRaises(policy.PolicyError) as caught:
            gate.check(messages.text(CUSTOMER, "hi"))
        self.assertEqual(caught.exception.kind, "not_allowed")
        self.open_window("966500000000")
        gate.check(messages.text("966500000000", "hi"))

    def test_hourly_cap_stops_a_runaway_loop(self):
        self.open_window()
        gate = self.gate(max_sends_per_hour=2)
        for i in range(2):
            self.store.record_message(f"out{i}", CUSTOMER, "out", ts=time.time(), body="x")
        with self.assertRaises(policy.PolicyError) as caught:
            gate.check(messages.text(CUSTOMER, "hi"))
        self.assertEqual(caught.exception.kind, "rate_limited")

    def test_old_sends_do_not_count_against_the_cap(self):
        self.open_window()
        self.store.record_message("old", CUSTOMER, "out", ts=time.time() - 7200, body="x")
        self.gate(max_sends_per_hour=1).check(messages.text(CUSTOMER, "hi"))

    def test_dry_run_stops_before_the_api(self):
        self.open_window()
        decision = self.gate(dry_run=True).check(messages.text(CUSTOMER, "hi"))
        self.assertTrue(decision.dry_run)

    def test_window_can_be_overridden_deliberately(self):
        self.open_window(seconds_ago=40 * 3600)
        self.gate(allow_outside_window=True).check(messages.text(CUSTOMER, "hi"))

    def test_refusals_are_written_to_the_audit_trail(self):
        self.store.opt_out(CUSTOMER)
        with self.assertRaises(policy.PolicyError):
            self.gate().check(messages.text(CUSTOMER, "hi"))
        self.assertEqual(self.store.audit_entries()[0]["outcome"], "refused")


# --------------------------------------------------------------------------- #
#  Graph client
# --------------------------------------------------------------------------- #
class TestGraphClient(Base):
    def test_send_hits_the_messages_endpoint_with_a_bearer_token(self):
        response = self.client.send(messages.text(CUSTOMER, "hello"))
        self.assertTrue(response["messages"][0]["id"].startswith("wamid."))
        method, path, body = self.api.state["requests"][-1]
        self.assertEqual(method, "POST")
        self.assertEqual(path, f"/{self.config.api_version}/{PHONE_ID}/messages")
        self.assertEqual(body["messaging_product"], "whatsapp")

    def test_a_bad_token_is_reported_with_what_to_do(self):
        config = config_mod.Config(**dict(self.config.as_dict(), access_token="wrong"))
        client = graph.GraphClient(config, opener=local_opener(), sleep=lambda s: None)
        with self.assertRaises(graph.WhatsAppError) as caught:
            client.send(messages.text(CUSTOMER, "hi"))
        self.assertEqual(caught.exception.code, 190)
        self.assertIn("expired", caught.exception.hint)

    def test_cloud_api_errors_keep_their_details(self):
        self.api.fail_next(400, {"message": "Re-engagement message", "code": 131047,
                                 "error_data": {"details": "outside the 24 hour window"},
                                 "fbtrace_id": "trace-1"})
        with self.assertRaises(graph.WhatsAppError) as caught:
            self.client.send(messages.text(CUSTOMER, "hi"))
        payload = caught.exception.to_dict()
        self.assertEqual(payload["code"], 131047)
        self.assertEqual(payload["details"], "outside the 24 hour window")
        self.assertIn("whatsapp_send_template", payload["what_to_do"])

    def test_an_unreachable_host_is_a_clear_network_error(self):
        config = config_mod.Config(**dict(self.config.as_dict(),
                                          api_base="http://127.0.0.1:1", timeout=1))
        client = graph.GraphClient(config, opener=local_opener(), sleep=lambda s: None)
        with self.assertRaises(graph.WhatsAppError) as caught:
            client.send(messages.text(CUSTOMER, "hi"))
        self.assertEqual(caught.exception.kind, "network_error")

    def test_transient_failures_are_retried(self):
        attempts = []

        class Flaky:
            def open(self, request, timeout=None):
                attempts.append(request.full_url)
                if len(attempts) < 2:
                    raise urllib.error.HTTPError(request.full_url, 503, "busy", {},
                                                 io.BytesIO(b'{"error":{"code":131016}}'))
                return _FakeResponse(b'{"messages":[{"id":"wamid.RETRIED"}]}')

        client = graph.GraphClient(self.config, opener=Flaky(), sleep=lambda s: None)
        response = client.send(messages.text(CUSTOMER, "hi"))
        self.assertEqual(response["messages"][0]["id"], "wamid.RETRIED")
        self.assertEqual(len(attempts), 2)

    def test_media_download_saves_the_file(self):
        info = self.client.download_media("MEDIA-ID")
        self.assertTrue(os.path.exists(info["path"]))
        with open(info["path"], "rb") as fh:
            self.assertEqual(fh.read(), b"\x89PNG-bytes")
        self.assertEqual(info["mime_type"], "image/png")

    def test_media_upload_sends_multipart_and_returns_an_id(self):
        path = os.path.join(self.dir, "pic.png")
        with open(path, "wb") as fh:
            fh.write(b"bytes")
        self.assertEqual(self.client.upload_media(path)["id"], "UPLOADED-MEDIA-ID")

    def test_templates_need_a_business_account_id(self):
        config = config_mod.Config(**dict(self.config.as_dict(), business_account_id=""))
        client = graph.GraphClient(config, opener=local_opener())
        with self.assertRaises(config_mod.ConfigError):
            client.list_templates()


class _FakeResponse:
    def __init__(self, body, status=200, headers=None):
        self._body = body
        self.status = status
        self.headers = headers or {}

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


# --------------------------------------------------------------------------- #
#  Tools
# --------------------------------------------------------------------------- #
class TestTools(Base):
    def test_every_tool_is_well_formed(self):
        names = set()
        for spec in tools.TOOLS:
            self.assertRegex(spec["name"], r"^whatsapp_[a-z_]+$")
            self.assertNotIn(spec["name"], names)
            names.add(spec["name"])
            self.assertTrue(spec["description"])
            self.assertFalse(spec["description"].startswith(" "))
            self.assertEqual(spec["inputSchema"]["type"], "object")
            for field in spec["inputSchema"].get("required", []):
                self.assertIn(field, spec["inputSchema"]["properties"], spec["name"])
            for field, schema in spec["inputSchema"]["properties"].items():
                self.assertTrue(schema.get("type") or schema.get("anyOf"),
                                f"{spec['name']}.{field} has no type")

    def test_sending_text_records_it_and_reports_the_id(self):
        self.open_window()
        result = self.call("whatsapp_send_message", to="+966501234567", text="On its way")
        self.assertFalse(result["isError"])
        self.assertTrue(result["structuredContent"]["sent"])
        wamid = result["structuredContent"]["message_id"]
        self.assertEqual(self.api.state["sent"][-1]["text"]["body"], "On its way")
        self.assertEqual(self.store.message(wamid)["body"], "On its way")
        self.assertEqual(self.store.audit_entries()[0]["outcome"], "sent")

    def test_a_closed_window_is_an_error_result_not_a_crash(self):
        self.open_window(seconds_ago=30 * 3600)
        result = self.call("whatsapp_send_message", to=CUSTOMER, text="hi")
        self.assertTrue(result["isError"])
        self.assertEqual(result["structuredContent"]["kind"], "window_closed")
        self.assertIn("whatsapp_send_template", self.text_of(result))
        self.assertEqual(self.api.state["sent"], [])

    def test_a_template_sends_even_with_the_window_closed(self):
        self.open_window(seconds_ago=30 * 3600)
        result = self.call("whatsapp_send_template", to=CUSTOMER, template="order_update",
                           language="en_US", body_params=["A12", "today"])
        self.assertFalse(result["isError"], self.text_of(result))
        sent = self.api.state["sent"][-1]
        self.assertEqual(sent["template"]["name"], "order_update")

    def test_a_contact_can_be_addressed_by_name(self):
        self.open_window(name="Josim")
        result = self.call("whatsapp_send_message", to="josim", text="hello")
        self.assertFalse(result["isError"], self.text_of(result))
        self.assertEqual(self.api.state["sent"][-1]["to"], CUSTOMER)

    def test_an_ambiguous_name_asks_for_the_number(self):
        self.open_window("966501111111", name="Ahmed Ali")
        self.open_window("966502222222", name="Ahmed Khan")
        result = self.call("whatsapp_send_message", to="Ahmed", text="hi")
        self.assertTrue(result["isError"])
        self.assertEqual(result["structuredContent"]["kind"], "ambiguous_recipient")
        self.assertEqual(len(result["structuredContent"]["candidates"]), 2)

    def test_an_unknown_name_explains_itself(self):
        result = self.call("whatsapp_send_message", to="Nobody At All", text="hi")
        self.assertTrue(result["isError"])
        self.assertEqual(result["structuredContent"]["kind"], "unknown_recipient")

    def test_dry_run_returns_the_payload_and_sends_nothing(self):
        self.open_window()
        ctx = tools.Context(config_mod.Config(**dict(self.config.as_dict(), dry_run=True)),
                            client=self.client, store_=self.store)
        result = tools.call(ctx, "whatsapp_send_message", {"to": CUSTOMER, "text": "hi"})
        self.assertTrue(result["structuredContent"]["dry_run"])
        self.assertEqual(self.api.state["sent"], [])
        self.assertIn("DRY RUN", self.text_of(result))

    def test_buttons_and_lists_go_out_as_interactive_messages(self):
        self.open_window()
        self.call("whatsapp_send_buttons", to=CUSTOMER, body="Confirm?",
                  buttons=["Yes", {"id": "no", "title": "No"}])
        self.assertEqual(self.api.state["sent"][-1]["interactive"]["type"], "button")
        self.call("whatsapp_send_list", to=CUSTOMER, body="Pick", button="Menu",
                  sections=[{"title": "Food", "rows": [{"id": "p", "title": "Pizza"}]}])
        self.assertEqual(self.api.state["sent"][-1]["interactive"]["type"], "list")

    def test_too_many_buttons_is_an_error_result(self):
        self.open_window()
        result = self.call("whatsapp_send_buttons", to=CUSTOMER, body="b",
                           buttons=["a", "b", "c", "d"])
        self.assertTrue(result["isError"])
        self.assertEqual(result["structuredContent"]["kind"], "invalid_request")

    def test_listing_chats_reports_the_window_state(self):
        self.open_window(seconds_ago=3600)
        self.open_window("966509999999", seconds_ago=30 * 3600, name="Old Chat")
        data = self.call("whatsapp_list_chats")["structuredContent"]["chats"]
        states = {c["name"]: c["window"] for c in data}
        self.assertEqual(states["Josim"], "open")
        self.assertEqual(states["Old Chat"], "closed")

    def test_an_empty_inbox_explains_the_webhook(self):
        result = self.call("whatsapp_list_chats")
        self.assertIn("webhook", self.text_of(result))

    def test_reading_a_chat_shows_both_sides_in_order(self):
        self.open_window()
        self.store.record_message("o1", CUSTOMER, "out", ts=time.time() - 30, body="Hello back",
                                  status="read")
        data = self.call("whatsapp_read_chat", contact=CUSTOMER)["structuredContent"]
        self.assertEqual([m["from"] for m in data["messages"]], ["them", "us"])
        self.assertEqual(data["window"]["state"], "open")

    def test_unanswered_filter_finds_chats_waiting_on_us(self):
        self.open_window("966501111111", seconds_ago=60, name="Waiting")
        self.open_window("966502222222", seconds_ago=600, name="Answered")
        self.store.record_message("o9", "966502222222", "out", ts=time.time(), body="done")
        chats = self.call("whatsapp_list_chats",
                          unanswered_only=True)["structuredContent"]["chats"]
        self.assertEqual([c["name"] for c in chats], ["Waiting"])

    def test_search_finds_a_message_across_chats(self):
        self.open_window()
        self.store.record_message("s1", CUSTOMER, "in", ts=time.time(), body="my order is A-1234")
        matches = self.call("whatsapp_search_messages",
                            query="A-1234")["structuredContent"]["matches"]
        self.assertEqual(len(matches), 1)

    def test_message_status_reports_the_delivery_history(self):
        self.store.record_message("out1", CUSTOMER, "out", body="hi", status="accepted")
        self.store.record_status("out1", "delivered")
        data = self.call("whatsapp_message_status",
                         message_id="out1")["structuredContent"]
        self.assertEqual(data["status"], "delivered")

    def test_unknown_message_id_is_explained(self):
        result = self.call("whatsapp_message_status", message_id="nope")
        self.assertTrue(result["isError"])
        self.assertEqual(result["structuredContent"]["kind"], "not_found")

    def test_consent_tools_control_sending(self):
        self.open_window()
        self.call("whatsapp_consent", action="opt_out", contact=CUSTOMER, reason="asked")
        result = self.call("whatsapp_send_message", to=CUSTOMER, text="hi")
        self.assertEqual(result["structuredContent"]["kind"], "opted_out")
        listed = self.call("whatsapp_consent", action="list")["structuredContent"]["opt_outs"]
        self.assertEqual(listed[0]["number"], "+" + CUSTOMER)
        self.call("whatsapp_consent", action="opt_in", contact=CUSTOMER)
        self.assertFalse(self.call("whatsapp_send_message", to=CUSTOMER,
                                   text="hi")["isError"])

    def test_templates_are_listed_with_their_parameter_count(self):
        data = self.call("whatsapp_list_templates")["structuredContent"]["templates"]
        by_name = {t["name"]: t for t in data}
        self.assertEqual(by_name["order_update"]["body_params"], 2)
        self.assertEqual(by_name["order_update"]["status"], "APPROVED")
        approved = self.call("whatsapp_list_templates",
                             approved_only=True)["structuredContent"]["templates"]
        self.assertEqual([t["name"] for t in approved], ["order_update"])

    def test_creating_a_template_requires_one_example_per_placeholder(self):
        result = self.call("whatsapp_create_template", name="order_ready", category="UTILITY",
                           body="Order {{1}} is ready at {{2}}.", body_examples=["A12"])
        self.assertTrue(result["isError"])
        self.assertIn("example", self.text_of(result))

    def test_creating_a_template_submits_components(self):
        result = self.call("whatsapp_create_template", name="order_ready", category="UTILITY",
                           body="Order {{1}} is ready at {{2}}.",
                           body_examples=["A12", "17:00"], footer="Thank you")
        self.assertFalse(result["isError"], self.text_of(result))
        submitted = self.api.state["templates"][-1]
        kinds = [c["type"] for c in submitted["components"]]
        self.assertEqual(kinds, ["BODY", "FOOTER"])
        self.assertEqual(submitted["components"][0]["example"]["body_text"], [["A12", "17:00"]])

    def test_a_bad_template_name_is_refused_locally(self):
        result = self.call("whatsapp_create_template", name="Order Ready", category="UTILITY",
                           body="hi")
        self.assertTrue(result["isError"])
        self.assertEqual(self.api.state["templates"], [])

    def test_account_info_reads_the_number_and_profile(self):
        data = self.call("whatsapp_account_info")["structuredContent"]
        self.assertEqual(data["verified_name"], "Test Pharmacy")
        self.assertEqual(data["profile"]["about"], "We deliver medicine")

    def test_status_is_ready_when_everything_is_configured(self):
        self.open_window()
        report = self.call("whatsapp_status")["structuredContent"]
        self.assertTrue(report["ready"], report)
        self.assertNotIn(TOKEN, json.dumps(report))

    def test_status_names_what_is_missing_without_credentials(self):
        ctx = tools.Context(config_mod.Config(db_path=self.config.db_path))
        report = tools.diagnose(ctx)
        self.assertFalse(report["ready"])
        failed = [c for c in report["checks"] if c["state"] == "fail"]
        self.assertIn("WHATSAPP_ACCESS_TOKEN", failed[0]["detail"])
        self.assertIn("setup", failed[0]["fix"])

    def test_an_unknown_tool_name_raises_for_the_protocol_layer(self):
        with self.assertRaises(KeyError):
            tools.call(self.ctx, "whatsapp_not_a_tool", {})

    def test_unexpected_failures_become_error_results(self):
        class Broken:
            def send(self, payload):
                raise RuntimeError("boom")

        self.open_window()
        ctx = tools.Context(self.config, client=Broken(), store_=self.store)
        result = tools.call(ctx, "whatsapp_send_message", {"to": CUSTOMER, "text": "hi"})
        self.assertTrue(result["isError"])
        self.assertEqual(result["structuredContent"]["kind"], "server_error")

    def test_placeholder_counting(self):
        self.assertEqual(tools._placeholders("Hi {{1}}, order {{2}} on {{3}}"), ["1", "2", "3"])
        self.assertEqual(tools._placeholders("no placeholders"), [])
        self.assertEqual(tools._placeholders("{{name}} is not numbered"), [])


# --------------------------------------------------------------------------- #
#  MCP protocol
# --------------------------------------------------------------------------- #
class TestProtocol(Base):
    def exchange(self, *requests):
        stdin = io.StringIO("\n".join(json.dumps(r) for r in requests) + "\n")
        stdout = io.StringIO()
        mcp.serve_stdio(self.ctx, stdin=stdin, stdout=stdout, quiet=True)
        return [json.loads(line) for line in stdout.getvalue().splitlines() if line.strip()]

    def initialize(self, version="2025-06-18"):
        return {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": version, "capabilities": {},
                           "clientInfo": {"name": "test-client", "version": "1"}}}

    def test_handshake_advertises_tools_resources_and_prompts(self):
        [reply] = self.exchange(self.initialize())
        result = reply["result"]
        self.assertEqual(result["protocolVersion"], "2025-06-18")
        self.assertEqual(result["serverInfo"]["name"], "whatsapp-business")
        for capability in ("tools", "resources", "prompts"):
            self.assertIn(capability, result["capabilities"])
        self.assertIn("24-hour", result["instructions"])

    def test_the_client_protocol_revision_is_echoed_when_known(self):
        [reply] = self.exchange(self.initialize("2026-07-28"))
        self.assertEqual(reply["result"]["protocolVersion"], "2026-07-28")

    def test_an_unknown_revision_falls_back_to_ours(self):
        [reply] = self.exchange(self.initialize("1999-01-01"))
        self.assertEqual(reply["result"]["protocolVersion"], mcp.PROTOCOL_VERSION)

    def test_notifications_get_no_reply(self):
        replies = self.exchange(self.initialize(),
                                {"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual(len(replies), 1)

    def test_tools_list_returns_the_whole_surface(self):
        replies = self.exchange(self.initialize(),
                                {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        self.assertEqual(len(replies[1]["result"]["tools"]), len(tools.TOOLS))

    def test_tools_call_goes_through_to_the_api(self):
        self.open_window()
        replies = self.exchange(self.initialize(), {
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": "whatsapp_send_message",
                       "arguments": {"to": CUSTOMER, "text": "from MCP"}}})
        self.assertFalse(replies[1]["result"]["isError"])
        self.assertEqual(self.api.state["sent"][-1]["text"]["body"], "from MCP")

    def test_a_failing_tool_is_a_result_not_a_protocol_error(self):
        self.open_window(seconds_ago=40 * 3600)
        replies = self.exchange(self.initialize(), {
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": "whatsapp_send_message",
                       "arguments": {"to": CUSTOMER, "text": "hi"}}})
        self.assertNotIn("error", replies[1])
        self.assertTrue(replies[1]["result"]["isError"])

    def test_calling_a_tool_that_does_not_exist_is_a_protocol_error(self):
        replies = self.exchange(self.initialize(), {
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": "whatsapp_nope", "arguments": {}}})
        self.assertEqual(replies[1]["error"]["code"], mcp.INVALID_PARAMS)

    def test_unknown_methods_and_bad_json_are_reported_properly(self):
        replies = self.exchange(self.initialize(),
                                {"jsonrpc": "2.0", "id": 2, "method": "no/such/method"})
        self.assertEqual(replies[1]["error"]["code"], mcp.METHOD_NOT_FOUND)
        stdout = io.StringIO()
        mcp.serve_stdio(self.ctx, stdin=io.StringIO("{not json}\n"), stdout=stdout, quiet=True)
        self.assertEqual(json.loads(stdout.getvalue())["error"]["code"], mcp.PARSE_ERROR)

    def test_blank_lines_are_skipped(self):
        stdout = io.StringIO()
        mcp.serve_stdio(self.ctx, stdin=io.StringIO("\n\n"), stdout=stdout, quiet=True)
        self.assertEqual(stdout.getvalue(), "")

    def test_ping_answers_empty(self):
        replies = self.exchange(self.initialize(),
                                {"jsonrpc": "2.0", "id": 2, "method": "ping"})
        self.assertEqual(replies[1]["result"], {})

    def test_resources_can_be_listed_and_read(self):
        replies = self.exchange(
            self.initialize(),
            {"jsonrpc": "2.0", "id": 2, "method": "resources/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "resources/read",
             "params": {"uri": "whatsapp://setup"}},
            {"jsonrpc": "2.0", "id": 4, "method": "resources/read",
             "params": {"uri": "whatsapp://status"}},
            {"jsonrpc": "2.0", "id": 5, "method": "resources/read",
             "params": {"uri": "whatsapp://nothing"}},
        )
        uris = [r["uri"] for r in replies[1]["result"]["resources"]]
        self.assertIn("whatsapp://setup", uris)
        self.assertIn("API Setup", replies[2]["result"]["contents"][0]["text"])
        self.assertIn("checks", json.loads(replies[3]["result"]["contents"][0]["text"]))
        self.assertIn("error", replies[4])

    def test_prompts_can_be_listed_and_fetched(self):
        replies = self.exchange(
            self.initialize(),
            {"jsonrpc": "2.0", "id": 2, "method": "prompts/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "prompts/get",
             "params": {"name": "whatsapp_reply", "arguments": {"contact": "Josim"}}},
        )
        self.assertEqual(len(replies[1]["result"]["prompts"]), len(mcp.PROMPTS))
        self.assertIn("Josim", replies[2]["result"]["messages"][0]["content"]["text"])

    def test_a_batch_of_requests_is_answered(self):
        stdin = io.StringIO(json.dumps([self.initialize(),
                                        {"jsonrpc": "2.0", "id": 2, "method": "ping"}]) + "\n")
        stdout = io.StringIO()
        mcp.serve_stdio(self.ctx, stdin=stdin, stdout=stdout, quiet=True)
        replies = json.loads(stdout.getvalue())
        self.assertEqual([r["id"] for r in replies], [1, 2])

    def test_params_must_be_an_object(self):
        replies = self.exchange({"jsonrpc": "2.0", "id": 1, "method": "ping", "params": []})
        self.assertEqual(replies[0]["error"]["code"], mcp.INVALID_PARAMS)

    def test_http_transport_serves_the_same_session(self):
        self.open_window()
        httpd = mcp.serve_http(self.ctx, host="127.0.0.1", port=0, auth_token="secret")
        thread = threading.Thread(target=httpd.serve_forever,
                                  kwargs={"poll_interval": 0.02}, daemon=True)
        thread.start()
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        host, port = httpd.server_address
        base = f"http://{host}:{port}"
        opener = local_opener()

        try:
            opener.open(base + "/")
            self.fail("a missing bearer token must be rejected")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

        def post(payload):
            request = urllib.request.Request(base + "/", data=json.dumps(payload).encode(),
                                             method="POST")
            request.add_header("Content-Type", "application/json")
            request.add_header("Authorization", "Bearer secret")
            with opener.open(request) as resp:
                return resp.status, json.loads(resp.read() or b"null")

        status, body = post(self.initialize())
        self.assertEqual(status, 200)
        self.assertEqual(body["result"]["serverInfo"]["name"], "whatsapp-business")
        status, _ = post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual(status, 202)
        status, body = post({"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                             "params": {"name": "whatsapp_send_message",
                                        "arguments": {"to": CUSTOMER, "text": "over http"}}})
        self.assertFalse(body["result"]["isError"])
        self.assertEqual(self.api.state["sent"][-1]["text"]["body"], "over http")

        request = urllib.request.Request(base + "/health")
        request.add_header("Authorization", "Bearer secret")
        with opener.open(request) as resp:
            self.assertEqual(json.loads(resp.read())["server"], "whatsapp-business")


# --------------------------------------------------------------------------- #
#  Config and installer
# --------------------------------------------------------------------------- #
class TestConfig(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="wamcp-config-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.path = os.path.join(self.dir, "config.json")

    def test_saved_config_is_private_and_reloads(self):
        config_mod.save(config_mod.Config(access_token=TOKEN, phone_number_id=PHONE_ID), self.path)
        self.assertEqual(os.stat(self.path).st_mode & 0o777, 0o600)
        self.assertEqual(config_mod.load(self.path).access_token, TOKEN)

    def test_environment_beats_the_file(self):
        config_mod.save(config_mod.Config(access_token="from-file",
                                          phone_number_id=PHONE_ID), self.path)
        os.environ["WHATSAPP_ACCESS_TOKEN"] = "from-env"
        self.addCleanup(os.environ.pop, "WHATSAPP_ACCESS_TOKEN", None)
        self.assertEqual(config_mod.load(self.path).access_token, "from-env")

    def test_a_secret_can_come_from_a_file(self):
        secret_path = os.path.join(self.dir, "token.txt")
        with open(secret_path, "w", encoding="utf-8") as fh:
            fh.write(TOKEN + "\n")
        os.environ["WHATSAPP_ACCESS_TOKEN_FILE"] = secret_path
        self.addCleanup(os.environ.pop, "WHATSAPP_ACCESS_TOKEN_FILE", None)
        self.assertEqual(config_mod.load(self.path).access_token, TOKEN)

    def test_booleans_and_lists_are_read_from_strings(self):
        config = config_mod.Config(dry_run="yes", allowlist="+966500000001, +966500000002")
        self.assertTrue(config.dry_run)
        self.assertEqual(len(config.allowlist), 2)
        self.assertFalse(config_mod.Config(dry_run="0").dry_run)

    def test_secrets_are_masked_for_display(self):
        redacted = config_mod.Config(access_token=TOKEN, app_secret=APP_SECRET).redacted()
        self.assertNotIn(TOKEN, json.dumps(redacted))
        self.assertIn("...", redacted["access_token"])

    def test_missing_fields_are_named(self):
        with self.assertRaises(config_mod.ConfigError) as caught:
            config_mod.Config().require_sending()
        self.assertIn("WHATSAPP_ACCESS_TOKEN", str(caught.exception))

    def test_a_bad_number_setting_is_rejected(self):
        with self.assertRaises(config_mod.ConfigError):
            config_mod.Config(max_sends_per_hour="lots")


class TestInstall(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="wamcp-install-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.path = os.path.join(self.dir, "client.json")

    def test_block_runs_this_checkout_without_installation(self):
        block = install.server_block()
        self.assertEqual(block["args"], ["-m", "wamcp", "serve"])
        self.assertEqual(block["env"]["PYTHONPATH"], install.package_root())

    def test_secrets_are_left_out_by_default(self):
        config = config_mod.Config(access_token=TOKEN, phone_number_id=PHONE_ID)
        self.assertNotIn("WHATSAPP_ACCESS_TOKEN", install.server_block(config)["env"])
        with_secrets = install.server_block(config, with_secrets=True)["env"]
        self.assertEqual(with_secrets["WHATSAPP_ACCESS_TOKEN"], TOKEN)
        self.assertEqual(with_secrets["WHATSAPP_PHONE_NUMBER_ID"], PHONE_ID)

    def test_installing_keeps_other_servers(self):
        with open(self.path, "w", encoding="utf-8") as fh:
            json.dump({"mcpServers": {"other": {"command": "node"}},
                       "theme": "dark"}, fh)
        target, action = install.install("claude-code", path=self.path)
        self.assertEqual(action, "created")
        with open(target, encoding="utf-8") as fh:
            document = json.load(fh)
        self.assertIn("other", document["mcpServers"])
        self.assertIn("whatsapp-business", document["mcpServers"])
        self.assertEqual(document["theme"], "dark")

    def test_installing_twice_changes_nothing(self):
        install.install("claude-code", path=self.path)
        self.assertEqual(install.install("claude-code", path=self.path)[1], "unchanged")

    def test_vscode_uses_its_own_key(self):
        document = install.config_document(key=install.CLIENTS["vscode"]["key"])
        self.assertIn("servers", document)

    def test_broken_client_json_is_reported_not_overwritten(self):
        with open(self.path, "w", encoding="utf-8") as fh:
            fh.write("{oops")
        with self.assertRaises(ValueError):
            install.install("claude-code", path=self.path)

    def test_the_claude_code_one_liner_is_shell_safe(self):
        command = install.claude_code_command()
        self.assertIn("claude mcp add whatsapp-business", command)
        self.assertIn("-m wamcp serve", command)


if __name__ == "__main__":
    unittest.main(verbosity=2)
