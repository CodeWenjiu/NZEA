#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/*
 * Cyclic scan over one byte per 64 B line. The working set is a bit larger
 * than the L2 (5120 lines = 320 KiB vs 256 KiB), so every set holds more
 * lines than it has ways and LRU evicts exactly the line needed next.
 */
#define LINE 64
#define LINES 5120

int main(int argc, char **argv)
{
    long iters = (argc > 1) ? atol(argv[1]) : 50;

    unsigned char *buf = NULL;
    if (posix_memalign((void **)&buf, LINE, (size_t)LINES * LINE) != 0)
        return 1;

    for (int i = 0; i < LINES; ++i)
        buf[(size_t)i * LINE] = (unsigned char)i;

    volatile unsigned sum = 0;
    for (long it = 0; it < iters; ++it) {
        for (int i = 0; i < LINES; ++i)
            sum += buf[(size_t)i * LINE];
    }

    printf("%u\n", sum);
    free(buf);
    return 0;
}
