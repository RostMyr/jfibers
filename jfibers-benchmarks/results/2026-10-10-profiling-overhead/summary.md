# JMH results

Medians [min, max] of independent JVM means. Batch = 256 fibers.

| Scenario | Runtime | Diagnostics | Runs | Batch µs | B/fiber |
| --- | --- | --- | ---: | ---: | ---: |
| owner | final | off | 3 | 25.83 [25.70, 26.02] | 232.78 |
| owner | final | recording | 3 | 58.58 [58.24, 59.60] | 236.53 |
| owner | final | standard | 3 | 26.35 [26.28, 27.81] | 232.78 |
