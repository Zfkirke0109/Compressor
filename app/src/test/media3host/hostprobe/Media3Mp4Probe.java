package hostprobe;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds a whole MP4 to Media3's Mp4Extractor and reports the samples it emits: the samples a
 * player or a Transformer export is handed. See README.md in this directory.
 */
public final class Media3Mp4Probe {

    private Media3Mp4Probe() { }

    /** The version of the Media3 this was compiled against, for a failure to name. */
    public static String media3Version() { return MediaLibraryInfo.VERSION; }

    /** Mp4Extractor's flag for reading the sample table as stored. */
    public static int ignoreEditListsFlag() { return Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS; }

    /** What Media3 logged as a warning or an error since the last call, oldest first. */
    public static List<String> drainLog() { return android.util.Log.drain(); }

    /** One emitted sample: its payload only when the read asked for payloads. */
    public static final class Sample {
        public final long timeUs;
        public final int flags;
        public final byte[] payload;

        Sample(long timeUs, int flags, byte[] payload) {
            this.timeUs = timeUs;
            this.flags = flags;
            this.payload = payload;
        }

        public boolean isKeyFrame() { return (flags & C.BUFFER_FLAG_KEY_FRAME) != 0; }
    }

    /** One track as the extractor emitted it, samples in emission order. */
    public static final class Track implements TrackOutput {
        public final int type;
        public Format format;
        public final List<Sample> samples = new ArrayList<>();
        private final boolean keepPayloads;
        private ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final byte[] scratch = new byte[1 << 16];

        Track(int type, boolean keepPayloads) {
            this.type = type;
            this.keepPayloads = keepPayloads;
        }

        @Override public void format(Format format) { this.format = format; }

        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart) throws IOException {
            int read = input.read(scratch, 0, Math.min(length, scratch.length));
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new EOFException();
            }
            if (keepPayloads) pending.write(scratch, 0, read);
            return read;
        }

        @Override public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            if (keepPayloads) {
                byte[] bytes = new byte[length];
                data.readBytes(bytes, 0, length);
                pending.write(bytes, 0, length);
            } else {
                data.skipBytes(length);
            }
        }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            byte[] payload = null;
            if (keepPayloads) {
                byte[] all = pending.toByteArray();
                payload = Arrays.copyOfRange(all, all.length - offset - size, all.length - offset);
                pending = new ByteArrayOutputStream();
            }
            samples.add(new Sample(timeUs, flags, payload));
        }

        public String kind() {
            return type == C.TRACK_TYPE_VIDEO ? "video" : type == C.TRACK_TYPE_AUDIO ? "audio" : "other";
        }
    }

    /**
     * Every track of a complete, unfragmented MP4 as Mp4Extractor emits it.
     *
     * @param flags Mp4Extractor flags. 0 is the extractor's default, which applies edit lists;
     *     {@link #ignoreEditListsFlag()} gives the sample table as stored.
     */
    public static List<Track> readTracks(final byte[] file, int flags, final boolean keepPayloads) throws IOException {
        final List<Track> tracks = new ArrayList<>();
        final int[] position = {0};
        DataReader reader = (buffer, offset, length) -> {
            if (length == 0) return 0;
            int count = Math.min(length, file.length - position[0]);
            if (count <= 0) return C.RESULT_END_OF_INPUT;
            System.arraycopy(file, position[0], buffer, offset, count);
            position[0] += count;
            return count;
        };
        Mp4Extractor extractor = new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, flags);
        extractor.init(new ExtractorOutput() {
            @Override public TrackOutput track(int id, int type) {
                Track track = new Track(type, keepPayloads);
                tracks.add(track);
                return track;
            }
            @Override public void endTracks() { }
            @Override public void seekMap(SeekMap seekMap) { }
        });
        PositionHolder holder = new PositionHolder();
        ExtractorInput input = new DefaultExtractorInput(reader, 0, file.length);
        // A parser that stops making progress must fail the test, not hang the suite.
        for (long steps = 0; ; steps++) {
            if (steps > 50_000_000L) throw new IOException("Mp4Extractor did not reach the end of the input");
            int result = extractor.read(input, holder);
            if (result == Extractor.RESULT_END_OF_INPUT) break;
            if (result == Extractor.RESULT_SEEK) {
                if (holder.position < 0 || holder.position > file.length) throw new IOException("seek outside the file: " + holder.position);
                position[0] = (int) holder.position;
                input = new DefaultExtractorInput(reader, holder.position, file.length);
            }
        }
        extractor.release();
        return tracks;
    }

    /**
     * The presentation time, in microseconds and in the order emitted, of every sample of the
     * file's one video track.
     */
    public static long[] videoTimesUs(byte[] file, int flags) throws IOException {
        Track video = null;
        for (Track track : readTracks(file, flags, false)) {
            if (track.type != C.TRACK_TYPE_VIDEO) continue;
            if (video != null) throw new IOException("more than one video track");
            video = track;
        }
        if (video == null) throw new IOException("no video track");
        long[] times = new long[video.samples.size()];
        for (int i = 0; i < times.length; i++) times[i] = video.samples.get(i).timeUs;
        return times;
    }

    /**
     * The file's video and audio tracks in plain JDK types, keyed "video" and "audio": timesUs
     * (long[], emission order), payloadDigests (String[], SHA-256 of each sample), codecData
     * (hex of each initialization buffer), mime, encoderDelay and encoderPadding (Integer; the
     * gapless values Media3 derives from an audio edit list, or -1 when it has none).
     */
    public static Map<String, Map<String, Object>> describe(byte[] file) throws Exception {
        Map<String, Map<String, Object>> described = new LinkedHashMap<>();
        for (Track track : readTracks(file, 0, true)) {
            if (track.kind().equals("other")) continue;
            if (described.containsKey(track.kind())) throw new IOException("more than one " + track.kind() + " track");
            Map<String, Object> one = new LinkedHashMap<>();
            long[] times = new long[track.samples.size()];
            String[] digests = new String[track.samples.size()];
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < times.length; i++) {
                times[i] = track.samples.get(i).timeUs;
                digests[i] = hex(sha.digest(track.samples.get(i).payload));
            }
            List<String> codecData = new ArrayList<>();
            for (byte[] data : track.format.initializationData) codecData.add(hex(data));
            one.put("timesUs", times);
            one.put("payloadDigests", digests);
            one.put("codecData", codecData);
            one.put("mime", track.format.sampleMimeType);
            one.put("encoderDelay", track.format.encoderDelay);
            one.put("encoderPadding", track.format.encoderPadding);
            described.put(track.kind(), one);
        }
        return described;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format("%02x", b));
        return text.toString();
    }
}
