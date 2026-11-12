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
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Feeds a whole MP4 to Media3's Mp4Extractor and reports the video samples it emits: the samples
 * a player or a Transformer export is handed to decode. See README.md in this directory.
 */
public final class Media3Mp4Probe {

    private Media3Mp4Probe() { }

    /** The version of the Media3 this was compiled against, for a failure to name. */
    public static String media3Version() { return MediaLibraryInfo.VERSION; }

    /** Mp4Extractor's flag for reading the sample table as stored. */
    public static int ignoreEditListsFlag() { return Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS; }

    private static final class Track implements TrackOutput {
        final int type;
        final List<Long> timesUs = new ArrayList<>();
        private final byte[] scratch = new byte[1 << 16];

        Track(int type) { this.type = type; }

        @Override public void format(Format format) { }

        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart) throws IOException {
            int read = input.read(scratch, 0, Math.min(length, scratch.length));
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new EOFException();
            }
            return read;
        }

        @Override public void sampleData(ParsableByteArray data, int length, int sampleDataPart) { data.skipBytes(length); }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) { timesUs.add(timeUs); }
    }

    /**
     * The presentation time, in microseconds and in the order emitted, of every sample of the
     * file's one video track.
     *
     * @param file a complete, unfragmented MP4.
     * @param flags Mp4Extractor flags. 0 is the extractor's default, which applies edit lists;
     *     Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS gives the sample table as stored.
     */
    public static long[] videoTimesUs(final byte[] file, int flags) throws IOException {
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
                Track track = new Track(type);
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
        Track video = null;
        for (Track track : tracks) {
            if (track.type != C.TRACK_TYPE_VIDEO) continue;
            if (video != null) throw new IOException("more than one video track");
            video = track;
        }
        if (video == null) throw new IOException("no video track");
        long[] times = new long[video.timesUs.size()];
        for (int i = 0; i < times.length; i++) times[i] = video.timesUs.get(i);
        return times;
    }
}
