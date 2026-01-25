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

	private URI getTrackStreamURI() throws IOException, URISyntaxException {
		String trackId = this.trackInfo.identifier;
		if (trackId == null || trackId.isEmpty()) {
			throw new FriendlyException("No track ID available for Audiomack track", FriendlyException.Severity.COMMON, null);
		}

		String section = "/search";
		if (this.trackInfo.uri != null) {
			try {
				section = new URI(this.trackInfo.uri).getPath();
			} catch (URISyntaxException ignored) {}
		}

		JsonBrowser response = this.sourceManager.makeSignedApiRequest(
			"GET",
			AudiomackAudioSourceManager.API_BASE + "/music/play/" + trackId,
			"environment", "desktop-web",
			"hq", "true",
			"section", section
		);

		if (response == null || response.isNull()) {
			throw new FriendlyException("Failed to get stream URL from Audiomack API", FriendlyException.Severity.COMMON, null);
		}

		JsonBrowser result = response.get("results");
		if (result.isNull()) {
			result = response.get("result");
		}
		if (result.isNull()) {
			result = response;
		}
		if (result.isList() && !result.values().isEmpty()) {
			result = result.values().get(0);
		}

		String streamUrl = result.get("signedUrl").text();
		if (streamUrl == null || streamUrl.isEmpty()) {
			streamUrl = result.get("signed_url").text();
		}
		if (streamUrl == null || streamUrl.isEmpty()) {
			streamUrl = result.get("url").text();
		}
		if (streamUrl == null || streamUrl.isEmpty()) {
			streamUrl = result.get("streamUrl").text();
		}
		if (streamUrl == null || streamUrl.isEmpty()) {
			streamUrl = result.get("stream_url").text();
		}

		if (streamUrl == null || streamUrl.isEmpty()) {
			throw new FriendlyException("No stream URL returned from Audiomack API for id " + trackId, FriendlyException.Severity.COMMON, null);
		}

		return new URI(streamUrl);
	}

	@Override
	public void process(@NotNull LocalAudioTrackExecutor executor) throws Exception {
		try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
			URI streamUri = getTrackStreamURI();
			try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, streamUri, trackInfo.length)) {
				this.processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
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
