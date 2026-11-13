"""Dependency-free first-version analysis service. All generated data are SIMULATED.

No camera recognition or trained-model inference is claimed in this version.
The deterministic rule outputs are computed from generated motion features.
"""
import json
import math
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "python-rule-demo-1.0"


def simulate(payload):
    items = payload.get("items", [])
    scenario = payload.get("scenario", "mixed")
    if not items or len(items) > 3 or scenario not in ("mixed", "standard"):
        raise ValueError("invalid exercise items or scenario")
    timeline = 0.0
    result_sets = []
    for item in items:
        sets, repetitions = int(item["sets"]), int(item["repetitions"])
        low, high = float(item["target_min"]), float(item["target_max"])
        hold = float(item.get("hold_seconds", 0))
        if not (1 <= sets <= 5 and 1 <= repetitions <= 20 and 0 <= low < high <= 180 and 0 <= hold <= 10):
            raise ValueError("exercise configuration out of range")
        for set_no in range(1, sets + 1):
            reps, samples = [], []
            for rep_no in range(1, repetitions + 1):
                insufficient = scenario == "mixed" and rep_no % 4 == 0
                fast = scenario == "mixed" and rep_no % 5 == 0
                shifted = scenario == "mixed" and rep_no % 6 == 0
                peak = low * 0.65 if insufficient else (low + high) / 2
                moving = 1.4 if fast else 3.6
                duration = moving + hold
                start = timeline
                trunk = float(item["trunk_limit"]) + 5 if shifted else 3.0
                ascent, descent = moving * 0.45, moving * 0.55
                count = max(30, math.ceil(duration * 8))
                for frame in range(count + 1):
                    t = frame / count * duration
                    if t < ascent:
                        angle = peak * math.sin(t / ascent * math.pi / 2)
                        phase = "RAISE"
                    elif t <= ascent + hold:
                        angle, phase = peak, "HOLD"
                    else:
                        angle = peak * max(0, math.cos((t - ascent - hold) / descent * math.pi / 2))
                        phase = "RETURN"
                    # Exclude the last duplicate time at the next repetition boundary.
                    if frame < count:
                        samples.append({"time": round(start + t, 5), "angle": round(angle, 3),
                                        "trunk": round(trunk * math.sin(t / duration * math.pi), 3),
                                        "visibility": 0.98, "phase": phase})
                amplitude_ok = low <= peak <= high
                tempo_ok = float(item["min_duration"]) <= moving <= float(item["max_duration"])
                trunk_ok = trunk <= float(item["trunk_limit"])
                hold_ok = hold == 0 or amplitude_ok
                events = []

                def event(code, message, observed, threshold, when):
                    events.append({"code": code, "message": message, "observed": round(observed, 3),
                                   "threshold_value": threshold, "event_time": round(when, 5)})

                if not amplitude_ok:
                    event("AMPLITUDE", "本次动作幅度未达到计划目标区间", peak, f"{low:g}–{high:g}°", start + ascent)
                if not tempo_ok:
                    event("TEMPO", "动作运动阶段过快，请放慢节奏", moving,
                          f"{float(item['min_duration']):g}–{float(item['max_duration']):g} 秒（不含保持）", start + duration / 2)
                if not trunk_ok:
                    event("TRUNK", "观察到躯干偏移，请指导者复核", trunk,
                          f"≤ {float(item['trunk_limit']):g}°", start + duration / 2)
                if not hold_ok:
                    event("HOLD", "未在计划目标区间完成保持", 0, f"≥ {hold:g} 秒", start + ascent + hold)
                reps.append({"rep_index": rep_no, "start_time": round(start, 5),
                             "end_time": round(start + duration, 5), "peak_angle": round(peak, 3),
                             "duration_seconds": round(duration, 3), "amplitude_ok": amplitude_ok,
                             "tempo_ok": tempo_ok, "trunk_ok": trunk_ok, "hold_ok": hold_ok,
                             "complete": True, "events": events})
                timeline += duration
            result_sets.append({"item_id": int(item["id"]), "set_index": set_no, "reps": reps, "samples": samples})
            timeline += int(item["rest_seconds"]) if set_no < sets else 2
    return {"version": VERSION, "data_source": "SIMULATED", "duration": round(timeline - 2, 5), "sets": result_sets}


class Handler(BaseHTTPRequestHandler):
    def respond(self, code, value):
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self.respond(200, {"status": "ok", "version": VERSION, "mode": "SIMULATED"})
        else:
            self.respond(404, {"message": "not found"})

    def do_POST(self):
        if self.path != "/simulate":
            return self.respond(404, {"message": "not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 1024 * 1024:
                raise ValueError("request too large or empty")
            self.respond(200, simulate(json.loads(self.rfile.read(length))))
        except (ValueError, KeyError, TypeError) as error:
            self.respond(400, {"message": str(error)})


if __name__ == "__main__":
    port = int(os.environ.get("ALGORITHM_PORT", "8001"))
    print(f"Python analysis service: http://127.0.0.1:{port} (SIMULATED DATA ONLY)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
