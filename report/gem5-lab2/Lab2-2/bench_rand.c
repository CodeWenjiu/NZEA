#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* Pointer chase over a random cyclic permutation: every load depends on the
 * previous one, so the miss latency cannot be overlapped. */
static uint64_t next(uint64_t *s)
{
    *s = *s * 6364136223846793005ULL + 1442695040888963407ULL;
    return *s;
}

int main(int argc, char **argv)
{
    size_t n = (argc > 1) ? strtoull(argv[1], NULL, 10) : (1u << 20);

    size_t *next_idx = malloc(n * sizeof(size_t));
    size_t *perm = malloc(n * sizeof(size_t));
    if (!next_idx || !perm)
        return 1;

    for (size_t i = 0; i < n; ++i)
        perm[i] = i;

    uint64_t seed = 0x123456789abcdefULL;
    for (size_t i = n - 1; i > 0; --i) {
        size_t j = (size_t)(next(&seed) % (i + 1));
        size_t t = perm[i];
        perm[i] = perm[j];
        perm[j] = t;
    }

    for (size_t i = 0; i < n; ++i)
        next_idx[perm[i]] = perm[(i + 1) % n];

    size_t idx = 0;
    for (size_t i = 0; i < n; ++i)
        idx = next_idx[idx];

    printf("%zu\n", idx);
    free(next_idx);
    free(perm);
    return 0;
}
