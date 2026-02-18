package com.github.topi314.lavasrc.gaana;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GaanaPlaylistParser {

    public static class Segment {
        public final String url;
        public final double duration;
        public final KeyInfo key;
        public final MapInfo map;
        public final int sequence;
        public final boolean discontinuity;

        public Segment(String url, double duration, KeyInfo key, MapInfo map, int sequence, boolean discontinuity) {
            this.url = url;
            this.duration = duration;
            this.key = key;
            this.map = map;
            this.sequence = sequence;
            this.discontinuity = discontinuity;
        }
    }

    public static class KeyInfo {
        public final String method;
        public final String uri;
        public final byte[] iv;

        public KeyInfo(String method, String uri, byte[] iv) {
            this.method = method;
            this.uri = uri;
            this.iv = iv;
        }
    }

    public static class MapInfo {
        public final String uri;

        public MapInfo(String uri) {
            this.uri = uri;
        }
    }

    public static class Variant {
        public final String url;
        public final int bandwidth;
        public final String codecs;
        public final String audio;

        public Variant(String url, int bandwidth, String codecs, String audio) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.codecs = codecs;
            this.audio = audio;
        }
    }

    public static class PlaylistResult {
        public final boolean isMaster;
        public final List<Variant> variants;
        public final List<Segment> segments;
        public final int mediaSequence;
        public final double targetDuration;
        public final boolean isLive;

        public PlaylistResult(boolean isMaster, List<Variant> variants, List<Segment> segments,
                              int mediaSequence, double targetDuration, boolean isLive) {
            this.isMaster = isMaster;
            this.variants = variants;
            this.segments = segments;
            this.mediaSequence = mediaSequence;
            this.targetDuration = targetDuration;
            this.isLive = isLive;
        }
    }

    public static PlaylistResult parse(String content, String baseUrl) throws URISyntaxException {
        if (!content.contains("#EXT")) {
            throw new IllegalArgumentException("Invalid HLS playlist");
        }

        String[] lines = content.split("\\r?\\n");
        List<String> filtered = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                filtered.add(trimmed);
            }
        }

        boolean isMaster = false;
        for (String line : filtered) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                isMaster = true;
                break;
            }
        }

        return isMaster ? parseMaster(filtered, baseUrl) : parseMedia(filtered, baseUrl, content);
    }

    private static PlaylistResult parseMaster(List<String> lines, String baseUrl) throws URISyntaxException {
        List<Variant> variants = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> attrs = parseAttributes(line);
                if (i + 1 < lines.size()) {
                    String urlLine = lines.get(++i);
                    String url = resolveUrl(urlLine, baseUrl);
                    int bandwidth = 0;
                    try {
                        bandwidth = Integer.parseInt(attrs.getOrDefault("bandwidth", "0"));
                    } catch (NumberFormatException ignored) {}

                    variants.add(new Variant(
                        url,
                        bandwidth,
                        attrs.getOrDefault("codecs", ""),
                        attrs.get("audio")
                    ));
                }
            }
        }

        variants.sort((a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
        return new PlaylistResult(true, variants, null, 0, 0, false);
    }

    private static PlaylistResult parseMedia(List<String> lines, String baseUrl, String content) throws URISyntaxException {
        List<Segment> segments = new ArrayList<>();
        int mediaSequence = 0;
        double targetDuration = 5;
        boolean isLive = !content.contains("#EXT-X-ENDLIST");

        KeyInfo currentKey = null;
        MapInfo currentMap = null;
        boolean pendingDiscontinuity = false;

        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    mediaSequence = Integer.parseInt(line.split(":")[1].trim());
                } catch (NumberFormatException ignored) {}
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                try {
                    targetDuration = Double.parseDouble(line.split(":")[1].trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        int segmentIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                pendingDiscontinuity = true;
            } else if (line.startsWith("#EXT-X-KEY:")) {
                currentKey = parseKeyInfo(line, baseUrl);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                currentMap = parseMapInfo(line, baseUrl);
            } else if (line.startsWith("#EXTINF:")) {
                double duration = 0;
                try {
                    String durationStr = line.split(":")[1].split(",")[0].trim();
                    duration = Double.parseDouble(durationStr);
                } catch (Exception ignored) {}

                int j = i + 1;
                while (j < lines.size() && lines.get(j).startsWith("#")) {
                    j++;
                }

                if (j < lines.size()) {
                    String segmentUrl = resolveUrl(lines.get(j), baseUrl);
                    segments.add(new Segment(
                        segmentUrl,
                        duration,
                        currentKey,
                        currentMap,
                        mediaSequence + segmentIndex,
                        pendingDiscontinuity
                    ));
                    segmentIndex++;
                    pendingDiscontinuity = false;
                    i = j;
                }
            }
        }

        return new PlaylistResult(false, null, segments, mediaSequence, targetDuration, isLive);
    }

    private static KeyInfo parseKeyInfo(String line, String baseUrl) throws URISyntaxException {
        Map<String, String> attrs = parseAttributes(line);
        String method = attrs.getOrDefault("method", "NONE");
        if ("NONE".equals(method)) {
            return null;
        }

        String uri = attrs.get("uri");
        if (uri != null) {
            uri = resolveUrl(uri, baseUrl);
        }

        byte[] iv = null;
        String ivStr = attrs.get("iv");
        if (ivStr != null && ivStr.startsWith("0x")) {
            iv = hexToBytes(ivStr.substring(2));
        }

        return new KeyInfo(method, uri, iv);
    }

    private static MapInfo parseMapInfo(String line, String baseUrl) throws URISyntaxException {
        Map<String, String> attrs = parseAttributes(line);
        String uri = attrs.get("uri");
        if (uri != null) {
            uri = resolveUrl(uri, baseUrl);
        }
        return new MapInfo(uri);
    }

    private static Map<String, String> parseAttributes(String line) {
        Map<String, String> attrs = new HashMap<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");
        java.util.regex.Matcher matcher = pattern.matcher(line);

        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase().replace("-", "");
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            attrs.put(key, value);
        }

        return attrs;
    }

    private static String resolveUrl(String url, String baseUrl) throws URISyntaxException {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        URI base = new URI(baseUrl);
        return base.resolve(url).toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
