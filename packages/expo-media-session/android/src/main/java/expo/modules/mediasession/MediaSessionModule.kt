package expo.modules.mediasession

import expo.modules.kotlin.exception.CodedException
import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.react.androidContext
import io.github.expo.modules.v2.react.androidContextOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap

private const val INTERRUPTION = "interruption"
private const val ROUTE_CHANGE = "routeChange"
private const val ACTIVATION_ERROR = "activationError"
private const val NOW_PLAYING_OWNER_CHANGE = "nowPlayingOwnerChange"

@Record
internal data class InterruptionEvent(
  val type: String,
  val reason: String? = null,
  val shouldResume: Boolean? = null
)

@Record
internal data class RouteChangeEvent(val reason: String)

@Record
internal data class ActivationErrorEvent(val message: String, val client: String)

@Record
internal data class NowPlayingOwnerChangeEvent(val id: String?)

@ExpoModule("ExpoMediaSession", classes = [JSNowPlayingOwner::class])
class MediaSessionModule : Module() {
  private val listeningLock = Any()
  private val listening = mutableSetOf<String>()
  private var registration: ObserverRegistration? = null
  private val holds = mutableMapOf<String, SessionHold>()
  private val owners: MutableSet<JSNowPlayingOwner> = Collections.newSetFromMap(WeakHashMap())

  private val manager: MediaSessionManager
    get() = MediaSessionManager.get(androidContext)

  @Event(name = INTERRUPTION)
  private val onInterruption = event<InterruptionEvent>(
    onStartObserving = { startObserving(INTERRUPTION) },
    onStopObserving = { stopObserving(INTERRUPTION) }
  )

  @Event(name = ROUTE_CHANGE)
  private val onRouteChange = event<RouteChangeEvent>(
    onStartObserving = { startObserving(ROUTE_CHANGE) },
    onStopObserving = { stopObserving(ROUTE_CHANGE) }
  )

  @Event(name = ACTIVATION_ERROR)
  private val onActivationError = event<ActivationErrorEvent>(
    onStartObserving = { startObserving(ACTIVATION_ERROR) },
    onStopObserving = { stopObserving(ACTIVATION_ERROR) }
  )

  @Event(name = NOW_PLAYING_OWNER_CHANGE)
  private val onNowPlayingOwnerChange = event<NowPlayingOwnerChangeEvent>(
    onStartObserving = { startObserving(NOW_PLAYING_OWNER_CHANGE) },
    onStopObserving = { stopObserving(NOW_PLAYING_OWNER_CHANGE) }
  )

  private val forwarder = object : MediaSessionObserver {
    override fun mediaSessionInterruptionBegan(reason: InterruptionReason) {
      onInterruption(InterruptionEvent(type = "began", reason = reason.raw))
    }

    override fun mediaSessionInterruptionEnded(shouldResume: Boolean) {
      onInterruption(InterruptionEvent(type = "ended", shouldResume = shouldResume))
    }

    override fun mediaSessionRouteChanged(reason: RouteChangeReason) {
      onRouteChange(RouteChangeEvent(reason.raw))
    }

    override fun mediaSessionDidFail(error: CodedException, client: String) {
      val message = error.message
        ?: "$client could not activate the media session for an unknown reason. Check Logcat for the error"
      onActivationError(ActivationErrorEvent(message, client))
    }

    override fun mediaSessionNowPlayingOwnerChanged(id: String?) {
      onNowPlayingOwnerChange(NowPlayingOwnerChangeEvent(id))
    }
  }

  @JS
  private suspend fun setAudioPolicyAsync(update: AudioPolicyRecord): Unit = withContext(Dispatchers.Default) {
    manager.setPolicy(update.toUpdate())
  }

  @JS
  private fun getAudioPolicy(): AudioPolicyRecord = manager.policy.toRecord()

  @JS
  fun holdSession(key: String) {
    val hold = manager.hold(key)
    synchronized(holds) { holds.put(key, hold) }?.release()
  }

  @JS
  fun releaseSession(key: String) {
    synchronized(holds) { holds.remove(key) }?.release()
  }

  @JS
  fun getNowPlayingOwnerId(): String? = manager.nowPlayingOwnerId

  @JS
  private fun createNowPlayingOwner(id: String, mediaType: String): JSNowPlayingOwner =
    JSNowPlayingOwner(
      manager,
      id,
      if (mediaType == "video") {
        NowPlayingMediaType.VIDEO
      } else {
        NowPlayingMediaType.AUDIO
      }
    )
      .also { owner -> synchronized(owners) { owners.add(owner) } }

  override fun onDestroy() {
    val removed = synchronized(listeningLock) {
      listening.clear()
      registration.also { registration = null }
    }
    removed?.remove()
    val released = synchronized(holds) {
      holds.values.toList().also { holds.clear() }
    }
    released.forEach { it.release() }
    val liveOwners = synchronized(owners) {
      owners.toList().also { owners.clear() }
    }
    liveOwners.forEach { it.withdraw(WithdrawReason.RELEASED) }
  }

  private fun startObserving(event: String) {
    val context = androidContextOrNull ?: return
    synchronized(listeningLock) {
      listening.add(event)
      if (registration == null) {
        registration = MediaSessionManager.get(context).addObserver(forwarder)
      }
    }
  }

  private fun stopObserving(event: String) {
    val removed = synchronized(listeningLock) {
      listening.remove(event)
      if (listening.isEmpty()) {
        registration.also { registration = null }
      } else {
        null
      }
    }
    removed?.remove()
  }
}

@Record
internal data class AudioPolicyRecord(
  val playsInSilentMode: Boolean? = null,
  val interruptionMode: InterruptionMode? = null,
  val allowsRecording: Boolean? = null,
  val shouldRouteThroughEarpiece: Boolean? = null
) {
  fun toUpdate() = AudioPolicyUpdate(
    playsInSilentMode = playsInSilentMode,
    interruptionMode = interruptionMode,
    allowsRecording = allowsRecording,
    shouldRouteThroughEarpiece = shouldRouteThroughEarpiece
  )
}

internal fun AudioPolicy.toRecord() = AudioPolicyRecord(
  playsInSilentMode = playsInSilentMode,
  interruptionMode = interruptionMode,
  allowsRecording = allowsRecording,
  shouldRouteThroughEarpiece = shouldRouteThroughEarpiece
)

@Record
internal data class NowPlayingInfoRecord(
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val artworkUrl: String? = null,
  val duration: Double? = null,
  val elapsedTime: Double? = null,
  val playbackRate: Double? = null,
  val isLiveStream: Boolean? = null,
  val queueIndex: Int? = null,
  val queueCount: Int? = null,
  val skipForwardInterval: Double? = null,
  val skipBackwardInterval: Double? = null
) {
  fun toInfo() = NowPlayingInfo(
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    duration = duration,
    elapsedTime = elapsedTime,
    playbackRate = playbackRate,
    isLiveStream = isLiveStream ?: false,
    queueIndex = queueIndex,
    queueCount = queueCount,
    skipForwardInterval = skipForwardInterval,
    skipBackwardInterval = skipBackwardInterval
  )
}

@Record
internal data class RemoteCommandEvent(val kind: String, val value: Double? = null)

@ExpoSharedObject(name = "NowPlayingOwner")
internal class JSNowPlayingOwner(
  private val manager: MediaSessionManager,
  override val nowPlayingId: String,
  override val mediaType: NowPlayingMediaType
) : SharedObject(), NowPlayingOwner {
  @Volatile
  override var isPlaying: Boolean = false
    private set

  private var claim: NowPlayingClaim? = null

  @Event(name = "command")
  private val onCommand = event<RemoteCommandEvent>()

  @Event(name = "pauseForTakeover")
  private val onPauseForTakeover = event<Unit>()

  @Event(name = "resumeAfterTakeover")
  private val onResumeAfterTakeover = event<Unit>()

  @JS
  fun offer(info: NowPlayingInfoRecord, commands: List<String>, isPlaying: Boolean) {
    this.isPlaying = isPlaying
    val offered = manager.offerNowPlaying(this, info.toInfo(), commandKinds(commands))
    val previous = synchronized(this) { claim.also { claim = offered } }
    previous?.withdraw(WithdrawReason.RELEASED)
  }

  @JS
  fun update(info: NowPlayingInfoRecord, commands: List<String>?, isPlaying: Boolean) {
    this.isPlaying = isPlaying
    synchronized(this) { claim }?.update(info.toInfo(), commands?.let(::commandKinds))
  }

  @JS(name = "withdraw")
  fun withdrawFromJS(reason: String) {
    withdraw(
      if (reason == "ended") {
        WithdrawReason.ENDED
      } else {
        WithdrawReason.RELEASED
      }
    )
  }

  fun withdraw(reason: WithdrawReason) {
    synchronized(this) { claim.also { claim = null } }?.withdraw(reason)
  }

  override fun handle(command: RemoteCommand): Boolean {
    onCommand(RemoteCommandEvent(command.kind.raw, command.value))
    return true
  }

  override fun sharedObjectDidRelease() {
    withdraw(WithdrawReason.RELEASED)
    super.sharedObjectDidRelease()
  }

  override fun pauseForTakeover() {
    onPauseForTakeover(Unit)
  }

  override fun resumeAfterTakeover() {
    onResumeAfterTakeover(Unit)
  }

  private companion object {
    val knownCommandKinds = listOf(
      RemoteCommandKind.PLAY,
      RemoteCommandKind.PAUSE,
      RemoteCommandKind.TOGGLE_PLAY_PAUSE,
      RemoteCommandKind.SEEK,
      RemoteCommandKind.SKIP_FORWARD,
      RemoteCommandKind.SKIP_BACKWARD,
      RemoteCommandKind.NEXT_TRACK,
      RemoteCommandKind.PREVIOUS_TRACK
    ).associateBy { it.raw }

    fun commandKinds(raw: List<String>) = raw.mapNotNull { knownCommandKinds[it] }.toSet()
  }
}
