#include "yuv_packing.h"
#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

/* Independent oracle: pack first, then rotate by source coordinates. */
static void reference(const CompressorPlane *planes, int w, int h, int rotation, uint8_t *out) {
    size_t offset = 0;
    for (int p = 0; p < 3; p++) {
        const int pw = p == 0 ? w : w / 2, ph = p == 0 ? h : h / 2;
        for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
            int ox = x, oy = y, ow = pw;
            if (rotation == 90) { ox = ph - 1 - y; oy = x; ow = ph; }
            if (rotation == 180) { ox = pw - 1 - x; oy = ph - 1 - y; }
            if (rotation == 270) { ox = y; oy = pw - 1 - x; ow = ph; }
            out[offset + (size_t)oy * ow + ox] = planes[p].data[(size_t)y * planes[p].row_stride + (size_t)x * planes[p].pixel_stride];
        }
        offset += (size_t)pw * ph;
    }
}

int main(void) {
    const int sizes[][2] = {{2, 2}, {6, 10}, {34, 66}, {96, 128}, {480, 848}, {2160, 3840}};
    const int rotations[] = {0, 90, 180, 270};
    int cases = 0;
    for (size_t s = 0; s < sizeof(sizes) / sizeof(sizes[0]); s++) {
        const int w = sizes[s][0], h = sizes[s][1];
        const size_t bytes = (size_t)w * h * 3 / 2;
        uint8_t *expected = malloc(bytes), *actual = malloc(bytes);
        assert(expected && actual);
        for (int pixel = 1; pixel <= 3; pixel++) {
            CompressorPlane planes[3]; uint8_t *allocations[3];
            for (int p = 0; p < 3; p++) {
                const int pw = p == 0 ? w : w / 2, ph = p == 0 ? h : h / 2;
                const int stride = pw * pixel + 17;
                /* Deliberately no trailing padding on the last row. */
                const size_t length = (size_t)(ph - 1) * stride + (pw - 1) * pixel + 1;
                allocations[p] = malloc(length + 11); assert(allocations[p]);
                uint8_t *data = allocations[p] + 11;
                for (size_t i = 0; i < length; i++) data[i] = (uint8_t)(i * 31 + p * 79);
                planes[p] = (CompressorPlane){data, length, stride, pixel};
            }
            for (int r = 0; r < 4; r++) {
                reference(planes, w, h, rotations[r], expected);
                memset(actual, 0, bytes);
                assert(compressor_pack_i420(planes, w, h, rotations[r], actual, bytes));
                assert(memcmp(actual, expected, bytes) == 0); cases++;
            }
            memset(actual, 0x55, bytes);
            planes[2].length--;
            assert(!compressor_pack_i420(planes, w, h, 0, actual, bytes));
            for (size_t i = 0; i < bytes; i++) assert(actual[i] == 0x55);
            planes[2].length++;
            assert(!compressor_pack_i420(planes, w, h, 45, actual, bytes));
            assert(!compressor_pack_i420(planes, w + 1, h, 0, actual, bytes));
            assert(!compressor_pack_i420(planes, w, h, 0, actual, bytes - 1));
            for (int p = 0; p < 3; p++) free(allocations[p]);
        }
        free(expected); free(actual);
    }
    printf("PASS: %d bit-exact native packing cases plus invalid-layout guards\n", cases);
    return 0;
}
