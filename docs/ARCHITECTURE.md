# Architecture

Rozan is two Android apps built from one Gradle project. Both embed the same
AirPlay receiver; the launcher adds a home screen around it.

```
 :app:launcher ─┐                       ┌─ :app:airplay
                ├── :airplay:android ───┤
 :ui ───────────┘          │            └─ :ui
                           │
          ┌────────────────┼──────────────────┐
     :airplay:core   :airplay:fairplay   :airplay:alac      (pure Kotlin/JVM)
                           │
                    :tools:devserver  (desktop JVM harness, not shipped)
                    :airplay:testing  (test sender for end-to-end tests, not shipped)
```

## Layers

### `:airplay:core` (JVM)

Everything about the AirPlay protocol that does not need Android:

- `plist`, `tlv`, `rtsp`: wire formats (binary/XML plists, TLV8, RTSP/HTTP
  framing). Hardened parsers with fixed limits.
- `crypto`: SRP-6a, X25519/Ed25519 (BouncyCastle lightweight API), AES
  (CTR/CBC/GCM via JCA so Android uses hardware AES), ChaCha20-Poly1305, HKDF.
- `time`: NTP and RTP timestamp arithmetic.
- `discovery`: builds the `_airplay._tcp` and `_raop._tcp` service records
  (name, port, TXT). Publishing them is the platform's job.
- `session`: one `ControlConnection` per TCP client on the control port; it
  routes requests (`/info`, pairing, `/fp-setup`, `SETUP`, `RECORD`,
  `GET/SET_PARAMETER`, `FLUSH`, `TEARDOWN`, `/feedback`) through a state
  machine that refuses media setup until PIN pairing has succeeded.
- `pairing`: legacy pair-setup/pair-verify and PIN pairing. A fresh random
  PIN per attempt; no client keys are stored, ever.
- `mirror`: the mirroring TCP stream (packet header parsing, AES-CTR
  decryption, codec configuration), producing access units for a `VideoSink`.
- `audio`: RTP over UDP, decryption, reordering/jitter buffer, retransmit
  requests, timing (NTP) client, producing access units for an `AudioSink`.

### `:airplay:fairplay` and `:airplay:alac` (JVM)

Kept separate from core: FairPlay is implemented from
`docs/protocol/04-fairplay.md`, ALAC is a port of Apple's Apache-2.0
reference decoder.

### `:airplay:testing` (JVM, tests only)

A scripted sender that plays a Mac or iPhone against a running receiver at a
known address, written from `docs/protocol/` like the receiver: PIN pairing
(the PIN comes from a callback), pair-verify, FairPlay v2 from the sender
side, the SETUPs, an Annex-B clip as encrypted mirroring records, ALAC audio
over RTP with sync packets and an NTP responder, volume, metadata and
TEARDOWN. `:airplay:core`'s end-to-end test uses it in process;
`:airplay:android`'s `SenderEndToEndTest` uses it against the real service on
the emulator over 127.0.0.1:7000, reading the PIN off the overlay.

### `:airplay:android`

Binds the receiver to the device. The service, the session screens and the
boot receiver run in the app's own `:airplay` process, so a receiver crash
never takes down the process of the launcher (on Android 11 one crash of the
default home app's process hands Home back to the stock launcher).

- `ReceiverService`: foreground service that owns the `AirPlayReceiver`
  (FairPlay from `:airplay:fairplay`, diagnostics to Logcat), publishes the
  service records with `NsdManager`, holds a multicast lock, and (with "wake
  on connect") a partial wake lock and Wi-Fi lock so the receiver stays
  reachable while the screen is off. If another app holds port 7000 the
  service keeps running, the status screen says so, and the next
  `ReceiverService.start` tries again.
- PIN overlay: a `TYPE_APPLICATION_OVERLAY` window drawn over whatever app is
  in front (needs the `SYSTEM_ALERT_WINDOW` app-op, granted over adb during
  install). Without the permission it falls back to an activity.
- `MirrorActivity`: full-screen `SurfaceView`; H.264/HEVC goes straight from
  `MediaCodec` to the surface (hardware overlay, zero copies). It is started
  over other apps once pairing succeeds. Frames that arrive before the surface
  exists wait in a bounded buffer (codec configuration plus everything since
  the last key frame); the decoder is only ever configured with the real
  surface, never switched with `setOutputSurface` (broken on several Fire TV
  models).
- Audio: AAC/AAC-ELD through `MediaCodec`, ALAC through `:airplay:alac`, PCM
  out through `AudioTrack`.
- Wake: when a sender asks for a PIN while the screen is off and
  wake-on-connect is on, show the PIN in `PinActivity`, whose `setTurnScreenOn`
  wakes the device, and also hold a screen wake lock with `ACQUIRE_CAUSES_WAKEUP`
  (the API 31 Android TV image does not wake for the lock alone; the lock covers
  devices where the activity cannot be started). HDMI-CEC then turns the TV on. A connection alone does not
  wake the screen: any host can open one, and Apple senders probe receivers
  with `GET /info` while their AirPlay menu is open.
- `AirPlaySettings`: enabled, receiver name, wake on connect. Shared between
  the UI process and `:airplay` as a small file in `noBackupFilesDir/airplay/`
  (atomic rename, cross-process file lock, `FileObserver` change events), not
  SharedPreferences, which caches per process. `ReceiverStatus` publishes the
  receiver's state back to status screens the same way.

### Apps

- `:app:launcher`: home screen (backdrop crossfade, alphabetical app grid with
  built-ins first, settings, screensaver, keep-awake) plus the receiver.
- `:app:airplay`: a small status/settings screen plus the receiver.

## Core ↔ platform contract

`:airplay:core` exposes one entry point and a handful of interfaces that the
Android layer (and the desktop dev server) implement. Names are normative;
exact signatures live in KDoc.

```kotlin
class AirPlayReceiver(config: ReceiverConfig, platform: ReceiverPlatform) {
    fun start()                  // binds sockets, returns the records to publish
    fun stop()
}

class ReceiverConfig(
    name: String,                // shown in the AirPlay menu
    deviceId: ByteArray,         // 6 bytes, stable per install (MAC-like)
    identity: Ed25519 key pair,  // stable per install, our own identity only
    display: DisplayInfo,        // width, height, refresh rate, for /info
)

interface ReceiverPlatform {
    fun publish(records: List<ServiceRecord>)      // mDNS
    fun unpublish()
    fun createVideoSink(): VideoSink
    fun createAudioSink(format: AudioFormat): AudioSink
    val events: ReceiverEvents
}

interface ReceiverEvents {
    fun onConnectionStarted(client: ClientInfo)    // any host, before pairing
    fun onPinRequired(pin: String, client: ClientInfo)
    fun onPinFinished(result: PinResult)           // success, failure, cancelled
    fun onMirroringStarted(client: ClientInfo)
    fun onMirroringStopped()
    fun onAudioStarted(client: ClientInfo)
    fun onAudioStopped()
    fun onVolumeChanged(linear: Float)
    fun onNowPlaying(metadata: NowPlaying?)        // title/artist/album/artwork when sent
}

interface VideoSink {
    fun configure(codec: VideoCodec, parameterSets: ByteArray, width: Int, height: Int)
    fun submit(accessUnit: ByteArray, offset: Int, length: Int, presentationNanos: Long)
    fun close()
}

interface AudioSink {
    fun submit(accessUnit: ByteArray, offset: Int, length: Int, presentationNanos: Long)
    fun setVolume(linear: Float)
    fun flush()
    fun close()
}
```

`presentationNanos` is on the local monotonic clock (`System.nanoTime()`),
already mapped from the sender's clock by the timing client.

## Threads

Blocking sockets on dedicated, named threads; closing a socket stops its
thread. One thread per control connection, one for the mirroring stream, one
each for the audio data, control and timing sockets. Hot paths reuse buffers
and never allocate per packet. Callbacks into `ReceiverEvents` happen on
receiver threads; the Android layer posts to the main thread.

## Performance rules

- Video never touches the Java heap after decryption: decrypt in place into a
  reusable buffer, copy once into the `MediaCodec` input buffer, render with
  `releaseOutputBuffer(index, timestamp)` to the `SurfaceView`.
- UI animations change only RenderNode properties; bitmaps are hardware
  bitmaps decoded at display size.
- Startup work happens off the main thread; the home grid appears before the
  backdrop.
