Medians [min, max] across fresh JVMs. Operation = two sequential HTTP requests.
p95/p99: median of each run's histogram upper bound, not pooled percentiles.

| Delay ms | Clients | Backend / runtime | Runs | Ops/s | p95 ms | p99 ms | Client CPU cores | Server CPU cores | Sampled mean heap MiB | Errors |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 256 | JFIBERS / final | 3 | 25137.52 [21683.18, 25449.24] | 16.00 [15.74, 17.92] | 23.55 [20.48, 23.55] | 2.63 [2.61, 2.64] | 1.09 [1.09, 1.16] | 194.61 [194.60, 195.38] | 0 |
| 0 | 256 | JFIBERS / original | 3 | 23105.16 [21765.69, 24288.22] | 17.15 [16.38, 17.66] | 22.02 [20.99, 22.27] | 2.67 [2.66, 2.74] | 1.09 [1.07, 1.11] | 184.44 [178.98, 188.27] | 0 |
| 5 | 256 | JFIBERS / final | 3 | 17867.72 [17686.43, 19132.63] | 19.71 [18.18, 19.97] | 29.70 [24.06, 30.21] | 2.27 [1.98, 2.43] | 1.28 [1.17, 1.32] | 175.89 [168.11, 181.17] | 0 |
| 5 | 256 | JFIBERS / original | 3 | 18382.87 [18164.34, 19839.44] | 20.48 [16.89, 20.48] | 27.90 [22.27, 28.16] | 2.14 [1.98, 2.15] | 1.26 [1.16, 1.28] | 172.44 [163.15, 180.19] | 0 |
