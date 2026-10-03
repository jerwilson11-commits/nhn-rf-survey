#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern char *nrrrc_decode_tdd_json(int pduKind, const unsigned char *buf, size_t n);

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: t <pduKind> <hexfile>\n"); return 1; }
    int kind = atoi(argv[1]);
    FILE *f = fopen(argv[2], "r"); if (!f) { perror("open"); return 1; }
    static char hex[65536]; size_t hn = fread(hex, 1, sizeof hex - 1, f); fclose(f); hex[hn] = 0;
    static unsigned char buf[32768]; size_t n = 0;
    for (char *p = hex; p[0] && p[1]; ) {
        if (p[0]=='\n'||p[0]=='\r'||p[0]==' '||p[0]=='\t') { p++; continue; }
        unsigned v; if (sscanf(p, "%2x", &v) != 1) break; buf[n++] = (unsigned char)v; p += 2;
    }
    char *json = nrrrc_decode_tdd_json(kind, buf, n);
    printf("%s\n", json ? json : "(null)");
    return 0;
}
