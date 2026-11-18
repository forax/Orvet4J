# Build the first ten Fibonacci numbers using a preallocated list.
size = 10
values = [0] * size
values[1] = 1

for index in range(2, size):
    values[index] = values[index - 1] + values[index - 2]

print(values)
