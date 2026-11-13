"""Real rollback and concurrent plan-version checks in an isolated in-memory DB.

Starts a separate Java process on 18082 and a deliberately faulty test-only
algorithm on 18001. Does not change the user's demo database.
"""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("analysis", ROOT / "algorithm/service.py")
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)
BASE = "http://127.0.0.1:18082/api"


class Stub(BaseHTTPRequestHandler):
    broken = True

    def log_message(self, *args):
        pass

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        result = analysis.simulate(payload)
        if Stub.broken:
            # Duplicate a unique timestamp only after Java has inserted sets/reps/events.
            result["sets"][0]["samples"].append(dict(result["sets"][0]["samples"][0]))
        data = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def req(path, token=None, body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def main():
    runtime = ROOT / ".runtime"
    runtime.mkdir(exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", 18001), Stub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log = (runtime / "transaction-test.log").open("w", encoding="utf-8")
    process = subprocess.Popen([shutil.which("java"), "-Dfile.encoding=UTF-8", "-jar",
                                str(ROOT / "server/target/rehab-platform-0.1.0.jar"),
                                "--server.port=18082", "--rehab.algorithm-url=http://127.0.0.1:18001",
                                "--spring.datasource.url=jdbc:h2:mem:transactiontest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"],
                               cwd=ROOT / "server", stdout=log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError("Test backend failed to start. See .runtime/transaction-test.log")
            try:
                if req("/health")[1]["status"] == "ok":
                    break
            except (OSError, ValueError):
                pass
            time.sleep(.2)
        else:
            raise RuntimeError("Test backend readiness timed out")
        token = lambda name: req("/login", body=dict(username=name, password="Demo123!"))[1]["token"]
        trainee, teacher, admin = token("trainee"), token("teacher"), token("admin")
        plan = req("/plans/1", trainee)[1]
        sid = req("/sessions", trainee, dict(plan_id=1))[1]["id"]
        code, _ = req(f"/sessions/{sid}/simulate", trainee, dict(scenario="mixed"))
        assert code == 409, code
        report = req(f"/sessions/{sid}", trainee)[1]
        assert report["status"] == "RUNNING"
        assert not report["repetitions"] and not report["events"] and not report["samples"]
        counts = {r["table"]: r["count"] for r in req("/database", admin)[1]["tables"]}
        assert counts["session_set"] == counts["repetition_result"] == counts["issue_event"] == counts["feature_sample"] == 0, counts
        Stub.broken = False
        code, report = req(f"/sessions/{sid}/simulate", trainee, dict(scenario="mixed"))
        assert code == 200 and report["total_reps"] == 12
        body = {"title": plan["title"], "trainee_id": plan["trainee_id"],
                "scheduled_date": plan["scheduled_date"], "note": "isolated concurrent-version test",
                "items": plan["items"]}
        before = len(req("/plans", teacher)[1])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: req("/plans/1/adjust", teacher, body), range(2)))
        assert sorted(code for code, _ in results) == [200, 400], results
        assert len(req("/plans", teacher)[1]) == before + 1
        print("PASS: real multi-table rollback, clean retry, concurrent plan-version serialization (isolated in-memory database).")
    finally:
        process.terminate()
        process.wait(timeout=15)
        server.shutdown()
        server.server_close()
        log.close()


if __name__ == "__main__":
    main()
