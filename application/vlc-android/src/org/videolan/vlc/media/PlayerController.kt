package org.videolan.vlc.media

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import org.videolan.BuildConfig
import org.videolan.libvlc.FactoryManager
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IMediaFactory
import org.videolan.libvlc.interfaces.IMediaList
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.VLCInstance
import org.videolan.resources.VLCOptions
import org.videolan.tools.KEY_EQUALIZER_ENABLED
import org.videolan.tools.*
import org.videolan.vlc.*
import org.videolan.vlc.gui.dialogs.VideoTracksDialog
import org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import org.videolan.vlc.repository.EqualizerRepository
import org.videolan.vlc.repository.SlaveRepository
import kotlin.math.absoluteValue

class PlayerController(val context: Context) : IVLCVout.Callback, MediaPlayer.EventListener, CoroutineScope {
    override val coroutineContext = Dispatchers.Main.immediate + SupervisorJob()

    //    private val exceptionHandler by lazy(LazyThreadSafetyMode.NONE) { CoroutineExceptionHandler { _, _ -> onPlayerError() } }
    private val playerContext by lazy(LazyThreadSafetyMode.NONE) { newSingleThreadContext("vlc-player") }
    private val settings by lazy(LazyThreadSafetyMode.NONE) { Settings.getInstance(context) }
    val progress by lazy(LazyThreadSafetyMode.NONE) { MutableLiveData<Progress>().apply { value = Progress() } }
    val speed by lazy(LazyThreadSafetyMode.NONE) { MutableLiveData<Float>().apply { value = 1.0F } }
    private val slaveRepository by lazy { SlaveRepository.getInstance(context) }
    private val mediaFactory by lazy { FactoryManager.getFactory(IMediaFactory.factoryId) as IMediaFactory }

    /**
     * Companion player used to play an external audio file alongside the video. This audio is
     * deliberately NOT attached to [mediaplayer] as a libvlc slave: an audio slave is a separate
     * demuxer that doesn't follow the master input on seek (the video resets to 00:00 and stalls
     * while the external audio keeps playing). This player mirrors the main transport instead.
     */
    private var externalAudioPlayer: MediaPlayer? = null

    /** External audio persisted for the media being played, set by [setSlaves]. */
    @Volatile
    private var pendingExternalAudioUri: Uri? = null

    /** Volume requested by the app, applied to the companion player while it is active. */
    private var externalAudioVolume: Int? = null

    /** Audio delay applied to the companion player while it is active. */
    @Volatile
    private var externalAudioDelay: Long = 0L

    /**
     * Re-applies [externalAudioDelay] once the companion player has an audio output: LibVLC only
     * honours `setAudioDelay` after the output exists, so a delay set before playback may be
     * dropped.
     *
     * The same event is used to realign the companion: opening the file and starting its audio
     * output takes an unpredictable time, so it always starts a bit late.
     */
    private val externalAudioEventListener = MediaPlayer.EventListener { event ->
        if (event?.type == MediaPlayer.Event.Playing) {
            val companion = externalAudioPlayer
            if (companion != null && !companion.isReleased) {
                companion.setAudioDelay(externalAudioDelay)
                syncExternalAudio()
            }
        }
    }

    var mediaplayer = newMediaPlayer()
        private set
    var switchToVideo = false
    var seekable = false
    var pausable = false
    var previousMediaStats: IMedia.Stats? = null
        private set
    @Volatile var hasRenderer = false
        private set

    fun getVout(): IVLCVout? = mediaplayer.vlcVout

    fun canDoPassthrough() = mediaplayer.hasMedia() && !mediaplayer.isReleased && mediaplayer.canDoPassthrough()

    fun getMedia(): IMedia? = mediaplayer.media

    fun play() {
        if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.play()
        val companion = externalAudioPlayer
        if (companion != null && !companion.isReleased) {
            // The companion is left paused while the video is paused, so it may be behind (or ahead,
            // when it kept playing): realign it before starting it so both start together
            syncExternalAudio()
            companion.play()
        }
    }

    fun pause(): Boolean {
        if (isPlaying() && mediaplayer.hasMedia() && pausable) {
            mediaplayer.pause()
            externalAudioPlayer?.let { if (!it.isReleased) it.pause() }
            return true
        }
        return false
    }

    fun stop() {
        stopExternalAudio()
        if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.stop()
        setPlaybackStopped()
    }

    private fun releaseMedia() = mediaplayer.media?.let {
        it.setEventListener(null)
        it.release()
    }

    private var mediaplayerEventListener: MediaPlayerEventListener? = null
    internal suspend fun startPlayback(media: IMedia, listener: MediaPlayerEventListener, time: Long) {
        mediaplayerEventListener = listener
        withContext(Dispatchers.Main.immediate) {
            resetPlaybackState(time, media.duration)
        }
        mediaplayer.setEventListener(null)
        withContext(Dispatchers.IO) { if (!mediaplayer.isReleased) mediaplayer.media = media.apply { if (hasRenderer) parse() } }
        mediaplayer.setEventListener(this@PlayerController)
        if (!mediaplayer.isReleased) {
            if (Settings.getInstance(context).getBoolean(KEY_EQUALIZER_ENABLED, false)) withContext(Dispatchers.IO) {
                val repository = EqualizerRepository.getInstance(context)
                mediaplayer.setEqualizer(repository.getCurrentEqualizer(context).getEqualizer())
            }
            mediaplayer.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
            mediaplayer.play()
        }
        val externalAudio = pendingExternalAudioUri
        // The media was just started: the companion must play, whatever the (not yet updated)
        // playback state says
        if (externalAudio !== null) startExternalAudio(externalAudio, time, play = true) else stopExternalAudio()
    }

    private fun resetPlaybackState(time: Long, duration: Long) {
        // safe to call updateProgress since the calling coroutine will be on the main dispatcher when used
        seekable = true
        pausable = true
        lastTime = time
        updateProgress(time, duration)
    }

    @MainThread
    fun restart() {
        val mp = mediaplayer
        val volume:Int? = if (!mp.isReleased) mp.volume else null
        // The companion player of the external audio was created on the libvlc instance of the
        // player that is released here: it is stopped, the next playback starts it again from
        // [pendingExternalAudioUri]
        stopExternalAudio()
        // Silence the old player: it is released on a background thread and would otherwise keep
        // playing, and rendering, on its own until it is freed
        if (!mp.isReleased && mp.hasMedia()) mp.stop()
        mediaplayer = newMediaPlayer()
        volume?.let {
            if (it > 100) {
                mediaplayer.volume = it
            }
        }
        release(mp)
    }

    fun setPosition(position: Float) {
        if (seekable && mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.position = position
        externalAudioPlayer?.let { if (!it.isReleased) it.position = position }
    }

    fun setTime(time: Long, fast:Boolean = false) {
        if (seekable && mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.setTime(time, fast)
        externalAudioPlayer?.let { if (!it.isReleased) it.setTime(time, fast) }
    }

    fun isPlaying() = playbackState == PlaybackStateCompat.STATE_PLAYING

    fun isPaused() = playbackState == PlaybackStateCompat.STATE_PAUSED

    fun isVideoPlaying() = !mediaplayer.isReleased && mediaplayer.vlcVout.areViewsAttached()

    fun canSwitchToVideo() = getVideoTracksCount() > 0

    fun getVideoTracksCount() = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getVideoTracksCount() else 0

    fun getVideoTracks(): Array<out VlcTrack> = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getAllVideoTracks() else emptyArray()

    fun getVideoTrack():String = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedVideoTrack()?.getId() ?: "-1" else "-1"

    fun getCurrentVideoTrack(): VlcTrack? = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedVideoTrack() else null

    fun getAudioTracksCount(): Int {
        var count = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getAudioTracksCount() else 0
        if (externalAudioPlayer !== null) count++
        return count
    }

    /**
     * Embedded audio tracks, plus a fake track representing the external audio file when the
     * companion player is active, so the selection can be shown (and switched back from) in the
     * audio tracks menu.
     */
    fun getAudioTracks(): Array<out VlcTrack>? {
        if (mediaplayer.isReleased || !mediaplayer.hasMedia()) return emptyArray()
        val tracks = mediaplayer.getAllAudioTracks()
        val external = pendingExternalAudioUri
        return if (externalAudioPlayer !== null && external !== null) tracks + ExternalAudioTrack(external) else tracks
    }

    fun getAudioTrack(): String {
        if (externalAudioPlayer !== null) return EXTERNAL_AUDIO_TRACK_ID
        return if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedAudioTrack()?.getId() ?: "-1" else "-1"
    }

    fun setVideoTrack(index: String) = !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setVideoTrack(index)

    fun setAudioTrack(index: String): Boolean {
        // Selecting the external audio entry keeps it as the active audio source and must never be
        // forwarded to the main player (its id is not a libvlc track index)
        if (index == EXTERNAL_AUDIO_TRACK_ID) return externalAudioPlayer !== null
        return !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setAudioTrack(index)
    }

    fun unselectTrackType(trackType: VideoTracksDialog.TrackType) {
        val vlcTrackType = when(trackType) {
            VideoTracksDialog.TrackType.VIDEO -> 1
            VideoTracksDialog.TrackType.AUDIO -> 0
            VideoTracksDialog.TrackType.SPU -> 2
        }
        if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.unselectTrackType(vlcTrackType)
    }

    fun setAudioDigitalOutputEnabled(enabled: Boolean) = !mediaplayer.isReleased && mediaplayer.setAudioDigitalOutputEnabled(enabled)

    fun getAudioDelay(): Long {
        val companion = externalAudioPlayer
        if (companion != null && !companion.isReleased) return externalAudioDelay
        return if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.audioDelay else 0L
    }

    fun getSpuDelay() = if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.spuDelay else 0L

    fun getRate() = if (mediaplayer.hasMedia() && !mediaplayer.isReleased && playbackState != PlaybackStateCompat.STATE_STOPPED) mediaplayer.rate else 1.0f

    fun setSpuDelay(delay: Long) = !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setSpuDelay(delay)

    fun setVideoTrackEnabled(enabled: Boolean) = mediaplayer.setVideoTrackEnabled(enabled)

    fun addSubtitleTrack(path: String, select: Boolean) = mediaplayer.addSlave(IMedia.Slave.Type.Subtitle, path, select)

    fun addSubtitleTrack(uri: Uri, select: Boolean) = mediaplayer.addSlave(IMedia.Slave.Type.Subtitle, uri, select)

    fun getSpuTracks(): Array<out VlcTrack>? = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getAllSpuTracks() else emptyArray()

    fun getSpuTrack() = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedSpuTrack()?.getId() ?: "-1" else "-1"

    fun setSpuTrack(index: String) = !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setSpuTrack(index)

    fun getSpuTracksCount() = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSpuTracksCount() else 0

    /**
     * Applies the audio delay to the companion player (when the external audio is playing) and to
     * the main player, so the delay is also in effect after switching back to the embedded audio.
     */
    fun setAudioDelay(delay: Long): Boolean {
        externalAudioDelay = delay
        val companion = externalAudioPlayer
        val appliedToCompanion = companion != null && !companion.isReleased && companion.setAudioDelay(delay)
        val appliedToMain = mediaplayer.hasMedia() && !mediaplayer.isReleased && mediaplayer.setAudioDelay(delay)
        return appliedToCompanion || appliedToMain
    }

    fun setEqualizer(equalizer: MediaPlayer.Equalizer?) = mediaplayer.setEqualizer(equalizer)

    @MainThread
    fun setVideoScale(scale: Float) {
        mediaplayer.scale = scale
    }

    fun setVideoAspectRatio(aspect: String?) {
        mediaplayer.aspectRatio = aspect
    }

    fun setRenderer(renderer: RendererItem?) {
        if (!mediaplayer.isReleased) mediaplayer.setRenderer(renderer)
        hasRenderer = renderer !== null
    }

    fun release(player: MediaPlayer = mediaplayer) {
        stopExternalAudio()
        player.setEventListener(null)
        if (isVideoPlaying()) player.vlcVout.detachViews()
        releaseMedia()
        launch(Dispatchers.IO) {
            if (BuildConfig.DEBUG) { // Warn if player release is blocking
                try {
                    withTimeout(5000) { player.release() }
                } catch (exception: TimeoutCancellationException) {
                    launch { Toast.makeText(context, "media stop has timeouted!", Toast.LENGTH_LONG).show() }
                }
            } else player.release()
        }
        setPlaybackStopped()
    }

    /**
     * Attaches the slaves of [mw] (medialibrary ones + the ones persisted in the database) to
     * [media]. This MUST happen before playback starts (i.e. before the media is set on the
     * player): slaves added at runtime with MediaPlayer.addSlave don't follow seeks — the master
     * input resets to 00:00 and stalls.
     *
     * Audio slaves are the exception: they are never attached to the media (that is what broke
     * seeking). Instead the persisted URI is kept in [pendingExternalAudioUri] so that
     * [startPlayback] can start the companion player. See [startExternalAudio].
     */
    suspend fun setSlaves(media: IMedia, mw: MediaWrapper) {
        if (mediaplayer.isReleased) return
        val slaves = mw.slaves
        slaves?.filter { it.type != IMedia.Slave.Type.Audio }?.forEach { media.addSlave(it) }
        val persisted = slaveRepository.getSlaves(mw.location)
        persisted.filter { it.type != IMedia.Slave.Type.Audio }.forEach { slave ->
            if (!slaves.contains(slave)) media.addSlave(slave)
        }
        pendingExternalAudioUri = persisted.firstOrNull { it.type == IMedia.Slave.Type.Audio }?.uri?.let { Uri.parse(it) }
        slaves?.let { slaveRepository.saveSlaves(mw) }
    }

    /**
     * Plays an external audio file in a companion player and mutes the embedded audio so only the
     * external file is audible.
     *
     * This replaces the libvlc audio slave mechanism, which cannot seek correctly. The companion
     * mirrors every transport change applied to [mediaplayer] (play, pause, seek, position, rate,
     * volume) so both stay in sync, and the embedded audio of the main player is muted so only the
     * external file is audible. Not available while casting, since a renderer is bound to a single
     * player.
     *
     * @param time the position, in milliseconds, the external file must start at.
     * @param play whether the companion starts playing straight away. It mirrors the main playback
     *   when the caller does not force it: the audio must not play on its own while the video is
     *   paused, otherwise it runs ahead of the video and both end up out of sync.
     */
    internal suspend fun startExternalAudio(uri: Uri, time: Long = getCurrentTime(), play: Boolean = shouldCompanionPlay()) {
        stopExternalAudio()
        if (mediaplayer.isReleased || hasRenderer) return
        pendingExternalAudioUri = uri
        val libVlc = VLCInstance.getInstance(context)
        // Callers may run on a background dispatcher (PlaybackService.launch uses Dispatchers.IO),
        // so the player is created on the main thread like every other player of the app
        val companion = withContext(Dispatchers.Main.immediate) {
            MediaPlayer(libVlc).apply {
                setAudioDigitalOutputEnabled(VLCOptions.isAudioDigitalOutputEnabled(settings))
                VLCOptions.getAout(settings)?.let { setAudioOutput(it) }
            }
        }
        val media = mediaFactory.getFromUri(libVlc, uri)
        media.addOption(":no-video")
        media.addOption(":no-spu")
        // LibVLC ignores MediaPlayer.setTime() before playback, :start-time is the workaround
        media.addOption(":start-time=${time / 1000L}")
        VLCOptions.setMediaOptions(media, context, MediaWrapper.MEDIA_FORCE_AUDIO, false)
        withContext(Dispatchers.IO) { if (!companion.isReleased) companion.media = media }
        media.release()
        val volume = externalAudioVolume ?: if (!mediaplayer.isReleased) mediaplayer.volume else 100
        externalAudioVolume = volume
        externalAudioPlayer = companion
        if (!companion.isReleased) {
            companion.setEventListener(externalAudioEventListener)
            companion.volume = volume
            companion.rate = mediaplayer.rate
            // Mirror the main playback: an external audio selected while the video is paused must
            // stay paused too, otherwise the audio plays on its own and runs ahead of the video
            if (play) companion.play()
            // The delay may have been requested before the companion existed (global/BT delay)
            companion.setAudioDelay(externalAudioDelay)
        }
        // The external file takes over: mute the embedded audio of the main player by volume,
        // so it works with both the VLC 3 and VLC 4 flavors
        if (!mediaplayer.isReleased) mediaplayer.setVolume(0)
    }

    /**
     * Tells whether the companion player of the external audio must be playing, i.e. whether the
     * main playback is neither paused nor stopped.
     *
     * [playbackState] is updated from the libvlc events, so it lags behind a `play()` call: a
     * playback that has just been started must be passed explicitly instead (see [startPlayback]).
     */
    private fun shouldCompanionPlay() = when (playbackState) {
        PlaybackStateCompat.STATE_PAUSED,
        PlaybackStateCompat.STATE_STOPPED,
        PlaybackStateCompat.STATE_NONE -> false
        else -> true
    }

    /**
     * Realigns the companion player of the external audio on the position of the main player.
     *
     * The companion is a full libvlc player: it has to open the file and create its audio output
     * before playing, which takes an unpredictable time, and its start position is only known to
     * the second, so it always ends up slightly behind the video (by a different amount each time).
     * Realigning it when it starts playing keeps both in sync. A seek costs a short flush, hence
     * the small tolerance below which the drift is left alone.
     */
    private fun syncExternalAudio() {
        val companion = externalAudioPlayer ?: return
        if (companion.isReleased || mediaplayer.isReleased) return
        val masterTime = mediaplayer.time
        if (masterTime <= 0L) return
        val companionTime = companion.time
        if (companionTime < 0L || (companionTime - masterTime).absoluteValue > EXTERNAL_AUDIO_SYNC_TOLERANCE)
            companion.setTime(masterTime, false)
    }

    /** Stops and releases the companion player used for the external audio, if any. */
    fun stopExternalAudio() {
        val companion = externalAudioPlayer ?: return
        externalAudioPlayer = null
        if (!mediaplayer.isReleased) mediaplayer.setVolume(externalAudioVolume ?: mediaplayer.volume)
        if (companion.isReleased) return
        companion.setEventListener(null)
        companion.stop()
        launch(Dispatchers.IO) { if (!companion.isReleased) companion.release() }
    }

    private fun newMediaPlayer() : MediaPlayer {
        return MediaPlayer(VLCInstance.getInstance(context)).apply {
            setAudioDigitalOutputEnabled(VLCOptions.isAudioDigitalOutputEnabled(settings))
            VLCOptions.getAout(settings)?.let { setAudioOutput(it) }
            setRenderer(PlaybackService.renderer.value)
            this.vlcVout.addCallback(this@PlayerController)
        }
    }

    override fun onSurfacesCreated(vlcVout: IVLCVout?) {}

    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
        switchToVideo = false
    }

    fun getCurrentTime() = progress.value?.time ?: 0L

    fun getLength() = progress.value?.length ?: 0L

    fun setRate(rate: Float, save: Boolean) {
        if (mediaplayer.isReleased) return
        mediaplayer.rate = rate
        externalAudioPlayer?.let { if (!it.isReleased) it.rate = rate }
        speed.postValue(rate)
    }

    /**
     * Update current media meta and return true if player needs to be updated
     *
     * @param id of the Meta event received, -1 for none
     * @return true if UI needs to be updated
     */
    internal fun updateCurrentMeta(id: Int, mw: MediaWrapper?): Boolean {
        if (id == IMedia.Meta.Publisher) return false
        mw?.updateMeta(mediaplayer)
        return id != IMedia.Meta.NowPlaying || mw?.nowPlaying !== null
    }

    /**
     * When changing current media, setPreviousStats is called to store statistics related to the
     * media. SetCurrentStats is called in the case where repeating is set to
     * PlaybackStateCompat.REPEAT_MODE_ONE, and the current media should not be released, as
     * it is still in use.
     */
    fun setCurrentStats() {
        val media = mediaplayer.media ?: return
        previousMediaStats = media.stats
    }

    fun setPreviousStats() {
        val media = mediaplayer.media ?: return
        previousMediaStats = media.stats
        media.release()
    }

    fun updateViewpoint(yaw: Float, pitch: Float, roll: Float, fov: Float, absolute: Boolean) = mediaplayer.updateViewpoint(yaw, pitch, roll, fov, absolute)

    fun navigate(where: Int) = mediaplayer.navigate(where)

    fun getChapters(title: Int): Array<out MediaPlayer.Chapter>? = if (!mediaplayer.isReleased) mediaplayer.getChapters(title) else emptyArray()

    fun getTitles(): Array<out MediaPlayer.Title>? = if (!mediaplayer.isReleased) mediaplayer.titles else emptyArray()

    fun getChapterIdx() = if (!mediaplayer.isReleased) mediaplayer.chapter else -1

    fun setChapterIdx(chapter: Int) {
        if (!mediaplayer.isReleased) mediaplayer.chapter = chapter
    }

    fun getTitleIdx() = if (!mediaplayer.isReleased) mediaplayer.title else -1

    fun setTitleIdx(title: Int) {
        if (!mediaplayer.isReleased)  mediaplayer.title = title
    }

    fun getVolume() = when {
        externalAudioPlayer !== null -> externalAudioVolume ?: 100
        !mediaplayer.isReleased -> mediaplayer.volume
        else -> 100
    }

    fun setVolume(volume: Int): Int {
        externalAudioVolume = volume
        val companion = externalAudioPlayer
        if (companion !== null && !companion.isReleased) companion.setVolume(volume)
        // The embedded audio is muted while the companion plays the external file
        if (!mediaplayer.isReleased) mediaplayer.setVolume(if (companion !== null) 0 else volume)
        return volume
    }

    suspend fun expand(): IMediaList? {
        return mediaplayer.media?.let {
            return withContext(playerContext) {
                mediaplayer.setEventListener(null)
                val items = it.subItems()
                it.release()
                mediaplayer.setEventListener(this@PlayerController)
                items
            }
        }
    }

    private var lastTime = 0L
    var lastPosition = 0F
    @OptIn(ObsoleteCoroutinesApi::class)
    private val eventActor = actor<MediaPlayer.Event>(capacity = Channel.UNLIMITED, start = CoroutineStart.UNDISPATCHED) {
        for (event in channel) {
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playbackState = PlaybackStateCompat.STATE_PLAYING
                    // The external audio must follow the video whenever it (re)starts playing, e.g.
                    // after a pause or a seek at open, so realign the companion on the new position
                    syncExternalAudio()
                }
                MediaPlayer.Event.Paused -> playbackState = PlaybackStateCompat.STATE_PAUSED
                MediaPlayer.Event.EncounteredError -> setPlaybackStopped()
                MediaPlayer.Event.PausableChanged -> pausable = event.pausable
                MediaPlayer.Event.SeekableChanged -> seekable = event.seekable
                MediaPlayer.Event.LengthChanged -> updateProgress(newLength = event.lengthChanged)
                MediaPlayer.Event.TimeChanged -> {
                    val time = event.timeChanged
                    if ((time - lastTime).absoluteValue > 950L) {
                        updateProgress(newTime = time)
                        lastTime = time
                    }
                }
                MediaPlayer.Event.PositionChanged -> {
                    lastPosition = event.positionChanged

                }
            }
            mediaplayerEventListener?.onEvent(event)
        }
    }

    @JvmOverloads
    fun updateProgress(newTime: Long = progress.value?.time ?: 0L, newLength: Long = progress.value?.length ?: 0L) {
        progress.value = progress.value?.apply { time = newTime; length = newLength }
    }

    override fun onEvent(event: MediaPlayer.Event?) {
        if (event != null) eventActor.trySend(event)
    }

    private fun setPlaybackStopped() {
        playbackState = PlaybackStateCompat.STATE_STOPPED
        updateProgress(0L, 0L)
        lastTime = 0L
    }

    //    private fun onPlayerError() {
//        launch(UI) {
//            restart()
//            Toast.makeText(context, context.getString(R.string.feedback_player_crashed), Toast.LENGTH_LONG).show()
//        }
//    }
    companion object {
        @Volatile var playbackState = PlaybackStateCompat.STATE_NONE
            private set
    }
}

const val NO_LENGTH_PROGRESS_MAX = 1000
class Progress(var time: Long = 0L, var length: Long = 0L)

/**
 * Drift, in milliseconds, tolerated between the external audio and the video before the companion
 * player is realigned on the main player. Realigning costs a short audio flush.
 */
private const val EXTERNAL_AUDIO_SYNC_TOLERANCE = 100L

/**
 * Id of the fake [VlcTrack] used to represent the external audio file in the audio tracks menu.
 * It is deliberately not numeric (VLC uses "-1" for "disable track" and "-2" as a UI sentinel).
 */
const val EXTERNAL_AUDIO_TRACK_ID = "external-audio"

/**
 * Fake [VlcTrack] exposing the external audio file in the audio tracks menu so the current
 * selection is visible. Selecting it goes through [PlayerController.setAudioTrack].
 */
class ExternalAudioTrack(private val uri: Uri) : VlcTrack {
    override fun getName() = Uri.decode(uri.lastPathSegment ?: uri.toString())

    override fun getId() = EXTERNAL_AUDIO_TRACK_ID

    override fun getWidth() = 0

    override fun getHeight() = 0

    override fun getProjection() = 0

    override fun getFrameRateDen() = 0

    override fun getFrameRateNum() = 0
}

internal interface MediaPlayerEventListener {
    suspend fun onEvent(event: MediaPlayer.Event)
}

private fun Array<IMedia.Slave>?.contains(item: IMedia.Slave) : Boolean {
    if (this == null) return false
    for (slave in this) if (slave.uri == item.uri) return true
    return false
}