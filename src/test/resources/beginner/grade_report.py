# Summarize a small gradebook and assign a human-readable result.
grades = {
    "Ada": [18, 16, 20],
    "Lin": [12, 15, 14],
    "Sam": [9, 11, 10],
}

for student in grades:
    marks = grades[student]
    total = 0
    for mark in marks:
        total += mark
    average = total / len(marks)

    if average >= 16:
        result = "excellent"
    elif average >= 12:
        result = "pass"
    else:
        result = "needs work"

    print(student, round(average, 1), result)
