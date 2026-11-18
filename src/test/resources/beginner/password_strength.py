# A deliberately small fixed-rule example; not a production password checker.
password = "Orvet4J!"
has_upper = False
has_lower = False
has_digit = False
has_symbol = False

for character in password:
    if character in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
        has_upper = True
    elif character in "abcdefghijklmnopqrstuvwxyz":
        has_lower = True
    elif character in "0123456789":
        has_digit = True
    else:
        has_symbol = True

if has_upper and has_lower and has_digit and has_symbol:
    print("strong")
else:
    print("weak")
