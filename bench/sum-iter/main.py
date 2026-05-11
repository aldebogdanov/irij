def sum_to(n):
    acc = 0
    while n:
        acc += n
        n -= 1
    return acc

print(sum_to(1000))
