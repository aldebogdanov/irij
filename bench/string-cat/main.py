def build(n):
    acc = ""
    while n:
        acc += "x"
        n -= 1
    return acc

print(build(500))
