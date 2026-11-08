#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <number> [repeats] [verbose]\n", argv[0]);
        return 1;
    }

    unsigned long long n0 = strtoull(argv[1], NULL, 10);
    int repeats = (argc > 2) ? atoi(argv[2]) : 1;
    int verbose = (argc > 3);
    unsigned long long total = 0;

    for (int r = 0; r < repeats; ++r) {
        unsigned long long n = n0;
        for (unsigned long long p = 2; p * p <= n; ++p) {
            while (n % p == 0) {
                if (verbose && r == 0)
                    printf("%llu\n", p);
                n /= p;
                ++total;
            }
        }
        if (n > 1) {
            if (verbose && r == 0)
                printf("%llu\n", n);
            ++total;
        }
    }

    printf("%llu\n", total);
    return 0;
}
