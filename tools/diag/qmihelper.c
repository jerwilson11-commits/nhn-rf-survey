/*
 * qmihelper - transport only: send one QMI request over QRTR, print the reply as hex.
 *
 * Deliberately dumb. Every byte of interpretation happens in Kotlin
 * (QmiCellParser), where it can be unit-tested against captured responses
 * instead of against a handset that happens to be in the right state. This
 * program's entire job is the part Kotlin cannot do: AF_QIPCRTR is not
 * reachable from the Java socket API, and the socket needs root.
 *
 * Output is exactly one line:
 *     OK <lowercase hex of the whole response, QMI header included>
 *     ERR <reason>
 *
 * An ERR line is never an empty reading. The caller must be able to tell
 * "the modem reported no neighbours" from "we could not ask", because those
 * two look identical downstream and only one of them is a measurement.
 *
 * usage: qmihelper <node> <port> <msg_id_hex>
 * build: tools/diag/build_qmi_probe.sh (same toolchain; see that script)
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

int main(int argc, char **argv)
{
    if (argc < 4) {
        printf("ERR usage: qmihelper <node> <port> <msg_id_hex>\n");
        return 1;
    }
    uint32_t node = (uint32_t)strtoul(argv[1], NULL, 0);
    uint32_t port = (uint32_t)strtoul(argv[2], NULL, 0);
    uint16_t msg_id = (uint16_t)strtoul(argv[3], NULL, 16);

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) return fail("socket(AF_QIPCRTR)");

    struct sockaddr_qrtr me;
    socklen_t melen = sizeof(me);
    memset(&me, 0, sizeof(me));
    if (getsockname(sock, (struct sockaddr *)&me, &melen) < 0) return fail("getsockname");
    me.sq_family = AF_QIPCRTR;
    me.sq_port = 0;
    if (bind(sock, (struct sockaddr *)&me, sizeof(me)) < 0) return fail("bind");

    struct timeval tv = { .tv_sec = 4, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct qmi_hdr h = { .type = 0x00, .txn = 1, .msg_id = msg_id, .len = 0 };
    struct sockaddr_qrtr dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;

    if (sendto(sock, &h, sizeof(h), 0, (struct sockaddr *)&dst, sizeof(dst)) < 0)
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
