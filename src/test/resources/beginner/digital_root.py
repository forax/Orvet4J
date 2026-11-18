# Repeatedly sum decimal digits until a single digit remains.
number = 98765

while number >= 10:
    digit_sum = 0
    while number > 0:
        quotient_and_remainder = divmod(number, 10)
        digit_sum += quotient_and_remainder[1]
        number = quotient_and_remainder[0]
    number = digit_sum

print("digital root", number)
