# Scheduler JMH results

Medians [min, max] of three independent JVM means. Diagnostics disabled.
One batch = 256 fibers. Allocation values are JMH GC-profiler bytes per fiber.

| Scenario | Runtime | Batch microseconds | Bytes/fiber |
| --- | --- | ---: | ---: |
| external | baseline | 99.34 [78.65, 105.49] | 232.97 |
| external | optimized | 85.91 [60.23, 133.52] | 232.97 |
| external | original | 76.18 [71.87, 137.39] | 232.97 |
| owner | baseline | 44.17 [44.00, 45.16] | 232.78 |
| owner | optimized | 25.77 [25.48, 25.86] | 232.78 |
| owner | original | 43.53 [43.48, 44.15] | 232.78 |
| ready | baseline | 24.92 [24.78, 25.45] | 136.31 |
| ready | optimized | 14.21 [14.06, 14.24] | 136.31 |
| ready | original | 25.08 [24.83, 25.88] | 136.31 |
