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
#include "BCCH-DL-SCH-Message.h"
#include "BCCH-DL-SCH-MessageType.h"
#include "SIB1.h"
#include "ServingCellConfigCommonSIB.h"
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

static void emit_hex(struct sb *s, const char *key, const BIT_STRING_t *bs) {
    sbput(s, "\"%s\":\"", key);
    for (int i = 0; i < bs->size; i++) sbput(s, "%02x", bs->buf[i]);
    sbput(s, "\",");
}

static void emit_pattern(struct sb *s, const char *name, const TDD_UL_DL_Pattern_t *p) {
    sbput(s, "\"%s\":{\"periodicity\":%ld,\"dlSlots\":%ld,\"dlSymbols\":%ld,\"ulSlots\":%ld,\"ulSymbols\":%ld",
          name, p->dl_UL_TransmissionPeriodicity, p->nrofDownlinkSlots, p->nrofDownlinkSymbols,
          p->nrofUplinkSlots, p->nrofUplinkSymbols);
    if (p->ext1 && p->ext1->dl_UL_TransmissionPeriodicity_v1530)
        sbput(s, ",\"periodicityV1530\":%ld", *p->ext1->dl_UL_TransmissionPeriodicity_v1530);
    sbput(s, "}");
}

/* The shared TDD leaf (identical type from either root): refSCS + patterns. Ends with no comma. */
static void emit_tdd_leaf(struct sb *s, const TDD_UL_DL_ConfigCommon_t *t) {
    sbput(s, "\"refSCS\":%ld", t->referenceSubcarrierSpacing);
    if (t->pattern1) { sbput(s, ","); emit_pattern(s, "pattern1", t->pattern1); }
    if (t->pattern2) { sbput(s, ","); emit_pattern(s, "pattern2", t->pattern2); }
}

/* RRCReconfiguration / NSA variant of ServingCellConfigCommon. */
static int emit_from_scc(struct sb *s, const ServingCellConfigCommon_t *scc) {
    if (!scc || !scc->tdd_UL_DL_ConfigurationCommon) { sbput(s, "\"tddPresent\":false}"); return 0; }
    sbput(s, "\"tddPresent\":true,");
    if (scc->ssbSubcarrierSpacing) sbput(s, "\"ssbSCS\":%ld,", *scc->ssbSubcarrierSpacing);
    if (scc->ssb_periodicityServingCell) sbput(s, "\"ssbPeriodicity\":%ld,", *scc->ssb_periodicityServingCell);
    if (scc->ssb_PositionsInBurst) {
        const BIT_STRING_t *bs = 0; const char *kind = 0;
        switch (scc->ssb_PositionsInBurst->present) {
            case ServingCellConfigCommon__ssb_PositionsInBurst_PR_shortBitmap:
                kind = "short"; bs = &scc->ssb_PositionsInBurst->choice.shortBitmap; break;
            case ServingCellConfigCommon__ssb_PositionsInBurst_PR_mediumBitmap:
                kind = "medium"; bs = &scc->ssb_PositionsInBurst->choice.mediumBitmap; break;
            case ServingCellConfigCommon__ssb_PositionsInBurst_PR_longBitmap:
                kind = "long"; bs = &scc->ssb_PositionsInBurst->choice.longBitmap; break;
            default: break;
        }
        if (kind && bs) { sbput(s, "\"ssbKind\":\"%s\",", kind); emit_hex(s, "ssbHex", bs); }
    }
    emit_tdd_leaf(s, scc->tdd_UL_DL_ConfigurationCommon);
    sbput(s, "}");
    return 0;
}

/* SIB1 variant: ServingCellConfigCommonSIB (ssb-PositionsInBurst is a SEQUENCE, not a CHOICE;
 * ssb periodicity is mandatory; no ssbSubcarrierSpacing field at this level). */
static int emit_from_sccsib(struct sb *s, const ServingCellConfigCommonSIB_t *scc) {
    if (!scc || !scc->tdd_UL_DL_ConfigurationCommon) { sbput(s, "\"tddPresent\":false}"); return 0; }
    sbput(s, "\"tddPresent\":true,");
    sbput(s, "\"ssbPeriodicity\":%ld,", scc->ssb_PeriodicityServingCell);
    sbput(s, "\"ssbKind\":\"inOneGroup\",");
    emit_hex(s, "ssbHex", &scc->ssb_PositionsInBurst.inOneGroup);
    emit_tdd_leaf(s, scc->tdd_UL_DL_ConfigurationCommon);
    sbput(s, "}");
    return 0;
}

char *nrrrc_decode_tdd_json(int pduKind, const unsigned char *buf, size_t n) {
    struct sb s; s.cap = 8192; s.p = (char *)malloc(s.cap); s.len = 0; if (!s.p) return 0; s.p[0] = 0;
    sbput(&s, "{");

    if (pduKind == 0) { /* RRCReconfiguration (NSA SCG) */
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
        emit_from_scc(&s, cg->spCellConfig->reconfigurationWithSync->spCellConfigCommon);
        return s.p;
    }

    if (pduKind == 1) { /* BCCH-DL-SCH-Message / SIB1 (SA broadcast) */
        BCCH_DL_SCH_Message_t *m = 0;
        asn_dec_rval_t rc = uper_decode_complete(0, &asn_DEF_BCCH_DL_SCH_Message, (void **)&m, buf, n);
        if (rc.code != RC_OK) { sbput(&s, "\"error\":\"bcch_decode\"}"); return s.p; }
        if (!m->message || m->message->present != BCCH_DL_SCH_MessageType_PR_c1 ||
            m->message->choice.c1.present != BCCH_DL_SCH_MessageType__c1_PR_systemInformationBlockType1) {
            sbput(&s, "\"error\":\"not_sib1\"}"); return s.p; }
        SIB1_t *sib1 = m->message->choice.c1.choice.systemInformationBlockType1;
        if (!sib1 || !sib1->servingCellConfigCommon) { sbput(&s, "\"error\":\"no_scc_sib\"}"); return s.p; }
        sbput(&s, "\"root\":\"SIB1\",");
        emit_from_sccsib(&s, sib1->servingCellConfigCommon);
        return s.p;
    }

    sbput(&s, "\"error\":\"unsupported_pdukind\"}");
    return s.p;
}
