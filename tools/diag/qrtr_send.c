/*
 * qrtr_send - send arbitrary bytes to a QRTR port and dump whatever comes back.
 *
 * qmi_probe speaks QMI: it builds a QMI header and understands TLVs. The DIAG
 * service registered on QRTR (service 4097, MODEM:CMD and MODEM:DCI_CMD) does
 * not speak QMI, so reaching it needs a tool that sends exactly the bytes it is
 * given and interprets nothing.
 *
 * That "interprets nothing" is the point. What framing DIAG expects over QRTR is
 * an open question -- the kernel diagchar driver used to own HDLC, and there is
 * no diagchar here -- so the first job is to try the candidates and look at the
 * raw reply rather than to assume one and read a silence as "the channel is
 * dead". That mistake already cost this project a day on the wrong device.
 *
 * usage: qrtr_send <node> <port> <hex bytes>
 *   e.g. qrtr_send 0 34 00            (bare DIAG version request)
 *        qrtr_send 0 34 0078f07e      (the same, HDLC framed)
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

static int hexval(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

int main(int argc, char **argv)
{
    if (argc < 4) {
        printf("usage: %s <node> <port> <hex>\n", argv[0]);
        return 1;
    }
    uint32_t node = (uint32_t)strtoul(argv[1], NULL, 0);
    uint32_t port = (uint32_t)strtoul(argv[2], NULL, 0);

    const char *hex = argv[3];
    size_t hexlen = strlen(hex);
    if (hexlen == 0 || hexlen % 2) {
        printf("hex must be an even number of digits\n");
        return 1;
    }
    size_t n = hexlen / 2;
    uint8_t *msg = malloc(n);
    for (size_t i = 0; i < n; i++) {
        int hi = hexval(hex[i * 2]), lo = hexval(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) {
            printf("bad hex\n");
            return 1;
        }
        msg[i] = (uint8_t)((hi << 4) | lo);
    }

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) {
        printf("socket: %s\n", strerror(errno));
        return 1;
    }

    struct sockaddr_qrtr me;
    socklen_t melen = sizeof(me);
    memset(&me, 0, sizeof(me));
    if (getsockname(sock, (struct sockaddr *)&me, &melen) < 0) {
        printf("getsockname: %s\n", strerror(errno));
        return 1;
    }
    me.sq_family = AF_QIPCRTR;
    me.sq_port = 0;
    if (bind(sock, (struct sockaddr *)&me, sizeof(me)) < 0) {
        printf("bind: %s\n", strerror(errno));
        return 1;
    }
    melen = sizeof(me);
    getsockname(sock, (struct sockaddr *)&me, &melen);

    struct timeval tv = { .tv_sec = 4, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_qrtr dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;

    printf("node %u port %u  <- %zu byte(s): %s\n", node, port, n, hex);
    if (sendto(sock, msg, n, 0, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        printf("  sendto: %s\n", strerror(errno));
        return 1;
    }

    /* More than one datagram can come back: an ack then the payload, or a
     * burst of log packets. Read until the socket goes quiet. */
    uint8_t buf[8192];
    int got = 0;
    for (;;) {
        struct sockaddr_qrtr src;
        socklen_t sl = sizeof(src);
        ssize_t r = recvfrom(sock, buf, sizeof(buf), 0, (struct sockaddr *)&src, &sl);
        if (r < 0) {
            if (got == 0) printf("  no reply: %s\n", strerror(errno));
            break;
        }
        got++;
        printf("  reply %d from node %u port %u, %zd bytes:\n    ",
               got, src.sq_node, src.sq_port, r);
        for (ssize_t i = 0; i < r && i < 256; i++) printf("%02x", buf[i]);
        printf("\n");
        if (got >= 4) break;
        tv.tv_sec = 1;
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    }
    close(sock);
    return got > 0 ? 0 : 2;
}
