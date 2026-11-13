"""End-to-end check against running services. Adds a clearly named demo plan/report.
Run: python tests/smoke_test.py
"""
import concurrent.futures
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"


def request(path, token=None, body=None, expected=200, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method or ("POST" if body is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            code, raw = response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        code, raw = error.code, error.read().decode("utf-8")
    assert code == expected, (path, code, raw[:600])
    try:
        return json.loads(raw)
    except ValueError:
        return raw


def main():
    tokens = {}
    for name in ("teacher", "trainee", "teacher2", "trainee2", "admin"):
        tokens[name] = request("/login", body={"username": name, "password": "Demo123!"})["token"]
    teacher, trainee, admin = tokens["teacher"], tokens["trainee"], tokens["admin"]
    request("/plans", expected=401)
    request("/login", body={"username": "teacher", "password": "wrong"}, expected=401)
    exercises = request("/exercises", trainee)
    assert len(exercises) == 3
    owner = request("/me", trainee)["id"]
    other = request("/me", tokens["trainee2"])["id"]
    assert [u["id"] for u in request("/trainees", teacher)] == [owner]
    plans = request("/plans", trainee)
    date = plans[0]["scheduled_date"]
    body = {"trainee_id": owner, "title": "肩肘训练联调演示", "scheduled_date": date,
            "note": "用于验证计划、模拟动作、报告、复核和版本调整的完整流程。",
            "items": [{"exercise_id": e["id"], "sets": 1, "repetitions": 6,
                       "target_min": e["target_min"], "target_max": e["target_max"],
                       "rest_seconds": 10, "hold_seconds": 0} for e in exercises]}
    request("/plans", trainee, body, expected=403)
    request("/plans", teacher, dict(body, trainee_id=other), expected=403)
    before = len(request("/plans", teacher))
    broken = dict(body, items=[dict(body["items"][0], sets=0)])
    request("/plans", teacher, broken, expected=400)
    assert len(request("/plans", teacher)) == before
    plan = request("/plans", teacher, body)
    assert len(plan["items"]) == 3
    request(f"/plans/{plan['id']}", tokens["teacher2"], expected=403)
    session = request("/sessions", trainee, {"plan_id": plan["id"]})
    sid = session["id"]
    # Racing completions must result in exactly one persisted batch.
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        reports = list(pool.map(lambda _: request(f"/sessions/{sid}/simulate", trainee, {"scenario": "mixed"}), range(2)))
    report = reports[0]
    assert report["total_reps"] == 18, report
    assert report["valid_reps"] == 9
    assert len(report["repetitions"]) == 18
    assert len(report["events"]) == 9
    assert len(report["samples"]) > 400
    assert report["data_source"] == "SIMULATED"
    assert request(f"/sessions/{sid}/simulate", trainee, {"scenario": "mixed"})["total_reps"] == 18
    request(f"/sessions/{sid}", tokens["teacher2"], expected=403)
    request(f"/sessions/{sid}", tokens["trainee2"], expected=403)
    request(f"/sessions/{sid}/reviews", trainee, {"decision": "COMMENT", "note": "越权测试"}, expected=403)
    event = report["events"][0]
    review = request(f"/sessions/{sid}/reviews", teacher, {"event_id": event["id"], "decision": "CONFIRMED", "note": "模拟曲线与幅度提示一致，已复核该问题。"})
    assert review["status"] == "REVIEWED" and len(review["reviews"]) == 1
    request(f"/sessions/{sid}/reviews", teacher, {"decision": "REJECTED", "note": "缺少事件"}, expected=400)
    csv = request(f"/sessions/{sid}/export", trainee)
    assert csv.startswith("\ufeff动作,") and len(csv.splitlines()) == 19
    updated = request(f"/plans/{plan['id']}/adjust", teacher, dict(body, note="根据演示报告调整下一阶段，历史报告仍绑定原计划。"))
    assert updated["version"] == 2 and updated["id"] != plan["id"]
    assert request(f"/plans/{plan['id']}", teacher)["status"] == "SUPERSEDED"
    assert request(f"/sessions/{sid}", trainee)["plan_id"] == plan["id"]
    request("/sessions", trainee, {"plan_id": plan["id"]}, expected=400)
    request("/games", trainee, {"cycles": 3, "duration_seconds": 15, "session_id": sid})
    assert request("/games", trainee)[0]["cycles"] == 3
    request("/database", trainee, expected=403)
    database = request("/database", admin)
    assert len(database["tables"]) == 17
    tables = {x["table"]: x["count"] for x in database["tables"]}
    assert tables["repetition_result"] >= 18 and tables["plan_adjustment"] >= 1
    assert request("/overview", trainee)["total_reps"] >= 18
    request("/logout", tokens["teacher2"], method="POST")
    request("/me", tokens["teacher2"], expected=401)
    print(json.dumps({"result": "PASS", "checks": ["authentication", "ownership", "plan validation", "multi-table save", "concurrent idempotency", "rule labels", "event review", "CSV export", "version history", "game link", "database overview", "logout invalidation"], "demo_plan_id": updated["id"], "demo_report_id": sid, "repetitions": 18, "events": 9, "samples": len(report["samples"])}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
