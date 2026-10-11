import json
from pathlib import Path
import tempfile
import unittest

from request_http_profile import COUNTERS, PREFIX, analyze_events, thread_role
from request_http_summarize import summarize


def event(kind, **values):
    return {"type": kind, "values": values}


class ProfileAccountingTest(unittest.TestCase):
    def test_scheduler_uses_interval_not_lifetime_sum_or_peak_difference(self):
        def snapshot(time, executions, peak):
            values = {key: 0 for key in COUNTERS}
            values.update(runId=7, elapsedNanos=f"PT{time}S", executionNanos="PT0.1S",
                          parkedNanos="PT0.2S", executions=executions, startTime=str(time),
                          queuedFibers=1, peakQueuedFibers=peak)
            return event(PREFIX + "Scheduler", **values)
        result = analyze_events([snapshot(20, 2000, 9), snapshot(10, 1000, 8)])
        interval = result["scheduler_intervals"][0]
        self.assertEqual(1000, interval["delta_first_to_last"]["executions"])
        self.assertEqual(10_000_000_000, interval["delta_first_to_last"]["elapsedNanos"])
        self.assertEqual(9, interval["lifetime_peak_queue"])
        self.assertEqual(1, interval["maximum_observed_queue"])

    def test_allocation_weights_and_cpu_sample_types_stay_separate(self):
        thread = {"javaName": "jfibers-compute-1-0", "virtual": False}
        result = analyze_events([
            event("jdk.ExecutionSample", sampledThread=thread),
            event("jdk.NativeMethodSample", sampledThread=thread),
            event("jdk.ObjectAllocationSample", eventThread=thread, weight=4096, objectClass={"name": "java/lang/Object"}),
            event("jdk.ObjectAllocationSample", eventThread=thread, weight=8192, objectClass={"name": "java/lang/Object"})])
        self.assertEqual([1, 1], [row["samples"] for row in result["samples_by_type_and_thread_role"]])
        allocation = result["allocation_samples_by_thread_role"][0]
        self.assertEqual(2, allocation["samples"])
        self.assertEqual(12288, allocation["weight_bytes"])
        self.assertEqual(12288, allocation["top_classes_weight_bytes"][0]["count"])

    def test_virtual_threads_and_common_pool_are_not_owner_or_carrier(self):
        self.assertEqual("virtual-thread", thread_role({"javaName": "", "virtual": True}))
        self.assertEqual("fork-join-common", thread_role({"javaName": "ForkJoinPool.commonPool-worker-1"}))
        self.assertEqual("fork-join-carrier", thread_role({"javaName": "ForkJoinPool-1-worker-1"}))

    def test_waits_keep_continuation_and_cancellation_groups(self):
        rows = [event(PREFIX + "Await", fiberClass="Workload", continuation=state, waitKind="completionStage",
                      outcome=outcome, observedWaitNanos="PT0.001S", signalToRunnableNanos="PT0.000002S",
                      runnableToExecutionNanos="PT0.000003S", readyToExecutionNanos="PT0.000005S")
                for state, outcome in ((2, "ready"), (3, "ready"), (2, "cancelled"))]
        result = analyze_events(rows)
        self.assertEqual(3, len(result["sampled_awaits_by_continuation"]))
        self.assertEqual(5, result["sampled_awaits_by_continuation"][0]["latency_us"]["readyToExecutionNanos"]["p99"])

    def test_performance_summary_rejects_profiled_data_and_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "environment.json").write_text(json.dumps({"profiles_only": True}))
            with self.assertRaisesRegex(ValueError, "Diagnostic"):
                summarize(path)
            (path / "environment.json").write_text("{}")
            (path / "results.json").write_text(json.dumps([{"profiled": True, "validated": True}]))
            with self.assertRaisesRegex(ValueError, "Diagnostic"):
                summarize(path)


if __name__ == "__main__":
    unittest.main()
