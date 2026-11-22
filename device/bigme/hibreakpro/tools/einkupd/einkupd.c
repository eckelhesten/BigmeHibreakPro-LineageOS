// SPDX-License-Identifier: Apache-2.0
// einkupd: drive the HiBreak Pro e-ink panel directly with DRM_IOCTL_EINK_UPDATE.
// Phase A step 1 of docs/daily-driver-plan.md: refresh an arbitrary rectangle with a chosen mode
// from our own code. Allocates an ION buffer in the panel's native orientation (1648x824), draws a
// test pattern and submits one update for a rectangle.
// The kernel maps the buffer with dma_buf_kmap and caches up to 5 buffers keyed by the fd number
// (not by process). DRM dumb buffers can't be kernel-mapped: the ioctl fails with ESRCH
// ("fail to get dma kernel addr"), so the buffer comes from an ION heap.
//
// Usage (as root): einkupd [-m mode] [-x l -y t -w w -h h] [-b bpp] [-f format] [-p pattern]
//                          [-H heap id] (default: first system heap; heaps are listed)
//                          [-F fd number to submit the buffer as] (default: 300 + pid % 600)
//                          [-n count -s sleep_ms] repeat, alternating black and white in the rect,
//                          and print how long each ioctl blocks (frame pacing measurements)
//                          [-S "script"] run a sequence on ONE buffer (kernel experiments), steps
//                          separated by ';':
//                            f V            fill the whole buffer with grey V (0-255)
//                            r X Y W H V    fill a rectangle with grey V (no update)
//                            c X Y W H T    checkerboard of T-pixel squares in a rectangle
//                            g X Y W H      16-step grey ramp in a rectangle
//                            u MODE X Y W H submit one update (prints time and how long it blocked)
//                            s MS           sleep
//                            i NR SIZE      raw DRM ioctl _IOWR('d', NR, SIZE) on a zeroed argument
//   mode    : waveform or XRZ policy mode (default 0xb2 = NORMAL); 0x400 = RECT flag
//   pattern : white | black | stripes | grey (16-step ramp) (default stripes)
// Run with `setprop vendor.xrz.disable_commit 1` to keep the HWC from overwriting the result.
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

struct drm_eink_update {          // 0x44 bytes, layout from hwcomposer.mt6877.so (docs/eink-interface.md)
    uint32_t mode;                // 0x00
    uint32_t left, top;           // 0x04, 0x08
    uint32_t right, bottom;       // 0x0c, 0x10 (inclusive)
    uint32_t dither;              // 0x14
    uint64_t zero0[2];            // 0x18
    int32_t fd;                   // 0x28 dma-buf
    uint32_t format;              // 0x2c
    uint64_t zero1;               // 0x30
    uint32_t width, height;       // 0x38
    uint32_t zero2;               // 0x40
} __attribute__((packed));
_Static_assert(sizeof(struct drm_eink_update) == 0x44, "size");
#define DRM_IOCTL_EINK_UPDATE 0xc04464cfu

// ION (Linux 4.19 staging API)
struct ion_allocation_data { uint64_t len; uint32_t heap_id_mask, flags, fd, unused; };
struct ion_heap_data { char name[32]; uint32_t type, heap_id, reserved0, reserved1, reserved2; };
struct ion_heap_query { uint32_t cnt, reserved0; uint64_t heaps; uint32_t reserved1, reserved2; };
#define ION_IOC_ALLOC 0xc0184900u
// MediaTek 4.19 keeps the legacy ION API: allocate a handle, then share it as a dma-buf fd.
struct ion_allocation_data_legacy { uint64_t len, align; uint32_t heap_id_mask, flags; int32_t handle; uint32_t pad; };
struct ion_fd_data { int32_t handle, fd; };
#define ION_IOC_ALLOC_LEGACY 0xc0204900u
#define ION_IOC_SHARE 0xc0084904u
#define ION_IOC_HEAP_QUERY 0xc0184908u
#define ION_HEAP_TYPE_SYSTEM 0

static uint32_t num(const char *s) { return (uint32_t)strtoul(s, NULL, 0); }

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

// Runs a ';'-separated script on one 8-bit grey buffer (see the usage comment).
static int run_script(int card, int buf, uint8_t *px, uint32_t W, uint32_t H, char *script) {
    const double start = now_ms();
    char *save = NULL;
    for (char *step = strtok_r(script, ";", &save); step; step = strtok_r(NULL, ";", &save)) {
        char op = 0;
        unsigned long a[6] = {0};
        char *q = step;
        while (*q == ' ') q++;
        op = *q ? *q++ : 0;
        int n = 0;
        for (; n < 6; n++) {
            char *end;
            unsigned long v = strtoul(q, &end, 0);
            if (end == q) break;
            a[n] = v;
            q = end;
        }
        if (op == 'f') {
            memset(px, (int)a[0], (size_t)W * H);
        } else if (op == 'r' || op == 'c' || op == 'g') {
            if (a[2] == 0 || a[3] == 0 || a[0] > W || a[2] > W - a[0] ||
                a[1] > H || a[3] > H - a[1]) {
                fprintf(stderr, "rect outside\n");
                return 2;
            }
            for (unsigned long y = a[1]; y < a[1] + a[3]; y++) {
                for (unsigned long x = a[0]; x < a[0] + a[2]; x++) {
                    uint8_t v = (uint8_t)a[4];
                    if (op == 'c') v = (((x - a[0]) / (a[4] ? a[4] : 1) + (y - a[1]) / (a[4] ? a[4] : 1)) & 1) ? 0xff : 0;
                    if (op == 'g') v = (uint8_t)((((x - a[0]) * 16) / a[2]) * 17);
                    px[y * W + x] = v;
                }
            }
        } else if (op == 'u') {
            if (n < 5 || a[3] == 0 || a[4] == 0 || a[1] > W || a[3] > W - a[1] ||
                a[2] > H || a[4] > H - a[2]) {
                fprintf(stderr, "bad update rect\n");
                return 2;
            }
            struct drm_eink_update u = {
                .mode = (uint32_t)a[0], .left = (uint32_t)a[1], .top = (uint32_t)a[2],
                .right = (uint32_t)(a[1] + a[3] - 1), .bottom = (uint32_t)(a[2] + a[4] - 1),
                .fd = buf, .format = 0, .width = W, .height = H,
            };
            const double t0 = now_ms();
            const int r = ioctl(card, DRM_IOCTL_EINK_UPDATE, &u);
            printf("%8.1f u mode=0x%lx (%lu,%lu %lux%lu): %s, blocked %.2f ms\n", t0 - start, a[0],
                   a[1], a[2], a[3], a[4], r ? strerror(errno) : "ok", now_ms() - t0);
        } else if (op == 's') {
            usleep((useconds_t)a[0] * 1000);
        } else if (op == 'i') {
            uint8_t arg[256] = {0};
            const unsigned long req = 0xc0000000ul | ((a[1] & 0xff) << 16) | ('d' << 8) | (a[0] & 0xff);
            const double t0 = now_ms();
            const int r = ioctl(card, req, arg);
            printf("%8.1f i 0x%lx: %s, blocked %.2f ms, out:", t0 - start, req,
                   r ? strerror(errno) : "ok", now_ms() - t0);
            for (unsigned long k = 0; k < a[1] && k < 32; k++) printf(" %02x", arg[k]);
            printf("\n");
        } else if (op) {
            fprintf(stderr, "bad step '%s'\n", step);
            return 2;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    int heap = -1, fdnum = 0, count = 1, sleep_ms = 0;
    uint32_t mode = 0xb2, l = 0, t = 0, w = 1648, h = 824, bpp = 8, format = 0, dither = 0;
    const char *pattern = "stripes";
    char *script = NULL;
    const uint32_t W = 1648, H = 824;
    int c;
    while ((c = getopt(argc, argv, "m:x:y:w:h:b:f:d:p:H:F:n:s:S:")) != -1) {
        switch (c) {
            case 'm': mode = num(optarg); break;
            case 'x': l = num(optarg); break;
            case 'y': t = num(optarg); break;
            case 'w': w = num(optarg); break;
            case 'h': h = num(optarg); break;
            case 'b': bpp = num(optarg); break;
            case 'f': format = num(optarg); break;
            case 'd': dither = num(optarg); break;
            case 'p': pattern = optarg; break;
            case 'H': heap = (int)num(optarg); break;
            case 'F': fdnum = (int)num(optarg); break;
            case 'n': count = (int)num(optarg); break;
            case 's': sleep_ms = (int)num(optarg); break;
            case 'S': script = optarg; bpp = 8; break;
            default: fprintf(stderr, "bad option\n"); return 2;
        }
    }
    if (!w || !h || l > W || w > W - l || t > H || h > H - t) {
        fprintf(stderr, "rect outside %ux%u\n", W, H);
        return 2;
    }
    if (bpp < 8 || (bpp % 8) != 0) {
        fprintf(stderr, "bad bpp %u\n", bpp);
        return 2;
    }

    int card = open("/dev/dri/card0", O_RDWR | O_CLOEXEC);
    if (card < 0) { perror("open card0"); return 1; }
    int ion = open("/dev/ion", O_RDONLY | O_CLOEXEC);
    if (ion < 0) { perror("open ion"); return 1; }
    struct ion_heap_data heaps[32];
    struct ion_heap_query q = { .cnt = 32, .heaps = (uint64_t)(uintptr_t)heaps };
    if (ioctl(ion, ION_IOC_HEAP_QUERY, &q)) { perror("ION_IOC_HEAP_QUERY"); return 1; }
    const uint32_t nh = q.cnt < 32 ? q.cnt : 32;
    for (uint32_t i = 0; i < nh; i++) {
        printf("heap %u: %s type=%u\n", heaps[i].heap_id, heaps[i].name, heaps[i].type);
        if (heap < 0 && heaps[i].type == ION_HEAP_TYPE_SYSTEM) heap = (int)heaps[i].heap_id;
    }
    if (heap < 0 || heap >= 32) { fprintf(stderr, "no usable system heap (%d)\n", heap); return 1; }
    const uint32_t pitch = W * (bpp / 8);
    struct ion_allocation_data ad = { .len = (uint64_t)pitch * H, .heap_id_mask = 1u << heap };
    int buf;
    if (ioctl(ion, ION_IOC_ALLOC, &ad) == 0) {
        buf = (int)ad.fd;
    } else {
        struct ion_allocation_data_legacy la = { .len = ad.len, .align = 4096,
                                                 .heap_id_mask = 1u << heap };
        if (ioctl(ion, ION_IOC_ALLOC_LEGACY, &la)) { perror("ION_IOC_ALLOC (legacy)"); return 1; }
        struct ion_fd_data fdd = { .handle = la.handle };
        if (ioctl(ion, ION_IOC_SHARE, &fdd)) { perror("ION_IOC_SHARE"); return 1; }
        buf = fdd.fd;
    }
    uint8_t *px = mmap(NULL, ad.len, PROT_READ | PROT_WRITE, MAP_SHARED, buf, 0);
    if (px == MAP_FAILED) { perror("mmap"); return 1; }
    printf("ion buffer heap=%d %ux%u bpp=%u pitch=%u fd=%d\n", heap, W, H, bpp, pitch, buf);

    uint32_t Bpp = bpp / 8;
    for (uint32_t y = 0; y < H; y++) {
        for (uint32_t x = 0; x < W; x++) {
            uint8_t v;
            if (!strcmp(pattern, "white")) v = 0xff;
            else if (!strcmp(pattern, "black")) v = 0;
            else if (!strcmp(pattern, "grey")) v = (uint8_t)(((x * 16) / W) * 17);
            else v = ((x / 32 + y / 32) & 1) ? 0xff : 0;   // 32px checkerboard
            uint8_t *p = px + (size_t)y * pitch + (size_t)x * Bpp;
            for (uint32_t i = 0; i < Bpp; i++) p[i] = (Bpp == 4 && i == 3) ? 0xff : v;
        }
    }

    // The kernel caches mapped buffers by fd number, across processes: a reused number shows the
    // old buffer. Move ours to a number unlikely to be cached (or the one given with -F).
    int target = fdnum > 0 ? fdnum : 300 + (int)(getpid() % 600);
    if (target <= 2 || target == card || target == ion) {
        fprintf(stderr, "bad target fd %d\n", target);
        return 2;
    }
    if (target != buf) {
        if (dup2(buf, target) < 0) { perror("dup2"); return 1; }
        close(buf);
        buf = target;
    }
    if (script) return run_script(card, buf, px, W, H, script);

    struct drm_eink_update u = {
        .mode = mode, .left = l, .top = t, .right = l + w - 1, .bottom = t + h - 1,
        .dither = dither, .fd = buf, .format = format, .width = W, .height = H,
    };
    int r = 0;
    struct timespec t0, t1, start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < count && r == 0; i++) {
        if (count > 1) {  // alternate the rectangle between black and white
            uint8_t v = (i & 1) ? 0x00 : 0xff;
            for (uint32_t y = t; y < t + h; y++) {
                for (uint32_t x = l; x < l + w; x++) {
                    uint8_t *p = px + (size_t)y * pitch + (size_t)x * Bpp;
                    for (uint32_t k = 0; k < Bpp; k++) p[k] = (Bpp == 4 && k == 3) ? 0xff : v;
                }
            }
        }
        clock_gettime(CLOCK_MONOTONIC, &t0);
        r = ioctl(card, DRM_IOCTL_EINK_UPDATE, &u);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        double ms = (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
        double at = (t0.tv_sec - start.tv_sec) * 1e3 + (t0.tv_nsec - start.tv_nsec) / 1e6;
        printf("EINK_UPDATE #%d at %.1f ms mode=0x%x area=(%u,%u)-(%u,%u) fd=%d format=%u: %s, "
               "blocked %.2f ms\n", i, at, mode, u.left, u.top, u.right, u.bottom, buf, format,
               r ? strerror(errno) : "ok", ms);
        if (sleep_ms > 0 && i + 1 < count) usleep((useconds_t)sleep_ms * 1000);
    }
    return r ? 1 : 0;
}
