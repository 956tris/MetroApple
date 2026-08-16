/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * A [ForwardingPlayer] that gates all transport commands based on the external input control
 * toggle. When disabled, [getAvailableCommands] returns [Player.Commands.EMPTY] and all
 * transport methods become no-ops, causing Media3's session bridging to automatically
 * reflect hidden/disabled buttons in system UI (lockscreen, notification, AVRCP, Android Auto).
 *
 * Metadata-read operations (position, duration, current item) always delegate through so that
 * any still-active UI can display current state if needed.
 */
@UnstableApi
class InputControlGatingPlayer(
    player: Player,
) : ForwardingPlayer(player) {
    @Volatile
    private var enabled: Boolean = true

    /**
     * Updates the gating state.
     */
    fun setInputControlEnabled(value: Boolean) {
        enabled = value
    }

    override fun getAvailableCommands(): Player.Commands =
        if (enabled) super.getAvailableCommands() else Player.Commands.EMPTY

    override fun isCommandAvailable(command: Int): Boolean =
        enabled && super.isCommandAvailable(command)

    // --- Transport commands gated when disabled ---

    override fun play() {
        if (enabled) super.play()
    }

    override fun pause() {
        if (enabled) super.pause()
    }

    override fun stop() {
        if (enabled) super.stop()
    }

    override fun prepare() {
        if (enabled) super.prepare()
    }

    override fun seekToDefaultPosition() {
        if (enabled) super.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (enabled) super.seekToDefaultPosition(mediaItemIndex)
    }

    override fun seekTo(positionMs: Long) {
        if (enabled) super.seekTo(positionMs)
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (enabled) super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekBack() {
        if (enabled) super.seekBack()
    }

    override fun seekForward() {
        if (enabled) super.seekForward()
    }

    override fun seekToNext() {
        if (enabled) super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (enabled) super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (enabled) super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (enabled) super.seekToPreviousMediaItem()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (enabled) super.setPlayWhenReady(playWhenReady)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        if (enabled) super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(
        mediaItem: MediaItem,
        startPositionMs: Long,
    ) {
        if (enabled) super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(
        mediaItem: MediaItem,
        resetPosition: Boolean,
    ) {
        if (enabled) super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        if (enabled) super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        resetPosition: Boolean,
    ) {
        if (enabled) super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        if (enabled) super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        if (enabled) super.addMediaItem(mediaItem)
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: MediaItem,
    ) {
        if (enabled) super.addMediaItem(index, mediaItem)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        if (enabled) super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ) {
        if (enabled) super.addMediaItems(mediaItems)
    }

    override fun removeMediaItem(index: Int) {
        if (enabled) super.removeMediaItem(index)
    }

    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (enabled) super.removeMediaItems(fromIndex, toIndex)
    }

    override fun clearMediaItems() {
        if (enabled) super.clearMediaItems()
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (enabled) super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    override fun setRepeatMode(repeatMode: Int) {
        if (enabled) super.setRepeatMode(repeatMode)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (enabled) super.setPlaybackSpeed(speed)
    }
}
