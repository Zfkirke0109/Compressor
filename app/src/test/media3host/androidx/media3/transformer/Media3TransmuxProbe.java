package androidx.media3.transformer;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import com.google.common.collect.ImmutableList;
import hostprobe.Media3Mp4Probe;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Media3's extractor straight into Media3's muxer wrapper and default muxer: the path a copied
 * track takes through a Transformer export, and the muxing half of a re-encoded one, with no
 * codec in between. See README.md in src/test/media3host.
 *
 * In Media3's package because MuxerWrapper, where a Transformer export's timestamps are adjusted
 * before they reach the muxer, is package-private. It is built exactly as Transformer builds it
 * for a plain export: the default muxer (wrapped only to note the timestamps it is handed), the
 * default mode, no dropping of samples before the first video sample, nothing appended.
 */
public final class Media3TransmuxProbe {

    private Media3TransmuxProbe() { }

    /**
     * Media3's default muxer, unchanged, with a note taken of the timestamp of every sample the
     * wrapper hands it: what MuxerWrapper did to a timestamp, before the muxer turns it into boxes.
     */
    private static final class Recording implements Muxer.Factory {
        private final Muxer.Factory real = new DefaultMuxer.Factory();
        final List<Long> videoTimesUs = new ArrayList<>();
        final List<Long> audioTimesUs = new ArrayList<>();

        @Override public Muxer create(String path) throws MuxerException {
            final Muxer muxer = real.create(path);
            final Map<Integer, List<Long>> byTrack = new HashMap<>();
            return new Muxer() {
                @Override public int addTrack(Format format) throws MuxerException {
                    int id = muxer.addTrack(format);
                    if (MimeTypes.isVideo(format.sampleMimeType)) byTrack.put(id, videoTimesUs);
                    if (MimeTypes.isAudio(format.sampleMimeType)) byTrack.put(id, audioTimesUs);
                    return id;
                }
                @Override public void writeSampleData(int trackId, ByteBuffer data, BufferInfo bufferInfo) throws MuxerException {
                    List<Long> times = byTrack.get(trackId);
                    if (times != null) times.add(bufferInfo.presentationTimeUs);
                    muxer.writeSampleData(trackId, data, bufferInfo);
                }
                @Override public void addMetadataEntry(Metadata.Entry metadataEntry) { muxer.addMetadataEntry(metadataEntry); }
                @Override public void close() throws MuxerException { muxer.close(); }
            };
        }

        @Override public ImmutableList<String> getSupportedSampleMimeTypes(int trackType) { return real.getSupportedSampleMimeTypes(trackType); }

        @Override public boolean supportsWritingNegativeTimestampsInEditList() { return real.supportsWritingNegativeTimestampsInEditList(); }
    }

    private static long[] array(List<Long> values) {
        long[] out = new long[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    /**
     * Writes the chosen tracks of [source] to [outputPath] and returns what was observed: "log"
     * (List of String: what Media3 logged while writing), "videoSamplesWritten" and
     * "audioSamplesWritten" (Integer, absent for a track not written), and "videoTimesAtMuxerUs"
     * and "audioTimesAtMuxerUs" (long[]: the timestamps the wrapper handed the muxer, in order).
     */
    public static Map<String, Object> transmux(byte[] source, String outputPath, boolean video, boolean audio) throws Exception {
        final List<Media3Mp4Probe.Track> tracks = new ArrayList<>();
        for (Media3Mp4Probe.Track track : Media3Mp4Probe.readTracks(source, 0, true)) {
            if ((track.type == C.TRACK_TYPE_VIDEO && video) || (track.type == C.TRACK_TYPE_AUDIO && audio)) tracks.add(track);
        }
        if (tracks.isEmpty()) throw new IOException("no track chosen");
        Media3Mp4Probe.drainLog();
        final Map<String, Object> report = new LinkedHashMap<>();
        final ExportException[] failure = {null};
        final Recording recording = new Recording();
        MuxerWrapper wrapper = new MuxerWrapper(
            outputPath,
            recording,
            new MuxerWrapper.Listener() {
                @Override public void onTrackEnded(int trackType, Format format, int averageBitrate, int sampleCount) {
                    report.put(trackType == C.TRACK_TYPE_VIDEO ? "videoSamplesWritten" : "audioSamplesWritten", sampleCount);
                }
                @Override public void onSampleWrittenOrDropped() { }
                @Override public void onEnded(long durationMs) { }
                @Override public void onFileSizeBytesAvailable(long fileSizeBytes) { }
                @Override public void onError(ExportException exportException) { failure[0] = exportException; }
            },
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
        wrapper.setTrackCount(tracks.size());
        for (Media3Mp4Probe.Track track : tracks) wrapper.addTrackFormat(track.format);
        int[] next = new int[tracks.size()];
        int remaining = 0;
        for (Media3Mp4Probe.Track track : tracks) remaining += track.samples.size();
        while (remaining > 0) {
            // The track whose next sample is earliest goes first. The wrapper refuses a sample
            // when its track has run too far ahead of the other, and then the other one is tried.
            int earliest = -1;
            for (int i = 0; i < tracks.size(); i++) {
                if (next[i] >= tracks.get(i).samples.size()) continue;
                if (earliest < 0 || tracks.get(i).samples.get(next[i]).timeUs < tracks.get(earliest).samples.get(next[earliest]).timeUs) earliest = i;
            }
            boolean wrote = false;
            for (int attempt = 0; attempt < tracks.size() && !wrote; attempt++) {
                int i = (earliest + attempt) % tracks.size();
                Media3Mp4Probe.Track track = tracks.get(i);
                if (next[i] >= track.samples.size()) continue;
                Media3Mp4Probe.Sample sample = track.samples.get(next[i]);
                if (wrapper.writeSample(track.type, ByteBuffer.wrap(sample.payload), sample.isKeyFrame(), sample.timeUs)) {
                    next[i]++;
                    remaining--;
                    wrote = true;
                    if (next[i] == track.samples.size()) wrapper.endTrack(track.type);
                }
            }
            if (!wrote) throw new IOException("the muxer wrapper refused the next sample of every track");
        }
        wrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
        if (failure[0] != null) throw failure[0];
        report.put("log", Media3Mp4Probe.drainLog());
        report.put("videoTimesAtMuxerUs", array(recording.videoTimesUs));
        report.put("audioTimesAtMuxerUs", array(recording.audioTimesUs));
        return report;
    }
}
