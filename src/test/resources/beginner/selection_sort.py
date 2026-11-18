# Sort a short list in place using selection sort (no sort() method or slicing).
values = [8, 3, 6, 1, 9, 2]

for start in range(len(values)):
    smallest = start
    for index in range(start + 1, len(values)):
        if values[index] < values[smallest]:
            smallest = index

    if smallest != start:
        temporary = values[start]
        values[start] = values[smallest]
        values[smallest] = temporary

print("sorted", values)
