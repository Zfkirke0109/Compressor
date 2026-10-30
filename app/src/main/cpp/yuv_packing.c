#include "yuv_packing.h"
#include <limits.h>
#include <string.h>

static void pack_plane(const CompressorPlane *p, int w, int h, int rotation, uint8_t *out) {
    if (rotation == 90 || rotation == 270) {
        /* Block the transpose so both source and destination stay in cache. */
        const int tile = 8;
        for (int by = 0; by < h; by += tile) {
            const int th = h - by < tile ? h - by : tile;
            for (int bx = 0; bx < w; bx += tile) {
                const int tw = w - bx < tile ? w - bx : tile;
                uint8_t block[8][8];
                for (int y = 0; y < th; y++) {
                    const uint8_t *row = p->data + (size_t)(by + y) * p->row_stride + (size_t)bx * p->pixel_stride;
                    for (int x = 0; x < tw; x++) block[x][rotation == 90 ? th - 1 - y : y] = row[x * p->pixel_stride];
                }
                for (int x = 0; x < tw; x++) {
                    const size_t d = rotation == 90 ? (size_t)(bx + x) * h + h - by - th
                                                  : (size_t)(w - 1 - bx - x) * h + by;
                    memcpy(out + d, block[x], (size_t)th);
                }
            }
        }
        return;
    }
    for (int y = 0; y < h; y++) {
        const uint8_t *source = p->data + (size_t)y * p->row_stride;
        if (rotation == 0 && p->pixel_stride == 1) {
            memcpy(out + (size_t)y * w, source, (size_t)w);
        } else {
            uint8_t *row = out + (size_t)(rotation == 180 ? h - 1 - y : y) * w;
            for (int x = 0; x < w; x++) row[rotation == 180 ? w - 1 - x : x] = source[(size_t)x * p->pixel_stride];
        }
    }
}

int compressor_pack_i420(const CompressorPlane planes[3], int width, int height, int rotation,
                         uint8_t *dest, size_t dest_length) {
    if (!planes || !dest || width <= 0 || height <= 0 || (width & 1) || (height & 1) ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) return 0;
    const uint64_t pixels = (uint64_t)width * (uint64_t)height;
    if (pixels * 3 / 2 > INT_MAX || dest_length != pixels * 3 / 2) return 0;
    for (int i = 0; i < 3; i++) {
        const CompressorPlane *p = &planes[i];
        const int w = i == 0 ? width : width / 2, h = i == 0 ? height : height / 2;
        if (!p->data || p->row_stride <= 0 || p->pixel_stride <= 0) return 0;
        const uint64_t row_bytes = (uint64_t)(w - 1) * p->pixel_stride + 1;
        const uint64_t needed = (uint64_t)(h - 1) * p->row_stride + row_bytes;
        if (row_bytes > (uint64_t)p->row_stride || needed > p->length) return 0;
    }
    size_t offset = 0;
    for (int i = 0; i < 3; i++) {
        const int w = i == 0 ? width : width / 2, h = i == 0 ? height : height / 2;
        pack_plane(&planes[i], w, h, rotation, dest + offset);
        offset += (size_t)w * h;
    }
    return 1;
}
