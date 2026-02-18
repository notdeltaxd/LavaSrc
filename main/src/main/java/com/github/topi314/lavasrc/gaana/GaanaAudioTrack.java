package com.github.topi314.lavasrc.gaana;

import com.sedmelluq.discord.lavaplayer.container.adts.AdtsAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpegts.MpegTsElementaryInputStream;
import com.sedmelluq.discord.lavaplayer.container.mpegts.PesPacketInputStream;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class GaanaAudioTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(GaanaAudioTrack.class);

    private static final String STREAM_API = "https://gaana.com/api/stream-url";
    private static final String HLS_BASE_URL = "https://vodhlsgaana-ebw.akamaized.net/";
    private static final String CRYPTO_KEY = new String(Base64.getDecoder().decode("Z3kxdCNiQGpsKGIkd3RtZQ=="), StandardCharsets.UTF_8);
    private static final String CRYPTO_IV = new String(Base64.getDecoder().decode("eEM0ZG1WSkFxMTRCZm50WA=="), StandardCharsets.UTF_8);

    private final GaanaAudioSourceManager sourceManager;
    private volatile GaanaHlsInputStream hlsStream;
    private volatile boolean seeking;
    private volatile long seekTarget;
    private volatile boolean tokenExpired;

    public GaanaAudioTrack(AudioTrackInfo trackInfo, GaanaAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        executor.executeProcessingLoop(() -> playback(executor), this::handleSeek);
    }

    private void handleSeek(long position) {
        log.debug("Seek to {}ms", position);
        seeking = true;
        seekTarget = position;
        closeCurrentStream();
    }

    void markExpired() {
        tokenExpired = true;
        closeCurrentStream();
    }

    private void closeCurrentStream() {
        if (hlsStream != null) {
            try {
                hlsStream.close();
            } catch (IOException ignored) {}
        }
    }

    private void playback(LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            while (true) {
                long startPosition = seeking ? seekTarget : (tokenExpired && hlsStream != null ? hlsStream.getPosition() : 0);
                seeking = false;
                tokenExpired = false;

                String hlsUrl = fetchStreamUrl(httpInterface, trackInfo.identifier);
                log.debug("HLS URL: {}", hlsUrl);

                try {
                    hlsStream = new GaanaHlsInputStream(httpInterface, hlsUrl, trackInfo.length, startPosition, this);
                    BufferedInputStream bufferedStream = new BufferedInputStream(hlsStream, 65536);

                    MpegTsElementaryInputStream tsStream = new MpegTsElementaryInputStream(
                        bufferedStream, MpegTsElementaryInputStream.ADTS_ELEMENTARY_STREAM
                    );
                    PesPacketInputStream pesStream = new PesPacketInputStream(tsStream);
                    AdtsAudioTrack adtsTrack = new AdtsAudioTrack(trackInfo, pesStream);

                    adtsTrack.process(executor);
                    break;

                } catch (Exception e) {
                    if (seeking || tokenExpired) {
                        log.debug("Restarting stream (seek={}, expired={})", seeking, tokenExpired);
                        continue;
                    }
                    throw e;
                } finally {
                    hlsStream = null;
                }
            }
        } catch (Exception e) {
            throw new FriendlyException("Gaana playback failed", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    private String fetchStreamUrl(HttpInterface httpInterface, String trackId) throws IOException {
        HttpPost request = new HttpPost(STREAM_API);
        request.setHeader("User-Agent", GaanaAudioSourceManager.USER_AGENT);
        request.setHeader("Accept", "application/json");
        request.setHeader("Origin", "https://gaana.com");
        request.setHeader("Referer", "https://gaana.com/");
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");

        String requestBody = "quality=high&track_id=" + URLEncoder.encode(trackId, "UTF-8") + "&stream_format=mp4";
        request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_FORM_URLENCODED));

        try (CloseableHttpResponse response = httpInterface.execute(request)) {
            JsonBrowser json = JsonBrowser.parse(response.getEntity().getContent());

            if (!"success".equals(json.get("api_status").text())) {
                throw new IOException("Stream API error: " + json.get("api_status").text());
            }

            String encryptedPath = json.get("data").get("stream_path").text();
            if (encryptedPath == null || encryptedPath.isEmpty()) {
                throw new IOException("No stream path returned");
            }

            String hlsUrl = decryptStreamPath(encryptedPath);
            if (hlsUrl == null) {
                throw new IOException("Failed to decrypt stream path");
            }
            return hlsUrl;
        }
    }

    private String decryptStreamPath(String encryptedData) {
        try {
            int offset = Character.digit(encryptedData.charAt(0), 10);
            if (offset < 0) return null;

            String base64Data = encryptedData.substring(offset + 16);
            int paddingNeeded = (4 - base64Data.length() % 4) % 4;
            for (int i = 0; i < paddingNeeded; i++) {
                base64Data += "=";
            }

            byte[] ciphertext = Base64.getMimeDecoder().decode(base64Data);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(CRYPTO_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(CRYPTO_IV.getBytes(StandardCharsets.UTF_8)));

            String decrypted = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

            StringBuilder cleaned = new StringBuilder();
            for (char c : decrypted.toCharArray()) {
                if (c >= 32 && c <= 126) cleaned.append(c);
            }
            decrypted = cleaned.toString().trim();

            int hlsIndex = decrypted.indexOf("hls/");
            return hlsIndex >= 0 ? HLS_BASE_URL + decrypted.substring(hlsIndex) : null;
        } catch (Exception e) {
            log.error("Decrypt error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public long getPosition() {
        return hlsStream != null ? hlsStream.getPosition() : super.getPosition();
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new GaanaAudioTrack(trackInfo, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
