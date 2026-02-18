package com.github.topi314.lavasrc.audiomack;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.ExtendedAudioSourceManager;
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
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
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
import org.apache.http.client.methods.CloseableHttpResponse;

public class AudiomackAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable {

	public static final Pattern URL_PATTERN = Pattern.compile("(https?://)?(www\\.)?audiomack\\.com/(?<uploader>[a-zA-Z0-9-_]+)/(?<type>song|album|playlist)/(?<slug>[a-zA-Z0-9-_]+)");
	public static final String SEARCH_PREFIX = "amksearch:";

	private static final Logger log = LoggerFactory.getLogger(AudiomackAudioSourceManager.class);
	private static final String API_BASE_URL = "https://api.audiomack.com/v1";
	private static final String DEFAULT_CONSUMER_KEY = "audiomack-web";
	private static final String DEFAULT_CONSUMER_SECRET = "bd8a07e9f23fbe9d808646b730f89b8e";
	private static final String NONCE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
	private final SecureRandom secureRandom = new SecureRandom();
	private final String consumerKey;
	private final String consumerSecret;
	private final String accessToken;
	private final String accessSecret;
	private int searchLimit = 20;

	public AudiomackAudioSourceManager(String consumerKey, String consumerSecret, String accessToken, String accessSecret) {
		this.consumerKey = (consumerKey != null && !consumerKey.isEmpty()) ? consumerKey : DEFAULT_CONSUMER_KEY;
		this.consumerSecret = (consumerSecret != null && !consumerSecret.isEmpty()) ? consumerSecret : DEFAULT_CONSUMER_SECRET;
		this.accessToken = accessToken;
		this.accessSecret = accessSecret;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit < 1 ? 20 : Math.min(searchLimit, 100);
	}

	public String getTrackId(String identifier) {
		if (identifier.matches("^\\d+$")) {
			return identifier;
		}
		return null;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "audiomack";
	}

	@Override
	public @Nullable AudioItem loadItem(@NotNull AudioPlayerManager manager, @NotNull AudioReference reference) {
		String identifier = reference.identifier;
		if (identifier == null || identifier.isEmpty()) {
			return null;
		}

		try {
			if (identifier.startsWith(SEARCH_PREFIX)) {
				String query = identifier.substring(SEARCH_PREFIX.length());
				if (query.isEmpty()) {
					log.warn("Audiomack search triggered with empty query");
					return null;
				}
				return this.getSearch(query);
			}

			Matcher matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.matches()) {
				return null;
			}

			String uploader = matcher.group("uploader");
			String type = matcher.group("type");
			String slug = matcher.group("slug");

			switch (type) {
				case "song":
					return this.getSong(uploader, slug);
				case "album":
					return this.getAlbum(uploader, slug);
				case "playlist":
					return this.getPlaylist(uploader, slug);
				default:
					return null;
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Audiomack item", e);
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

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		httpInterfaceManager.configureBuilder(configurator);
	}

	@Override
	public void shutdown() {
		try {
			httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	public HttpInterface getHttpInterface() {
		return httpInterfaceManager.getInterface();
	}

	// ==================== Private API Methods ====================

	private AudioItem getSearch(String query) throws IOException {
		log.debug("Searching Audiomack for query: {}", query);

		Map<String, String> params = new TreeMap<>();
		params.put("q", query);
		params.put("limit", String.valueOf(this.searchLimit));
		params.put("show", "songs");
		params.put("sort", "popular");

		JsonBrowser response = this.makeGetRequest("/search", params);
		if (response.isNull()) {
			log.debug("No Audiomack search results for query: {}", query);
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser results = response.get("results");
		if (!results.isNull()) {
			for (JsonBrowser song : results.values()) {
				String type = song.get("type").text();
				if (type != null && !"song".equals(type)) {
					continue;
				}
				AudioTrack track = this.mapSearchSong(song);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			log.debug("Audiomack search returned no playable tracks for query: {}", query);
			return AudioReference.NO_TRACK;
		}

		return new BasicAudioPlaylist("Audiomack Search: " + query, tracks, null, true);
	}

	private AudioItem getSong(String uploader, String slug) throws IOException {
		log.debug("Getting Audiomack song: {}/{}", uploader, slug);
		JsonBrowser response = this.makeGetRequest("/music/song/" + uploader + "/" + slug, null);
		return this.extractTrack(response, uploader, slug, "song");
	}

	private AudioItem getAlbum(String uploader, String slug) throws IOException {
		log.debug("Getting Audiomack album: {}/{}", uploader, slug);

		JsonBrowser response = this.makeGetRequest("/music/album/" + uploader + "/" + slug, null);
		if (response.isNull()) {
			log.debug("Audiomack: no album for {}/{}", uploader, slug);
			return AudioReference.NO_TRACK;
		}

		JsonBrowser results = response.get("results");
		if (results.isNull()) {
			return AudioReference.NO_TRACK;
		}

		String name = results.get("title").text();
		String image = results.get("image").text();
		String albumUrl = results.get("links").get("self").text();
		String artist = results.get("artist").text();
		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = results.get("tracks");

		if (!tracksArray.isNull()) {
			for (JsonBrowser track : tracksArray.values()) {
				AudioTrack audioTrack = this.mapAlbumTrack(track, image, name);
				if (audioTrack != null) {
					tracks.add(audioTrack);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new AudiomackAudioPlaylist(
				name,
				tracks,
				ExtendedAudioPlaylist.Type.ALBUM,
				albumUrl,
				image,
				artist,
				tracks.size()
		);
	}

	private AudioItem getPlaylist(String uploader, String slug) throws IOException {
		log.debug("Getting Audiomack playlist: {}/{}", uploader, slug);

		JsonBrowser response = this.makeGetRequest("/playlist/" + uploader + "/" + slug, null);
		if (response.isNull()) {
			log.debug("Audiomack: no playlist for {}/{}", uploader, slug);
			return AudioReference.NO_TRACK;
		}

		JsonBrowser results = response.get("results");
		if (results.isNull()) {
			return AudioReference.NO_TRACK;
		}

		String name = results.get("title").text();
		String image = results.get("image").text();
		String playlistUrl = results.get("links").get("self").text();
		String creator = results.get("artist").get("name").text();
		int trackCount = (int) results.get("track_count").asLong(0);
		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = results.get("tracks");

		if (!tracksArray.isNull()) {
			for (JsonBrowser track : tracksArray.values()) {
				AudioTrack audioTrack = this.mapPlaylistTrack(track);
				if (audioTrack != null) {
					tracks.add(audioTrack);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new AudiomackAudioPlaylist(
				name,
				tracks,
				ExtendedAudioPlaylist.Type.PLAYLIST,
				playlistUrl,
				image,
				creator,
				(trackCount > 0) ? trackCount : tracks.size()
		);
	}

	private AudioItem extractTrack(JsonBrowser response, String uploader, String slug, String type) {
		if (response.isNull()) {
			log.debug("Audiomack: no {} for {}/{}", type, uploader, slug);
			return AudioReference.NO_TRACK;
		}

		JsonBrowser results = response.get("results");
		if (results.isNull()) {
			log.debug("Audiomack: no results in response for {} {}/{}", type, uploader, slug);
			return AudioReference.NO_TRACK;
		}

		AudioTrack track = this.mapSongInfo(results);
		return (track != null) ? track : AudioReference.NO_TRACK;
	}

	// ==================== Mapping Methods ====================

	private AudioTrack mapSearchSong(JsonBrowser json) {
		if (json == null || json.isNull()) {
			return null;
		}

		String id = json.get("id").text();
		if (id == null || id.isEmpty()) {
			return null;
		}

		String title = json.get("title").text();
		if (title == null || title.isEmpty()) {
			return null;
		}

		String artist = json.get("artist").text();
		if (artist == null || artist.isEmpty()) {
			artist = "Unknown";
		}

		String image = json.get("image").text();
		long durationMs = json.get("duration").asLong(0) * 1000;

		String uploaderSlug = json.get("uploader").get("url_slug").text();
		if (uploaderSlug == null) {
			uploaderSlug = json.get("uploader_url_slug").text();
		}
		String urlSlug = json.get("url_slug").text();
		String url = "https://audiomack.com/" + uploaderSlug + "/song/" + urlSlug;
		String isrc = json.get("isrc").text();
		String albumName = json.get("album_details").get("title").text();

		if (albumName == null) {
			albumName = json.get("album").text();
		}

		AudioTrackInfo info = new AudioTrackInfo(
				title,
				artist,
				durationMs,
				id,
				false,
				url,
				image,
				isrc
		);

		return new AudiomackAudioTrack(
				info,
				albumName,
				null,
				null,
				null,
				null,
				false,
				this
		);
	}

	private AudioTrack mapSongInfo(JsonBrowser json) {
		if (json == null || json.isNull()) {
			return null;
		}

		String id = json.get("id").text();
		if (id == null || id.isEmpty()) {
			return null;
		}

		String title = json.get("title").text();
		if (title == null || title.isEmpty()) {
			return null;
		}

		String artist = json.get("artist").text();
		if (artist == null || artist.isEmpty()) {
			artist = "Unknown";
		}

		String image = json.get("image").text();
		long durationMs = json.get("duration").asLong(0) * 1000;
		String url = json.get("links").get("self").text();
		String isrc = json.get("isrc").text();
		String albumName = json.get("album").text();

		AudioTrackInfo info = new AudioTrackInfo(
				title,
				artist,
				durationMs,
				id,
				false,
				url,
				image,
				isrc
		);

		return new AudiomackAudioTrack(
				info,
				albumName,
				null,
				null,
				null,
				null,
				false,
				this
		);
	}

	private AudioTrack mapAlbumTrack(JsonBrowser json, String albumImage, String albumName) {
		if (json == null || json.isNull()) {
			return null;
		}

		String id = json.get("song_id").text();
		if (id == null || id.isEmpty()) {
			id = json.get("id").text();
		}
		if (id == null || id.isEmpty()) {
			return null;
		}

		String title = json.get("title").text();
		if (title == null || title.isEmpty()) {
			return null;
		}

		String artist = json.get("artist").text();
		if (artist == null || artist.isEmpty()) {
			artist = "Unknown";
		}

		long durationMs = json.get("duration").asLong(0) * 1000;
		String uploaderSlug = json.get("uploader_url_slug").text();
		String urlSlug = json.get("url_slug").text();
		String url = "https://audiomack.com/" + uploaderSlug + "/song/" + urlSlug;
		String isrc = json.get("isrc").text();

		AudioTrackInfo info = new AudioTrackInfo(
				title,
				artist,
				durationMs,
				id,
				false,
				url,
				albumImage,
				isrc
		);

		return new AudiomackAudioTrack(
				info,
				albumName,
				null,
				null,
				null,
				null,
				false,
				this
		);
	}

	private AudioTrack mapPlaylistTrack(JsonBrowser json) {
		return this.mapSearchSong(json);
	}

	// ==================== OAuth / HTTP Methods ====================

	private String percentEncode(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		return URLEncoder.encode(value, StandardCharsets.UTF_8)
				.replace("+", "%20")
				.replace("*", "%2A")
				.replace("%7E", "~");
	}

	private String buildAuthHeader(String method, String url, Map<String, String> requestParams) {
		Map<String, String> oauthParams = new TreeMap<>();
		oauthParams.put("oauth_consumer_key", this.consumerKey);
		oauthParams.put("oauth_nonce", generateNonce());
		oauthParams.put("oauth_signature_method", "HMAC-SHA1");
		oauthParams.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000));
		oauthParams.put("oauth_version", "1.0");
		if (this.accessToken != null && !this.accessToken.isEmpty()) {
			oauthParams.put("oauth_token", this.accessToken);
		}

		Map<String, String> allParams = new TreeMap<>();
		for (Map.Entry<String, String> entry : oauthParams.entrySet()) {
			allParams.put(percentEncode(entry.getKey()), percentEncode(entry.getValue()));
		}
		if (requestParams != null) {
			for (Map.Entry<String, String> entry : requestParams.entrySet()) {
				allParams.put(percentEncode(entry.getKey()), percentEncode(entry.getValue()));
			}
		}

		StringBuilder paramStr = new StringBuilder();
		for (Map.Entry<String, String> entry : allParams.entrySet()) {
			if (paramStr.length() > 0) {
				paramStr.append("&");
			}
			paramStr.append(entry.getKey()).append("=").append(entry.getValue());
		}

		String baseString = method.toUpperCase() + "&" + percentEncode(url) + "&" + percentEncode(paramStr.toString());
		String tokenSecret = (this.accessSecret != null) ? this.accessSecret : "";
		String signingKey = percentEncode(this.consumerSecret) + "&" + percentEncode(tokenSecret);

		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
			String signature = Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
			oauthParams.put("oauth_signature", signature);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new RuntimeException("Failed to generate OAuth signature", e);
		}

		StringBuilder header = new StringBuilder("OAuth ");
		boolean first = true;
		for (Map.Entry<String, String> entry : oauthParams.entrySet()) {
			if (!first) {
				header.append(", ");
			}
			header.append(percentEncode(entry.getKey())).append("=\"").append(percentEncode(entry.getValue())).append("\"");
			first = false;
		}
		return header.toString();
	}

	private String generateNonce() {
		StringBuilder sb = new StringBuilder(32);
		for (int i = 0; i < 32; i++) {
			sb.append(NONCE_CHARS.charAt(secureRandom.nextInt(NONCE_CHARS.length())));
		}
		return sb.toString();
	}

	private JsonBrowser makeGetRequest(String endpoint, Map<String, String> queryParams) throws IOException {
		String baseUrl = API_BASE_URL + endpoint;
		StringBuilder urlBuilder = new StringBuilder(baseUrl);
		if (queryParams != null && !queryParams.isEmpty()) {
			urlBuilder.append("?");
			boolean first = true;
			for (Map.Entry<String, String> entry : queryParams.entrySet()) {
				if (!first) {
					urlBuilder.append("&");
				}
				urlBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
						.append("=")
						.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
				first = false;
			}
		}

		HttpGet request = new HttpGet(urlBuilder.toString());
		request.setHeader("Authorization", buildAuthHeader("GET", baseUrl, queryParams));
		setCommonHeaders(request);

		return executeRequest(request);
	}

	JsonBrowser makePostRequest(String endpoint, Map<String, String> bodyParams) throws IOException {
		String baseUrl = API_BASE_URL + endpoint;
		HttpPost request = new HttpPost(baseUrl);
		request.setHeader("Authorization", buildAuthHeader("POST", baseUrl, bodyParams));
		setCommonHeaders(request);
		request.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

		if (bodyParams != null && !bodyParams.isEmpty()) {
			StringBuilder body = new StringBuilder();
			for (Map.Entry<String, String> entry : bodyParams.entrySet()) {
				if (body.length() > 0) {
					body.append("&");
				}
				body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
						.append("=")
						.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
			}
			request.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));
		}

		return executeRequest(request);
	}

	private void setCommonHeaders(HttpUriRequest request) {
		request.setHeader("Accept", "application/json");
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("Accept-Language", "en-US,en;q=0.9");
		request.setHeader("Origin", "https://audiomack.com");
		request.setHeader("Referer", "https://audiomack.com/");
		request.setHeader("Sec-Fetch-Site", "same-site");
		request.setHeader("Sec-Fetch-Mode", "cors");
		request.setHeader("Sec-Fetch-Dest", "empty");
		request.setHeader("Priority", "u=1, i");
		request.setHeader("DNT", "1");
		request.setHeader("sec-ch-ua-platform", "\"Android\"");
	}

	private JsonBrowser executeRequest(HttpUriRequest request) throws IOException {
		try (CloseableHttpResponse response = httpInterfaceManager.getInterface().execute(request)) {
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode != 200) {
				log.warn("Audiomack API returned status {}: {}", statusCode, response.getStatusLine().getReasonPhrase());
				return JsonBrowser.NULL_BROWSER;
			}
			return JsonBrowser.parse(response.getEntity().getContent());
		}
	}
}
