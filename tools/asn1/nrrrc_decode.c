/* NR RRC -> TDD-config JSON core. No JVM deps; the JNI wrapper calls nrrrc_decode_tdd_json().
 * Emits raw ASN.1 enum integers; the Kotlin adapter maps them to ms/kHz values. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include "RRCReconfiguration.h"
#include "RRCReconfiguration-IEs.h"
#include "CellGroupConfig.h"
#include "SpCellConfig.h"
#include "ReconfigurationWithSync.h"
#include "ServingCellConfigCommon.h"
#include "TDD-UL-DL-ConfigCommon.h"
#include "TDD-UL-DL-Pattern.h"

struct sb { char *p; size_t cap; size_t len; };
static void sbput(struct sb *s, const char *fmt, ...) {
    if (s->len + 1 >= s->cap) return;
    va_list a; va_start(a, fmt);
    int n = vsnprintf(s->p + s->len, s->cap - s->len, fmt, a);
    va_end(a);
    if (n < 0) return;
    size_t room = s->cap - s->len - 1;
    s->len += ((size_t)n < room) ? (size_t)n : room;
}

static void emit_pattern(struct sb *s, const char *name, TDD_UL_DL_Pattern_t *p) {
    sbput(s, "\"%s\":{\"periodicity\":%ld,\"dlSlots\":%ld,\"dlSymbols\":%ld,\"ulSlots\":%ld,\"ulSymbols\":%ld",
          name, p->dl_UL_TransmissionPeriodicity, p->nrofDownlinkSlots, p->nrofDownlinkSymbols,
          p->nrofUplinkSlots, p->nrofUplinkSymbols);
    if (p->ext1 && p->ext1->dl_UL_TransmissionPeriodicity_v1530)
        sbput(s, ",\"periodicityV1530\":%ld", *p->ext1->dl_UL_TransmissionPeriodicity_v1530);
    sbput(s, "}");
}

/* ServingCellConfigCommon (used by the RRCReconfiguration/NSA path). */
static int emit_tdd_from_scc(struct sb *s, ServingCellConfigCommon_t *scc) {
    if (!scc || !scc->tdd_UL_DL_ConfigurationCommon) return -1;
    TDD_UL_DL_ConfigCommon_t *t = scc->tdd_UL_DL_ConfigurationCommon;
    sbput(s, "\"refSCS\":%ld,", t->referenceSubcarrierSpacing);
    if (scc->ssbSubcarrierSpacing) sbput(s, "\"ssbSCS\":%ld,", *scc->ssbSubcarrierSpacing);
    if (scc->ssb_periodicityServingCell) sbput(s, "\"ssbPeriodicity\":%ld,", *scc->ssb_periodicityServingCell);
    if (scc->ssb_PositionsInBurst) {
        const char *kind = 0; BIT_STRING_t *bs = 0;
        switch (scc->ssb_PositionsInBurst->present) {
            case ServingCellConfigCommon__ssb_PositionsInBurst_PR_shortBitmap:
                kind = "short"; bs = &scc->ssb_PositionsInBurst->choice.shortBitmap; break;
            case ServingCellConfigCommon__ssb_PositionsInBurst_PR_mediumBitmap:
                kind = "medium"; bs = &scc->ssb_PositionsInBurst->choice.mediumBitmap; break;
            case ServingCellConfigCommon__ssb_PositionsInBurst_PR_longBitmap:
                kind = "long"; bs = &scc->ssb_PositionsInBurst->choice.longBitmap; break;
            default: break;
        }
        if (kind && bs) {
            sbput(s, "\"ssbKind\":\"%s\",\"ssbHex\":\"", kind);
            for (int i = 0; i < bs->size; i++) sbput(s, "%02x", bs->buf[i]);
            sbput(s, "\",");
        }
    }
    if (t->pattern1) emit_pattern(s, "pattern1", t->pattern1);
    if (t->pattern2) { sbput(s, ","); emit_pattern(s, "pattern2", t->pattern2); }
    return 0;
}

char *nrrrc_decode_tdd_json(int pduKind, const unsigned char *buf, size_t n) {
    struct sb s; s.cap = 8192; s.p = (char *)malloc(s.cap); s.len = 0; if (s.p) s.p[0] = 0; else return 0;
    sbput(&s, "{");
    if (pduKind == 0) { /* RRCReconfiguration (NSA) */
        RRCReconfiguration_t *r = 0;
        asn_dec_rval_t rc = uper_decode_complete(0, &asn_DEF_RRCReconfiguration, (void **)&r, buf, n);
        if (rc.code != RC_OK) { sbput(&s, "\"error\":\"rrcreconf_decode\"}"); return s.p; }
        if (r->criticalExtensions.present != RRCReconfiguration__criticalExtensions_PR_rrcReconfiguration) {
            sbput(&s, "\"error\":\"no_ies\"}"); return s.p; }
        OCTET_STRING_t *scg = r->criticalExtensions.choice.rrcReconfiguration.secondaryCellGroup;
        if (!scg) { sbput(&s, "\"error\":\"no_scg\"}"); return s.p; }
        CellGroupConfig_t *cg = 0;
        asn_dec_rval_t rc2 = uper_decode_complete(0, &asn_DEF_CellGroupConfig, (void **)&cg, scg->buf, scg->size);
        if (rc2.code != RC_OK) { sbput(&s, "\"error\":\"cellgroup_decode\"}"); return s.p; }
        if (!cg->spCellConfig || !cg->spCellConfig->reconfigurationWithSync ||
            !cg->spCellConfig->reconfigurationWithSync->spCellConfigCommon) {
            sbput(&s, "\"error\":\"no_spcellcommon\"}"); return s.p; }
        sbput(&s, "\"root\":\"RRCReconfiguration\",");
        if (emit_tdd_from_scc(&s, cg->spCellConfig->reconfigurationWithSync->spCellConfigCommon) != 0) {
            sbput(&s, "\"error\":\"no_tdd\"}"); return s.p; }
        sbput(&s, "}");
        return s.p;
    }
    sbput(&s, "\"error\":\"unsupported_pdukind\"}");
    return s.p;
}
