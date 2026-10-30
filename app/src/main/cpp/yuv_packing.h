#ifndef COMPRESSOR_YUV_PACKING_H
#define COMPRESSOR_YUV_PACKING_H
#include <stddef.h>
#include <stdint.h>

typedef struct {
    const uint8_t *data;
    size_t length;
    int row_stride;
    int pixel_stride;
} CompressorPlane;

/* Sources start at the crop origin. All bounds are validated before the destination is touched. */
int compressor_pack_i420(const CompressorPlane planes[3], int width, int height, int rotation,
                         uint8_t *dest, size_t dest_length);
#endif
