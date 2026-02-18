package com.github.topi314.lavasrc.gaana;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class GaanaSegmentFetcher {

    private static final Logger log = LoggerFactory.getLogger(GaanaSegmentFetcher.class);
    private static final int MAX_CACHED_KEYS = 20;

    private final HttpInterface httpInterface;
    private final GaanaHlsInputStream hlsStream;
    private final Map<String, byte[]> keyCache = new HashMap<>();

    public GaanaSegmentFetcher(HttpInterface httpInterface, GaanaHlsInputStream hlsStream) {
        this.httpInterface = httpInterface;
        this.hlsStream = hlsStream;
    }

    public byte[] fetchKey(GaanaPlaylistParser.KeyInfo keyInfo) throws IOException {
        if (keyInfo == null || "NONE".equals(keyInfo.method)) return null;
        if (keyCache.containsKey(keyInfo.uri)) return keyCache.get(keyInfo.uri);

        HttpGet request = new HttpGet(keyInfo.uri);
        try (CloseableHttpResponse response = httpInterface.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 403) {
                hlsStream.onTokenExpired();
                throw new IOException("Key request failed: token expired");
            }
            if (statusCode != 200) {
                throw new IOException("Key request failed: " + statusCode);
            }

            byte[] keyData = readBytes(response.getEntity().getContent());
            if (keyData == null || keyData.length == 0) {
                throw new IOException("Empty key response");
            }

            if (keyCache.size() >= MAX_CACHED_KEYS) {
                keyCache.remove(keyCache.keySet().iterator().next());
            }
            keyCache.put(keyInfo.uri, keyData);
            return keyData;
        }
    }

    public byte[] fetchMap(GaanaPlaylistParser.MapInfo mapInfo, GaanaPlaylistParser.KeyInfo keyInfo) throws IOException {
        if (mapInfo == null || mapInfo.uri == null) return null;

        HttpGet request = new HttpGet(mapInfo.uri);
        try (CloseableHttpResponse response = httpInterface.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 403) {
                hlsStream.onTokenExpired();
                throw new IOException("Map request failed: token expired");
            }
            if (statusCode != 200) {
                throw new IOException("Map request failed: " + statusCode);
            }

            byte[] mapData = readBytes(response.getEntity().getContent());
            if (keyInfo != null && keyInfo.iv != null && mapData.length % 16 == 0) {
                byte[] key = fetchKey(keyInfo);
                if (key != null) {
                    mapData = decrypt(mapData, key, keyInfo.iv);
                }
            }
            return mapData;
        }
    }

    public byte[] fetchSegment(GaanaPlaylistParser.Segment segment) throws IOException {
        return fetchSegmentWithRetry(segment, 1);
    }

    private byte[] fetchSegmentWithRetry(GaanaPlaylistParser.Segment segment, int attempt) throws IOException {
        try {
            HttpGet request = new HttpGet(segment.url);
            try (CloseableHttpResponse response = httpInterface.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 403) {
                    log.warn("Segment {} returned 403 - token expired", segment.sequence);
                    hlsStream.onTokenExpired();
                    throw new IOException("Token expired");
                }
                if (statusCode != 200 && statusCode != 206) {
                    throw new IOException("Segment request failed: " + statusCode);
                }

                byte[] segmentData = readBytes(response.getEntity().getContent());

                if (segment.key != null && !"NONE".equals(segment.key.method)) {
                    byte[] key = fetchKey(segment.key);
                    byte[] iv = segment.key.iv != null ? segment.key.iv : deriveIvFromSequence(segment.sequence);
                    segmentData = decrypt(segmentData, key, iv);
                }

                return segmentData;
            }
        } catch (IOException e) {
            if (attempt <= 2 && !e.getMessage().contains("expired")) {
                int delayMs = (int) Math.pow(2, attempt) * 500;
                log.warn("Segment {} retry {}: {}", segment.sequence, attempt, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry", ie);
                }
                return fetchSegmentWithRetry(segment, attempt + 1);
            }
            throw e;
        }
    }

    private byte[] deriveIvFromSequence(int sequence) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(0);
        buffer.putLong(sequence);
        return buffer.array();
    }

    private byte[] decrypt(byte[] data, byte[] key, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IOException("Decryption failed", e);
        }
    }

    private byte[] readBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        return output.toByteArray();
    }
}
