import timeit
import statistics
import math
from scipy.stats import t

def loop():
    i = 0
    sum = 0
    while i < 10_000:
        sum = sum + i
        i = i + 1
    return sum


# Configuration
WARMUP_RUNS = 5
MEASUREMENT_RUNS = 5
CONFIDENCE = 0.999

timer = timeit.Timer(loop)

# Determine iterations per measurement
number, _ = timer.autorange()

# Warmup
for _ in range(WARMUP_RUNS):
    timer.timeit(number=number)

# Measurements
times = timer.repeat(
    repeat=MEASUREMENT_RUNS,
    number=number
)

# One sample = average time per function invocation
samples = [elapsed / number for elapsed in times]

# Statistics
n = len(samples)
mean = statistics.mean(samples)
std_dev = statistics.stdev(samples)

# JMH-style confidence interval
if n > 1:
    critical_value = t.ppf(
        (1 + CONFIDENCE) / 2,
        df=n - 1
    )
    error = critical_value * std_dev / math.sqrt(n)
else:
    error = float("nan")

# Results in microsecond per operation
scale = 1e6

print(f"Function result: {loop()}")
print(f"Iterations per measurement: {number}")
print(f"Number of measurements: {n}")
print(f"Score: {mean * scale:.3f} us/op")
print(f"StdDev: {std_dev * scale:.3f} us/op")
print(f"Error (99.9%): ± {error * scale:.3f} us/op")
print(
    f"Result: {mean * scale:.3f} "
    f"± {error * scale:.3f} us/op"
)
