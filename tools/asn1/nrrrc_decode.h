#ifndef NRRRC_DECODE_H
#define NRRRC_DECODE_H
#include <stddef.h>

/*
 * Decode a captured NR RRC UPER body and return the TDD UL/DL configuration as JSON.
 *
 * pduKind: 0 = RRCReconfiguration (the NSA SCG-add, from 0xB821 DL-DCCH / the LTE-embedded
 *              nr-SecondaryCellGroupConfig) -- decodes the nested CellGroupConfig octet string.
 *          1 = BCCH-DL-SCH-Message / SIB1 (the SA broadcast, from 0xB821 SIB1).
 *
 * Returns a malloc'd NUL-terminated JSON string the caller must free. On failure the JSON is an
 * {"error":"..."} object rather than NULL (NULL only on allocation failure). Enum fields carry the
 * raw ASN.1 integer index (e.g. refSCS 1 = kHz30, periodicity 0 = ms0p5); the Kotlin adapter maps
 * them to ms/kHz so no 3GPP enum semantics live in C.
 */
char *nrrrc_decode_tdd_json(int pduKind, const unsigned char *buf, size_t n);

#endif /* NRRRC_DECODE_H */
