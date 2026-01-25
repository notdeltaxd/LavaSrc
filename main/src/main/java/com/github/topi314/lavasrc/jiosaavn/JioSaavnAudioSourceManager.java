package com.github.topi314.lavasrc.jiosaavn;

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

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JioSaavnAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable {

	private static final Pattern JIOSAAVN_REGEX = Pattern.compile(
		"(?:https?://)?(?:www\\.)?(?:jiosaavn\\.com|saavn\\.com)/((?:s/)?(?:song|album|artist|featured|p/album|s/playlist))"
	);

	public static final String SEARCH_PREFIX = "jssearch:";
	public static final String RECOMMENDATIONS_PREFIX = "jsrec:";

	public static final String API_SEARCH = "/api/search?q=%s";
	public static final String API_TRACK_BY_URL = "/api/track?url=%s";
	public static final String API_RECOMMENDATIONS = "/api/recommendations?id=%s&limit=%d";
	public static final String API_ALBUM_BY_URL = "/api/album?url=%s";
	public static final String API_ARTIST_BY_URL = "/api/artist?url=%s";
	public static final String API_PLAYLIST_BY_URL = "/api/playlist?url=%s";
	public static final String API_MEDIA_URL_BY_ID = "/api/media-url?id=%s";

	private static final Logger log = LoggerFactory.getLogger(JioSaavnAudioSourceManager.class);

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
	private final String baseUrl;
	private int searchLimit = 5;
	private int recommendationsLimit = 5;

	public JioSaavnAudioSourceManager(@NotNull String apiUrl) {
		if (apiUrl == null || apiUrl.isEmpty()) {
			throw new IllegalArgumentException("JioSaavn API URL must be set");
		}
		this.baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit < 1 ? 5 : searchLimit;
	}

	public void setRecommendationsLimit(int recommendationsLimit) {
		this.recommendationsLimit = recommendationsLimit < 1 ? 5 : recommendationsLimit;
	}

	private JsonBrowser getJson(String path) throws IOException {
		if (!path.startsWith("/")) {
			path = "/" + path;
		}
		if (this.baseUrl.endsWith("/api") && path.startsWith("/api")) {
			path = path.substring(4);
		}

		HttpGet request = new HttpGet(this.baseUrl + path);
		request.setHeader("Accept", "application/json");

		JsonBrowser response = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		if (response == null) {
			return JsonBrowser.NULL_BROWSER;
		}

		return response;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "jiosaavn";
	}

	@Override
	public @Nullable AudioItem loadItem(@NotNull AudioPlayerManager manager, @NotNull AudioReference reference) {
		String identifier = reference.identifier;
		try {
			// Text search via jssearch:
			if (identifier != null && identifier.startsWith(SEARCH_PREFIX)) {
				String query = identifier.substring(SEARCH_PREFIX.length());
				if (query.isEmpty()) {
					throw new IllegalArgumentException("No query provided for JioSaavn search");
				}
				return this.getSearch(query);
			}

			// Recommendations by track id:
			if (identifier != null && identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
				String id = identifier.substring(RECOMMENDATIONS_PREFIX.length());
				if (id.isEmpty()) {
					throw new IllegalArgumentException("No track id provided for JioSaavn recommendations");
				}
				return this.getRecommendations(id);
			}

			if (identifier == null || identifier.isEmpty()) {
				return null;
			}

			// If it's a full JioSaavn URL, resolve by type using URL-based endpoints.
			Matcher matcher = JIOSAAVN_REGEX.matcher(identifier);
			if (!matcher.find()) {
				return null;
			}

			String pathType = matcher.group(1);
			String type;
			if (pathType.equals("song") || pathType.equals("s/song")) {
				type = "song";
			} else if (pathType.equals("album") || pathType.equals("p/album")) {
				type = "album";
			} else if (pathType.equals("artist")) {
				type = "artist";
			} else if (pathType.equals("featured") || pathType.equals("s/playlist")) {
				type = "playlist";
			} else {
				type = null;
			}

			// Strip query parameters from URL before sending to API, as URL IDs differ from metadata IDs
			String fullUrl = stripQueryParameters(identifier);

			switch (type) {
				case "song":
					return getTrackByUrl(fullUrl);
				case "album":
					return getAlbumByUrl(fullUrl);
				case "playlist":
					return getPlaylistByUrl(fullUrl);
				case "artist":
					return getArtistByUrl(fullUrl);
				default:
					return null;
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load JioSaavn item: " + e.getMessage(), e);
		}
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		ExtendedAudioTrackInfo extended = super.decodeTrack(input);
		return new JioSaavnAudioTrack(
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
		log.debug("Searching JioSaavn songs for query: {}", query);
		JsonBrowser response = this.getJson(String.format(API_SEARCH, URLEncoder.encode(query, StandardCharsets.UTF_8)));

		if (response.isNull()) {
			log.debug("No JioSaavn search results for query: {}", query);
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser results = response.get("results");
		if (!results.isNull()) {
			for (JsonBrowser song : results.values()) {
				AudioTrack track = this.mapSong(song);
				if (track != null) {
					tracks.add(track);
					if (tracks.size() >= this.searchLimit) {
						break;
					}
				}
			}
		}

		if (tracks.isEmpty()) {
			log.debug("JioSaavn search returned no playable tracks for query: {}", query);
			return AudioReference.NO_TRACK;
		}

		return new BasicAudioPlaylist("JioSaavn Search: " + query, tracks, null, true);
	}

	private AudioItem getTrackByUrl(String url) throws IOException {
		JsonBrowser response = this.getJson(String.format(API_TRACK_BY_URL, URLEncoder.encode(url, StandardCharsets.UTF_8)));
		if (response.isNull()) {
			log.debug("JioSaavn: no track for url {}", url);
			return AudioReference.NO_TRACK;
		}
		JsonBrowser trackData = response.get("track");
		if (trackData.isNull()) {
			log.debug("JioSaavn: no track data in response for url {}", url);
			return AudioReference.NO_TRACK;
		}
		AudioTrack track = this.mapSong(trackData);
		return track != null ? track : AudioReference.NO_TRACK;
	}

	private AudioItem getRecommendations(String id) throws IOException {
		JsonBrowser response = this.getJson(String.format(API_RECOMMENDATIONS, URLEncoder.encode(id, StandardCharsets.UTF_8), this.recommendationsLimit));
		if (response.isNull()) {
			log.debug("JioSaavn: no recommendations for id {}", id);
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = response.get("tracks");
		if (!tracksArray.isNull()) {
			for (JsonBrowser song : tracksArray.values()) {
				AudioTrack track = this.mapSong(song);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new JioSaavnAudioPlaylist(
			"JioSaavn Recommendations",
			tracks,
			ExtendedAudioPlaylist.Type.RECOMMENDATIONS,
			id,
			null,
			"JioSaavn",
			tracks.size()
		);
	}

	private AudioItem getAlbumByUrl(String url) throws IOException {
		JsonBrowser response = this.getJson(String.format(API_ALBUM_BY_URL, URLEncoder.encode(url, StandardCharsets.UTF_8)));
		if (response.isNull()) {
			log.debug("JioSaavn: no album for url {}", url);
			return AudioReference.NO_TRACK;
		}

		JsonBrowser albumData = response.get("album");
		if (albumData.isNull()) {
			log.debug("JioSaavn: no album data in response for url {}", url);
			return AudioReference.NO_TRACK;
		}

		String title = cleanString(albumData.get("name").text());
		String artworkUrl = albumData.get("artworkUrl").text();
		String albumUrl = albumData.get("uri").text();
		String author = cleanString(albumData.get("author").text());

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = albumData.get("tracks");
		if (!tracksArray.isNull()) {
			for (JsonBrowser song : tracksArray.values()) {
				AudioTrack track = this.mapSong(song);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new JioSaavnAudioPlaylist(
			title,
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			albumUrl,
			artworkUrl,
			author,
			tracks.size()
		);
	}

	private AudioItem getPlaylistByUrl(String url) throws IOException {
		JsonBrowser response = this.getJson(String.format(API_PLAYLIST_BY_URL, URLEncoder.encode(url, StandardCharsets.UTF_8)));
		if (response.isNull()) {
			log.debug("JioSaavn: no playlist for url {}", url);
			return AudioReference.NO_TRACK;
		}
		JsonBrowser playlistData = response.get("playlist");
		if (playlistData.isNull()) {
			log.debug("JioSaavn: no playlist data in response for url {}", url);
			return AudioReference.NO_TRACK;
		}
		return getPlaylistFromData(playlistData);
	}

	private AudioItem getPlaylistFromData(JsonBrowser playlistData) {
		String title = cleanString(playlistData.get("title").text());
		String artworkUrl = playlistData.get("artworkUrl").text();
		String url = playlistData.get("uri").text();

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = playlistData.get("tracks");
		if (!tracksArray.isNull()) {
			for (JsonBrowser song : tracksArray.values()) {
				AudioTrack track = this.mapSong(song);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		String author = tracks.get(0).getInfo().author;

		return new JioSaavnAudioPlaylist(
			title,
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			url,
			artworkUrl,
			author,
			tracks.size()
		);
	}

	private AudioItem getArtistByUrl(String url) throws IOException {
		JsonBrowser response = this.getJson(String.format(API_ARTIST_BY_URL, URLEncoder.encode(url, StandardCharsets.UTF_8)));
		if (response.isNull()) {
			log.debug("JioSaavn: no artist for url {}", url);
			return AudioReference.NO_TRACK;
		}

		JsonBrowser artistData = response.get("artist");
		if (artistData.isNull()) {
			log.debug("JioSaavn: no artist data in response for url {}", url);
			return AudioReference.NO_TRACK;
		}

		String artistName = cleanString(artistData.get("name").text());
		String artworkUrl = artistData.get("artworkUrl").text();
		String artistUrl = artistData.get("uri").text();

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser tracksArray = artistData.get("tracks");
		if (!tracksArray.isNull()) {
			for (JsonBrowser song : tracksArray.values()) {
				AudioTrack track = this.mapSong(song);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new JioSaavnAudioPlaylist(
			artistName + "'s Top Tracks",
			tracks,
			ExtendedAudioPlaylist.Type.ARTIST,
			artistUrl,
			artworkUrl,
			artistName,
			tracks.size()
		);
	}

	private AudioTrack mapSong(JsonBrowser json) {
		if (json == null || json.isNull()) {
			return null;
		}

		// New API format uses "identifier" instead of "id"
		String id = json.get("identifier").text();
		if (id == null || id.isEmpty()) {
			return null;
		}

		// New API format uses "title" instead of "name"
		String title = cleanString(json.get("title").text());
		if (title == null || title.isEmpty()) {
			return null;
		}

		// New API format uses "author" as direct string instead of nested artists object
		String author = cleanString(json.get("author").text());
		if (author == null || author.isEmpty()) {
			author = "Unknown";
		}

		// New API format uses "artworkUrl" as direct string instead of image array
		String artworkUrl = json.get("artworkUrl").text();

		// New API format uses "length" in milliseconds instead of "duration" in seconds
		long durationMs = 0L;
		if (!json.get("length").isNull()) {
			durationMs = json.get("length").asLong(0L);
		}

		// New API format uses "uri" instead of "url"
		String url = json.get("uri").text();

		// New API format uses "albumName" and "albumUrl" as direct fields
		String albumName = cleanString(json.get("albumName").text());
		String albumUrl = json.get("albumUrl").text();

		// New API format uses "artistUrl" and "artistArtworkUrl" as direct fields
		String artistUrl = json.get("artistUrl").text();
		String artistArtworkUrl = json.get("artistArtworkUrl").text();

		AudioTrackInfo info = new AudioTrackInfo(
			title,
			author,
			durationMs,
			id,
			false,
			url,
			artworkUrl,
			null
		);

		return new JioSaavnAudioTrack(
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

	private String cleanString(String text) {
		return text != null ? text.replace("&quot;", "").replace("&amp;", "") : null;
	}

	private String stripQueryParameters(String url) {
		if (url == null || url.isEmpty()) {
			return url;
		}
		int queryIndex = url.indexOf('?');
		if (queryIndex >= 0) {
			return url.substring(0, queryIndex);
		}
		return url;
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

	HttpInterface getHttpInterface() {
		return this.httpInterfaceManager.getInterface();
	}

	String getBaseUrl() {
		return this.baseUrl;
	}
}


