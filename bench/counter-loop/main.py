# Cross-language counter-bump — see bench/README.md.
# 50 trivial increments.

state = 0
for _ in range(50):
    state += 1
print(state)
