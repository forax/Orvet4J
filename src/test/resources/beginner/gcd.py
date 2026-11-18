# Euclid's algorithm: find the greatest common divisor of several pairs.
def gcd(a, b):
    while b != 0:
        remainder = a % b
        a = b
        b = remainder
    return a

pairs = [(84, 30), (105, 35), (17, 13)]
for pair in pairs:
    print("gcd", pair[0], pair[1], "=", gcd(pair[0], pair[1]))
