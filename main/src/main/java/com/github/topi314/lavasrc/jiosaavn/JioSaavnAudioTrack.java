package com.github.topi314.lavasrc.jiosaavn;

import com.github.topi314.lavasrc.ExtendedAudioTrack;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.HttpGet;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public class JioSaavnAudioTrack extends ExtendedAudioTrack {

	private final JioSaavnAudioSourceManager sourceManager;

	public JioSaavnAudioTrack(@NotNull AudioTrackInfo trackInfo, @NotNull JioSaavnAudioSourceManager sourceManager) {
		this(trackInfo, null, null, null, null, null, false, sourceManager);
	}

	public JioSaavnAudioTrack(
		@NotNull AudioTrackInfo trackInfo,
		String albumName,
		String albumUrl,
		String artistUrl,
		String artistArtworkUrl,
		String previewUrl,
		boolean isPreview,
		@NotNull JioSaavnAudioSourceManager sourceManager
	) {
		// Previews are not used with the external API; always store null preview and false flag.
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false);
		this.sourceManager = sourceManager;
	}

	private URI getTrackMediaURI() throws IOException, URISyntaxException {
		String trackId = this.trackInfo.identifier;
		if (trackId == null || trackId.isEmpty()) {
			throw new FriendlyException("No track ID available for JioSaavn track", FriendlyException.Severity.COMMON, null);
		}

		String base = this.sourceManager.getBaseUrl();
		String path = String.format(JioSaavnAudioSourceManager.API_MEDIA_URL_BY_ID, java.net.URLEncoder.encode(trackId, StandardCharsets.UTF_8));
		
		// Handle double /api if baseUrl already ends with /api
		if (!path.startsWith("/")) {
			path = "/" + path;
		}
		if (base.endsWith("/api") && path.startsWith("/api")) {
			path = path.substring(4);
		}
		
		String url = base + path;

		HttpInterface httpInterface = this.sourceManager.getHttpInterface();
		HttpGet request = new HttpGet(url);
		request.setHeader("Accept", "application/json");
		JsonBrowser response = LavaSrcTools.fetchResponseAsJson(httpInterface, request);
		checkResponse(response, "Failed to get JioSaavn media URL:");

		String mediaUrl = response.get("mediaUrl").text();
		if (mediaUrl == null || mediaUrl.isEmpty()) {
			throw new FriendlyException("No media URL returned from JioSaavn API for id " + trackId, FriendlyException.Severity.COMMON, null);
		}

		return new URI(mediaUrl);
	}

	private void checkResponse(JsonBrowser json, String message) {
		if (json == null || json.isNull()) {
			throw new IllegalStateException(message + " no response");
		}
		// New API format doesn't use success wrapper, check for error fields if needed
	}

	@Override
	public void process(@NotNull LocalAudioTrackExecutor executor) throws Exception {
		try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
			URI mediaUri = getTrackMediaURI();
			try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, mediaUri, trackInfo.length)) {
				this.processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
			}
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new JioSaavnAudioTrack(
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


