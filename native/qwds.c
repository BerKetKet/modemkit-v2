/* qwds: QMI WDS (data) helper, same plumbing as qband (vendor libqmi_cci via dlopen).
   Every TLV id below is from memory of the QMI WDS interface (libqmi / qmi_wds_v01.h):
   the raw TLV dump printed for every reply is the ground truth, check ids against it. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define WDS_START 0x0020
#define WDS_STOP 0x0021
#define WDS_STATUS 0x0022
#define WDS_MODIFY_PROFILE 0x0028
#define WDS_PROFILE_LIST 0x002A
#define WDS_PROFILE_SETTINGS 0x002B
#define WDS_RUNTIME 0x002D
#define WDS_BIND_MUX 0x00A2
#define TLV_RESULT 0x02

typedef int (*init_fn)(void *, int, void *, void *, void *, uint32_t, void **);
typedef int (*raw_fn)(void *, unsigned, void *, unsigned, void *, unsigned, unsigned *, unsigned);
typedef int (*rel_fn)(void *);
typedef int (*svclist_fn)(void *, void *, unsigned *, unsigned *);
typedef int (*cinit_fn)(void *, void *, void *, void *, void *, void **);

static raw_fn raw_send;
static void *client;
static uint8_t resp[8192];
static unsigned got;
static int g_swap;           /* swap=1: IPv4 u32 byte order reversed */
static volatile int g_stop;
static int g_argc;
static char **g_argv;

static const char *arg(const char *k)
{
    size_t n = strlen(k);
    for (int i = 2; i < g_argc; i++)
        if (!strncmp(g_argv[i], k, n) && g_argv[i][n] == '=')
            return g_argv[i] + n + 1;
    return NULL;
}

static void *load(const char *dir, const char *name)
{
    char p[512];
    snprintf(p, sizeof p, "%s/%s", dir, name);
    void *h = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
    if (!h) { fprintf(stderr, "dlopen %s failed: %s\n", p, dlerror()); exit(1); }
    return h;
}

static void *sym(void *h, const char *n)
{
    void *s = dlsym(h, n);
    if (!s) { fprintf(stderr, "missing symbol: %s\n", n); exit(1); }
    return s;
}

static unsigned put_tlv(uint8_t *p, uint8_t t, unsigned len, const void *d)
{
    p[0] = t; p[1] = len & 0xff; p[2] = len >> 8;
    memcpy(p + 3, d, len);
    return 3 + len;
}
static unsigned put_u8(uint8_t *p, uint8_t t, uint8_t v) { return put_tlv(p, t, 1, &v); }
static unsigned put_u32(uint8_t *p, uint8_t t, uint32_t v) { return put_tlv(p, t, 4, &v); }
static unsigned put_str(uint8_t *p, uint8_t t, const char *s) { return put_tlv(p, t, strlen(s), s); }

static const uint8_t *find(uint8_t t, unsigned *len)
{
    unsigned i = 0;
    while (i + 3 <= got) {
        unsigned l = resp[i + 1] | (resp[i + 2] << 8);
        if (i + 3 + l > got) break;
        if (resp[i] == t) { if (len) *len = l; return resp + i + 3; }
        i += 3 + l;
    }
    return NULL;
}

static uint32_t rd32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }

static void ip_str(uint32_t v, char *out)
{
    if (g_swap) v = __builtin_bswap32(v);
    sprintf(out, "%u.%u.%u.%u", v >> 24, (v >> 16) & 255, (v >> 8) & 255, v & 255);
}

static int ip_parse(const char *s, uint32_t *v)
{
    unsigned a, b, c, d;
    if (sscanf(s, "%u.%u.%u.%u", &a, &b, &c, &d) != 4 || a > 255 || b > 255 || c > 255 || d > 255) return -1;
    *v = (a << 24) | (b << 16) | (c << 8) | d;
    if (g_swap) *v = __builtin_bswap32(*v);
    return 0;
}

static void dump(void)
{
    unsigned i = 0;
    while (i + 3 <= got) {
        unsigned l = resp[i + 1] | (resp[i + 2] << 8);
        if (i + 3 + l > got) { printf("  TLV 0x%02x truncated\n", resp[i]); break; }
        printf("  TLV 0x%02x len %u:", resp[i], l);
        for (unsigned k = 0; k < l && k < 48; k++) printf(" %02x", resp[i + 3 + k]);
        printf("\n");
        i += 3 + l;
    }
}

/* Sends msg, prints the raw TLVs, returns the QMI result (0 = ok) or -1 if nothing came back. */
static int txn(unsigned msg, const uint8_t *req, unsigned rl, const char *what)
{
    got = 0;
    int rc = raw_send(client, msg, (void *)req, rl, resp, sizeof resp, &got, 15000);
    printf("[%s] msg 0x%04x\n", what, msg);
    if (rc) { printf("  send failed rc=%d\n", rc); return -1; }
    dump();
    unsigned l;
    const uint8_t *r = find(TLV_RESULT, &l);
    if (!r || l < 4) return -1;
    int res = r[0] | (r[1] << 8), err = r[2] | (r[3] << 8);
    printf("  -> result=%d error=%d%s\n", res, err, res ? " (FAILED)" : "");
    return res ? err ? err : 1 : 0;
}

static void show_runtime(void)
{
    uint8_t req[16];
    unsigned n = put_u32(req, 0x10, arg("mask") ? strtoul(arg("mask"), NULL, 0) : 0xFFFF);
    if (txn(WDS_RUNTIME, req, n, "runtime settings")) return;
    unsigned l;
    const uint8_t *p;
    char b[32];
    if ((p = find(0x1E, &l)) && l == 4) { ip_str(rd32(p), b); printf("ipv4 address: %s\n", b); }
    if ((p = find(0x20, &l)) && l == 4) { ip_str(rd32(p), b); printf("ipv4 gateway: %s\n", b); }
    if ((p = find(0x21, &l)) && l == 4) { ip_str(rd32(p), b); printf("ipv4 mask: %s\n", b); }
    if ((p = find(0x15, &l)) && l == 4) { ip_str(rd32(p), b); printf("dns1: %s\n", b); }
    if ((p = find(0x16, &l)) && l == 4) { ip_str(rd32(p), b); printf("dns2: %s\n", b); }
    if ((p = find(0x14, &l))) printf("apn: %.*s\n", (int)l, p);
}

static void cmd_profiles(void)
{
    if (txn(WDS_PROFILE_LIST, (uint8_t *)"", 0, "profile list")) return;
    unsigned l;
    const uint8_t *p = find(0x01, &l);
    if (!p || l < 1) return;
    unsigned cnt = p[0], o = 1;
    for (unsigned k = 0; k < cnt && o + 3 <= l; k++) {
        unsigned nl = p[o + 2];
        if (o + 3 + nl > l) break;
        printf("profile type %u index %u name %.*s\n", p[o], p[o + 1], (int)nl, p + o + 3);
        o += 3 + nl;
    }
}

static void cmd_profile(int idx)
{
    uint8_t req[8], t[2] = {0, (uint8_t)idx};
    unsigned n = put_tlv(req, 0x01, 2, t);
    if (txn(WDS_PROFILE_SETTINGS, req, n, "profile settings")) return;
    unsigned l;
    const uint8_t *p;
    char b[32];
    if ((p = find(0x10, &l))) printf("profile name: %.*s\n", (int)l, p);
    if ((p = find(0x14, &l))) printf("profile apn: %.*s\n", (int)l, p);
    if ((p = find(0x11, &l)) && l == 1) printf("profile pdp type: %u\n", p[0]);
    if ((p = find(0x1B, &l))) printf("profile user: %.*s\n", (int)l, p);
    if ((p = find(0x1D, &l)) && l == 1) printf("profile auth: %u\n", p[0]);
    if ((p = find(0x1E, &l)) && l == 4) { ip_str(rd32(p), b); printf("profile ipv4 pref: %s\n", b); }
}

static void cmd_setip(int idx, const char *ip)
{
    uint32_t v;
    if (ip_parse(ip, &v)) { fprintf(stderr, "bad ip\n"); return; }
    uint8_t req[32], t[2] = {0, (uint8_t)idx};
    unsigned n = put_tlv(req, 0x01, 2, t);
    n += put_u32(req + n, 0x1E, v);
    txn(WDS_MODIFY_PROFILE, req, n, "modify profile ipv4 pref");
}

static void on_term(int s) { (void)s; g_stop = 1; }

static void cmd_start(void)
{
    uint8_t req[512];
    unsigned n = 0;
    const char *v;
    if ((v = arg("mux"))) { /* optional: bind to a mux/endpoint first (experimental) */
        uint8_t r2[32];
        unsigned m = 0;
        if (arg("ep")) {
            uint32_t ep[2] = {strtoul(arg("ep"), NULL, 0), arg("epid") ? strtoul(arg("epid"), NULL, 0) : 0};
            m += put_tlv(r2 + m, 0x10, 8, ep);
        }
        m += put_u8(r2 + m, 0x11, atoi(v));
        txn(WDS_BIND_MUX, r2, m, "bind mux");
    }
    if ((v = arg("apn"))) n += put_str(req + n, 0x14, v);
    if ((v = arg("ip"))) {
        uint32_t ip;
        if (ip_parse(v, &ip)) { fprintf(stderr, "bad ip\n"); return; }
        n += put_u32(req + n, 0x15, ip);
    }
    if ((v = arg("auth"))) n += put_u8(req + n, 0x16, atoi(v));
    if ((v = arg("user"))) n += put_str(req + n, 0x17, v);
    if ((v = arg("pass"))) n += put_str(req + n, 0x18, v);
    if ((v = arg("fam"))) n += put_u8(req + n, 0x19, atoi(v));
    if ((v = arg("prof"))) { n += put_u8(req + n, 0x30, 1); n += put_u8(req + n, 0x31, atoi(v)); }
    int r = txn(WDS_START, req, n, "start network");
    unsigned l;
    const uint8_t *p;
    if ((p = find(0x10, &l)) && l == 2) printf("call end reason: %u\n", p[0] | (p[1] << 8));
    if ((p = find(0x11, &l)) && l == 4) printf("verbose call end reason: type %u reason %u\n", p[0] | (p[1] << 8), p[2] | (p[3] << 8));
    if (r) return;
    uint32_t handle = 0;
    if ((p = find(0x01, &l)) && l == 4) handle = rd32(p);
    printf("packet data handle: 0x%x\n", handle);
    int hold = arg("hold") ? atoi(arg("hold")) : 20;
    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);
    for (int i = 0; i < hold && !g_stop; i++) {
        sleep(1);
        if (i == 2) show_runtime();
    }
    uint8_t s[8];
    unsigned sn = put_u32(s, 0x01, handle);
    txn(WDS_STOP, s, sn, "stop network");
}

int main(int argc, char **argv)
{
    g_argc = argc; g_argv = argv;
    if (argc < 2) {
        fprintf(stderr, "usage: qwds status | profiles | profile N | setip N a.b.c.d | rt |\n"
                "  start [apn= ip= fam=4|6 auth=0..3 user= pass= prof=N hold=SECS mux=ID ep= epid=] [swap=1] [mask=0x..]\n");
        return 1;
    }
    g_swap = arg("swap") && atoi(arg("swap"));
    const char *dir = getenv("QLIBDIR");
    if (!dir) dir = "/data/local/tmp/q";
    void *hs = load(dir, "libqmiservices.so");
    void *hc = load(dir, "libqmi_cci.so");
    void *svc = sym(hs, "wds_qmi_idl_service_object_v01");
    init_fn init = (init_fn)sym(hc, "qmi_client_init_instance");
    rel_fn rel = (rel_fn)sym(hc, "qmi_client_release");
    raw_send = (raw_fn)sym(hc, "qmi_client_send_raw_msg_sync");

    static uint8_t infos[8 * 256], os_params[1024];
    int found = 0, rc = -1;
    svclist_fn lst = (svclist_fn)dlsym(hc, "qmi_client_get_service_list");
    if (lst) {
        unsigned entries = 8, services = 0;
        found = (lst(svc, infos, &entries, &services) == 0 && entries >= 1);
    }
    cinit_fn cinit = (cinit_fn)dlsym(hc, "qmi_client_init");
    if (found && cinit) rc = cinit(infos, svc, NULL, NULL, os_params, &client);
    if (rc) rc = init(svc, -1, NULL, NULL, os_params, 5000, &client);
    if (rc) { fprintf(stderr, "qmi client init failed rc=%d\n", rc); return 1; }

    const char *c = argv[1];
    if (!strcmp(c, "status")) {
        if (!txn(WDS_STATUS, (uint8_t *)"", 0, "packet service status")) {
            unsigned l;
            const uint8_t *p = find(0x01, &l);
            if (p && l >= 1) printf("connection status: %u (1 disconnected, 2 connected, 3 suspended, 4 authenticating)\n", p[0]);
        }
    } else if (!strcmp(c, "profiles")) cmd_profiles();
    else if (!strcmp(c, "profile") && argc >= 3) cmd_profile(atoi(argv[2]));
    else if (!strcmp(c, "setip") && argc >= 4) cmd_setip(atoi(argv[2]), argv[3]);
    else if (!strcmp(c, "rt")) show_runtime();
    else if (!strcmp(c, "start")) cmd_start();
    else fprintf(stderr, "unknown command\n");
    rel(client);
    return 0;
}
