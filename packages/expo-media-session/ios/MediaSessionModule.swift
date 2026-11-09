import ExpoModulesCore
import Foundation
import os

@Record
struct InterruptionEvent {
  var type: String
  var reason: String?
  var shouldResume: Bool?
}

@Record
struct RouteChangeEvent {
  var reason: String
}

@Record
struct ActivationErrorEvent {
  var message: String
  var client: String
}

@Record
struct NowPlayingOwnerChangeEvent {
  var id: String?
}

@ExpoModule("ExpoMediaSession", classes: [JSNowPlayingOwner.self])
public final class MediaSessionModule: Module {
  private let forwarder = EventForwarder()
  private let listening = OSAllocatedUnfairLock(uncheckedState: Listening())
  private let holds = OSAllocatedUnfairLock<[String: SessionHold]>(initialState: [:])
  private let owners = OSAllocatedUnfairLock(uncheckedState: NSHashTable<JSNowPlayingOwner>.weakObjects())

  @Event
  var onInterruption: (InterruptionEvent) -> Void

  @Event
  var onRouteChange: (RouteChangeEvent) -> Void

  @Event
  var onActivationError: (ActivationErrorEvent) -> Void

  @Event
  var onNowPlayingOwnerChange: (NowPlayingOwnerChangeEvent) -> Void

  @JS(.concurrent)
  func setAudioPolicyAsync(_ policy: AudioPolicyRecord) async throws {
    try MediaSessionManager.shared.setPolicy(policy.update)
  }

  @JS
  func getAudioPolicy() -> AudioPolicyRecord {
    AudioPolicyRecord(MediaSessionManager.shared.policy)
  }

  @JS
  func holdSession(_ key: String) {
    let hold = MediaSessionManager.shared.hold(client: key)
    let previous = holds.withLockUnchecked { $0.updateValue(hold, forKey: key) }
    previous?.release()
  }

  @JS
  func releaseSession(_ key: String) {
    let hold = holds.withLockUnchecked { $0.removeValue(forKey: key) }
    hold?.release()
  }

  @JS
  func getNowPlayingOwnerId() -> String? {
    MediaSessionManager.shared.nowPlayingOwnerID
  }

  @JS
  func createNowPlayingOwner(_ id: String, _ mediaType: String) -> JSNowPlayingOwner {
    let owner = JSNowPlayingOwner(id: id, mediaType: NowPlayingMediaType(rawValue: mediaType))
    owners.withLockUnchecked { $0.add(owner) }
    return owner
  }

  public override func didCreate() {
    forwarder.module = self
  }

  public override func didStartListening(event: String) {
    listening.withLockUnchecked { listening in
      listening.events.insert(event)
      if listening.registration == nil {
        listening.registration = MediaSessionManager.shared.addObserver(forwarder)
      }
    }
  }

  public override func didStopListening(event: String) {
    let removed = listening.withLockUnchecked { listening -> ObserverRegistration? in
      listening.events.remove(event)
      guard listening.events.isEmpty else {
        return nil
      }
      defer { listening.registration = nil }
      return listening.registration
    }
    removed?.remove()
  }

  public override func willDestroy() {
    let removed = listening.withLockUnchecked { listening in
      defer { listening = Listening() }
      return listening.registration
    }
    removed?.remove()
    let released = holds.withLockUnchecked { holds in
      defer { holds = [:] }
      return holds
    }
    for hold in released.values {
      hold.release()
    }
    let owners = owners.withLockUnchecked { owners in
      defer { owners.removeAllObjects() }
      return owners.allObjects
    }
    for owner in owners {
      owner.withdraw(.released)
    }
  }
}

private struct Listening {
  var events: Set<String> = []
  var registration: ObserverRegistration?
}

@Record
struct AudioPolicyRecord {
  var playsInSilentMode: Bool?
  var interruptionMode: InterruptionMode?
  var allowsRecording: Bool?
  var shouldRouteThroughEarpiece: Bool?
}

extension AudioPolicyRecord {
  init(_ policy: AudioPolicy) {
    self.init(
      playsInSilentMode: policy.playsInSilentMode,
      interruptionMode: policy.interruptionMode,
      allowsRecording: policy.allowsRecording,
      shouldRouteThroughEarpiece: policy.shouldRouteThroughEarpiece
    )
  }

  var update: AudioPolicyUpdate {
    AudioPolicyUpdate(
      playsInSilentMode: playsInSilentMode,
      interruptionMode: interruptionMode,
      allowsRecording: allowsRecording,
      shouldRouteThroughEarpiece: shouldRouteThroughEarpiece
    )
  }
}

@Record
struct NowPlayingInfoRecord {
  var title: String?
  var artist: String?
  var album: String?
  var artworkUrl: URL?
  var duration: Double?
  var elapsedTime: Double?
  var playbackRate: Double?
  var isLiveStream: Bool?
  var queueIndex: Int?
  var queueCount: Int?
  var skipForwardInterval: Double?
  var skipBackwardInterval: Double?
}

extension NowPlayingInfoRecord {
  var info: NowPlayingInfo {
    NowPlayingInfo(
      title: title,
      artist: artist,
      album: album,
      artworkURL: artworkUrl,
      duration: duration,
      elapsedTime: elapsedTime,
      playbackRate: playbackRate,
      isLiveStream: isLiveStream ?? false,
      queueIndex: queueIndex,
      queueCount: queueCount,
      skipForwardInterval: skipForwardInterval,
      skipBackwardInterval: skipBackwardInterval
    )
  }
}

@Record
struct RemoteCommandEvent {
  var kind: String
  var value: Double?
}

@SharedObject("NowPlayingOwner")
final class JSNowPlayingOwner: SharedObject, NowPlayingOwner {
  let nowPlayingID: String
  let mediaType: NowPlayingMediaType
  private let playing = OSAllocatedUnfairLock(initialState: false)
  private let claim = OSAllocatedUnfairLock<NowPlayingClaim?>(initialState: nil)

  @Event
  var onCommand: (RemoteCommandEvent) -> Void

  @Event
  var onPauseForTakeover: () -> Void

  @Event
  var onResumeAfterTakeover: () -> Void

  init(id: String, mediaType: NowPlayingMediaType) {
    nowPlayingID = id
    self.mediaType = mediaType
  }

  var isPlaying: Bool {
    playing.withLock { $0 }
  }

  @JS
  func offer(_ info: NowPlayingInfoRecord, _ commands: [String], _ isPlaying: Bool) {
    playing.withLock { $0 = isPlaying }
    let offered = MediaSessionManager.shared.offerNowPlaying(self, info: info.info, commands: commandKinds(commands))
    let previous = claim.withLockUnchecked { claim in
      defer { claim = offered }
      return claim
    }
    previous?.withdraw(.released)
  }

  @JS
  func update(_ info: NowPlayingInfoRecord, _ commands: [String]?, _ isPlaying: Bool) {
    playing.withLock { $0 = isPlaying }
    claim.withLockUnchecked { $0 }?.update(info.info, commands: commands.map(commandKinds))
  }

  @JS
  func withdraw(_ reason: String) {
    withdraw(WithdrawReason(rawValue: reason))
  }

  func withdraw(_ reason: WithdrawReason) {
    let removed = claim.withLockUnchecked { claim in
      defer { claim = nil }
      return claim
    }
    removed?.withdraw(reason)
  }

  func handle(_ command: RemoteCommand) -> Bool {
    onCommand(RemoteCommandEvent(kind: command.kind.rawValue, value: command.value))
    return true
  }

  override func sharedObjectDidRelease() {
    withdraw(.released)
  }

  func pauseForTakeover() {
    onPauseForTakeover()
  }

  func resumeAfterTakeover() {
    onResumeAfterTakeover()
  }

  private func commandKinds(_ commands: [String]) -> Set<RemoteCommandKind> {
    Set(commands.map(RemoteCommandKind.init))
  }
}

private final class EventForwarder: MediaSessionObserver {
  weak var module: MediaSessionModule?

  func mediaSessionInterruptionBegan(reason: InterruptionReason) {
    module?.onInterruption(InterruptionEvent(type: "began", reason: reason.rawValue))
  }

  func mediaSessionInterruptionEnded(shouldResume: Bool) {
    module?.onInterruption(InterruptionEvent(type: "ended", shouldResume: shouldResume))
  }

  func mediaSessionRouteChanged(reason: RouteChangeReason) {
    module?.onRouteChange(RouteChangeEvent(reason: reason.rawValue))
  }

  func mediaSession(didFail error: Error, client: String) {
    let message = (error as? Exception)?.description ?? error.localizedDescription
    module?.onActivationError(ActivationErrorEvent(message: message, client: client))
  }

  func mediaSessionNowPlayingOwnerChanged(id: String?) {
    module?.onNowPlayingOwnerChange(NowPlayingOwnerChangeEvent(id: id))
  }
}
