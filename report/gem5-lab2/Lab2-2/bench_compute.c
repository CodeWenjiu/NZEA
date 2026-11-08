#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* Eight independent accumulator chains: no memory traffic, high ILP. */
int main(int argc, char **argv)
{
    uint64_t n = (argc > 1) ? strtoull(argv[1], NULL, 10) : 500000ULL;

    uint64_t a0 = 1, a1 = 2, a2 = 3, a3 = 4;
    uint64_t a4 = 5, a5 = 6, a6 = 7, a7 = 8;

    for (uint64_t i = 0; i < n; ++i) {
        a0 += i ^ a1;
        a1 += i ^ a2;
        a2 += i ^ a3;
        a3 += i ^ a4;
        a4 += i ^ a5;
        a5 += i ^ a6;
        a6 += i ^ a7;
        a7 += i ^ a0;
    }

    uint64_t s = a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7;
    printf("%llu\n", (unsigned long long)s);
    return 0;
}
