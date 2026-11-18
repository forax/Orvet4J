# Print all prime numbers in a small range.
def is_prime(number):
    if number < 2:
        return False
    divisor = 2
    while divisor * divisor <= number:
        if number % divisor == 0:
            return False
        divisor += 1
    return True

for candidate in range(2, 20):
    if is_prime(candidate):
        print(candidate)
