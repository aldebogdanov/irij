import sys
sys.setrecursionlimit(100_000)

def tak(x, y, z):
    if y >= x:
        return z
    return tak(tak(x - 1, y, z),
               tak(y - 1, z, x),
               tak(z - 1, x, y))

print(tak(22, 16, 8))
