# Apply monthly interest and a regular deposit to a savings balance.
balance = 1000.0
monthly_rate = 0.01
deposit = 50.0
month = 1

while month <= 6:
    balance = balance * (1.0 + monthly_rate) + deposit
    print(month, round(balance, 2))
    month += 1
