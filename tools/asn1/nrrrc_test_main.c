#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "BCCH-DL-SCH-Message.h"
#include "BCCH-DL-SCH-MessageType.h"
#include "SystemInformation.h"
#include "SystemInformation-IEs.h"
#include "SIB2.h"
extern char *nrrrc_decode_tdd_json(int pduKind, const unsigned char *buf, size_t n);

/* Round-trip B4: build a SystemInformation carrying one SIB2 with known values, UPER-encode it, then
 * decode with the production decoder. Proves the SIB2 field extraction against the ASN.1 spec without
 * needing a live SI capture (the BCCH-DL-SCH container + asn1c model are already validated on real
 * SIB1 bytes). Expected values: qHyst 2, qRxLevMin -62, sIntraSearchP 7, tReselectionNR 1,
 * threshServingLowP 5, cellReselectionPriority 6. */
static int roundtrip(void) {
    SIB2_t *sib2 = calloc(1, sizeof *sib2);
    sib2->cellReselectionInfoCommon.q_Hyst = 2;
    sib2->cellReselectionServingFreqInfo.threshServingLowP = 5;
    sib2->cellReselectionServingFreqInfo.cellReselectionPriority = 6;
    sib2->intraFreqCellReselectionInfo.q_RxLevMin = -62;
    sib2->intraFreqCellReselectionInfo.s_IntraSearchP = 7;
    sib2->intraFreqCellReselectionInfo.t_ReselectionNR = 1;
    sib2->intraFreqCellReselectionInfo.deriveSSB_IndexFromCell = 1;

    SystemInformation_IEs__sib_TypeAndInfo__sib_TypeAndInfo_Member *mem = calloc(1, sizeof *mem);
    mem->present = SystemInformation_IEs__sib_TypeAndInfo__sib_TypeAndInfo_Member_PR_sib2;
    mem->choice.sib2 = *sib2;

    BCCH_DL_SCH_Message_t *m = calloc(1, sizeof *m);
    m->message = calloc(1, sizeof *m->message);
    m->message->present = BCCH_DL_SCH_MessageType_PR_c1;
    m->message->choice.c1.present = BCCH_DL_SCH_MessageType__c1_PR_systemInformation;
    SystemInformation_t *si = &m->message->choice.c1.choice.systemInformation;
    si->criticalExtensions.present = SystemInformation__criticalExtensions_PR_systemInformation;
    ASN_SEQUENCE_ADD(&si->criticalExtensions.choice.systemInformation.sib_TypeAndInfo.list, mem);

    void *buf = NULL;
    ssize_t enc = uper_encode_to_new_buffer(&asn_DEF_BCCH_DL_SCH_Message, NULL, m, &buf);
    if (enc < 0 || buf == NULL) { printf("ENCODE_FAILED\n"); return 1; }
    size_t n = (size_t)enc;
    printf("encoded %zu bytes: ", n);
    for (size_t i = 0; i < n; i++) printf("%02x", ((unsigned char *)buf)[i]);
    printf("\n");
    char *json = nrrrc_decode_tdd_json(1, buf, n);
    printf("decoded: %s\n", json ? json : "(null)");
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "rt") == 0) return roundtrip();
    if (argc < 3) { fprintf(stderr, "usage: t <pduKind> <hexfile>  |  t rt\n"); return 1; }
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
