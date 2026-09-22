/*
 * qmi_probe - send one QMI request over a QRTR socket and dump the response.
 *
 * Why this exists
 * ---------------
 * The three things this app cannot do through Android -- neighbour cells, band
 * lock and technology lock -- all live behind the modem's QMI Network Access
 * Service. On this handset (SM8350, integrated X60) the modem is reachable over
 * QRTR, not over any character device: there is no /dev/diag and no diagchar,
 * and the MHI pipe that looked like DIAG belongs to the WCN6855 Wi-Fi chip.
 *
 * AF_QIPCRTR is not reachable from the Java/Kotlin socket API, so proving the
 * path needs a small native binary. This is that binary, and nothing more: it
 * sends one request and prints what comes back.
 *
 * Wire format
 * -----------
 * Over QRTR there is no QMUX header -- the QRTR port identifies the client, so
 * the datagram starts directly with the QMI header:
 *
 *     u8  type     0 = request, 2 = response, 4 = indication
 *     u16 txn_id   little-endian
 *     u16 msg_id   little-endian
 *     u16 len      little-endian, bytes of TLVs that follow
 *
 * then a sequence of TLVs: u8 id, u16 len, <len> bytes.
 *
 * TLV meanings are deliberately NOT guessed at here beyond the standard result
 * TLV (0x02). Everything else is dumped as hex to be cross-referenced against
 * libqmi, because a plausible-looking wrong decode is worse than raw bytes.
 *
 * usage: qmi_probe [node] [port] [msg_id_hex]
 *        defaults to the Network Access Service as qrtr-lookup reports it.
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

static void hexdump(const uint8_t *p, size_t n, const char *indent)
{
    for (size_t i = 0; i < n; i += 16) {
        printf("%s%04zx  ", indent, i);
        for (size_t j = 0; j < 16; j++) {
            if (i + j < n) printf("%02x ", p[i + j]);
            else printf("   ");
        }
        printf(" |");
        for (size_t j = 0; j < 16 && i + j < n; j++) {
            uint8_t c = p[i + j];
            putchar(c >= 32 && c < 127 ? c : '.');
        }
        printf("|\n");
    }
}

int main(int argc, char **argv)
{
    uint32_t node = argc > 1 ? (uint32_t)strtoul(argv[1], NULL, 0) : 0;
    uint32_t port = argc > 2 ? (uint32_t)strtoul(argv[2], NULL, 0) : 86;
    uint16_t msg_id = argc > 3 ? (uint16_t)strtoul(argv[3], NULL, 16) : 0x0043;

    printf("target: node %u port %u, msg_id 0x%04x\n", node, port, msg_id);

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) {
        printf("socket(AF_QIPCRTR): %s\n", strerror(errno));
        return 1;
    }

    /* Bind with port 0 so the kernel assigns us one; our node comes back from
     * getsockname. This is the sequence the qrtr userspace tools use. */
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
    printf("bound as node %u port %u\n", me.sq_node, me.sq_port);

    struct timeval tv = { .tv_sec = 5, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    uint8_t req[sizeof(struct qmi_hdr)];
    struct qmi_hdr h = { .type = 0x00, .txn = 1, .msg_id = msg_id, .len = 0 };
    memcpy(req, &h, sizeof(h));

    struct sockaddr_qrtr dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;

    printf("sending %zu bytes: ", sizeof(req));
    for (size_t i = 0; i < sizeof(req); i++) printf("%02x ", req[i]);
    printf("\n");

    ssize_t sent = sendto(sock, req, sizeof(req), 0,
                          (struct sockaddr *)&dst, sizeof(dst));
    if (sent < 0) {
        printf("sendto: %s\n", strerror(errno));
        return 1;
    }

    uint8_t buf[8192];
    struct sockaddr_qrtr src;
    socklen_t srclen = sizeof(src);
    ssize_t n = recvfrom(sock, buf, sizeof(buf), 0,
                         (struct sockaddr *)&src, &srclen);
    if (n < 0) {
        printf("recvfrom: %s%s\n", strerror(errno),
               errno == EAGAIN ? "  (timed out - nothing came back)" : "");
        return 2;
    }

    printf("\nreceived %zd bytes from node %u port %u\n", n, src.sq_node, src.sq_port);
    if ((size_t)n < sizeof(struct qmi_hdr)) {
        printf("shorter than a QMI header:\n");
        hexdump(buf, n, "  ");
        return 3;
    }

    struct qmi_hdr r;
    memcpy(&r, buf, sizeof(r));
    const char *kind = r.type == 0 ? "request" :
                       r.type == 2 ? "response" :
                       r.type == 4 ? "indication" : "?";
    printf("QMI header: type=0x%02x (%s) txn=%u msg_id=0x%04x len=%u\n",
           r.type, kind, r.txn, r.msg_id, r.len);

    size_t off = sizeof(r);
    int tlvs = 0;
    while (off + 3 <= (size_t)n) {
        uint8_t tid = buf[off];
        uint16_t tlen;
        memcpy(&tlen, buf + off + 1, 2);
        off += 3;
        if (off + tlen > (size_t)n) {
            printf("  TLV 0x%02x claims %u bytes but only %zu remain - truncated\n",
                   tid, tlen, (size_t)n - off);
            break;
        }
        tlvs++;
        if (tid == 0x02 && tlen >= 4) {
            uint16_t result, error;
            memcpy(&result, buf + off, 2);
            memcpy(&error, buf + off + 2, 2);
            printf("  TLV 0x02 result: %s (result=%u error=%u)\n",
                   result == 0 ? "SUCCESS" : "FAILURE", result, error);
        } else {
            printf("  TLV 0x%02x, %u bytes:\n", tid, tlen);
            hexdump(buf + off, tlen, "      ");
        }
        off += tlen;
    }
    printf("%d TLV(s)\n", tlvs);
    close(sock);
    return 0;
}
