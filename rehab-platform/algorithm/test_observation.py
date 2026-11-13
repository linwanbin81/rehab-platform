"""Synthetic geometry tests; these do not establish clinical accuracy."""
import json
import math
import unittest
from pathlib import Path
from clinical_analysis import analyze, feature, CycleObserver


def item(code="elbow_flexion"):
    return dict(id=1, code=code, name=code, sets=1, repetitions=2, target_min=70,
                target_max=120, min_duration=2, max_duration=6, trunk_limit=12, hold_seconds=0)


def frame(t, degrees, code="elbow_flexion", visible=True):
    p = [dict(x=.45, y=.5, visibility=1., presence=1.) for _ in range(33)]
    p[12].update(x=.45, y=.3)
    p[24].update(x=.45, y=.7)
    a = math.radians(degrees)
    if code == "elbow_flexion":
        p[14].update(x=.45, y=.5)
        p[16].update(x=.45+.15*math.sin(a), y=.5+.2*math.cos(a))
    else:
        p[14].update(x=.45+.15*math.sin(a), y=.3+.2*math.cos(a))
        p[16].update(x=.45+.30*math.sin(a), y=.3+.4*math.cos(a))
    if not visible:
        p[14]["visibility"] = .1
    return dict(time=t, pose_count=1, landmarks=p)


def frames(code="elbow_flexion"):
    out = []
    for i in range(90):
        t = i*.125
        pos = (t-1.5) % 4.5
        value = 0 if t < 1.5 else 100*min(1, max(0, min(pos/1.25, (3.5-pos)/1.25)))
        out.append(frame(t, value, code))
    return out


def payload(code="elbow_flexion", evaluate=False, sample_frames=None):
    return dict(items=[item(code)], captures=[dict(item_id=1, set_index=1, width=640,
                height=480, side="RIGHT", frames=sample_frames or frames(code))],
                evaluation_mode="TARGET" if evaluate else "OBSERVATION")


class ObservationTest(unittest.TestCase):
    def test_geometry_uses_pixel_aspect_ratio(self):
        for code in ("elbow_flexion", "shoulder_flexion", "shoulder_abduction"):
            for degrees in (0, 30, 60, 90):
                self.assertAlmostEqual(degrees, feature(frame(0, degrees, code), 640, 480, code, "RIGHT")["angle"])

    def test_all_three_actions_count_complete_cycles(self):
        for code in ("elbow_flexion", "shoulder_flexion", "shoulder_abduction"):
            r = analyze(payload(code, True))
            self.assertEqual(2, r["assessment"]["completed_cycles"], code)
            self.assertEqual(2, r["assessment"]["task_met_cycles"], code)

    def test_observation_is_not_a_quality_judgement(self):
        r = analyze(payload())
        self.assertIsNone(r["assessment"]["task_met_cycles"])
        for rep in r["sets"][0]["reps"]:
            for field in ("amplitude_ok", "tempo_ok", "hold_ok", "trunk_ok", "task_met"):
                self.assertIsNone(rep[field])

    def test_missing_measurements_remain_missing(self):
        f = [frame(i*.125, 30, visible=False) for i in range(12)]
        r = analyze(payload(sample_frames=f))
        self.assertEqual(0, r["assessment"]["completed_cycles"])
        self.assertEqual(0, r["assessment"]["reliable_time_ratio"])
        self.assertTrue(all(s["angle"] is None for s in r["sets"][0]["samples"]))

    def test_long_occlusion_cannot_complete_a_cycle(self):
        f = frames()[:38]
        for i in range(22, 36):
            f[i]["landmarks"][14]["visibility"] = .1
        r = analyze(payload(sample_frames=f))
        self.assertEqual(0, r["assessment"]["completed_cycles"])
        self.assertEqual(1, r["assessment"]["interrupted_attempts"])

    def test_short_occlusion_interrupts_continuous_hold(self):
        i = item(); i["hold_seconds"] = 2
        o = CycleObserver(i, True)
        f = frames()[:30]
        for x in f: o.update(x["time"], feature(x,640,480,i["code"],"RIGHT"))
        self.assertIsNotNone(o.hold_since)
        o.update(3.7, dict(valid=False))
        self.assertIsNone(o.hold_since)

    def test_duplicate_time_rejected(self):
        f = frames(); f[2]["time"] = f[1]["time"]
        with self.assertRaises(ValueError): analyze(payload(sample_frames=f))

    def test_multiple_people_not_evaluated(self):
        f = frame(0, 30); f["pose_count"] = 2
        self.assertFalse(feature(f,640,480,"elbow_flexion","RIGHT")["valid"])

    def test_degenerate_torso_not_evaluated(self):
        f = frame(0, 30); f["landmarks"][24] = dict(f["landmarks"][12])
        self.assertFalse(feature(f,640,480,"shoulder_flexion","RIGHT")["valid"])


if __name__ == "__main__":
    unittest.main()
