# Count space-separated words without split(), imports, or container methods.
text = "red blue red green blue red"
counts = {}
word = ""

for character in text:
    if character == " ":
        if word != "":
            if word in counts:
                counts[word] += 1
            else:
                counts[word] = 1
            word = ""
    else:
        word += character

if word != "":
    if word in counts:
        counts[word] += 1
    else:
        counts[word] = 1

for word in counts:
    print(word, counts[word])
print("distinct words", len(counts))
