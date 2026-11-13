import unittest
from service import simulate


class RulesTest(unittest.TestCase):
    def item(self):
        return dict(id=1, sets=1, repetitions=6, target_min=70, target_max=110,
                    hold_seconds=0, min_duration=2.5, max_duration=6, trunk_limit=12, rest_seconds=10)

    def test_standard_meets_all_requirements(self):
        result = simulate(dict(items=[self.item()], scenario="standard"))
        self.assertEqual(6, len(result["sets"][0]["reps"]))
        self.assertTrue(all(not r["events"] for r in result["sets"][0]["reps"]))

    def test_independent_issue_labels_and_monotonic_samples(self):
        result = simulate(dict(items=[self.item()], scenario="mixed"))
        reps = result["sets"][0]["reps"]
        self.assertEqual(["AMPLITUDE"], [e["code"] for e in reps[3]["events"]])
        self.assertEqual(["TEMPO"], [e["code"] for e in reps[4]["events"]])
        self.assertEqual(["TRUNK"], [e["code"] for e in reps[5]["events"]])
        times = [s["time"] for s in result["sets"][0]["samples"]]
        self.assertEqual(times, sorted(set(times)))
        self.assertEqual(result, simulate(dict(items=[self.item()], scenario="mixed")))

    def test_hold_requires_target_range(self):
        item = self.item()
        item["hold_seconds"] = 1
        result = simulate(dict(items=[item], scenario="mixed"))
        self.assertFalse(result["sets"][0]["reps"][3]["hold_ok"])
        self.assertTrue(result["sets"][0]["reps"][0]["tempo_ok"])


if __name__ == "__main__":
    unittest.main()
