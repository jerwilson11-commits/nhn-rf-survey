/*
 * JNI bridge for the NR RRC TDD decoder. Compiled only into the Android libnrrrc.so (not the host
 * test harness). Pure computation -- no root, no privileged access -- so it loads in-process via
 * System.loadLibrary, unlike the su-run DIAG helpers.
 *
 * Kotlin side (object com.nhnengineering.rftest.modem.NrRrcDecoder):
 *     external fun decodeTdd(pduKind: Int, uper: ByteArray): String?
 */
#include <jni.h>
#include <stdlib.h>
#include "nrrrc_decode.h"

JNIEXPORT jstring JNICALL
Java_com_nhnengineering_rftest_modem_NrRrcDecoder_decodeTdd(
        JNIEnv *env, jobject thiz, jint pduKind, jbyteArray uper) {
    (void)thiz;
    if (!uper) return NULL;
    jsize n = (*env)->GetArrayLength(env, uper);
    jbyte *bytes = (*env)->GetByteArrayElements(env, uper, NULL);
    if (!bytes) return NULL;
    char *json = nrrrc_decode_tdd_json((int)pduKind, (const unsigned char *)bytes, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, uper, bytes, JNI_ABORT);
    if (!json) return NULL;
    jstring result = (*env)->NewStringUTF(env, json);
    free(json);
    return result;
}
