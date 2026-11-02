import copy
import unittest

from verify import reverse_port_available, sampling_names, verify_checkpoint, verify_device_mode, verify_elapsed, verify_identity, verify_sampling


class DeviceModeTest(unittest.TestCase):
    def test_explicit_phone_and_default_emulator(self):
        verify_device_mode("phone", True, False, False, "abort")
        verify_device_mode("emulator-5554", False, True, True, "abort")

    def test_rejects_implicit_phone_root_and_unattended_timeout(self):
        for args in (("phone", False, False, False, "abort"),
                     ("phone", True, True, False, "abort"),
                     ("phone", True, False, True, "abort"),
                     ("phone", True, False, False, "signal"),
                     ("emulator-5554", True, False, False, "abort")):
            with self.subTest(args=args), self.assertRaises(AssertionError):
                verify_device_mode(*args)

    def test_does_not_replace_existing_reverse_port(self):
        self.assertTrue(reverse_port_available(""))
        self.assertTrue(reverse_port_available("device tcp:8000 tcp:14318\n"))
        self.assertFalse(reverse_port_available("device tcp:14318 tcp:8000\n"))
        self.assertFalse(reverse_port_available("device tcp:14318 tcp:14318\n"))


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


class RealTimeVerifierTest(unittest.TestCase):
    def setUp(self):
        self.current = dict(pid=123, process="probe", launch="launch-a", storageFailure="",
                            checkpoint=dict(token="test", session="b", beforeMs=1000, afterMs=1001))
        self.records = [
            dict(kind="log", name="session.start", attrs={"session.id": "a"}),
            dict(kind="log", name="session.end", attrs={"session.id": "a"}),
            dict(kind="log", name="session.start", attrs={"session.id": "b", "session.previous_id": "a"}),
            dict(kind="log", name="probe.realtime.test", attrs={"session.id": "b"}),
            dict(kind="span", name="probe.realtime.test", attrs={"session.id": "b"}),
        ]

    def test_accepts_linked_rotation_and_attribution(self):
        verify_identity(self.current, self.current)
        verify_checkpoint(self.records, self.current, ["a", "b"])

    def test_accepts_retained_session(self):
        current = copy.deepcopy(self.current)
        current["checkpoint"]["session"] = "a"
        records = [self.records[0]] + [dict(r, attrs={"session.id": "a"}) for r in self.records[3:]]
        verify_checkpoint(records, current, ["a"])

    def test_rejects_process_restart_or_storage_failure(self):
        for key, value in (("pid", 124), ("process", "worker"), ("launch", "launch-b"), ("storageFailure", "write-failed")):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                verify_identity(self.current, dict(self.current, **{key: value}))

    def test_rejects_shortened_wait_and_clock_disagreement(self):
        verify_elapsed(840000, 840, 840, 900)
        verify_elapsed(14460000, 14460, 14460)
        for device, host in ((839999, 840), (840000, 839.9), (900000, 900), (840000, 850), (850000, 840)):
            with self.subTest(device=device, host=host), self.assertRaises(AssertionError):
                verify_elapsed(device, host, 840, 900)

    def test_rejects_missing_or_duplicate_telemetry(self):
        for index in range(len(self.records)):
            for records in (self.records[:index] + self.records[index + 1:], self.records + [self.records[index]]):
                with self.subTest(index=index, count=len(records)), self.assertRaises(AssertionError):
                    verify_checkpoint(records, self.current, ["a", "b"])

    def test_rejects_wrong_attribution_link_and_order(self):
        for index in range(len(self.records)):
            changed = copy.deepcopy(self.records)
            changed[index]["attrs"]["session.id"] = "wrong"
            with self.subTest(index=index), self.assertRaises(AssertionError):
                verify_checkpoint(changed, self.current, ["a", "b"])
        for previous in (None, "wrong"):
            changed = copy.deepcopy(self.records)
            changed[2]["attrs"]["session.previous_id"] = previous
            with self.subTest(previous=previous), self.assertRaises(AssertionError):
                verify_checkpoint(changed, self.current, ["a", "b"])
        changed = copy.deepcopy(self.records)
        changed[1], changed[2] = changed[2], changed[1]
        with self.assertRaises(AssertionError):
            verify_checkpoint(changed, self.current, ["a", "b"])

    def test_rejects_wrong_checkpoint_and_slow_read(self):
        for key, value in (("session", "a"), ("token", "wrong"), ("afterMs", 999), ("afterMs", 2000)):
            changed = copy.deepcopy(self.current)
            changed["checkpoint"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(AssertionError):
                verify_checkpoint(self.records, changed, ["a", "b"])


if __name__ == "__main__":
    unittest.main()
