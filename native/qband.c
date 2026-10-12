#define _GNU_SOURCE
#include <dlfcn.h>
#include <grp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define NAS_SET_SSP 0x0033
#define NAS_GET_SSP 0x0034
#define TLV_RESULT 0x02
#define TLV_LTE_BANDS 0x15     /* set + get: bands 1-64 */
#define TLV_DURATION 0x17      /* set: 0 = power cycle */
#define TLV_EXT_GET 0x23       /* get: four 64-bit masks, bands 1-256 */
#define TLV_EXT_SET 0x24       /* set: four 64-bit masks, bands 1-256 */
#define TLV_MODE 0x11          /* set + get: RAT mode preference (2 bytes) */
#define NAS_GET_SUPPORTED 0x001E
#define NAS_GET_CELL_LOC 0x0043 /* read-only: serving + neighbor cells */
#define MAX_BAND 256
#define QBAND_VERSION "2026-10-02-perm"
#define ORIG_FILE "/data/local/tmp/qband.orig"

typedef int (*init_fn)(void *, int, void *, void *, void *, uint32_t, void **);
typedef int (*raw_fn)(void *, unsigned, void *, unsigned, void *, unsigned, unsigned *, unsigned);
typedef int (*rel_fn)(void *);
typedef int (*svclist_fn)(void *, void *, unsigned *, unsigned *);
typedef int (*cinit_fn)(void *, void *, void *, void *, void *, void **);

/* m[0] = bands 1-64, m[1] = 65-128, m[2] = 129-192, m[3] = 193-256 */
typedef struct {
    uint64_t m[4];
    int have_low; /* TLV 0x15 seen */
    int have_ext; /* TLV 0x23 seen */
    uint16_t mode; /* TLV 0x11: 0x01/0x02 CDMA, 0x04 GSM, 0x08 UMTS, 0x10 LTE, 0x20 TD-SCDMA */
    int have_mode;
} bandset;

static raw_fn raw_send;
static void *client;
static int quiet;
static int last_err;
static int g_perm; /* "perm" as the last argument: change duration 1 (permanent) instead of 0 (until power cycle) */

static void *load(const char *dir, const char *name)
{
    char p[512];
    snprintf(p, sizeof p, "%s/%s", dir, name);
    void *h = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        fprintf(stderr, "dlopen %s failed: %s\n", p, dlerror());
        exit(1);
    }
    return h;
}

static void *sym(void *h, const char *n)
{
    void *s = dlsym(h, n);
    if (!s) {
        fprintf(stderr, "missing symbol: %s\n", n);
        exit(1);
    }
    return s;
}

static int parse(const uint8_t *b, unsigned n, bandset *bs)
{
    int result = -1;
    uint64_t low = 0, ext[4] = {0, 0, 0, 0};
    int have_low = 0, have_ext = 0;
    uint16_t mode = 0;
    int have_mode = 0;
    unsigned i = 0;
    while (i + 3 <= n) {
        uint8_t t = b[i];
        unsigned l = b[i + 1] | (b[i + 2] << 8);
        i += 3;
        if (i + l > n)
            break;
        if (!quiet) {
            printf("  TLV 0x%02x len %u:", t, l);
            for (unsigned k = 0; k < l && k < 32; k++)
                printf(" %02x", b[i + k]);
            printf("\n");
        }
        if (t == TLV_RESULT && l >= 4) {
            result = b[i] | (b[i + 1] << 8);
            last_err = b[i + 2] | (b[i + 3] << 8);
            if (!quiet)
                printf("  -> result=%d error=%d\n", result, b[i + 2] | (b[i + 3] << 8));
        }
        if (t == TLV_LTE_BANDS && l == 8) {
            memcpy(&low, b + i, 8);
            have_low = 1;
        }
        if (t == TLV_EXT_GET && l == 32) {
            memcpy(ext, b + i, 32);
            have_ext = 1;
        }
        if (t == TLV_MODE && l == 2) {
            memcpy(&mode, b + i, 2);
            have_mode = 1;
        }
        i += l;
    }
    if (bs) {
        memset(bs, 0, sizeof *bs);
        bs->have_low = have_low;
        bs->have_ext = have_ext;
        bs->mode = mode;
        bs->have_mode = have_mode;
        if (have_ext)
            memcpy(bs->m, ext, 32);
        else if (have_low)
            bs->m[0] = low;
    }
    return result;
}

static int txn(unsigned msg, const uint8_t *req, unsigned rlen, bandset *bs)
{
    uint8_t resp[2048];
    unsigned got = 0;
    int rc = raw_send(client, msg, (void *)req, rlen, resp, sizeof resp, &got, 5000);
    if (rc) {
        fprintf(stderr, "send failed rc=%d\n", rc);
        return -1;
    }
    return parse(resp, got, bs);
}

static int get_bands(bandset *bs)
{
    uint8_t dummy = 0;
    memset(bs, 0, sizeof *bs); /* a failed send never reaches parse(), so don't keep stack junk */
    txn(NAS_GET_SSP, &dummy, 0, bs);
    return (bs->have_low || bs->have_ext) ? 0 : -1;
}

static unsigned put_tlv(uint8_t *p, uint8_t t, unsigned len, const void *data)
{
    p[0] = t;
    p[1] = len & 0xff;
    p[2] = len >> 8;
    memcpy(p + 3, data, len);
    return 3 + len;
}

static int set_bands(const bandset *want, int use_ext)
{
    uint8_t req[64];
    unsigned n = 0;
    uint8_t dur = g_perm ? 1 : 0; /* 0 = until power cycle, 1 = permanent (QMI NAS Change Duration) */
    /* This firmware rejects TLV 0x15 and 0x24 together (QMI error 48), and a
       0x15-only set clears every block above 64. So the extended TLV goes alone. */
    if (!use_ext)
        n += put_tlv(req + n, TLV_LTE_BANDS, 8, &want->m[0]);
    n += put_tlv(req + n, TLV_DURATION, 1, &dur);
    if (use_ext)
        n += put_tlv(req + n, TLV_EXT_SET, 32, want->m);
    return txn(NAS_SET_SSP, req, n, NULL);
}

static int set_mode(uint16_t mode)
{
    uint8_t req[16];
    unsigned n = 0;
    uint8_t dur = g_perm ? 1 : 0; /* 0 = until power cycle, 1 = permanent (QMI NAS Change Duration) */
    n += put_tlv(req + n, TLV_MODE, 2, &mode);
    n += put_tlv(req + n, TLV_DURATION, 1, &dur);
    return txn(NAS_SET_SSP, req, n, NULL);
}

/* Read-only: ask the modem which NAS message IDs it supports (libqmi's
   Get Supported Messages). Bit j of byte i is decoded as message ID i*8+j. */
static void list_msgs(void)
{
    uint8_t resp[2048];
    unsigned got = 0;
    uint8_t dummy = 0;
    int rc = raw_send(client, NAS_GET_SUPPORTED, &dummy, 0, resp, sizeof resp, &got, 5000);
    if (rc) {
        fprintf(stderr, "get supported messages failed rc=%d\n", rc);
        return;
    }
    unsigned i = 0;
    while (i + 3 <= got) {
        uint8_t t = resp[i];
        unsigned l = resp[i + 1] | (resp[i + 2] << 8);
        i += 3;
        if (i + l > got)
            break;
        if (t == TLV_RESULT && l >= 4)
            printf("  result=%d error=%d\n", resp[i] | (resp[i + 1] << 8), resp[i + 2] | (resp[i + 3] << 8));
        if (t == 0x10 && l >= 2) {
            unsigned cnt = resp[i] | (resp[i + 1] << 8);
            if (cnt > l - 2)
                cnt = l - 2;
            printf("  raw list (%u bytes):", cnt);
            for (unsigned k = 0; k < cnt; k++)
                printf(" %02x", resp[i + 2 + k]);
            printf("\n  NAS message IDs:");
            for (unsigned k = 0; k < cnt; k++)
                for (unsigned bit = 0; bit < 8; bit++)
                    if (resp[i + 2 + k] & (1u << bit))
                        printf(" %04x", k * 8 + bit);
            printf("\n");
        }
        i += l;
    }
}

/* Read-only: NAS Get Cell Location Info (0x0043). Every TLV is printed in full, then the LTE
   intra-frequency (0x13) and inter-frequency (0x14) lists are decoded using the field layout
   from the QMI NAS interface description (written from memory: if the numbers look wrong,
   the raw bytes above them are the ground truth). RSRP/RSRQ/RSSI are in 1/10 dB units. */
static unsigned u16le(const uint8_t *p) { return p[0] | (p[1] << 8); }
static int s16le(const uint8_t *p) { return (int16_t)(p[0] | (p[1] << 8)); }

static void print_cells(const uint8_t *p, unsigned n)
{
    for (unsigned c = 0; c < n; c++, p += 10)
        printf("      PCI %-3u RSRP %.1f RSRQ %.1f RSSI %.1f srxlev %d\n",
               u16le(p), s16le(p + 4) / 10.0, s16le(p + 2) / 10.0, s16le(p + 6) / 10.0, s16le(p + 8));
}

static void decode_intra(const uint8_t *p, unsigned l)
{
    if (l < 19)
        return;
    unsigned n = p[18];
    uint32_t eci = (uint32_t)p[6] | ((uint32_t)p[7] << 8) | ((uint32_t)p[8] << 16) | ((uint32_t)p[9] << 24);
    printf("  LTE intra-frequency: idle=%u tac=%u eci=%u (tower %u cell %u) earfcn=%u serving pci=%u cells=%u\n",
           p[0], u16le(p + 4), eci, eci >> 8, eci & 0xff, u16le(p + 10), u16le(p + 12), n);
    if (19 + n * 10 > l) {
        printf("    cell list does not fit in the TLV, check the raw bytes\n");
        n = (l - 19) / 10;
    }
    print_cells(p + 19, n);
}

static void decode_inter(const uint8_t *p, unsigned l)
{
    if (l < 2)
        return;
    unsigned nf = p[1], off = 2;
    printf("  LTE inter-frequency: idle=%u frequencies=%u\n", p[0], nf);
    for (unsigned f = 0; f < nf; f++) {
        if (off + 6 > l) {
            printf("    frequency list does not fit in the TLV, check the raw bytes\n");
            break;
        }
        unsigned nc = p[off + 5];
        printf("    EARFCN %u priority %u cells %u\n", u16le(p + off), p[off + 4], nc);
        off += 6;
        if (off + nc * 10 > l) {
            printf("      cell list does not fit in the TLV, check the raw bytes\n");
            break;
        }
        print_cells(p + off, nc);
        off += nc * 10;
    }
}

static void cell_info(void)
{
    static uint8_t resp[8192];
    unsigned got = 0;
    uint8_t dummy = 0;
    int rc = raw_send(client, NAS_GET_CELL_LOC, &dummy, 0, resp, sizeof resp, &got, 5000);
    if (rc) {
        fprintf(stderr, "get cell location info failed rc=%d\n", rc);
        return;
    }
    printf("cell location info: %u bytes\n", got);
    unsigned i = 0;
    while (i + 3 <= got) {
        uint8_t t = resp[i];
        unsigned l = u16le(resp + i + 1);
        i += 3;
        if (i + l > got) {
            printf("  TLV 0x%02x truncated\n", t);
            break;
        }
        printf("  TLV 0x%02x len %u:", t, l);
        for (unsigned k = 0; k < l; k++)
            printf(" %02x", resp[i + k]);
        printf("\n");
        if (t == TLV_RESULT && l >= 4)
            printf("  -> result=%u error=%u\n", u16le(resp + i), u16le(resp + i + 2));
        if (t == 0x13)
            decode_intra(resp + i, l);
        if (t == 0x14)
            decode_inter(resp + i, l);
        i += l;
    }
}

static int same(const bandset *a, const bandset *b, int ext)
{
    if (a->m[0] != b->m[0])
        return 0;
    if (ext)
        return a->m[1] == b->m[1] && a->m[2] == b->m[2] && a->m[3] == b->m[3];
    return 1;
}

/* Reads the saved baseline; returns how many masks were in the file (0 if none). */
static int load_orig(unsigned long long v[4])
{
    FILE *f = fopen(ORIG_FILE, "r");
    int cnt = 0;
    v[0] = v[1] = v[2] = v[3] = 0;
    if (f) {
        cnt = fscanf(f, "%llu %llu %llu %llu", &v[0], &v[1], &v[2], &v[3]);
        fclose(f);
    }
    return cnt < 0 ? 0 : cnt;
}

static void print_mask(const bandset *bs)
{
    printf("  LTE band mask: 0x%llx\n", (unsigned long long)bs->m[0]);
    if (bs->have_ext)
        printf("  EXT masks: 0x%llx 0x%llx 0x%llx 0x%llx\n",
               (unsigned long long)bs->m[0], (unsigned long long)bs->m[1],
               (unsigned long long)bs->m[2], (unsigned long long)bs->m[3]);
    if (bs->have_mode)
        printf("  MODE pref: 0x%x\n", bs->mode);
}

static int parse_list(const char *s, bandset *out)
{
    char buf[2048];
    int any = 0;
    memset(out, 0, sizeof *out);
    strncpy(buf, s, sizeof buf - 1);
    buf[sizeof buf - 1] = 0;
    for (char *tok = strtok(buf, ","); tok; tok = strtok(NULL, ",")) {
        if (*tok == 'B' || *tok == 'b')
            tok++;
        int n = atoi(tok);
        if (n < 1 || n > MAX_BAND)
            return -1;
        out->m[(n - 1) / 64] |= 1ULL << ((n - 1) % 64);
        any = 1;
    }
    return any ? 0 : -1;
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: qband get | set 7 | set 3,7,66 | set all | mode auto|lte|3g2g | msgs | cell | version   (bands 1-%d)\n  add perm as the last word of set or mode to make it survive reboots\n", MAX_BAND);
        return 1;
    }
    if (!strcmp(argv[1], "version")) {
        printf("qband %s (built %s %s)\n", QBAND_VERSION, __DATE__, __TIME__);
        return 0;
    }
    if (argc >= 4 && !strcmp(argv[argc - 1], "perm"))
        g_perm = 1;
    const char *dir = getenv("QLIBDIR");
    if (!dir)
        dir = "/data/local/tmp/q";

    void *hs = load(dir, "libqmiservices.so");
    void *hc = load(dir, "libqmi_cci.so");
    void *svc = sym(hs, "nas_qmi_idl_service_object_v01");
    init_fn init = (init_fn)sym(hc, "qmi_client_init_instance");
    rel_fn rel = (rel_fn)sym(hc, "qmi_client_release");
    raw_send = (raw_fn)sym(hc, "qmi_client_send_raw_msg_sync");

    const char *quid = getenv("QUID");
    if (quid) {
        int id = atoi(quid);
        gid_t groups[] = {1001, 3003, 3004, 3005};
        if (setgroups(4, groups) || setgid(id) || setuid(id)) {
            perror("drop privileges");
            return 1;
        }
        printf("running as uid %d\n", (int)getuid());
    }

    static uint8_t infos[8 * 256];
    void *found_info = infos;
    int found = 0;
    svclist_fn lst = (svclist_fn)dlsym(hc, "qmi_client_get_service_list");
    if (lst) {
        unsigned entries = 8, services = 0;
        int lr = lst(svc, infos, &entries, &services);
        printf("service list: rc=%d entries=%u services=%u\n", lr, entries, services);
        found = (lr == 0 && entries >= 1);
    } else {
        printf("qmi_client_get_service_list not exported\n");
    }

    static uint8_t os_params[1024];
    int rc = -1;
    cinit_fn cinit = (cinit_fn)dlsym(hc, "qmi_client_init");
    if (found && cinit) {
        rc = cinit(found_info, svc, NULL, NULL, os_params, &client);
        printf("qmi_client_init rc=%d\n", rc);
    }
    if (rc)
        rc = init(svc, -1, NULL, NULL, os_params, 5000, &client);
    if (rc) {
        fprintf(stderr, "qmi_client_init_instance failed rc=%d\n", rc);
        return 1;
    }

    bandset cur;
    quiet = strcmp(argv[1], "get") != 0;
    printf("current preference:\n");
    int have = (get_bands(&cur) == 0);
    if (have)
        print_mask(&cur);
    else
        printf("  no LTE band TLV found in response, check TLV ids above\n");

    if (!strcmp(argv[1], "set") && argc >= 3) {
        bandset want;
        memset(&want, 0, sizeof want);
        /* Only send the extended TLV if the modem reported one in the get. */
        int use_ext = have && cur.have_ext;

        if (have) {
            FILE *f = fopen(ORIG_FILE, "r");
            if (!f) {
                f = fopen(ORIG_FILE, "w");
                if (f) {
                    fprintf(f, "%llu %llu %llu %llu\n",
                            (unsigned long long)cur.m[0], (unsigned long long)cur.m[1],
                            (unsigned long long)cur.m[2], (unsigned long long)cur.m[3]);
                    printf("saved original masks to %s\n", ORIG_FILE);
                }
            }
            if (f)
                fclose(f);
        }
        if (!strcmp(argv[2], "all")) {
            FILE *f = fopen(ORIG_FILE, "r");
            unsigned long long v[4] = {0, 0, 0, 0};
            int cnt = f ? fscanf(f, "%llu %llu %llu %llu", &v[0], &v[1], &v[2], &v[3]) : 0;
            if (f)
                fclose(f);
            if (cnt < 1) {
                fprintf(stderr, "no saved original mask, reboot to reset\n");
                return 1;
            }
            if (cnt < 4) {
                printf("warning: old-format save file, upper bands (65+) not restored.\n"
                       "  Reboot, delete %s, then run get to re-save a full baseline.\n", ORIG_FILE);
                use_ext = 0;
            }
            for (int i = 0; i < 4; i++)
                want.m[i] = i < cnt ? v[i] : 0;
        } else if (parse_list(argv[2], &want)) {
            fprintf(stderr, "bad band list (bands 1-%d, comma separated)\n", MAX_BAND);
            return 1;
        }
        printf("setting masks 0x%llx 0x%llx 0x%llx 0x%llx (%s, %s)\n",
               (unsigned long long)want.m[0], (unsigned long long)want.m[1],
               (unsigned long long)want.m[2], (unsigned long long)want.m[3],
               use_ext ? "extended" : "64-bit only",
               g_perm ? "PERMANENT, survives reboots" : "power-cycle duration, reboot undoes it");
        int sr = set_bands(&want, use_ext);
        if (sr != 0 && use_ext && g_perm) {
            printf("extended set rejected (result=%d error=%d); not retrying with the 64-bit mask because that would clear bands above 64 permanently\n", sr, last_err);
        } else if (sr != 0 && use_ext) {
            printf("extended set rejected (result=%d error=%d), retrying with 64-bit mask only (this clears bands above 64 until reboot)\n", sr, last_err);
            use_ext = 0;
            sr = set_bands(&want, 0);
        }
        printf("set result=%d error=%d (0 means accepted)\n", sr, last_err);
        /* The modem may silently drop bands it doesn't support, so the readback
           can legitimately be smaller than what we sent. Also accept "want AND
           baseline" (baseline = what the modem had enabled before we touched it). */
        bandset expect = want;
        unsigned long long ov[4];
        int oc = load_orig(ov);
        if (oc >= 1)
            expect.m[0] &= ov[0];
        if (use_ext && oc >= 4)
            for (int i = 1; i < 4; i++)
                expect.m[i] &= ov[i];

        int applied = 0;
        bandset now;
        for (int i = 1; i <= 20 && !applied; i++) {
            sleep(1);
            if (get_bands(&now) != 0)
                continue;
            if (same(&now, &want, use_ext)) {
                applied = 1;
                cur = now;
                printf("applied after %d s\n", i);
            } else if (same(&now, &expect, use_ext)) {
                applied = 1;
                cur = now;
                printf("applied after %d s (modem dropped bands it doesn't support)\n", i);
            }
        }
        if (!applied) {
            get_bands(&cur);
            printf("not applied within 20 s\n");
        }
        print_mask(&cur);
    }

    if (!strcmp(argv[1], "mode") && argc >= 3) {
        static const uint16_t auto_c[] = {0x3f, 0x3c, 0x1c, 0};
        static const uint16_t lte_c[] = {0x10, 0};
        static const uint16_t g32_c[] = {0x2c, 0x0c, 0};
        uint16_t raw_c[2] = {0, 0};
        const uint16_t *cands = NULL;
        if (!strcmp(argv[2], "auto")) {
            cands = auto_c;
        } else if (!strcmp(argv[2], "lte")) {
            cands = lte_c;
        } else if (!strcmp(argv[2], "3g2g")) {
            cands = g32_c;
        } else {
            unsigned long v = strtoul(argv[2], NULL, 0);
            if (v && v <= 0x7f) {
                raw_c[0] = (uint16_t)v;
                cands = raw_c;
            }
        }
        if (!cands) {
            fprintf(stderr, "bad mode: use auto, lte, 3g2g or a hex mask\n");
            rel(client);
            return 1;
        }
        int done = 0;
        for (; *cands && !done; cands++) {
            int sr = set_mode(*cands);
            if (sr != 0) {
                printf("mode 0x%x rejected (result=%d error=%d)\n", *cands, sr, last_err);
                continue;
            }
            printf("mode 0x%x accepted (%s)\n", *cands,
                   g_perm ? "PERMANENT, survives reboots" : "power-cycle duration, reboot undoes it");
            done = 1;
            uint16_t want_m = *cands;
            uint16_t prior = cur.have_mode ? cur.mode : 0xffff;
            bandset now;
            memset(&now, 0, sizeof now);
            int ok = 0;
            for (int i = 1; i <= 10 && !ok; i++) {
                sleep(1);
                if (get_bands(&now) != 0 || !now.have_mode)
                    continue;
                if (now.mode == want_m)
                    ok = 1;
                /* Normalized value (e.g. sent 0x3f, modem reports 0x3c): a nonzero
                   subset of what we sent that differs from before counts as applied. */
                else if (now.mode != 0 && (now.mode & ~want_m) == 0 && now.mode != prior)
                    ok = 2;
            }
            if (ok == 1) {
                printf("mode applied\n");
            } else if (ok == 2) {
                printf("mode applied (modem reports normalized value 0x%x)\n", now.mode);
            } else {
                get_bands(&now);
                if (now.have_mode && now.mode != 0 && (now.mode & ~want_m) == 0)
                    printf("mode looks applied (modem reports 0x%x, same as before)\n", now.mode);
                else
                    printf("mode not confirmed within 10 s\n");
            }
            print_mask(&now);
        }
        if (!done)
            printf("no mode candidate was accepted\n");
    }

    if (!strcmp(argv[1], "msgs"))
        list_msgs();

    if (!strcmp(argv[1], "cell"))
        cell_info();

    rel(client);
    return 0;
}
