package com.github.topi314.lavasrc.audiomack;

import com.github.topi314.lavasrc.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.TreeMap;

public class AudiomackAudioTrack extends ExtendedAudioTrack {

	private final AudiomackAudioSourceManager sourceManager;

	public AudiomackAudioTrack(@NotNull AudioTrackInfo trackInfo, @NotNull AudiomackAudioSourceManager sourceManager) {
		this(trackInfo, null, null, null, null, null, false, sourceManager);
	}

	public AudiomackAudioTrack(
			@NotNull AudioTrackInfo trackInfo,
			String albumName,
			String albumUrl,
			String artistUrl,
			String artistArtworkUrl,
			String previewUrl,
			boolean isPreview,
			@NotNull AudiomackAudioSourceManager sourceManager
	) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false);
		this.sourceManager = sourceManager;
	}

	private URI getTrackMediaURI() throws IOException, URISyntaxException {
		String trackId = this.sourceManager.getTrackId(this.trackInfo.identifier);
		if (trackId == null || trackId.isEmpty()) {
			trackId = this.trackInfo.identifier;
		}

		if (trackId == null || trackId.isEmpty()) {
			throw new FriendlyException("No track ID available for Audiomack track", FriendlyException.Severity.COMMON, null);
		}

		Map<String, String> bodyParams = new TreeMap<>();
		bodyParams.put("environment", "desktop-web");
		bodyParams.put("session", "backend-session");
		bodyParams.put("hq", "true");

		JsonBrowser response = this.sourceManager.makePostRequest("/music/" + trackId + "/play", bodyParams);
		checkResponse(response, "Failed to get Audiomack media URL:");

		String signedUrl = response.text();
		if (signedUrl != null && !signedUrl.isEmpty() && signedUrl.startsWith("http")) {
			return new URI(signedUrl);
		}

		JsonBrowser data = response.get("data");
		if (!data.isNull()) {
			signedUrl = data.get("signedUrl").text();
			if (signedUrl != null && !signedUrl.isEmpty()) {
				return new URI(signedUrl);
			}
		}

		JsonBrowser results = response.get("results");
		if (!results.isNull()) {
			for (String key : new String[]{"url", "streamUrl"}) {
				signedUrl = results.get(key).text();
				if (signedUrl != null && !signedUrl.isEmpty()) {
					return new URI(signedUrl);
				}
			}
		}

		String rootUrl = response.get("signedUrl").text();
		if (rootUrl != null && !rootUrl.isEmpty()) {
			return new URI(rootUrl);
		}
		
		throw new FriendlyException("No streaming URL returned from Audiomack API", FriendlyException.Severity.COMMON, null);
	}

	private void checkResponse(JsonBrowser json, String message) {
		if (json == null || json.isNull()) {
			throw new IllegalStateException(message + " no response");
		}
		if (!json.get("success").isNull() && !json.get("success").asBoolean(true)) {
			var error = json.get("error").text();
			var errorMessage = json.get("message").text();
			throw new IllegalStateException(message + " " + (error != null ? error : "") + " " + (errorMessage != null ? errorMessage : ""));
		}
	}

	@Override
	public void process(@NotNull LocalAudioTrackExecutor executor) throws Exception {
		try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
			try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, getTrackMediaURI(), trackInfo.length)) {
				processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
			}
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new AudiomackAudioTrack(
				this.getInfo(),
				this.albumName,
				this.albumUrl,
				this.artistUrl,
				this.artistArtworkUrl,
				null,
				false,
				this.sourceManager
		);
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.sourceManager;
	}
}
