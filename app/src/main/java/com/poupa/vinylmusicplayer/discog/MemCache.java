package com.poupa.vinylmusicplayer.discog;

import androidx.annotation.NonNull;

import com.poupa.vinylmusicplayer.loader.AlbumLoader;
import com.poupa.vinylmusicplayer.loader.SongLoader;
import com.poupa.vinylmusicplayer.model.Album;
import com.poupa.vinylmusicplayer.model.Artist;
import com.poupa.vinylmusicplayer.model.Genre;
import com.poupa.vinylmusicplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * @author SC (soncaokim)
 */

class MemCache {
    Map<Long, Song> songsById = new HashMap<>();

    Map<String, Artist> artistsByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    Map<Long, Artist> artistsById = new HashMap<>();

    // internal implementation class, to make explicit that we are dealing with slices of album, not the full one
    static class AlbumSlice extends Album {
    }

    Map<Long, Map<Long, AlbumSlice>> albumsByAlbumIdAndArtistId = new HashMap<>();

    Map<String, Genre> genresByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    Map<Long, ArrayList<Song>> songsByGenreId = new HashMap<>();

    synchronized void addSong(@NonNull final Song song) {
        Map<Long, AlbumSlice> albums = getOrCreateAlbumById(song);
//        TODO // Merge album by name - MediaStore may index album of same name with different IDs
//        if (!album.songs.isEmpty() && (album.getId() != song.albumId)) {
//            song.albumId = album.getId();
//        }
        for (Album album : albums.values()) {
            album.songs.add(song);
        }

        // Update genre cache
        Genre genre = getOrCreateGenreByName(song);
        ArrayList<Song> songs = songsByGenreId.get(genre.id);
        if (songs != null) {
            songs.add(song);
            genre.songCount = songs.size();
        }

        songsById.put(song.id, song);

        // Only sort albums after the song has been added
        for (Long artistId : albums.keySet()) {
            Artist artist = artistsById.get(artistId);
            if (artist == null) continue;
            Collections.sort(artist.albums, AlbumLoader.BY_YEAR_DESC);
        }
        for (Album album : albums.values()) {
            Collections.sort(album.songs, SongLoader.BY_DISC_TRACK);
        }
    }

    synchronized void removeSongById(long songId) {
        Song song = songsById.get(songId);
        if (song != null) {
            // ---- Remove the song from linked Album cache
            Map<Long, AlbumSlice> impactedAlbumsByArtist = albumsByAlbumIdAndArtistId.get(song.albumId);
            Set<Long> orphanArtists = new HashSet<>();
            for (Map.Entry<Long, AlbumSlice> pair : impactedAlbumsByArtist.entrySet()) {
                Album album = pair.getValue();
                if (album.songs.remove(song)) {
                    if (album.songs.isEmpty()) {
                        orphanArtists.add(pair.getKey());
                    }
                }
            }

            // ---- Check the Artist/Album link
            for (Long artistId : orphanArtists) {
                Artist artist = artistsById.get(artistId);

                Album album = impactedAlbumsByArtist.get(artistId);
                impactedAlbumsByArtist.remove(artistId);

                if (artist == null) continue;

                artist.albums.remove(album);
                if (artist.albums.isEmpty()) {
                    artistsById.remove(artist.id);
                    artistsByName.remove(artist.name);
                }
            }
            if (impactedAlbumsByArtist.isEmpty()) {
                albumsByAlbumIdAndArtistId.remove(song.albumId);
            }

            // ---- Remove song from Genre cache
            Genre genre = genresByName.get(song.genre);
            if (genre != null) {
                ArrayList<Song> songs = songsByGenreId.get(genre.id);
                if (songs != null) {
                    songs.remove(song);
                    if (songs.isEmpty()) {
                        genresByName.remove(genre.name);
                        songsByGenreId.remove(genre.id);
                    } else {
                        genre.songCount = songs.size();
                    }
                }
            }

            // ---- Remove the song from the memory cache
            songsById.remove(songId);
        }
    }

    synchronized void clear() {
        songsById.clear();

        artistsByName.clear();
        artistsById.clear();

        albumsByAlbumIdAndArtistId.clear();

        genresByName.clear();
        songsByGenreId.clear();
    }

    @NonNull
    private synchronized Set<Artist> getOrCreateArtistByName(@NonNull final Song song) {
        Function<String, Artist> getOrCreateArtist = (@NonNull final String artistName) -> {
            Artist artist = artistsByName.get(artistName);
            if (artist == null) {
                long artistId = artistsByName.size();
                artist = new Artist(artistId, artistName);

                artistsByName.put(artistName, artist);
                artistsById.put(artistId, artist);
            }
            return artist;
        };

        Set<String> names = new HashSet<>();
        names.addAll(song.artistNames);
        names.addAll(song.albumArtistNames);
        if (names.size() > 1) {
            // after merging one empty and one non-empty artists lists,
            // we end up with a list containing an empty element
            // remove it if that's the case
            names.remove("");
        }
        Set<Artist> artists = new HashSet<>();
        for (final String name : names) {
            artists.add(getOrCreateArtist.apply(name));
        }

        // Since the MediaStore artistId is disregarded, correct the link on the Song object
        Artist mainArtist = getOrCreateArtist.apply(song.artistNames.get(0));
        song.artistId = mainArtist.getId();

        return artists;
    }

    @NonNull
    private synchronized Map<Long, AlbumSlice> getOrCreateAlbumById(@NonNull final Song song) {
        Set<Artist> artists = getOrCreateArtistByName(song);
        Map<Long, AlbumSlice> albumsByArtist = albumsByAlbumIdAndArtistId.get(song.albumId);
        if (albumsByArtist == null) {
            albumsByAlbumIdAndArtistId.put(song.albumId, new HashMap<>());
            albumsByArtist = albumsByAlbumIdAndArtistId.get(song.albumId);
        }
        // attach to the artists if needed
        for (Artist artist : artists) {
            if (!albumsByArtist.containsKey(artist.id)) {
                AlbumSlice album = new AlbumSlice();
                albumsByArtist.put(artist.id, album);
                artist.albums.add(album);
            }

            albumsByArtist.put(artist.id, albumsByArtist.get(artist.id));
        }
        return albumsByArtist;
    }

    @NonNull
    private synchronized Genre getOrCreateGenreByName(@NonNull final Song song) {
        Genre genre = genresByName.get(song.genre);
        if (genre == null) {
            genre = new Genre(genresByName.size(), song.genre, 0);

            genresByName.put(song.genre, genre);
            songsByGenreId.put(genre.id, new ArrayList<>());
        }
        return genre;
    }
}
