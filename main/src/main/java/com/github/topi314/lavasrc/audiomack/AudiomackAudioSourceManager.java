package com.github.topi314.lavasrc.audiomack;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.ExtendedAudioSourceManager;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AudiomackAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable {

	public static final String API_BASE = "https://api.audiomack.com/v1";
	public static final String SEARCH_PREFIX = "admsearch:";

	private static final String DEFAULT_CONSUMER_KEY = "audiomack-web";
	private static final String DEFAULT_CONSUMER_SECRET = "bd8a07e9f23fbe9d808646b730f89b8e";

	// Unified pattern: matches song/album/playlist with type, or artist-only URLs
	private static final Pattern URL_PATTERN = Pattern.compile(
		"https?://(?:www\\.)?audiomack\\.com/(?<artist>[^/]+)(?:/(?<type>song|album|playlist)/(?<slug>[^/?]+))?(?:/)?(?:\\?.*)?$"
	);

	private static final Logger log = LoggerFactory.getLogger(AudiomackAudioSourceManager.class);
	private static final SecureRandom secureRandom = new SecureRandom();

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
	private final String consumerKey;
	private final String consumerSecret;
	private int searchLimit = 10;

	public AudiomackAudioSourceManager() {
		this(null, null);
	}

	public AudiomackAudioSourceManager(@Nullable String consumerKey, @Nullable String consumerSecret) {
		this.consumerKey = (consumerKey != null && !consumerKey.isEmpty()) ? consumerKey : DEFAULT_CONSUMER_KEY;
		this.consumerSecret = (consumerSecret != null && !consumerSecret.isEmpty()) ? consumerSecret : DEFAULT_CONSUMER_SECRET;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit < 1 ? 10 : searchLimit;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "audiomack";
	}

	@Override
	public @Nullable AudioItem loadItem(@NotNull AudioPlayerManager manager, @NotNull AudioReference reference) {
		String identifier = reference.identifier;
		try {
			if (identifier != null && identifier.startsWith(SEARCH_PREFIX)) {
				String query = identifier.substring(SEARCH_PREFIX.length());
				if (query.isEmpty()) {
					throw new IllegalArgumentException("No query provided for Audiomack search");
				}
				return this.getSearch(query);
			}

			if (identifier == null || identifier.isEmpty()) {
				return null;
			}

			Matcher matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.find()) {
				return null;
			}

			String artistSlug = matcher.group("artist");
			String type = matcher.group("type");
			String slug = matcher.group("slug");

			// Skip reserved paths
			if (artistSlug.equals("search") || artistSlug.equals("trending") || artistSlug.equals("top-songs")) {
				return null;
			}

			if (type == null) {
				// Artist URL (no type specified)
				return this.getArtist(artistSlug, identifier);
			}

			switch (type) {
				case "song":
					return this.getSong(artistSlug, slug, identifier);
				case "album":
					return this.getAlbum(artistSlug, slug, identifier);
				case "playlist":
					return this.getPlaylist(artistSlug, slug, identifier);
				default:
					return null;
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Audiomack item: " + e.getMessage(), e);
		}
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		ExtendedAudioTrackInfo extended = super.decodeTrack(input);
		return new AudiomackAudioTrack(
			trackInfo,
			extended.albumName,
			extended.albumUrl,
			extended.artistUrl,
			extended.artistArtworkUrl,
			null,
			false,
			this
		);
	}

	private AudioItem getSearch(String query) throws IOException {
		log.debug("Searching Audiomack for: {}", query);

		JsonBrowser response = this.makeSignedApiRequest(
			"GET",
			API_BASE + "/search",
			"q", query,
			"limit", String.valueOf(this.searchLimit),
			"show", "music",
			"sort", "popular",
			"page", "1",
			"section", "/search"
		);

		if (response == null || response.isNull()) {
			log.debug("No Audiomack search results for query: {}", query);
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser results = response.get("results");
		if (!results.isNull()) {
			for (JsonBrowser item : results.values()) {
				String type = item.get("type").text();
				if ("song".equals(type)) {
					AudioTrack track = this.mapSong(item, null);
					if (track != null) {
						tracks.add(track);
					}
				}
			}
		}

		if (tracks.isEmpty()) {
			log.debug("Audiomack search returned no playable tracks for query: {}", query);
			return AudioReference.NO_TRACK;
		}

		return new BasicAudioPlaylist("Audiomack Search: " + query, tracks, null, true);
	}

	private AudioItem getSong(String artistSlug, String songSlug, String queryUrl) throws IOException {
		String apiUrl = API_BASE + "/music/song/" + artistSlug + "/" + songSlug;

		String section = "/" + artistSlug + "/song/" + songSlug;
		try {
			section = new java.net.URI(queryUrl).getPath();
		} catch (java.net.URISyntaxException ignored) {}

		JsonBrowser response = this.makeSignedApiRequest("GET", apiUrl, "section", section);

		if (response == null || response.isNull()) {
			return AudioReference.NO_TRACK;
		}

		JsonBrowser song = normalizeResult(response);
		if (song == null || song.get("id").isNull()) {
			return AudioReference.NO_TRACK;
		}

		AudioTrack track = this.mapSong(song, queryUrl);
		return track != null ? track : AudioReference.NO_TRACK;
	}

	private AudioItem getAlbum(String artistSlug, String albumSlug, String queryUrl) throws IOException {
		String apiUrl = API_BASE + "/music/album/" + artistSlug + "/" + albumSlug;

		String section = "/" + artistSlug + "/album/" + albumSlug;
		try {
			section = new java.net.URI(queryUrl).getPath();
		} catch (java.net.URISyntaxException ignored) {}

		JsonBrowser response = this.makeSignedApiRequest("GET", apiUrl, "section", section);

		if (response == null || response.isNull()) {
			return AudioReference.NO_TRACK;
		}

		JsonBrowser album = normalizeResult(response);
		if (album == null || album.isNull()) {
			return AudioReference.NO_TRACK;
		}

		String title = album.get("title").text();
		String artworkUrl = album.get("image").text();
		if (artworkUrl == null) {
			artworkUrl = album.get("image_base").text();
		}
		String author = album.get("artist").text();
		if (author == null) {
			author = album.get("uploader").get("name").text();
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = album.get("tracks");
		if (!tracksArray.isNull()) {
			for (JsonBrowser song : tracksArray.values()) {
				AudioTrack track = this.mapSong(song, null);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new AudiomackAudioPlaylist(
			title,
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			queryUrl,
			artworkUrl,
			author,
			tracks.size()
		);
	}

	private AudioItem getPlaylist(String artistSlug, String playlistSlug, String queryUrl) throws IOException {
		String apiUrl = API_BASE + "/music/playlist/" + artistSlug + "/" + playlistSlug;

		String section = "/" + artistSlug + "/playlist/" + playlistSlug;
		try {
			section = new java.net.URI(queryUrl).getPath();
		} catch (java.net.URISyntaxException ignored) {}

		JsonBrowser response = this.makeSignedApiRequest("GET", apiUrl, "section", section);

		if (response == null || response.isNull()) {
			return AudioReference.NO_TRACK;
		}

		JsonBrowser playlist = normalizeResult(response);
		if (playlist == null || playlist.isNull()) {
			return AudioReference.NO_TRACK;
		}

		String title = playlist.get("title").text();
		String artworkUrl = playlist.get("image").text();
		if (artworkUrl == null) {
			artworkUrl = playlist.get("image_base").text();
		}
		String author = playlist.get("artist").text();
		if (author == null) {
			author = playlist.get("uploader").get("name").text();
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = playlist.get("tracks");
		if (!tracksArray.isNull()) {
			for (JsonBrowser song : tracksArray.values()) {
				AudioTrack track = this.mapSong(song, null);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new AudiomackAudioPlaylist(
			title,
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			queryUrl,
			artworkUrl,
			author,
			tracks.size()
		);
	}

	private AudioItem getArtist(String artistSlug, String queryUrl) throws IOException {
		String apiUrl = API_BASE + "/music/artist/" + artistSlug;

		JsonBrowser response = this.makeSignedApiRequest("GET", apiUrl, "section", "/" + artistSlug);

		if (response == null || response.isNull()) {
			return AudioReference.NO_TRACK;
		}

		JsonBrowser artist = normalizeResult(response);
		if (artist == null || artist.isNull()) {
			return AudioReference.NO_TRACK;
		}

		String artistName = artist.get("name").text();
		String artworkUrl = artist.get("image").text();
		if (artworkUrl == null) {
			artworkUrl = artist.get("image_base").text();
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = artist.get("songs");
		if (tracksArray.isNull()) {
			tracksArray = artist.get("tracks");
		}
		if (!tracksArray.isNull()) {
			for (JsonBrowser song : tracksArray.values()) {
				AudioTrack track = this.mapSong(song, null);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new AudiomackAudioPlaylist(
			artistName + "'s Top Tracks",
			tracks,
			ExtendedAudioPlaylist.Type.ARTIST,
			queryUrl,
			artworkUrl,
			artistName,
			tracks.size()
		);
	}

	private JsonBrowser normalizeResult(JsonBrowser json) {
		if (json == null || json.isNull()) {
			return null;
		}
		JsonBrowser data = json.get("results");
		if (data.isNull()) {
			data = json.get("result");
		}
		if (data.isNull()) {
			data = json;
		}
		if (data.isList() && !data.values().isEmpty()) {
			data = data.values().get(0);
		}
		return data.isNull() ? null : data;
	}

	private AudioTrack mapSong(JsonBrowser json, String queryUrl) {
		if (json == null || json.isNull()) {
			return null;
		}

		String id = json.get("id").text();
		if (id == null || id.isEmpty()) {
			return null;
		}

		String title = json.get("title").text();
		if (title == null || title.isEmpty()) {
			title = "Unknown Title";
		}

		String author = json.get("artist").text();
		if (author == null || author.isEmpty()) {
			author = json.get("uploader").get("name").text();
		}
		if (author == null || author.isEmpty()) {
			author = "Unknown Artist";
		}

		long durationMs = 0L;
		if (!json.get("duration").isNull()) {
			durationMs = json.get("duration").asLong(0L) * 1000;
		}

		String artworkUrl = json.get("image").text();
		if (artworkUrl == null) {
			artworkUrl = json.get("image_base").text();
		}

		String uri = queryUrl;
		if (uri == null) {
			String uploaderSlug = json.get("uploader").get("url_slug").text();
			if (uploaderSlug == null) {
				uploaderSlug = json.get("uploader_url_slug").text();
			}
			if (uploaderSlug == null) {
				uploaderSlug = json.get("artist_slug").text();
			}
			if (uploaderSlug == null) {
				uploaderSlug = "unknown";
			}
			String songSlug = json.get("url_slug").text();
			if (songSlug == null) {
				songSlug = json.get("slug").text();
			}
			if (songSlug == null) {
				songSlug = "";
			}
			uri = "https://audiomack.com/" + uploaderSlug + "/song/" + songSlug;
		}

		String albumName = json.get("album").text();
		String albumUrl = null;
		String artistUrl = "https://audiomack.com/" + json.get("uploader").get("url_slug").text();
		String artistArtworkUrl = json.get("uploader").get("image").text();

		String isrc = json.get("isrc").text();

		AudioTrackInfo info = new AudioTrackInfo(
			title,
			author,
			durationMs,
			id,
			false,
			uri,
			artworkUrl,
			isrc
		);

		return new AudiomackAudioTrack(
			info,
			albumName,
			albumUrl,
			artistUrl,
			artistArtworkUrl,
			null,
			false,
			this
		);
	}

	public JsonBrowser makeSignedApiRequest(String method, String url, String... params) throws IOException {
		Map<String, String> paramMap = new TreeMap<>();
		for (int i = 0; i < params.length - 1; i += 2) {
			paramMap.put(params[i], params[i + 1]);
		}
		return makeSignedApiRequest(method, url, paramMap);
	}

	public JsonBrowser makeSignedApiRequest(String method, String url, Map<String, String> additionalParams) throws IOException {
		Map<String, String> params = new TreeMap<>(additionalParams);

		// OAuth1 parameters
		byte[] nonceBytes = new byte[16];
		secureRandom.nextBytes(nonceBytes);
		StringBuilder nonceBuilder = new StringBuilder();
		for (byte b : nonceBytes) {
			nonceBuilder.append(String.format("%02x", b));
		}

		params.put("oauth_consumer_key", this.consumerKey);
		params.put("oauth_nonce", nonceBuilder.toString());
		params.put("oauth_signature_method", "HMAC-SHA1");
		params.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000));
		params.put("oauth_version", "1.0");

		// Build parameter string
		StringBuilder paramString = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, String> entry : params.entrySet()) {
			if (!first) {
				paramString.append("&");
			}
			paramString.append(strictEncode(entry.getKey()));
			paramString.append("=");
			paramString.append(strictEncode(entry.getValue()));
			first = false;
		}

		// Generate signature
		String signatureBase = method.toUpperCase() + "&" + strictEncode(url) + "&" + strictEncode(paramString.toString());
		String signingKey = strictEncode(this.consumerSecret) + "&";

		String signature;
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
			signature = Base64.getEncoder().encodeToString(mac.doFinal(signatureBase.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IOException("Failed to generate OAuth signature", e);
		}

		String signedUrl = url + "?" + paramString + "&oauth_signature=" + strictEncode(signature);

		HttpGet request = new HttpGet(signedUrl);
		request.setHeader("Accept", "application/json");

		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private String strictEncode(String str) {
		return URLEncoder.encode(str, StandardCharsets.UTF_8)
			.replace("!", "%21")
			.replace("'", "%27")
			.replace("(", "%28")
			.replace(")", "%29")
			.replace("*", "%2A");
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	public HttpInterface getHttpInterface() {
		return this.httpInterfaceManager.getInterface();
	}
}
