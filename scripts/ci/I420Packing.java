package compress.joshattic.us.quality;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Host JNI parity check and reproducible synthetic benchmark; never a phone speed claim. */
class I420Packing {
    private static native boolean packNative(ByteBuffer[] planes, int[] layout, byte[] dest,
                                             int width, int height, int rotation);

    // The previous Android implementation: per-sample ByteBuffer reads, then a second allocation
    // and pass for display rotation. Retain this baseline so the benchmark cannot flatter itself.
    private static byte[] legacy(ByteBuffer[] planes, int[] layout, int w, int h, int rotation) {
        byte[] packed = new byte[w * h * 3 / 2];
        int offset = 0;
        for (int p = 0; p < 3; p++) {
            int pw = p == 0 ? w : w / 2, ph = p == 0 ? h : h / 2;
            ByteBuffer b = planes[p].duplicate();
            for (int y = 0; y < ph; y++) {
                int row = layout[p * 4] + y * layout[p * 4 + 1];
                int d = offset + y * pw;
                if (layout[p * 4 + 2] == 1) { b.position(row); b.get(packed, d, pw); }
                else for (int x = 0; x < pw; x++) packed[d + x] = b.get(row + x * layout[p * 4 + 2]);
            }
            offset += pw * ph;
        }
        if (rotation == 0) return packed;
        byte[] out = new byte[packed.length]; offset = 0;
        for (int p = 0; p < 3; p++) {
            int pw = p == 0 ? w : w / 2, ph = p == 0 ? h : h / 2;
            for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
                int d = rotation == 90 ? x * ph + ph - 1 - y :
                        rotation == 270 ? (pw - 1 - x) * ph + y : (ph - 1 - y) * pw + pw - 1 - x;
                out[offset + d] = packed[offset + y * pw + x];
            }
            offset += pw * ph;
        }
        return out;
    }

    private static byte[] nativePack(ByteBuffer[] planes, int[] layout, int w, int h, int rotation) {
        byte[] result = new byte[w * h * 3 / 2];
        if (!packNative(planes, layout, result, w, h, rotation)) throw new AssertionError("JNI packing failed");
        return result;
    }


    public static void main(String[] args) {
        System.load(args[0]);
        for (int[] size : new int[][]{{480, 848}, {1080, 1920}, {2160, 3840}, {3840, 2160}}) {
            int w = size[0], h = size[1];
            ByteBuffer[] planes = new ByteBuffer[3]; int[] layout = new int[12];
            for (int p = 0; p < 3; p++) {
                int pw = p == 0 ? w : w / 2, ph = p == 0 ? h : h / 2, pixel = p == 0 ? 1 : 2;
                int stride = pw * pixel + 16, offset = 7 + stride + pixel;
                int limit = offset + (ph - 1) * stride + (pw - 1) * pixel + 1;
                planes[p] = ByteBuffer.allocateDirect(limit);
                for (int i = 0; i < limit; i++) planes[p].put(i, (byte)(i * 31 + p * 79));
                layout[p * 4] = offset; layout[p * 4 + 1] = stride;
                layout[p * 4 + 2] = pixel; layout[p * 4 + 3] = limit;
            }
            for (int rotation : new int[]{0, 90, 180, 270}) {
                byte[] expected = legacy(planes, layout, w, h, rotation);
                if (!Arrays.equals(expected, nativePack(planes, layout, w, h, rotation)))
                    throw new AssertionError("JNI bytes differ at " + w + "x" + h + " rotation " + rotation);
            }
            // Alternate order, equal warmup and sample counts; use medians to reduce GC noise.
            for (int rotation : new int[]{90}) {
            for (int i = 0; i < 8; i++) { legacy(planes, layout, w, h, rotation); nativePack(planes, layout, w, h, rotation); }
            long[] old = new long[9], fast = new long[9];
            for (int i = 0; i < 18; i++) {
                boolean oldFirst = (i & 1) == 0;
                for (int k = 0; k < 2; k++) {
                    boolean baseline = k == 0 ? oldFirst : !oldFirst;
                    long start = System.nanoTime();
                    byte[] result = baseline ? legacy(planes, layout, w, h, rotation) : nativePack(planes, layout, w, h, rotation);
                    long elapsed = System.nanoTime() - start;
                    if (result.length != w * h * 3 / 2) throw new AssertionError();
                    if (i >= 9) { if (baseline) old[i - 9] = elapsed; else fast[i - 9] = elapsed; }
                }
            }
            Arrays.sort(old); Arrays.sort(fast);
            System.out.printf(java.util.Locale.US, "host %dx%d rotation=%d median legacy=%.3f ms native=%.3f ms ratio=%.2fx%n",
                    w, h, rotation, old[4] / 1e6, fast[4] / 1e6, (double)old[4] / fast[4]);
            }
        }
        System.out.println("PASS: JNI matches the previous pixel path for all 16 geometry/rotation cases");
    }
}
