def build(n):
    v = []
    while n:
        v.append(n)
        n -= 1
    return v

print(sum(build(500)))
