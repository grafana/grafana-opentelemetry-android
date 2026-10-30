import copy
import unittest

from verify import sampling_names, verify_sampling


class SamplingVerifierTest(unittest.TestCase):
    def setUp(self):
        self.records = []
        for name in sampling_names("startup"):
            span = dict(kind="span", name=name, flags=1, traceId="1" * 32,
                        spanId="3" * 16, parentSpanId="" if name.endswith(".root") else "2" * 16,
                        attrs={"session.id": "session-a"})
            log = dict(span, kind="log")
            self.records.extend((span, log))

    def test_accepts_all_parent_cases(self):
        verify_sampling(self.records, "startup", "session-a")

    def test_rejects_missing_or_duplicate_span_and_log(self):
        for index in range(len(self.records)):
            with self.subTest(index=index, mutation="missing"):
                with self.assertRaises(AssertionError):
                    verify_sampling(self.records[:index] + self.records[index + 1:], "startup", "session-a")
            with self.subTest(index=index, mutation="duplicate"):
                with self.assertRaises(AssertionError):
                    verify_sampling(self.records + [self.records[index]], "startup", "session-a")

    def test_rejects_wrong_session_flags_and_correlation(self):
        for index in range(len(self.records)):
            for field, value in (("attrs", {"session.id": "wrong"}), ("flags", 0),
                                 ("traceId", "4" * 32), ("spanId", "5" * 16)):
                with self.subTest(index=index, field=field):
                    changed = copy.deepcopy(self.records)
                    changed[index][field] = value
                    with self.assertRaises(AssertionError):
                        verify_sampling(changed, "startup", "session-a")

    def test_rejects_lost_or_invented_parent(self):
        for index in range(0, len(self.records), 2):
            changed = copy.deepcopy(self.records)
            changed[index]["parentSpanId"] = "" if changed[index]["parentSpanId"] else "2" * 16
            with self.subTest(index=index), self.assertRaises(AssertionError):
                verify_sampling(changed, "startup", "session-a")


if __name__ == "__main__":
    unittest.main()
