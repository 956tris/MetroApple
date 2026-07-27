/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.reportException
import com.metrolist.music.apple.AppleMusicCanvasProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel
@Inject
constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!
    val playlistId = MutableStateFlow("")
    val albumWithSongs =
        database
            .albumWithSongs(albumId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

    private val _albumCanvasUrl = MutableStateFlow<String?>(null)
    val albumCanvasUrl = _albumCanvasUrl.asStateFlow()

    init {
        viewModelScope.launch {
            val album = database.album(albumId).first()
            if (album != null) {
                resolveCanvas(album.album.title, album.artists.firstOrNull()?.name ?: "")
            }
            YouTube
                .album(albumId)
                .onSuccess {
                    playlistId.value = it.album.playlistId
                    otherVersions.value = it.otherVersions
                    if (album == null) {
                        resolveCanvas(it.album.title, it.album.artists?.firstOrNull()?.name ?: "")
                    }
                    database.transaction {
                        if (album == null) {
                            insert(it)
                        } else {
                            update(album.album, it, album.artists)
                        }
                    }
                }.onFailure {
                    reportException(it)
                    if (it.message?.contains("NOT_FOUND") == true) {
                        database.query {
                            album?.album?.let(::delete)
                        }
                    }
                }
        }
    }

    private fun resolveCanvas(title: String, artist: String) {
        viewModelScope.launch {
            AppleMusicCanvasProvider.getBySongArtist(
                song = title,
                artist = artist,
                album = title,
                explicit = false,
                isrc = null,
                durationSeconds = null,
                preferredAspect = AppleMusicCanvasProvider.CanvasAspectPreference.SQUARE
            )?.let {
                _albumCanvasUrl.value = it.animated
            }
        }
    }
}
