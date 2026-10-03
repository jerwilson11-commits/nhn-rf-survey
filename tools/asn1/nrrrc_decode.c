/* NR RRC -> TDD-config + cell-info JSON core. No JVM deps; the JNI wrapper calls
 * nrrrc_decode_tdd_json(). Emits raw ASN.1 enum/integer values; the Kotlin adapter maps and scales
 * them (e.g. refSCS 1 = kHz30; qRxLevMin is in units of 2 dBm; ssPBCHBlockPower is already dBm). */
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
#include "DownlinkConfigCommon.h"
#include "DownlinkConfigCommonSIB.h"
#include "FrequencyInfoDL.h"
#include "FrequencyInfoDL-SIB.h"
#include "SCS-SpecificCarrier.h"
#include "CellAccessRelatedInfo.h"
#include "PLMN-IdentityInfoList.h"
#include "PLMN-IdentityInfo.h"
#include "PLMN-Identity.h"
#include "MCC.h"
#include "MNC.h"
#include "UE-TimersAndConstants.h"

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
    for (int i = 0; i < bs->size; i++) sbput(s, "%02x", (unsigned char)bs->buf[i]);
    sbput(s, "\",");
}

/* A fixed-length BIT STRING (TAC 24-bit, etc.) as an unsigned integer, honouring unused bits. */
static unsigned long bitstr_to_ulong(const BIT_STRING_t *b) {
    unsigned long v = 0;
    for (int i = 0; i < b->size; i++) v = (v << 8) | (unsigned char)b->buf[i];
    return v >> b->bits_unused;
}

/* A SEQUENCE OF MCC-MNC-Digit (NativeInteger) as a decimal string field. */
static void emit_digits(struct sb *s, const char *key, void **array, int count) {
    sbput(s, "\"%s\":\"", key);
    for (int i = 0; i < count; i++) sbput(s, "%ld", *((long *)array[i]));
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

/* First SCS-SpecificCarrier of a carrier list: channel bandwidth (RB), carrier SCS, offset. */
#define EMIT_CARRIER(s, listexpr) do {                                                        \
    if ((listexpr).count > 0 && (listexpr).array[0]) {                                         \
        const SCS_SpecificCarrier_t *c = (listexpr).array[0];                                  \
        sbput((s), "\"carrierBandwidthRb\":%ld,\"carrierSCS\":%ld,\"offsetToCarrier\":%ld,",   \
              c->carrierBandwidth, c->subcarrierSpacing, c->offsetToCarrier);                  \
    }                                                                                          \
} while (0)

/* RRCReconfiguration / NSA variant of ServingCellConfigCommon. */
static int emit_from_scc(struct sb *s, const ServingCellConfigCommon_t *scc) {
    if (!scc || !scc->tdd_UL_DL_ConfigurationCommon) { sbput(s, "\"tddPresent\":false}"); return 0; }
    sbput(s, "\"tddPresent\":true,");
    sbput(s, "\"ssPBCHBlockPower\":%ld,", scc->ss_PBCH_BlockPower);
    if (scc->ssbSubcarrierSpacing) sbput(s, "\"ssbSCS\":%ld,", *scc->ssbSubcarrierSpacing);
    if (scc->ssb_periodicityServingCell) sbput(s, "\"ssbPeriodicity\":%ld,", *scc->ssb_periodicityServingCell);
    if (scc->downlinkConfigCommon && scc->downlinkConfigCommon->frequencyInfoDL) {
        const FrequencyInfoDL_t *f = scc->downlinkConfigCommon->frequencyInfoDL;
        if (f->absoluteFrequencySSB) sbput(s, "\"ssbArfcn\":%ld,", *f->absoluteFrequencySSB);
        sbput(s, "\"pointAArfcn\":%ld,", f->absoluteFrequencyPointA);
        EMIT_CARRIER(s, f->scs_SpecificCarrierList.list);
    }
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

/* SIB1 variant: ServingCellConfigCommonSIB. The SIB1-level cell identity / selection fields are
 * emitted by the caller (they live on SIB1, not here). Does not open/close the JSON object. */
static int emit_from_sccsib(struct sb *s, const ServingCellConfigCommonSIB_t *scc) {
    if (!scc || !scc->tdd_UL_DL_ConfigurationCommon) { sbput(s, "\"tddPresent\":false}"); return 0; }
    sbput(s, "\"tddPresent\":true,");
    sbput(s, "\"ssPBCHBlockPower\":%ld,", scc->ss_PBCH_BlockPower);
    sbput(s, "\"ssbPeriodicity\":%ld,", scc->ssb_PeriodicityServingCell);
    if (scc->downlinkConfigCommon && scc->downlinkConfigCommon->frequencyInfoDL) {
        const FrequencyInfoDL_SIB_t *f = scc->downlinkConfigCommon->frequencyInfoDL;
        sbput(s, "\"offsetToPointA\":%ld,", f->offsetToPointA);
        EMIT_CARRIER(s, f->scs_SpecificCarrierList.list);
    }
    sbput(s, "\"ssbKind\":\"inOneGroup\",");
    emit_hex(s, "ssbHex", &scc->ssb_PositionsInBurst.inOneGroup);
    emit_tdd_leaf(s, scc->tdd_UL_DL_ConfigurationCommon);
    sbput(s, "}");
    return 0;
}

/* SIB1-level identity / selection / timers -- each as a trailing-comma field, emitted before the
 * ServingCellConfigCommonSIB block. */
static void emit_sib1_cellinfo(struct sb *s, const SIB1_t *sib1) {
    const CellAccessRelatedInfo_t *car = sib1->cellAccessRelatedInfo;
    if (car && car->plmn_IdentityInfoList && car->plmn_IdentityInfoList->list.count > 0) {
        const PLMN_IdentityInfo_t *pii = car->plmn_IdentityInfoList->list.array[0];
        if (pii) {
            if (pii->plmn_IdentityList.list.count > 0 && pii->plmn_IdentityList.list.array[0]) {
                const PLMN_Identity_t *p = pii->plmn_IdentityList.list.array[0];
                if (p->mcc) emit_digits(s, "mcc", (void **)p->mcc->list.array, p->mcc->list.count);
                if (p->mnc) emit_digits(s, "mnc", (void **)p->mnc->list.array, p->mnc->list.count);
            }
            if (pii->trackingAreaCode) sbput(s, "\"tac\":%lu,", bitstr_to_ulong(pii->trackingAreaCode));
            emit_hex(s, "nci", &pii->cellIdentity);
            sbput(s, "\"cellReservedForOperatorUse\":%ld,", pii->cellReservedForOperatorUse);
        }
    }
    if (sib1->cellSelectionInfo) {
        sbput(s, "\"qRxLevMin\":%ld,", sib1->cellSelectionInfo->q_RxLevMin);
        if (sib1->cellSelectionInfo->q_QualMin)
            sbput(s, "\"qQualMin\":%ld,", *sib1->cellSelectionInfo->q_QualMin);
    }
    if (sib1->ims_EmergencySupport) sbput(s, "\"imsEmergencySupport\":true,");
    if (sib1->ue_TimersAndConstants) {
        const UE_TimersAndConstants_t *u = sib1->ue_TimersAndConstants;
        sbput(s, "\"t310\":%ld,\"n310\":%ld,\"t311\":%ld,", u->t310, u->n310, u->t311);
    }
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
        const ReconfigurationWithSync_t *rws = cg->spCellConfig->reconfigurationWithSync;
        if (rws->spCellConfigCommon && rws->spCellConfigCommon->physCellId)
            sbput(&s, "\"pci\":%ld,", *rws->spCellConfigCommon->physCellId);
        emit_from_scc(&s, rws->spCellConfigCommon);
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
        emit_sib1_cellinfo(&s, sib1);
        emit_from_sccsib(&s, sib1->servingCellConfigCommon);
        return s.p;
    }

    sbput(&s, "\"error\":\"unsupported_pdukind\"}");
    return s.p;
}
