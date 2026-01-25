package com.github.topi314.lavasrc.audiomack;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.List;

public class AudiomackAudioPlaylist extends ExtendedAudioPlaylist {
	public AudiomackAudioPlaylist(
		String name, List<AudioTrack> tracks, ExtendedAudioPlaylist.Type type, String identifier, String artworkURL, String author, Integer totalTracks
	) {
		super(name, tracks, type, identifier, artworkURL, author, totalTracks);
	}
}
