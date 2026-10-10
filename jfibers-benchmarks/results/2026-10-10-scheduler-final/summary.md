# JMH results

Medians [min, max] of independent JVM means. Batch = 256 fibers.

| Scenario | Runtime | Diagnostics | Runs | Batch µs | B/fiber |
| --- | --- | --- | ---: | ---: | ---: |
| external | final | off | 3 | 108.37 [102.59, 128.70] | 232.97 |
| external | original | off | 3 | 186.48 [96.81, 215.51] | 232.97 |
| owner | final | off | 3 | 25.85 [25.84, 26.49] | 232.78 |
| owner | original | off | 3 | 44.42 [42.93, 44.77] | 232.78 |
| ready | final | off | 3 | 14.24 [14.24, 14.83] | 136.31 |
| ready | original | off | 3 | 24.82 [24.61, 25.06] | 136.31 |
