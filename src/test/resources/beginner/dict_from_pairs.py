# Build a dictionary from pairs, then calculate the value of a small basket.
prices = dict([("tea", 2.5), ("coffee", 3.0), ("bread", 1.5)])
basket = [("tea", 2), ("bread", 3), ("coffee", 1)]
total = 0.0

for line in basket:
    item = line[0]
    quantity = line[1]
    total += prices[item] * quantity

print("basket total", round(total, 2))
