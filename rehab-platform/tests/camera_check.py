"""Synthetic camera-input integration checks in an isolated, temporary database.

The local Python service must be running. No webcam or patient data is used.
"""
import concurrent.futures
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import smoke_test

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "algorithm"))
from test_observation import frames


def check():
    request = smoke_test.request
    token = lambda name: request("/login", body=dict(username=name, password="Demo123!"))["token"]
    trainee, teacher, outsider, admin = (token(n) for n in ("trainee", "teacher", "teacher2", "admin"))
    exercises = request("/exercises", teacher)
    me = request("/me", trainee)
    plan = request("/plans", teacher, dict(title="隔离测试：合成关键点，不代表真人效果", trainee_id=me["id"],
                   scheduled_date="2026-11-13", note="仅验证数据流程，合成数据不是模型精度证据。",
                   items=[dict(exercise_id=e["id"], sets=1, repetitions=2, target_min=130,
                               target_max=150, rest_seconds=0, hold_seconds=0, min_duration=2, max_duration=8) for e in exercises]))
    assert all(i["min_duration"]==2 and i["max_duration"]==8 for i in plan["items"])
    manifest = json.loads((ROOT / "web/public/models/manifest.json").read_text(encoding="utf-8"))
    captures = []
    for n, item in enumerate(plan["items"]):
        samples = frames(item["code"])
        for f in samples:
            f["time"] += n*20
        captures.append(dict(item_id=item["id"], set_index=1, side="RIGHT", width=640, height=480, frames=samples))
    body = dict(captures=captures, evaluation_mode="OBSERVATION", model_sha256=manifest["sha256"],
                subjective=dict(pain_before=1, pain_after=2), stop_reason="隔离测试结束", client_total_reps=999)
    sid = request("/sessions", trainee, dict(plan_id=plan["id"], data_source="CAMERA"))["id"]
    request(f"/sessions/{sid}/simulate", trainee, dict(scenario="mixed"), expected=400)
    request(f"/sessions/{sid}/capture", outsider, body, expected=403)
    request(f"/sessions/{sid}/capture", trainee, dict(body, model_sha256="incorrect"), expected=400)
    request(f"/sessions/{sid}/capture", trainee, dict(body, evaluation_mode="TARGET"), expected=400)
    broken = copy.deepcopy(body); broken["captures"][0]["frames"][1]["time"] = 0
    request(f"/sessions/{sid}/capture", trainee, broken, expected=400)
    assert request(f"/sessions/{sid}", trainee)["status"] == "RUNNING"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        reports = list(pool.map(lambda _: request(f"/sessions/{sid}/capture", trainee, body), range(2)))
    r = reports[0]
    assert all(x["total_reps"] == 6 for x in reports)
    assert r["valid_reps"] == 0 and r["data_source"] == "CAMERA"
    assert r["assessment"]["task_met_cycles"] is None
    assert len(r["samples"]) == 270 and len(r["samples"][0]["landmarks"]) == 33
    assert len(r["repetitions"]) == 6 and r["assessment"]["subjective"]["pain_after"] == 2
    assert all(x.get("amplitude_ok") is None for x in r["repetitions"])
    sid2 = request("/sessions", trainee, dict(plan_id=plan["id"], data_source="CAMERA"))["id"]
    r = request(f"/sessions/{sid2}/capture", trainee, dict(body, evaluation_mode="TARGET", target_confirmed=True))
    assert r["assessment"]["task_met_cycles"] == 0
    assert len(r["events"]) == 6 and all(e["code"] == "AMPLITUDE" for e in r["events"])
    assert all(x.get("trunk_ok") is None for x in r["repetitions"])
    r = request(f"/sessions/{sid2}/reviews", teacher, dict(event_id=r["events"][0]["id"], decision="CONFIRMED", note="仅复核合成几何测试，不代表临床意见。"))
    assert len(r["reviews"]) == 1 and r["assessment"]["model"]["sha256"] == manifest["sha256"]
    assert len(r["assessment"]["references"]["exercises"]) == 3
    counts = {x["table"]: x["count"] for x in request("/database", admin)["tables"]}
    assert counts["observation_record"] == 2, counts
    print("PASS: camera inputs, server recomputation, target confirmation, nullable observation results, replay evidence, source snapshots, permissions, concurrent completion and review.")


def main():
    smoke_test.BASE = "http://127.0.0.1:18083/api"
    runtime = ROOT / ".runtime"
    with (runtime / "camera-test.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([shutil.which("java"), "-Dfile.encoding=UTF-8", "-jar",
                    str(ROOT / "server/target/rehab-platform-0.1.0.jar"), "--server.port=18083",
                    "--spring.datasource.url=jdbc:h2:mem:cameratest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"],
                    cwd=ROOT / "server", stdout=log, stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            for _ in range(100):
                if process.poll() is not None: raise RuntimeError("Test backend failed; see .runtime/camera-test.log")
                try:
                    if smoke_test.request("/health")["status"] == "ok": break
                except OSError: pass
                time.sleep(.2)
            else: raise RuntimeError("Readiness timed out")
            smoke_test.main()
            check()
        finally:
            process.terminate(); process.wait(timeout=15)


if __name__ == "__main__":
    main()
