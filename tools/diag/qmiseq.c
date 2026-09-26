/*
 * qmiseq - send several QMI requests on ONE QRTR socket, then keep listening for indications.
 *
 * Research tool, not shipped in the app. It exists because some QMI services (PDC, notably)
 * answer a request with only a result code and deliver the actual data later as an
 * *indication*, and only to a client that registered for indications from the same socket.
 * qmilock is deliberately one request / one reply, and the app depends on that exact output,
 * so this is a separate binary rather than a grown-up qmilock.
 *
 * usage: qmiseq <node> <port> <wait_ms> <msg_hex> [id:hexbytes ...] [-- <msg_hex> [id:hexbytes ...]] ...
 *
 * Requests are sent in order. After each one this waits (up to 3 s) for its response. After the
 * last, it listens for <wait_ms> more milliseconds. Every message received is printed as a line:
 *     RSP <hex>     a response (QMI type 0x02)
 *     IND <hex>     an indication (QMI type 0x04)
 *     ??? <hex>     anything else
 * Hex is the whole message, QMI header included, exactly as the socket delivered it.
 *
 * Example: which PDC config is selected (register, then ask, then wait for the indication):
 *     qmiseq 0 42 3000 20 10:01 -- 22 01:01000000 10:01000000
 */

#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif

struct sockaddr_qrtr {
    unsigned short sq_family;
    uint32_t sq_node;
    uint32_t sq_port;
};

struct qmi_hdr {
    uint8_t type;
    uint16_t txn;
    uint16_t msg_id;
    uint16_t len;
} __attribute__((packed));

static int hexval(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Parses "11:1000" into a TLV appended to buf. Returns bytes written, or -1. */
static int add_tlv(uint8_t *buf, size_t cap, const char *spec)
{
    const char *colon = strchr(spec, ':');
    if (!colon) { printf("ERR bad TLV %s: expected id:hexbytes\n", spec); return -1; }
    long id = strtol(spec, NULL, 16);
    if (id < 0 || id > 0xFF) { printf("ERR bad TLV id in %s\n", spec); return -1; }
    const char *hex = colon + 1;
    size_t hexlen = strlen(hex);
    if (hexlen % 2) { printf("ERR bad TLV %s: odd number of hex digits\n", spec); return -1; }
    size_t vlen = hexlen / 2;
    if (3 + vlen > cap) { printf("ERR TLV %s does not fit\n", spec); return -1; }
    buf[0] = (uint8_t)id;
    uint16_t l = (uint16_t)vlen;
    memcpy(buf + 1, &l, 2);
    for (size_t i = 0; i < vlen; i++) {
        int hi = hexval(hex[i * 2]), lo = hexval(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) { printf("ERR bad hex in %s\n", spec); return -1; }
        buf[3 + i] = (uint8_t)((hi << 4) | lo);
    }
    return (int)(3 + vlen);
}

static long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/* Prints one received message. Returns its QMI type, or -1 if too short to have one. */
static int print_msg(const uint8_t *buf, ssize_t n, uint16_t *txn_out)
{
    const char *tag = "???";
    int type = -1;
    if (n >= (ssize_t)sizeof(struct qmi_hdr)) {
        type = buf[0];
        if (type == 0x02) tag = "RSP";
        else if (type == 0x04) tag = "IND";
        if (txn_out) memcpy(txn_out, buf + 1, 2);
    }
    printf("%s ", tag);
    for (ssize_t i = 0; i < n; i++) printf("%02x", buf[i]);
    putchar('\n');
    fflush(stdout);
    return type;
}

/* Waits for a message until deadline_ms. Returns bytes read, 0 on timeout, -1 on error. */
static ssize_t wait_msg(int sock, uint8_t *buf, size_t cap, long deadline_ms)
{
    long left = deadline_ms - now_ms();
    if (left < 0) left = 0;
    struct pollfd p = { .fd = sock, .events = POLLIN };
    int r = poll(&p, 1, (int)left);
    if (r < 0) return -1;
    if (r == 0) return 0;
    return recvfrom(sock, buf, cap, 0, NULL, NULL);
}

int main(int argc, char **argv)
{
    if (argc < 5) {
        printf("ERR usage: qmiseq <node> <port> <wait_ms> <msg_hex> [id:hexbytes ...] [-- ...]\n");
        return 1;
    }
    uint32_t node = (uint32_t)strtoul(argv[1], NULL, 0);
    uint32_t port = (uint32_t)strtoul(argv[2], NULL, 0);
    long wait_ms = strtol(argv[3], NULL, 0);

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) { printf("ERR socket: %s\n", strerror(errno)); return 1; }

    struct sockaddr_qrtr me;
    socklen_t melen = sizeof(me);
    memset(&me, 0, sizeof(me));
    if (getsockname(sock, (struct sockaddr *)&me, &melen) < 0) { printf("ERR getsockname: %s\n", strerror(errno)); return 1; }
    me.sq_family = AF_QIPCRTR;
    me.sq_port = 0;
    if (bind(sock, (struct sockaddr *)&me, sizeof(me)) < 0) { printf("ERR bind: %s\n", strerror(errno)); return 1; }

    struct sockaddr_qrtr dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;

    uint8_t buf[8192];
    uint16_t txn = 0;
    int i = 4;
    while (i < argc) {
        uint16_t msg_id = (uint16_t)strtoul(argv[i++], NULL, 16);
        uint8_t req[4096];
        size_t off = sizeof(struct qmi_hdr);
        while (i < argc && strcmp(argv[i], "--") != 0) {
            int n = add_tlv(req + off, sizeof(req) - off, argv[i++]);
            if (n < 0) return 1;
            off += (size_t)n;
        }
        if (i < argc) i++; /* skip "--" */

        txn++;
        struct qmi_hdr h = { .type = 0x00, .txn = txn, .msg_id = msg_id,
                             .len = (uint16_t)(off - sizeof(struct qmi_hdr)) };
        memcpy(req, &h, sizeof(h));
        if (sendto(sock, req, off, 0, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
            printf("ERR sendto: %s\n", strerror(errno));
            return 1;
        }

        /* Wait for this request's response; print anything else that arrives meanwhile. */
        long deadline = now_ms() + 3000;
        for (;;) {
            ssize_t n = wait_msg(sock, buf, sizeof(buf), deadline);
            if (n < 0) { printf("ERR recv: %s\n", strerror(errno)); return 1; }
            if (n == 0) { printf("ERR no response to request %u within 3 s\n", txn); break; }
            uint16_t rtxn = 0;
            int type = print_msg(buf, n, &rtxn);
            if (type == 0x02 && rtxn == txn) break;
        }
    }

    long end = now_ms() + wait_ms;
    for (;;) {
        ssize_t n = wait_msg(sock, buf, sizeof(buf), end);
        if (n <= 0) break;
        print_msg(buf, n, NULL);
    }
    close(sock);
    return 0;
}
