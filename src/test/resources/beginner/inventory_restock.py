# Report shortages and update stock to its target level.
stock = {"pens": 3, "notebooks": 1, "erasers": 0, "folders": 5}
target = {"pens": 10, "notebooks": 10, "erasers": 20, "folders": 5}

for item in stock:
    needed = target[item] - stock[item]
    if needed > 0:
        print("order", item, needed)
        stock[item] = target[item]

print("updated stock", stock)
