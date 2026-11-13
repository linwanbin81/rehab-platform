"""Geometry and task comparison for camera-derived landmarks.

Engineering observation rules, not a diagnostic or clinically validated model.
The downloaded neural network extracts landmarks; this module evaluates time
series and compares them with separately configured individual task targets.
"""
import math
import statistics

VERSION = "camera-observation-0.2.0"
VISIBILITY = 0.65


def angle(a, b, c):
    v = [a[0] - b[0], a[1] - b[1]]
    w = [c[0] - b[0], c[1] - b[1]]
    norm = math.hypot(*v) * math.hypot(*w)
    if norm < 1e-8:
        return None
    return math.degrees(math.acos(max(-1, min(1, sum(x*y for x, y in zip(v, w)) / norm))))


def feature(frame, width, height, code, side):
    points = frame.get("landmarks", [])
    invalid = dict(valid=False, angle=None, trunk=None, visibility=0.0, reason="未识别到完整人体关键点")
    if frame.get("pose_count", 1) > 1:
        return dict(invalid, reason="检测到多人，请确保画面中只有训练者本人")
    if len(points) != 33 or frame.get("pose_count", 1) != 1:
        return invalid
    shoulder, elbow, wrist, hip = (12, 14, 16, 24) if side == "RIGHT" else (11, 13, 15, 23)
    ids = [shoulder, elbow, wrist, hip]
    try:
        for p in points:
            if not all(math.isfinite(float(p[k])) for k in ("x", "y", "visibility")):
                return invalid
            if not 0 <= float(p["visibility"]) <= 1 or not 0 <= float(p.get("presence", 1)) <= 1:
                return invalid
        confidence = min(min(float(points[i]["visibility"]), float(points[i].get("presence", 1))) for i in ids)
        if confidence < VISIBILITY:
            return dict(invalid, visibility=confidence, reason="肩肘腕髋的可见性不足，暂停计数")
        if any(not .01 <= float(points[i][axis]) <= .99 for i in ids for axis in ("x", "y")):
            return dict(invalid, visibility=confidence, reason="关键关节接近画面边缘或出画")
        p = lambda i: (float(points[i]["x"])*width, float(points[i]["y"])*height)
        if math.dist(p(shoulder), p(hip)) < 20:
            return dict(invalid, visibility=confidence, reason="躯干关键点投影异常，请调整机位")
        if math.dist(p(shoulder), p(elbow)) < 12 or math.dist(p(elbow), p(wrist)) < 10:
            return dict(invalid, visibility=confidence, reason="手臂投影过短，请调整侧面或正面机位")
        value = 180-angle(p(shoulder), p(elbow), p(wrist)) if code == "elbow_flexion" else angle(p(hip), p(shoulder), p(elbow))
        torso = math.degrees(math.atan2(p(shoulder)[0]-p(hip)[0], p(hip)[1]-p(shoulder)[1]))
        return dict(valid=True, angle=value, trunk=torso, visibility=confidence, reason="")
    except (ValueError, TypeError, KeyError):
        return invalid


class CycleObserver:
    def __init__(self, item, evaluate=False):
        self.item, self.evaluate = item, evaluate
        self.calibration, self.baseline, self.trunk_base = [], None, None
        self.last_time, self.smooth, self.last_valid = None, None, None
        self.current, self.phase, self.results = None, "CALIBRATING", []
        self.attempts, self.interruptions = 0, 0
        self.return_since, self.rest_since = None, None
        self.max_hold, self.hold_since = 0, None

    def update(self, time, f):
        dt = 0 if self.last_time is None else time-self.last_time
        self.last_time = time
        if not f["valid"]:
            self.phase = "UNRELIABLE"
            self.hold_since, self.return_since, self.rest_since = None, None, None
            if self.baseline is None:
                self.calibration.clear()
            # Never use a missing measurement as a zero/straight arm.
            if self.last_valid is not None and time-self.last_valid > .8:
                if self.current:
                    self.attempts += 1
                    self.interruptions += 1
                self.current, self.baseline, self.trunk_base = None, None, None
                self.calibration.clear()
                self.smooth, self.return_since, self.rest_since = None, None, None
            return None
        if self.last_valid is not None and time-self.last_valid > .8:
            if self.current:
                self.attempts += 1
                self.interruptions += 1
            self.current, self.baseline, self.trunk_base = None, None, None
            self.calibration.clear()
            self.smooth, self.return_since, self.rest_since, self.hold_since = None, None, None, None
        self.last_valid = time
        self.smooth = f["angle"] if self.smooth is None else self.smooth + (1-math.exp(-min(dt, .2)/.12))*(f["angle"]-self.smooth)
        value = self.smooth
        if self.baseline is None:
            self.calibration.append((time, value, f["trunk"]))
            self.calibration = [x for x in self.calibration if time-x[0] <= 1.2]
            self.phase = "CALIBRATING"
            if len(self.calibration) >= 6 and self.calibration[-1][0]-self.calibration[0][0] >= .7:
                values = [x[1] for x in self.calibration]
                if max(values)-min(values) <= 6:
                    self.baseline = statistics.median(values)
                    self.trunk_base = statistics.median(x[2] for x in self.calibration)
                    self.phase = "READY"
            return None
        trunk = abs((f["trunk"]-self.trunk_base+180) % 360-180)
        if self.current is None:
            self.phase = "READY"
            if value >= self.baseline+12:
                self.current = dict(start=time, peak=value, peak_time=time, trunk=trunk)
                self.max_hold, self.hold_since = 0, None
                self.return_since, self.rest_since = None, None
                self.phase = "RAISE"
            return None
        current = self.current
        if time-current["start"] > 30:
            self.attempts += 1
            self.current, self.baseline = None, None
            self.calibration.clear()
            self.phase = "CALIBRATING"
            return None
        if value > current["peak"]:
            current["peak"], current["peak_time"] = value, time
        current["trunk"] = max(current["trunk"], trunk)
        in_target = float(self.item["target_min"]) <= value <= float(self.item["target_max"])
        if in_target and self.evaluate:
            if self.hold_since is None:
                self.hold_since = time
            self.max_hold = max(self.max_hold, time-self.hold_since)
        else:
            self.hold_since = None
        if current["peak"]-value >= 6:
            if self.return_since is None:
                self.return_since = time
            if time-self.return_since >= .15:
                self.phase = "RETURN"
        else:
            self.return_since = None
            self.phase = "HOLD" if self.evaluate and in_target else "RAISE"
        if self.phase == "RETURN" and value <= self.baseline+6:
            if self.rest_since is None:
                self.rest_since = time
            if time-self.rest_since >= .2 and time-current["start"] >= .6:
                result = self.finish(time)
                self.current = None
                self.phase = "READY"
                self.results.append(result)
                return result
        else:
            self.rest_since = None
        return None

    def finish(self, time):
        c, item = self.current, self.item
        duration = time-c["start"]
        hold_required = float(item.get("hold_seconds", 0))
        amplitude = float(item["target_min"]) <= c["peak"] <= float(item["target_max"])
        tempo = float(item["min_duration"]) <= duration <= float(item["max_duration"])
        hold = self.max_hold >= max(0, hold_required-.15)
        events = []
        def add(code, message, observed, threshold, when):
            events.append(dict(code=code, message=message, observed=round(observed, 3), threshold_value=threshold, event_time=round(when, 4)))
        if self.evaluate and not amplitude:
            add("AMPLITUDE", "测得峰值不在本计划个体目标区间内；请指导者复核", c["peak"], f"{item['target_min']}–{item['target_max']}°（个体计划）", c["peak_time"])
        if self.evaluate and not tempo:
            add("TEMPO", "完整循环耗时与本计划节奏要求不一致", duration, f"{item['min_duration']}–{item['max_duration']} 秒（个体计划）", time)
        if self.evaluate and not hold:
            add("HOLD", "未观察到满足计划时长的连续目标区间保持", self.max_hold, f"{hold_required:g} 秒（个体计划）", c["peak_time"])
        if c["trunk"] > float(item["trunk_limit"]):
            add("TRUNK", "观察到相对起始姿势的躯干投影偏移；该提示需人工复核", c["trunk"], f"{item['trunk_limit']}°（工程观察阈值）", c["peak_time"])
        return dict(rep_index=len(self.results)+1, start_time=round(c["start"], 4), end_time=round(time, 4),
                    peak_angle=round(c["peak"], 2), duration_seconds=round(duration, 3),
                    amplitude_ok=amplitude if self.evaluate else None, tempo_ok=tempo if self.evaluate else None,
                    trunk_ok=None, hold_ok=hold if self.evaluate else None, complete=True,
                    hold_observed=round(self.max_hold, 2), trunk_peak=round(c["trunk"], 2),
                    task_met=(amplitude and tempo and hold) if self.evaluate else None, events=events)


def analyze(payload):
    items = {int(x["id"]): x for x in payload["items"]}
    captures = payload.get("captures", [])
    if not captures or len(captures) > 15:
        raise ValueError("需要 1～15 组真实采集数据")
    evaluate = payload.get("evaluation_mode") == "TARGET"
    total_frames = sum(len(c.get("frames", [])) for c in captures)
    if total_frames < 2 or total_frames > 6000:
        raise ValueError("每次报告需要 2～6000 帧，超出时请拆分训练")
    sets, all_angles, tracked_duration, observed_duration = [], [], 0., 0.
    invalid_frames, unfinished, interruptions, valid_frames, last_global = 0, 0, 0, 0, -1.
    seen = set()
    for capture in captures:
        item_id, set_no = int(capture["item_id"]), int(capture["set_index"])
        if item_id not in items or (item_id, set_no) in seen or not 1 <= set_no <= int(items[item_id]["sets"]):
            raise ValueError("采集动作或组号与计划不匹配")
        seen.add((item_id, set_no))
        item = items[item_id]
        width, height = float(capture["width"]), float(capture["height"])
        if not 100 <= width <= 4096 or not 100 <= height <= 4096:
            raise ValueError("输入画面尺寸无效")
        observer, samples = CycleObserver(item, evaluate), []
        previous_time, previous_valid = None, False
        for frame in capture["frames"]:
            time = float(frame["time"])
            if not math.isfinite(time) or not 0 <= time <= 3600 or time <= last_global:
                raise ValueError("采集时间戳必须在会话内严格递增")
            last_global = time
            f = feature(frame, width, height, item["code"], capture.get("side", "RIGHT"))
            if previous_time is not None:
                interval = time-previous_time
                observed_duration += interval
                if f["valid"] and previous_valid and interval <= .5:
                    tracked_duration += interval
            previous_time, previous_valid = time, f["valid"]
            observer.update(time, f)
            if f["valid"]:
                valid_frames += 1
                all_angles.append(f["angle"])
            else:
                invalid_frames += 1
            drift = abs((f["trunk"]-(observer.trunk_base if observer.trunk_base is not None else f["trunk"])+180) % 360-180) if f["valid"] else None
            samples.append(dict(time=round(time, 4), angle=round(observer.smooth, 3) if f["valid"] else None,
                                trunk=round(drift, 3) if drift is not None else 0, visibility=round(f["visibility"], 3),
                                phase=observer.phase, quality_reason=f["reason"], landmarks=frame.get("landmarks", [])))
        unfinished += observer.attempts + int(observer.current is not None)
        interruptions += observer.interruptions
        sets.append(dict(item_id=item_id, set_index=set_no, reps=observer.results, samples=samples))
    reps = [r for s in sets for r in s["reps"]]
    peaks = [r["peak_angle"] for r in reps]
    by_exercise = []
    for item in items.values():
        own_sets = [s for s in sets if s["item_id"] == int(item["id"])]
        own_reps = [r for s in own_sets for r in s["reps"]]
        by_exercise.append(dict(code=item["code"], name=item.get("name", item["code"]),
                                recorded_sets=len(own_sets), planned_sets=int(item["sets"]),
                                completed_cycles=len(own_reps), planned_cycles=int(item["sets"])*int(item["repetitions"]),
                                peak_median=round(statistics.median(r["peak_angle"] for r in own_reps), 2) if own_reps else None,
                                duration_median=round(statistics.median(r["duration_seconds"] for r in own_reps), 2) if own_reps else None,
                                task_met_cycles=sum(r["task_met"] is True for r in own_reps) if evaluate else None,
                                cycle_min_seconds=item["min_duration"] if evaluate else None,
                                cycle_max_seconds=item["max_duration"] if evaluate else None,
                                hold_required_seconds=item.get("hold_seconds", 0) if evaluate else None,
                                target_min=item["target_min"] if evaluate else None, target_max=item["target_max"] if evaluate else None))
    report = dict(report_type="康复训练动作观察报告", evaluation_mode="TARGET" if evaluate else "OBSERVATION",
                  measurement="指定机位下二维投影角度；不是临床量角器测量值", total_frames=total_frames,
                  tracked_frames=valid_frames, invalid_frames=invalid_frames,
                  reliable_time_seconds=round(tracked_duration, 2), observed_time_seconds=round(observed_duration, 2),
                  reliable_time_ratio=round(tracked_duration/observed_duration, 4) if observed_duration else 0,
                  incomplete_attempts=unfinished, interrupted_attempts=interruptions,
                  completed_cycles=len(reps), planned_cycles=sum(int(x["sets"])*int(x["repetitions"]) for x in items.values()),
                  task_met_cycles=sum(r["task_met"] is True for r in reps) if evaluate else None,
                  by_exercise=by_exercise,
                  peak_median=round(statistics.median(peaks), 2) if peaks else None,
                  peak_min=min(peaks) if peaks else None, peak_max=max(peaks) if peaks else None,
                  subjective=payload.get("subjective", {}), model=payload.get("model", {}),
                  stop_reason=payload.get("stop_reason", "用户结束"),
                  conclusion="已记录动作表现，待指导者结合病史和实际情况复核。" if reps else "未识别到完整动作循环，不据此判断动作质量或康复状态。",
                  limitations=["未验证诊断、疗效或损伤风险预测能力", "遮挡、衣着、机位和出平面运动可改变角度估计", "可见性是模型输出，不是临床测量准确度", "运动起止和计数阈值为工程规则，需通过独立采集验证"],
                  engineering_rules=dict(visibility=VISIBILITY, calibration_seconds=.7, start_excursion_degrees=12, return_hysteresis_degrees=6, occlusion_reset_seconds=.8))
    return dict(version=VERSION, data_source=payload.get("data_source", "CAMERA"), duration=round(last_global, 4), sets=sets, assessment=report)
