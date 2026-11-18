# Group line items by category and calculate each category's total.
expenses = [
    ("food", 12.50),
    ("transport", 2.80),
    ("food", 7.50),
    ("books", 8.50),
    ("transport", 1.20),
]
totals = {}

for expense in expenses:
    category = expense[0]
    amount = expense[1]
    if category in totals:
        totals[category] += amount
    else:
        totals[category] = amount

for category in totals:
    print(category, round(totals[category], 2))
