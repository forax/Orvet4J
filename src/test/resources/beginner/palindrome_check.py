# Check words by building their reverse, without string slicing.
words = ["level", "orvet", "radar", "python"]

for word in words:
    reversed_word = ""
    for character in word:
        reversed_word = character + reversed_word

    if word == reversed_word:
        print(word, "is a palindrome")
    else:
        print(word, "is not a palindrome")
