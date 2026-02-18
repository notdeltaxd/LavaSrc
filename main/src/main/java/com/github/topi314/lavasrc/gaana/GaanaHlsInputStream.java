package com.github.topi314.lavasrc.gaana;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GaanaHlsInputStream extends InputStream {

    private static final Logger log = LoggerFactory.getLogger(GaanaHlsInputStream.class);
    private static final int SEGMENT_BUFFER_SIZE = 5;

    private final HttpInterface httpInterface;
    private final GaanaSegmentFetcher segmentFetcher;
    private final GaanaAudioTrack track;

    private List<GaanaPlaylistParser.Segment> segments;
    private String playlistUrl;

    private final BlockingQueue<SegmentData> segmentQueue;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger currentVersion = new AtomicInteger(0);
    private Set<Integer> processedSequences = new HashSet<>();

    private byte[] currentBuffer;
    private int bufferPosition;
    private int currentSegmentIndex;
    private Thread downloadThread;
    private String lastMapUri;

    private static class SegmentData {
        final byte[] data;
        final int sequence;
        final int version;

        SegmentData(byte[] data, int sequence, int version) {
            this.data = data;
            this.sequence = sequence;
            this.version = version;
        }
    }

    public GaanaHlsInputStream(HttpInterface httpInterface, String hlsUrl, long duration, long startTimeMs, GaanaAudioTrack track) throws IOException {
        this.httpInterface = httpInterface;
        this.track = track;
        this.segmentFetcher = new GaanaSegmentFetcher(httpInterface, this);
        this.segmentQueue = new LinkedBlockingQueue<>(SEGMENT_BUFFER_SIZE);

        this.playlistUrl = resolveMediaPlaylist(hlsUrl);
        parsePlaylist(playlistUrl);

        this.currentSegmentIndex = 0;
        if (startTimeMs > 0) {
            skipToPosition(startTimeMs);
        }
        startDownloadThread();
    }

    void onTokenExpired() {
        if (track != null) {
            track.markExpired();
        }
    }

    private void skipToPosition(long positionMs) {
        double targetSeconds = positionMs / 1000.0;
        double elapsed = 0;
        currentSegmentIndex = 0;

        for (int i = 0; i < segments.size(); i++) {
            GaanaPlaylistParser.Segment segment = segments.get(i);
            if (elapsed + segment.duration <= targetSeconds) {
                elapsed += segment.duration;
                processedSequences.add(segment.sequence);
                currentSegmentIndex = i + 1;
            } else {
                break;
            }
        }
        log.debug("Skip to {}ms, starting at segment {}", positionMs, currentSegmentIndex);
    }

    public long getPosition() {
        double elapsed = 0;
        for (int i = 0; i < currentSegmentIndex && i < segments.size(); i++) {
            elapsed += segments.get(i).duration;
        }
        return (long) (elapsed * 1000);
    }

    private String resolveMediaPlaylist(String url) throws IOException {
        String content = fetchPlaylistContent(url);
        GaanaPlaylistParser.PlaylistResult result;
        try {
            result = GaanaPlaylistParser.parse(content, url);
        } catch (Exception e) {
            throw new IOException("Failed to parse playlist", e);
        }

        if (!result.isMaster) {
            return url;
        }

        GaanaPlaylistParser.Variant bestVariant = null;
        for (GaanaPlaylistParser.Variant variant : result.variants) {
            if ((variant.codecs.contains("mp4a") || variant.codecs.contains("opus")) && !variant.codecs.contains("avc1")) {
                if (bestVariant == null || variant.bandwidth > bestVariant.bandwidth) {
                    bestVariant = variant;
                }
            }
        }
        if (bestVariant == null) {
            for (GaanaPlaylistParser.Variant variant : result.variants) {
                if (variant.codecs.contains("mp4a") || variant.codecs.contains("opus")) {
                    if (bestVariant == null || variant.bandwidth > bestVariant.bandwidth) {
                        bestVariant = variant;
                    }
                }
            }
        }
        if (bestVariant == null && !result.variants.isEmpty()) {
            bestVariant = result.variants.get(0);
        }
        if (bestVariant == null) {
            throw new IOException("No suitable variant found");
        }

        log.debug("Selected variant: bandwidth={}, codecs={}", bestVariant.bandwidth, bestVariant.codecs);
        return bestVariant.url;
    }

    private void parsePlaylist(String url) throws IOException {
        String content = fetchPlaylistContent(url);
        try {
            GaanaPlaylistParser.PlaylistResult result = GaanaPlaylistParser.parse(content, url);
            if (result.isMaster) {
                throw new IOException("Expected media playlist");
            }
            this.segments = result.segments;
        } catch (Exception e) {
            throw new IOException("Failed to parse playlist", e);
        }
    }

    private String fetchPlaylistContent(String url) throws IOException {
        HttpGet request = new HttpGet(url);
        try (CloseableHttpResponse response = httpInterface.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IOException("Playlist fetch failed: " + statusCode);
            }
            return readString(response.getEntity().getContent());
        }
    }

    private void startDownloadThread() {
        int version = currentVersion.get();
        downloadThread = new Thread(() -> downloadSegments(currentSegmentIndex, version), "Gaana-HLS");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private void downloadSegments(int startIndex, int version) {
        try {
            for (int i = startIndex; i < segments.size() && !stopped.get(); i++) {
                if (version != currentVersion.get()) return;

                GaanaPlaylistParser.Segment segment = segments.get(i);
                if (processedSequences.contains(segment.sequence)) continue;

                if (segment.map != null && !segment.map.uri.equals(lastMapUri)) {
                    try {
                        byte[] mapData = segmentFetcher.fetchMap(segment.map, segment.key);
                        if (mapData != null && version == currentVersion.get()) {
                            segmentQueue.put(new SegmentData(mapData, -1, version));
                            lastMapUri = segment.map.uri;
                        }
                    } catch (Exception e) {
                        log.warn("Init segment fetch failed: {}", e.getMessage());
                    }
                }

                try {
                    byte[] segmentData = segmentFetcher.fetchSegment(segment);
                    if (version == currentVersion.get()) {
                        segmentQueue.put(new SegmentData(segmentData, segment.sequence, version));
                        processedSequences.add(segment.sequence);
                    }
                } catch (Exception e) {
                    log.warn("Segment {} fetch failed: {}", segment.sequence, e.getMessage());
                }

                while (segmentQueue.size() >= SEGMENT_BUFFER_SIZE && !stopped.get() && version == currentVersion.get()) {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int read() throws IOException {
        byte[] buffer = new byte[1];
        int bytesRead = read(buffer, 0, 1);
        return bytesRead == -1 ? -1 : (buffer[0] & 0xFF);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (stopped.get()) return -1;

        int totalRead = 0;
        while (totalRead < length && !stopped.get()) {
            if (currentBuffer != null && bufferPosition < currentBuffer.length) {
                int available = currentBuffer.length - bufferPosition;
                int toRead = Math.min(available, length - totalRead);
                System.arraycopy(currentBuffer, bufferPosition, buffer, offset + totalRead, toRead);
                bufferPosition += toRead;
                totalRead += toRead;
                continue;
            }

            try {
                SegmentData nextSegment = segmentQueue.poll(5000, TimeUnit.MILLISECONDS);
                if (nextSegment == null) {
                    if (currentSegmentIndex >= segments.size() || stopped.get()) {
                        return totalRead > 0 ? totalRead : -1;
                    }
                    continue;
                }

                if (nextSegment.version != currentVersion.get()) continue;

                currentBuffer = nextSegment.data;
                bufferPosition = 0;
                if (nextSegment.sequence >= 0) {
                    currentSegmentIndex++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }

        return totalRead > 0 ? totalRead : -1;
    }

    @Override
    public int available() {
        return currentBuffer != null ? currentBuffer.length - bufferPosition : 0;
    }

    @Override
    public void close() throws IOException {
        stopped.set(true);
        segmentQueue.clear();
        if (downloadThread != null) {
            downloadThread.interrupt();
        }
        super.close();
    }

    private String readString(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        return output.toString("UTF-8");
    }
}
