/*
 * qmilock - send one QMI request, optionally carrying TLVs, print the reply as hex.
 *
 * Why this is a second helper rather than reusing qmihelper
 * -----------------------------------------------------------
 * qmihelper always sends an empty-body request -- it was built for
 * GET_CELL_LOCATION_INFO, which needs no TLVs. Technology lock needs to *write*
 * QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE (0x0033), which carries the mode
 * preference and the change-duration TLVs in the request itself. That is a
 * different job (build a request body) from qmihelper's (send nothing, read
 * the reply), so it gets its own small binary rather than a shared one grown
 * a request-builder it does not otherwise need.
 *
 * Deliberately dumb, same as qmihelper: every byte of TLV construction and
 * response decoding happens in Kotlin (QmiSelectionPreference), where it can
 * be unit-tested against a real captured response. This program's only job is
 * the part Kotlin cannot do -- AF_QIPCRTR needs root and is not reachable from
 * the Java socket API.
 *
 * Output is exactly one line:
 *     OK <lowercase hex of the whole response, QMI header included>
 *     ERR <reason>
 *
 * usage: qmilock <node> <port> <msg_id_hex> [id:hexbytes ...]
 *
 *   read the current selection preference:
 *     qmilock 0 87 0034
 *   hold the radio on LTE until the next power cycle:
 *     qmilock 0 87 0033 11:1000 17:00
 *
 * TLV 0x17 is the change duration: 00 = until power cycle, 01 = permanent.
 * Every write from this project uses 00, so a reboot -- or, observed
 * 2026-09-22, an airplane-mode toggle -- is always the backstop.
 */

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
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

static int fail(const char *what)
{
    printf("ERR %s: %s\n", what, strerror(errno));
    return 1;
}

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
    if (!colon) {
        printf("ERR bad TLV %s: expected id:hexbytes\n", spec);
        return -1;
    }
    long id = strtol(spec, NULL, 16);
    if (id < 0 || id > 0xFF) {
        printf("ERR bad TLV id in %s\n", spec);
        return -1;
    }
    const char *hex = colon + 1;
    size_t hexlen = strlen(hex);
    if (hexlen % 2) {
        printf("ERR bad TLV %s: odd number of hex digits\n", spec);
        return -1;
    }
    size_t vlen = hexlen / 2;
    if (3 + vlen > cap) {
        printf("ERR TLV %s does not fit\n", spec);
        return -1;
    }
    buf[0] = (uint8_t)id;
    uint16_t l = (uint16_t)vlen;
    memcpy(buf + 1, &l, 2);
    for (size_t i = 0; i < vlen; i++) {
        int hi = hexval(hex[i * 2]), lo = hexval(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) {
            printf("ERR bad hex in %s\n", spec);
            return -1;
        }
        buf[3 + i] = (uint8_t)((hi << 4) | lo);
    }
    return (int)(3 + vlen);
}

int main(int argc, char **argv)
{
    if (argc < 4) {
        printf("ERR usage: qmilock <node> <port> <msg_id_hex> [id:hexbytes ...]\n");
        return 1;
    }
    uint32_t node = (uint32_t)strtoul(argv[1], NULL, 0);
    uint32_t port = (uint32_t)strtoul(argv[2], NULL, 0);
    uint16_t msg_id = (uint16_t)strtoul(argv[3], NULL, 16);

    uint8_t req[4096];
    size_t off = sizeof(struct qmi_hdr);
    for (int i = 4; i < argc; i++) {
        int n = add_tlv(req + off, sizeof(req) - off, argv[i]);
        if (n < 0) return 1;
        off += (size_t)n;
    }
    size_t tlv_bytes = off - sizeof(struct qmi_hdr);
    struct qmi_hdr h = { .type = 0x00, .txn = 1, .msg_id = msg_id,
                         .len = (uint16_t)tlv_bytes };
    memcpy(req, &h, sizeof(h));

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) return fail("socket(AF_QIPCRTR)");

    struct sockaddr_qrtr me;
    socklen_t melen = sizeof(me);
    memset(&me, 0, sizeof(me));
    if (getsockname(sock, (struct sockaddr *)&me, &melen) < 0) return fail("getsockname");
    me.sq_family = AF_QIPCRTR;
    me.sq_port = 0;
    if (bind(sock, (struct sockaddr *)&me, sizeof(me)) < 0) return fail("bind");

    struct timeval tv = { .tv_sec = 5, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_qrtr dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;

    if (sendto(sock, req, off, 0, (struct sockaddr *)&dst, sizeof(dst)) < 0)
        return fail("sendto");

    uint8_t buf[8192];
    ssize_t n = recvfrom(sock, buf, sizeof(buf), 0, NULL, NULL);
    if (n < 0) return fail("recvfrom");

    fputs("OK ", stdout);
    for (ssize_t i = 0; i < n; i++) printf("%02x", buf[i]);
    putchar('\n');
    close(sock);
    return 0;
}
