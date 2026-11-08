#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* Sequential traversal of an array: one 64 B line per eight loads. */
int main(int argc, char **argv)
{
    size_t n = (argc > 1) ? strtoull(argv[1], NULL, 10) : (1u << 20);

    uint64_t *a = malloc(n * sizeof(uint64_t));
    if (!a)
        return 1;

    for (size_t i = 0; i < n; ++i)
        a[i] = i;

    uint64_t s = 0;
    for (size_t i = 0; i < n; ++i)
        s += a[i];

    printf("%llu\n", (unsigned long long)s);
    free(a);
    return 0;
}
