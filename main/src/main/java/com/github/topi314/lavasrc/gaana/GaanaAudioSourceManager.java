package com.github.topi314.lavasrc.gaana;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.ExtendedAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import java.util.function.Consumer;
import java.util.function.Function;

public class GaanaAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable {

    private static final Logger log = LoggerFactory.getLogger(GaanaAudioSourceManager.class);
    
    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();

    public static final String SEARCH_PREFIX = "gaanasearch:";

    private static final String API_URL = "https://gaana.com/apiv2";
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    public static final Pattern URL_PATTERN = Pattern.compile("https?://(?:www\\.)?gaana\\.com/(?<type>song|album|playlist|artist)/(?<identifier>[\\w-]+)");

    private int searchLimit = 20;

    public GaanaAudioSourceManager() {
        super();
    }

    public GaanaAudioSourceManager(int searchLimit) {
        super();
        this.searchLimit = searchLimit > 0 ? searchLimit : 20;
    }

    @Override
    public String getSourceName() {
        return "gaana";
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit > 0 ? searchLimit : 20;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        try {
            String identifier = reference.identifier;

            if (identifier.startsWith(SEARCH_PREFIX)) {
                return search(identifier.substring(SEARCH_PREFIX.length()).trim());
            }

            Matcher matcher = URL_PATTERN.matcher(identifier);
            if (matcher.find()) {
                String type = matcher.group("type");
                String id = matcher.group("identifier");

                switch (type) {
                    case "song": return loadSong(id);
                    case "album": return loadAlbum(id);
                    case "playlist": return loadPlaylist(id);
                    case "artist": return loadArtist(id);
                }
            }

            return null;
        } catch (IOException e) {
            throw new FriendlyException("Failed to load Gaana item", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    private AudioItem search(String query) throws IOException {
        JsonBrowser json = getJson("search", query, "keyword", query, "secType", "track");
        JsonBrowser gr = json.get("gr");

        if (gr.isNull() || gr.values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        JsonBrowser trackGroup = null;
        for (JsonBrowser group : gr.values()) {
            if ("Track".equals(group.get("ty").text())) {
                trackGroup = group;
                break;
            }
        }

        if (trackGroup == null || trackGroup.get("gd").isNull()) {
            return AudioReference.NO_TRACK;
        }

        List<AudioTrack> results = new ArrayList<>();
        for (JsonBrowser item : trackGroup.get("gd").values()) {
            String seokey = item.get("seo").text();
            if (seokey == null) seokey = item.get("id").text();
            if (seokey != null) {
                try {
                    AudioItem track = loadSong(seokey);
                    if (track instanceof AudioTrack) {
                        results.add((AudioTrack) track);
                        if (results.size() >= searchLimit) break;
                    }
                } catch (Exception e) {
                    log.debug("Failed to load search result: {}", seokey, e);
                }
            }
        }

        if (results.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist("Gaana Search: " + query, results, null, true);
    }

    private AudioItem loadSong(String seokey) throws IOException {
        JsonBrowser json = getJson("songDetail", "song/" + seokey, "seokey", seokey);
        JsonBrowser tracks = json.get("tracks");

        if (tracks.isNull() || tracks.values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return mapTrack(tracks.index(0));
    }

    private AudioItem loadAlbum(String seokey) throws IOException {
        JsonBrowser json = getJson("albumDetail", "album/" + seokey, "seokey", seokey);
        JsonBrowser tracks = json.get("tracks");
        JsonBrowser albumData = json.get("album");

        if (tracks.isNull() || tracks.values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        String albumName = albumData.isNull() ? "Unknown Album" : albumData.get("title").text();
        String albumUrl = "https://gaana.com/album/" + seokey;
        String artworkUrl = albumData.isNull() ? null : albumData.get("atw").text();

        List<AudioTrack> trackList = new ArrayList<>();
        for (JsonBrowser track : tracks.values()) {
            AudioTrack audioTrack = mapTrack(track);
            if (audioTrack != null) trackList.add(audioTrack);
        }

        if (trackList.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new GaanaAudioPlaylist(albumName, trackList, ExtendedAudioPlaylist.Type.ALBUM, albumUrl, artworkUrl, null, trackList.size());
    }

    private AudioItem loadPlaylist(String seokey) throws IOException {
        JsonBrowser json = getJson("playlistDetail", "playlist/" + seokey, "seokey", seokey);
        JsonBrowser tracks = json.get("tracks");
        JsonBrowser playlistData = json.get("playlist");

        if (tracks.isNull() || tracks.values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        String playlistName = playlistData.isNull() ? "Unknown Playlist" : playlistData.get("title").text();
        String playlistUrl = "https://gaana.com/playlist/" + seokey;
        String artworkUrl = playlistData.isNull() ? null : playlistData.get("atw").text();

        List<AudioTrack> trackList = new ArrayList<>();
        for (JsonBrowser track : tracks.values()) {
            AudioTrack audioTrack = mapTrack(track);
            if (audioTrack != null) trackList.add(audioTrack);
        }

        if (trackList.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new GaanaAudioPlaylist(playlistName, trackList, ExtendedAudioPlaylist.Type.PLAYLIST, playlistUrl, artworkUrl, null, trackList.size());
    }

    private AudioItem loadArtist(String seokey) throws IOException {
        JsonBrowser detailJson = getJson("artistDetail", "artist/" + seokey, "seokey", seokey);
        JsonBrowser artistArray = detailJson.get("artist");

        if (artistArray.isNull() || artistArray.values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        JsonBrowser artistData = artistArray.index(0);
        String artistId = artistData.get("artist_id").text();
        String artistName = artistData.get("name").text();
        String artworkUrl = artistData.get("artwork_bio").text();

        if (artistId == null) {
            return AudioReference.NO_TRACK;
        }

        JsonBrowser tracksJson = getArtistTracks(artistId, seokey);
        JsonBrowser tracks = tracksJson.get("tracks");
        if (tracks.isNull()) tracks = tracksJson.get("entities");

        if (tracks.isNull() || tracks.values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        List<AudioTrack> trackList = new ArrayList<>();
        for (JsonBrowser track : tracks.values()) {
            AudioTrack audioTrack = mapTrack(track);
            if (audioTrack != null) trackList.add(audioTrack);
        }

        if (trackList.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new GaanaAudioPlaylist(
            (artistName != null ? artistName : "Unknown Artist") + "'s Top Tracks",
            trackList, ExtendedAudioPlaylist.Type.ARTIST,
            "https://gaana.com/artist/" + seokey, artworkUrl, artistName, trackList.size()
        );
    }

    private JsonBrowser getArtistTracks(String artistId, String seokey) throws IOException {
        try (HttpInterface httpInterface = getHttpInterface()) {
            String params = "type=artistTrackList&id=" + URLEncoder.encode(artistId, "UTF-8")
                + "&language=&order=0&page=0&sortBy=popularity";
            String url = API_URL + "?" + params;

            HttpPost request = new HttpPost(url);
            request.setHeader("User-Agent", USER_AGENT);
            request.setHeader("Accept", "application/json, text/plain, */*");
            request.setHeader("Origin", "https://gaana.com");
            request.setHeader("Referer", "https://gaana.com/artist/" + seokey);

            try (CloseableHttpResponse response = httpInterface.execute(request)) {
                return JsonBrowser.parse(response.getEntity().getContent());
            }
        }
    }

    private JsonBrowser getJson(String type, String refPath, String... params) throws IOException {
        try (HttpInterface httpInterface = getHttpInterface()) {
            StringBuilder urlBuilder = new StringBuilder(API_URL);
            urlBuilder.append("?type=").append(URLEncoder.encode(type, "UTF-8"));
            urlBuilder.append("&country=IN&page=0");

            for (int i = 0; i < params.length; i += 2) {
                urlBuilder.append("&").append(params[i]).append("=")
                    .append(URLEncoder.encode(params[i + 1], "UTF-8"));
            }

            HttpPost request = new HttpPost(urlBuilder.toString());
            request.setHeader("User-Agent", USER_AGENT);
            request.setHeader("Accept", "application/json, text/plain, */*");
            request.setHeader("Origin", "https://gaana.com");
            request.setHeader("Referer", "https://gaana.com/" + refPath);

            try (CloseableHttpResponse response = httpInterface.execute(request)) {
                return JsonBrowser.parse(response.getEntity().getContent());
            }
        }
    }

    private AudioTrack mapTrack(JsonBrowser track) {
        String id = track.get("track_id").text();
        if (id == null) id = track.get("entity_id").text();
        if (id == null) return null;

        String title = track.get("track_title").text();
        if (title == null) title = track.get("name").text();
        if (title == null) return null;

        String artist = null;
        JsonBrowser artistArray = track.get("artist");
        if (!artistArray.isNull() && !artistArray.values().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonBrowser a : artistArray.values()) {
                String name = a.get("name").text();
                if (name != null) names.add(name);
            }
            artist = String.join(", ", names);
        }
        if (artist == null || artist.isEmpty()) artist = "Unknown Artist";

        long duration = track.get("duration").asLong(0) * 1000;

        String artwork = track.get("artwork_large").text();
        if (artwork == null) artwork = track.get("atw").text();

        String seokey = track.get("seokey").text();
        String uri = seokey != null ? "https://gaana.com/song/" + seokey : "https://gaana.com/song/" + id;

        String isrc = track.get("isrc").text();

        AudioTrackInfo trackInfo = new AudioTrackInfo(title, artist, duration, id, false, uri, artwork, isrc);
        return new GaanaAudioTrack(trackInfo, this);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        return new GaanaAudioTrack(trackInfo, this);
    }

    @Override
    public void shutdown() {
        try {
            httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
        }
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        httpInterfaceManager.configureBuilder(configurator);
    }

    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }
}
