/*
 * dci_logger - subscribe to modem log codes over DCI and print each packet as hex.
 *
 * Why this rather than talking to the modem directly
 * --------------------------------------------------
 * Log masks cannot be set by talking to the peripheral. DIAG_LOG_CONFIG_F (0x73)
 * sent to MODEM:CMD or MODEM:DCI_CMD over QRTR comes back as 0x13, bad command,
 * and a control experiment (an invalid command returns the same shape) confirms
 * that is a rejection and not a framing mistake. On a normal Android device the
 * diagchar kernel driver intercepts 0x73 and translates it into control-channel
 * messages; this handset has no diagchar, so nothing implements it.
 *
 * What it does have is /vendor/lib64/libdiag.so, which already speaks the QRTR
 * transport and owns the mask translation, and /vendor/bin/diag_dci_sample,
 * which proves the whole chain works here:
 *
 *     Diag-Router:  log code 115f set
 *     Diag_Lib:     Received a Log of type Diag Log Stress Test, length = 524
 *
 * So this uses the vendor's own DCI API rather than reimplementing the control
 * channel. dlopen rather than link-time, because the NDK has no libdiag stub.
 *
 * Deliberately prints raw hex and decodes nothing. The log payload formats are
 * decoded in Kotlin where they can be unit-tested against captures, the same
 * split as qmihelper.
 *
 * usage: dci_logger <seconds> <log_code_hex> [log_code_hex ...]
 *   e.g. dci_logger 20 b97f b823
 *        b97f = NR ML1 Measurement Database Update  (neighbour measurements)
 *        b823 = NR RRC Serving Cell Info
 *
 * Output:
 *   READY <n> code(s)
 *   LOG <lowercase hex of one log packet>
 *   DONE <count>
 *   ERR <what failed>
 */

#include <dlfcn.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DIAG_CON_MPSS   0x0002
#define DIAG_PROC_MSM   0
#define DCI_ENABLE      1
#define DCI_DISABLE     0

typedef int (*fn_lsm_init)(void *);
typedef int (*fn_lsm_deinit)(void);
typedef int (*fn_reg_client)(int *, uint16_t *, int, void *);
typedef int (*fn_reg_stream_proc)(int,
                                  void (*)(unsigned char *, int),
                                  void (*)(unsigned char *, int));
typedef int (*fn_log_stream_config)(int, int, uint16_t *, int);
typedef int (*fn_release_client)(int *);

static unsigned long received = 0;

/*
 * Log masks are global modem state. If this exits without clearing them the
 * modem keeps logging for a client that is gone, so every exit path has to go
 * through the cleanup at the end of main -- which means no _exit from a signal
 * handler, just a flag the loop checks.
 */
static volatile sig_atomic_t stop_flag = 0;

static void on_term(int sig)
{
    (void)sig;
    stop_flag = 1;
}

static void on_log(unsigned char *ptr, int len)
{
    if (!ptr || len <= 0) return;
    received++;
    fputs("LOG ", stdout);
    for (int i = 0; i < len; i++) printf("%02x", ptr[i]);
    putchar('\n');
    if (fflush(stdout) != 0) {
        /* The reader closed the pipe. Stop rather than keep the masks set for
         * an audience that is no longer there. */
        stop_flag = 1;
    }
}

static void on_event(unsigned char *ptr, int len)
{
    (void)ptr;
    (void)len;
    /* Events are not subscribed to; the API requires a pointer regardless. */
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        printf("ERR usage: dci_logger <seconds> <log_code_hex> [...]\n");
        return 1;
    }
    int seconds = atoi(argv[1]);
    int n_codes = argc - 2;
    uint16_t *codes = calloc((size_t)n_codes, sizeof(uint16_t));
    for (int i = 0; i < n_codes; i++)
        codes[i] = (uint16_t)strtoul(argv[i + 2], NULL, 16);

    void *h = dlopen("libdiag.so", RTLD_NOW);
    if (!h) h = dlopen("/vendor/lib64/libdiag.so", RTLD_NOW);
    if (!h) {
        printf("ERR dlopen libdiag: %s\n", dlerror());
        return 1;
    }

    fn_lsm_init          lsm_init   = (fn_lsm_init)dlsym(h, "Diag_LSM_Init");
    fn_lsm_deinit        lsm_deinit = (fn_lsm_deinit)dlsym(h, "Diag_LSM_DeInit");
    fn_reg_client        reg_client = (fn_reg_client)dlsym(h, "diag_register_dci_client");
    fn_reg_stream_proc   reg_stream = (fn_reg_stream_proc)dlsym(h, "diag_register_dci_stream_proc");
    fn_log_stream_config log_config = (fn_log_stream_config)dlsym(h, "diag_log_stream_config");
    fn_release_client    release    = (fn_release_client)dlsym(h, "diag_release_dci_client");

    if (!lsm_init || !reg_client || !reg_stream || !log_config || !release) {
        printf("ERR libdiag is missing a DCI symbol\n");
        return 1;
    }

    /* The sample installs a handler for the signal it hands to the client; the
     * library raises it when data is waiting. Ignoring it would kill us. */
    int signal_type = SIGCONT;
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_IGN;
    sigemptyset(&sa.sa_mask);
    sigaction(signal_type, &sa, NULL);

    /* Without this a closed pipe kills the process outright and the masks stay
     * set; with it the write fails, the flag is set and cleanup runs. */
    sigaction(SIGPIPE, &sa, NULL);

    struct sigaction term;
    memset(&term, 0, sizeof(term));
    term.sa_handler = on_term;
    sigemptyset(&term.sa_mask);
    sigaction(SIGTERM, &term, NULL);
    sigaction(SIGINT, &term, NULL);

    if (!lsm_init(NULL)) {
        printf("ERR Diag_LSM_Init failed\n");
        return 2;
    }

    int client_id = 0;
    uint16_t peripherals = DIAG_CON_MPSS;
    int err = reg_client(&client_id, &peripherals, DIAG_PROC_MSM, &signal_type);
    if (err != 1001 /* DIAG_DCI_NO_ERROR */) {
        printf("ERR diag_register_dci_client returned %d\n", err);
        if (lsm_deinit) lsm_deinit();
        return 3;
    }

    err = reg_stream(client_id, on_log, on_event);
    if (err != 1001) {
        printf("ERR diag_register_dci_stream_proc returned %d\n", err);
        release(&client_id);
        if (lsm_deinit) lsm_deinit();
        return 4;
    }

    err = log_config(client_id, DCI_ENABLE, codes, n_codes);
    if (err != 1001) {
        printf("ERR diag_log_stream_config(ENABLE) returned %d\n", err);
        release(&client_id);
        if (lsm_deinit) lsm_deinit();
        return 5;
    }

    printf("READY %d code(s)\n", n_codes);
    fflush(stdout);

    /* Polled rather than slept so a stop is noticed promptly. Zero or negative
     * means run until stopped, which is how the app uses it. */
    for (long tick = 0; !stop_flag; tick++) {
        if (seconds > 0 && tick >= (long)seconds * 10) break;
        usleep(100000);
    }

    /* Masks are global modem state: leaving them set would keep the modem
     * logging for nobody after this exits. */
    log_config(client_id, DCI_DISABLE, codes, n_codes);
    release(&client_id);
    if (lsm_deinit) lsm_deinit();

    printf("DONE %lu\n", received);
    free(codes);
    return 0;
}
