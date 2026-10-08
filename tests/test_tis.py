import itertools
import unittest

from tis import db, exceptions, optimizer, sim, twin
from tis.agents import TransportAI, User, parse_truck
from tis.knowledge import KnowledgeBase
from tis.llm import DataBoundaryError, LocalLLM, Pseudonymizer, assert_private_endpoint

COORD = User("coord", "coordinator")
SUPER = User("sara", "supervisor")


def fresh():
    conn = db.create(":memory:")
    sim.generate(conn)
    return TransportAI(conn)


class TestTwinAndExceptions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ai = fresh()

    def types(self, truck):
        return {e.type for e in self.ai.twin[truck].exceptions}

    def test_fleet_size_and_statuses(self):
        self.assertEqual(len(self.ai.twin), 45)
        self.assertEqual(self.ai.twin["TRK-044"].status, "MAINTENANCE")
        self.assertEqual(self.ai.twin["TRK-038"].status, "AVAILABLE")

    def test_injected_scenarios_are_detected(self):
        self.assertIn("CUSTOMER_WAITING", self.types("TRK-007"))
        self.assertIn("DELAY", self.types("TRK-007"))
        self.assertIn("IDLE", self.types("TRK-012"))
        self.assertIn("ROUTE_DEVIATION", self.types("TRK-019"))
        self.assertIn("BREAKDOWN", self.types("TRK-023"))
        self.assertEqual(self.ai.twin["TRK-023"].risk, "CRITICAL")
        self.assertIn("GPS_STALE", self.types("TRK-031"))
        self.assertIn("MISSING_INFO", self.types("TRK-034"))
        self.assertIn("DOCK_CLOSED_RISK", self.types("TRK-036"))

    def test_normal_truck_waiting_is_not_an_exception(self):
        st = self.ai.twin["TRK-027"]
        self.assertEqual(st.status, "AT_CUSTOMER")
        self.assertEqual(st.dwell_min, 31)
        self.assertEqual(st.exceptions, [])

    def test_root_cause(self):
        self.assertIn("dock", exceptions.likely_cause(self.ai.twin["TRK-007"]))
        self.assertIn("late departure", exceptions.likely_cause(self.ai.twin["TRK-036"]))

    def test_generation_is_deterministic(self):
        other = fresh()
        a = [(e.truck_id, e.type) for e in self.ai.exceptions]
        b = [(e.truck_id, e.type) for e in other.exceptions]
        self.assertEqual(a, b)


class TestOptimizer(unittest.TestCase):
    def test_hungarian_matches_brute_force(self):
        import random
        rng = random.Random(1)
        for _ in range(30):
            n, m = rng.randint(1, 5), rng.randint(5, 6)
            cost = [[rng.randint(0, 50) for _ in range(m)] for _ in range(n)]
            cols = optimizer.hungarian(cost)
            best = min(sum(cost[i][p[i]] for i in range(n)) for p in itertools.permutations(range(m), n))
            self.assertEqual(sum(cost[i][cols[i]] for i in range(n)), best)
            self.assertEqual(len(set(cols)), n)

    def test_constraints(self):
        loc = {"O": (24.6, 46.8), "C": (24.7, 46.7)}
        trucks = [dict(id="dry", capacity=24, reefer=0, pos=loc["O"], driving_min_today=0),
                  dict(id="tired", capacity=18, reefer=1, pos=loc["O"], driving_min_today=599)]
        ships = [dict(id="cold", origin_id="O", customer_id="C", pallets=5, reefer=1),
                 dict(id="big", origin_id="O", customer_id="C", pallets=30, reefer=0)]
        res = optimizer.plan(trucks, ships, loc)
        self.assertEqual(res["assignments"], [])
        reasons = {u["shipment"]: " ".join(u["reasons"]) for u in res["unassigned"]}
        self.assertIn("refrigerated", reasons["cold"])
        self.assertIn("driving left", reasons["cold"])
        self.assertIn("capacity", reasons["big"])


class TestKnowledge(unittest.TestCase):
    kb = KnowledgeBase()

    def test_retrieves_right_sop(self):
        hits = self.kb.search("receiving dock is closed when the driver arrives")
        self.assertEqual(hits[0][1].doc_id, "SOP-DLV-04")
        self.assertEqual(self.kb.search("tyre burst on the highway")[0][1].doc_id, "SOP-FLT-01")

    def test_access_control(self):
        q = "late delivery penalty hospitals rate card"
        self.assertNotIn("FIN-TRN-01", [c.doc_id for _, c in self.kb.search(q, role="coordinator")])
        self.assertIn("FIN-TRN-01", [c.doc_id for _, c in self.kb.search(q, role="supervisor")])


class TestAgents(unittest.TestCase):
    def test_parse_truck(self):
        self.assertEqual(parse_truck("What's happening with 27?"), "TRK-027")
        self.assertEqual(parse_truck("why is trk-7 late"), "TRK-007")
        self.assertIsNone(parse_truck("trucks within 100 km of MLP"))

    def test_routing(self):
        ai = fresh()
        cases = {"Which trucks are delayed?": "Exception", "What's happening with 27?": "Tracking",
                 "Why is TRK-012 late?": "Exception", "Which trucks are free?": "Tracking",
                 "Which drivers completed the most trips?": "KPI",
                 "What shipments haven't been dispatched?": "Dispatcher",
                 "Prepare tomorrow's dispatch plan": "Dispatcher",
                 "What should I do if the dock is closed?": "Knowledge",
                 "morning report": "Reporting", "What requires attention right now?": "Exception"}
        for q, agent in cases.items():
            self.assertEqual(ai.ask(q, COORD).agent, agent, q)

    def test_available_radius(self):
        ans = fresh().ask("Show me all trucks available within 100 km of MLP", COORD)
        trucks = {r["truck"] for r in ans.facts["available"]}
        self.assertIn("TRK-038", trucks)
        self.assertNotIn("TRK-043", trucks)       # sits in Jeddah
        self.assertTrue(all(r["distance_km"] <= 100 for r in ans.facts["available"]))


class TestApprovalGate(unittest.TestCase):
    def test_plan_requires_supervisor_approval(self):
        ai = fresh()
        ans = ai.ask("prepare a dispatch plan", COORD)
        self.assertIsNotNone(ans.approval_id)
        pending = lambda: ai.conn.execute("SELECT COUNT(*) FROM shipment WHERE status='PENDING'").fetchone()[0]
        self.assertEqual(pending(), 9)                       # nothing executed yet
        with self.assertRaises(PermissionError):
            ai.decide(ans.approval_id, COORD)                # coordinator cannot approve
        with self.assertRaises(PermissionError):
            ai.decide(ans.approval_id, User("coord", "supervisor"))   # nor approve own request
        self.assertEqual(ai.decide(ans.approval_id, SUPER), "APPROVED")
        self.assertEqual(pending(), 1)                       # only the oversize load remains
        actions = [r["action"] for r in ai.conn.execute("SELECT action FROM audit_log")]
        self.assertIn("approval_requested", actions)
        self.assertIn("approval_approved", actions)

    def test_viewer_cannot_plan(self):
        with self.assertRaises(PermissionError):
            fresh().ask("prepare a dispatch plan", User("v", "viewer"))


class TestDataBoundary(unittest.TestCase):
    def test_private_endpoints_allowed(self):
        assert_private_endpoint("http://127.0.0.1:8000")
        assert_private_endpoint("http://10.20.0.5:8000")
        assert_private_endpoint("http://localhost:11434")

    def test_public_endpoint_refused(self):
        with self.assertRaises(DataBoundaryError):
            LocalLLM("http://8.8.8.8", "any")

    def test_pseudonymisation_round_trip(self):
        p = Pseudonymizer(["Khalid F.", "Ali C."])
        masked = p.mask("Khalid F. (+966512345678, id 1234567890) waits; Ali C. is free")
        self.assertNotIn("Khalid", masked)
        self.assertNotIn("+9665", masked)
        self.assertNotIn("1234567890", masked)
        self.assertEqual(p.unmask(masked).split(" (")[0], "Khalid F.")

    def test_local_model_never_sees_driver_names(self):
        import json
        import threading
        from http.server import BaseHTTPRequestHandler, HTTPServer
        seen = []

        class Fake(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.append(json.dumps(body))
                user_msg = body["messages"][1]["content"]
                token = next(w for w in user_msg.replace(".", " ").split() if w.startswith("DRIVER_"))
                out = json.dumps({"choices": [{"message": {"content": f"{token} is waiting at the customer."}}]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *a):
                pass

        srv = HTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            ai = fresh()
            ai.llm = LocalLLM(f"http://127.0.0.1:{srv.server_port}", "fake")
            name = ai.twin["TRK-027"].driver_name
            ans = ai.ask("What's happening with 27?", COORD)
        finally:
            srv.shutdown()
        self.assertNotIn(name, seen[0])               # model got a pseudonym only
        self.assertIn(name, ans.text)                 # answer is re-identified locally


if __name__ == "__main__":
    unittest.main()
