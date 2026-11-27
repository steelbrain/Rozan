# 03 — Media streams: SETUP, keys, stream control

This is the functional specification for the media side of the Rozan AirPlay
receiver: what the receiver must do once discovery (01), RTSP session, pairing
(02) and FairPlay (04) have produced their outputs. It is written for engineers
implementing in Kotlin (JVM plus Android `MediaCodec`/`AudioTrack`). The spec
is split into four files:

| File | Content |
|---|---|
| `03-media-streams.md` (this file) | Conventions, session flows, §1 SETUP, §2 key derivation, §6 volume/FLUSH/TEARDOWN/feedback/reconnect, §7 what to advertise, §8 bounds, implementation checklist, UNVERIFIED list, sources |
| `03b-mirroring-video.md` | §3 the mirroring TCP stream (128-byte header, codec config, H.264 framing, AES-CTR use) |
| `03c-audio-rtp-and-timing.md` | §4 audio RTP, control port, timing port, codecs, playout; §5 mirroring A/V sync |
| `03d-media-test-vectors.md` | Worked test vectors for every derivation and layout |

Target clients: macOS 15/26 and iOS/iPadOS 18/26, using the "legacy pairing +
FairPlay" path. HomeKit pairing and its ChaCha20-Poly1305 media encryption are
a different path that this receiver does not take; §7 says how to keep clients
off it.

## 0. Conventions

- Offsets are zero-based byte offsets. `u16be`/`u32be`/`u64be` are big-endian
  unsigned integers, `u32le`/`u64le` little-endian. `f32le` is an IEEE-754
  single-precision float stored little-endian.
- **NTP format** means a 64-bit Q32.32 fixed-point value: the high 32 bits are
  whole seconds and the low 32 bits are the fraction (units of 2^-32 s).
  Converting to nanoseconds: `ns = seconds * 1_000_000_000 + (fraction * 1_000_000_000) >>> 32`,
  computed in 64-bit (or 128-bit) unsigned arithmetic. The intermediate product
  `fraction * 1e9` fits in 62 bits.
- "Client NTP domain": timestamps the client puts in timing replies and audio
  sync packets. Their seconds field is the client's own epoch (observed:
  seconds since the client booted) **plus 2,208,988,800** (the 1900→1970
  offset). Video packet timestamps use the same clock **without** that
  offset (see 03b §3.5).
- `‖` means byte concatenation. `X[0..16)` means the first 16 bytes of X.
- `bplist` means an Apple binary property list (`bplist00`); the essentials
  are in 01 §3.5.1. Every SETUP/TEARDOWN body and every SETUP response in this
  file is a bplist sent with `Content-Type: application/x-apple-binary-plist`.
- Every request here arrives on the RTSP control connection, which stays
  plaintext in this profile (01 §3.6, 02 §4.3): pair-verify does not encrypt
  the channel; its shared secret is only mixed into the media key (§2).
- "UNVERIFIED:" marks a statement that no reference confirms on current
  clients. Each one says what would confirm it. All of them are collected at
  the end of this file.

### 0.1 Inputs from other specs

| Name | Size | Produced by | Meaning |
|---|---|---|---|
| `fairplay_key` | 16 bytes | 04 (FairPlay), from SETUP `ekey` | The AES key the client wrapped with FairPlay |
| `ecdh_secret` | 32 bytes | 02 (pair-verify) | The raw X25519 shared secret computed in pair-verify on **this RTSP connection**, **before** any hashing or HKDF. It is absent when the client did not run pair-verify, for example in 01's "Mode D" with features bit 27 off. |
| client IP | — | RTSP connection | Media sockets accept datagrams and connections only from this address, and the receiver sends only to it |

FairPlay v2 (used by macOS Music) and FairPlay v3 both produce the same
16-byte `fairplay_key`. Nothing in this file depends on the FairPlay version.

### 0.2 Session flows

A **mirroring** session, in the order public captures show.
The client may change the order of RECORD and the audio SETUP; accept any order.

```
client                                         receiver
SETUP  {ekey, eiv, timingPort, isScreenMirroringSession=true, ...}
                                     <-- 200 {timingPort, eventPort=0}
       (receiver starts sending NTP requests to client's timingPort)
SETUP  {streams:[{type:110, streamConnectionID, ...}]}
                                     <-- 200 {streams:[{type:110, dataPort}]}
TCP connect to dataPort; 128-byte-header packets start (03b)
RECORD                               <-- 200  Audio-Latency: 11025
SETUP  {streams:[{type:96, ct:8, spf:480, controlPort, ...}]}
                                     <-- 200 {streams:[{type:96, dataPort, controlPort}]}
UDP RTP audio to dataPort, sync packets to controlPort (03c)
GET_PARAMETER volume / SET_PARAMETER volume
POST /feedback  (every ~2 s)         <-- 200
TEARDOWN {streams:[{type:96}]}       <-- 200
TEARDOWN {streams:[{type:110}]}      <-- 200   (may be skipped by new iOS)
TEARDOWN (no streams)                <-- 200   end of session
```

An **audio-only** session (for example the Music app):

```
SETUP  {ekey, eiv, timingPort, ...}   (isScreenMirroringSession false or absent)
                                     <-- 200 {timingPort, eventPort=0}
SETUP  {streams:[{type:96, ct:2, spf:352, audioFormat:0x40000, controlPort,
                  latencyMin:11025, latencyMax:88200, ...}]}
                                     <-- 200 {streams:[{type:96, dataPort, controlPort}]}
RECORD                               <-- 200  Audio-Latency: 11025
SET_PARAMETER volume, FLUSH (RTP-Info), SET_PARAMETER metadata/artwork/progress
POST /feedback (every ~2 s)
TEARDOWN {streams:[{type:96}]}, then TEARDOWN (no streams)
```

A **classic RAOP** session (macOS Music, 01 §4.3.2, §6.8.1, §6.17; §1.6
below). Observed 2026-11-26, Music/1.7, macOS 27.0.1, up to the SETUP; the
rest as [NTO] and [SPEC] describe iTunes:

```
ANNOUNCE  SDP {AppleLossless, fmtp 352 0 16 40 10 14 2 255 0 0 44100, fpaeskey, aesiv}
                                     <-- 401 Digest challenge, then 200 to the retry (02 §6.4)
SETUP  (no body) Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;
                            control_port=<c>;timing_port=<t>
                                     <-- 200 Transport: RTP/AVP/UDP;unicast;mode=record;
                                         server_port=<data>;control_port=<control>;timing_port=<timing>
                                         Session: 1
       (receiver starts sending NTP requests to the client's timing_port)
RECORD (RTP-Info)                    <-- 200  Audio-Latency: 11025
UDP RTP audio to server_port, sync packets to control_port (03c)
SET_PARAMETER volume, DMAP metadata, artwork, progress; FLUSH (RTP-Info)
TEARDOWN (no body)                   <-- 200   end of session
```

One RTSP connection can switch modes. To leave mirroring the client tears down
streams 110 and 96, then sets up a new type-96 ALAC stream. To return to
mirroring it sets up 110 and an AAC-ELD 96 again.

---

## 1. SETUP

Derived from: SteeBono airplayreceiver wiki captures, java-airplay
`RtspMediaSetup.java`, `AudioStreamInfo.java`, emanuelecozzi.net AirPlay 2
RTSP notes.

### 1.1 Request classification

A SETUP body is a bplist dictionary. Process it in this order:

1. If the dictionary has **both** `ekey` and `eiv` of type *data*, it is the
   **session SETUP**. Run §1.2.
2. If it has `streams` of type *array*, run §1.3 for each element in array
   order. Collect one response element per accepted stream.
3. A body can in principle contain both. Do the session part first, then the
   streams. The response dictionary merges both results.

If the body is empty or not a dictionary, or `streams` is present but not an
array, respond `400 Bad Request`.

### 1.2 Session SETUP (first SETUP)

Request keys. Ignore any key not listed. Clients add keys between versions.

| Key | bplist type | Example | Required | Use |
|---|---|---|---|---|
| `ekey` | data, exactly 72 bytes | begins `46 50 4C 59` ("FPLY"), base64 `RlBMWQECAQAAAAA8…` | yes | Pass to 04 to obtain `fairplay_key` |
| `eiv` | data, exactly 16 bytes | base64 `Bp5tRB8BOW/MWVJSGzALLw==` | yes | IV for audio AES-CBC (§2.2) |
| `et` | integer | `32` | no | Ignore. UNVERIFIED: likely an encryption-type bitmask (32 = bit 5, which matches RAOP `et` value 5 "FairPlay SAPv2.5"). Confirm in a capture from a receiver that advertises a different `et` list. |
| `timingProtocol` | string | `"NTP"` | no | Must be `"NTP"` (we never advertise PTP). If it is `"PTP"`, the client expects PTP timing, which is unsupported: log it and continue without timing. If it is absent, it is an old client with no NTP offset: run the timing client anyway if `timingPort` is set. |
| `timingPort` | integer 1..65535 | `60373` | normally | Client UDP port for NTP requests (03c §4.6). If it is 0 or absent, the client may be using the AirPlay 2 "remote control" protocol. Log it and continue without timing. |
| `isScreenMirroringSession` | boolean | `true` | no | `true` means a mirroring session. Absent or `false` means audio-only. Use it only for UI and policy, never to choose keys. |
| `isRemoteControlOnly` | boolean | `true` | no | If true, the client wants an AirPlay 2 remote-control-only session, which is unsupported. Log it and continue. |
| `deviceID` | string | `"DC:0C:5C:B7:D6:DA"` | no | Client identity, used by 02 for registration and authorisation |
| `sessionUUID`, `macAddress`, `model`, `name`, `osName`, `osVersion`, `osBuildVersion`, `sourceVersion`, `sessionCorrelationUUID`, `statsCollectionEnabled` | string/bool | `"iPhone OS"`, `"16A404"`, `"371.4.7"` | no | Informational |

Receiver actions, in order:

0. Access control comes first and belongs to 01/02. In 01's Mode D the first
   session SETUP is answered `401` with a Digest challenge and retried with
   `Authorization`. Run the steps below only on the SETUP that passes
   authorisation, and do nothing media-related for a rejected one.
1. Validate the sizes. If `ekey` is not exactly 72 bytes or `eiv` is not exactly
   16 bytes, respond `400 Bad Request`.
2. `fairplay_key = FairPlayDecrypt(ekey)` (04). On failure respond `400`.
3. Derive `session_key` (§2.1) and keep it with `eiv` for the RTSP connection.
4. Open one UDP socket on an ephemeral port: the **timing socket**. Start the NTP
   client toward `client_ip:timingPort` (03c §4.6) and send the first request
   right away.
5. Respond `200 OK` with the bplist body below.

Response:

| Key | Type | Value |
|---|---|---|
| `timingPort` | integer | Local port of the timing socket |
| `eventPort` | integer | `0`. The event channel is not used in mirroring or realtime audio, and current clients accept 0. |

A second session SETUP on the same connection replaces `ekey`/`eiv` and
restarts the timing client. Streams that are already running keep the keys
they were created with.

### 1.3 Stream SETUP

Each element of `streams` is a dictionary with an integer `type`:

| `type` | Meaning | This receiver |
|---|---|---|
| 110 | Screen-mirroring video (TCP) | **Required** (§1.3.1) |
| 96 | Realtime audio (UDP RTP) | **Required** (§1.3.2) |
| 103 | Buffered audio (AirPlay 2, TCP, ChaCha20-Poly1305, PTP) | **Not needed.** Clients ask for it only when the receiver advertises features bit 40. See §1.4. |
| 120 | Playback (HLS/URL video) | Not in scope |
| 130 | Remote control | Not in scope |

For any type other than 96 or 110, leave it out of the response and respond
`400 Bad Request` if no stream in the request was accepted. UNVERIFIED:
whether a status code makes the client fall back cleanly. It should not
matter while bit 40 stays off.

#### 1.3.1 Type 110: mirroring video

Request keys:

| Key | Type | Example | Use |
|---|---|---|---|
| `type` | integer | `110` | |
| `streamConnectionID` | integer, 64-bit | `4964383553955644435`, `1946811920699902152` | Input to the video key derivation (§2.3). **Unsigned semantics**, see the note below. Required. Respond `400` if it is missing. |
| `timestampInfo` | array of dict `{name: string}` | names `SubSu`, `BePxT`, `AfPxT`, `BefEn`, `EmEnc` | Ignore |
| `latencyMs` | integer | `40`, `90` | Ignore. UNVERIFIED: whether it is a hint for the video playout delay. Confirm by correlating it with the measured client-side delay. |
| anything else | | | Ignore |

**streamConnectionID encoding.** The value is a random 64-bit number and about
half of all values are ≥ 2^63. A bplist encoder may store such a value as a
16-byte integer (high 8 bytes zero) or as a negative 8-byte integer. Either
way, take the low 64 bits as a two's-complement `Long` and treat them as
unsigned. The plist library chosen in 02 must decode 16-byte integers.
UNVERIFIED for whichever library is chosen; test with a hand-made bplist that
holds `0xC0FFEE1234567890`. Format the value with
`java.lang.Long.toUnsignedString(id)`, never `id.toString()`: a signed decimal
gives a wrong key for half of all sessions.

Receiver actions:

1. A session SETUP must already have succeeded on this connection, because
   `session_key` must exist. Otherwise respond `400`.
2. If a type-110 stream is already active on this connection, stop it first
   (close its sockets, release its decoder).
3. Derive `video_key` and `video_iv` (§2.3) and create a fresh AES-CTR state.
4. Open a TCP listening socket on an ephemeral port, backlog 1, accepting only
   `client_ip` (03b §3.1).
5. Add `{type: 110, dataPort: <listening port>}` to the response `streams`
   array.

#### 1.3.2 Type 96: realtime audio

Request keys (ignore unknown keys):

| Key | Type | Mirroring example | Audio-only example | Use |
|---|---|---|---|---|
| `type` | integer | `96` | `96` | |
| `ct` | integer | `8` | `2` | Compression type: 1 = LPCM, 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD (32 = Opus seen from non-Apple senders). Accept **2 and 8**. 4 is optional (03c §4.8). Respond `400` for anything else. |
| `spf` | integer | `480` | `352` | Samples (frames) per packet. ct=8 requires 480. ct=2 accepts 1..4096 and the value becomes the ALAC `frameLength`. Respond `400` otherwise. |
| `audioFormat` | integer bitmask | `16777216` (0x1000000) | `262144` (0x40000) | Exactly one format bit. Table below. Cross-check it against `ct`, and log a mismatch. `ct` decides. |
| `sr` | integer | (absent) | `44100` | Sample rate. If it is absent assume 44100. Respond `400` for any other value. |
| `controlPort` | integer | `63658` | `59079` | Client UDP port that receives resend requests (03c §4.5). 0 or absent means do not send resend requests. |
| `latencyMin` | integer (frames) | `3750` | `11025` | Client's minimum latency in frames. A playout hint (03c §4.7). |
| `latencyMax` | integer (frames) | `3750` | `88200` | Client's maximum latency in frames |
| `redundantAudio` | integer | `2` | (absent) | Each packet is sent more than once (03c §4.2). UNVERIFIED: the exact meaning of the value. Each AAC-ELD packet has been seen three times. |
| `usingScreen` | boolean | `true` | (absent or false) | Audio belongs to a mirroring session |
| `isMedia` | boolean | | `true` | Informational |
| `audioMode` | string | | `"default"` | Informational |
| `streamConnectionID`, `supportsDynamicStreamID` | integer/bool | | sometimes | Ignore for type 96 |
| `shk`, `shiv` | data | | | Session key for ChaCha20-Poly1305 audio. Present in HomeKit AirPlay 2 sessions. Ignore it: in our path the payload is AES-CBC (§2.2). UNVERIFIED whether current clients send `shk` on this path at all. If CBC sanity checks fail (03c §4.2) while `shk` is present, log both. |

`audioFormat` bits (one bit per format, per java-airplay `AudioStreamInfo` and
the emanuelecozzi notes). Only 0x40000 (ALAC) and 0x1000000 (AAC-ELD) are
confirmed in public captures from Apple clients on this path. The other rows
come from java-airplay's table and are informational. UNVERIFIED: in
particular the two bits above 2^31, which java-airplay itself marks as
uncertain.

| Bit value | Format | | Bit value | Format |
|---|---|---|---|---|
| 0x4 | PCM 8000/16/1 | | 0x40000 | **ALAC 44100/16/2** |
| 0x8 | PCM 8000/16/2 | | 0x80000 | ALAC 44100/24/2 |
| 0x10 | PCM 16000/16/1 | | 0x100000 | ALAC 48000/16/2 |
| 0x20 | PCM 16000/16/2 | | 0x200000 | ALAC 48000/24/2 |
| 0x40 | PCM 24000/16/1 | | 0x400000 | AAC-LC 44100/2 |
| 0x80 | PCM 24000/16/2 | | 0x800000 | AAC-LC 48000/2 |
| 0x100 | PCM 32000/16/1 | | 0x1000000 | **AAC-ELD 44100/2** |
| 0x200 | PCM 32000/16/2 | | 0x2000000 | AAC-ELD 48000/2 |
| 0x400 | PCM 44100/16/1 | | 0x4000000 | AAC-ELD 16000/1 |
| 0x800 | PCM 44100/16/2 | | 0x8000000 | AAC-ELD 24000/1 |
| 0x1000 | PCM 44100/24/1 | | 0x10000000 | Opus 16000/1 |
| 0x2000 | PCM 44100/24/2 | | 0x20000000 | Opus 24000/1 |
| 0x4000 | PCM 48000/16/1 | | 0x40000000 | Opus 48000/1 |
| 0x8000 | PCM 48000/16/2 | | 0x80000000 | AAC-ELD 44100/1 |
| 0x10000 | PCM 48000/24/1 | | 0x100000000 | AAC-ELD 48000/1 |
| 0x20000 | PCM 48000/24/2 | | | |

Receiver actions:

1. A session SETUP must already have succeeded, because `session_key` and `eiv`
   must exist. Otherwise respond `400`.
2. If a type-96 stream is already active on this connection, stop it first. A
   mode switch arrives as TEARDOWN 96 followed by SETUP 96. Handle the case
   without the TEARDOWN too.
3. Open two UDP sockets on ephemeral ports: the **data socket** and the
   **control socket** (03c §4.1).
4. Create the decoder for `ct` (03c §4.8) and the playout state.
5. Add `{type: 96, dataPort: <data port>, controlPort: <control port>}` to the
   response `streams` array.

#### 1.3.3 Stream SETUP response

```
{ streams: [ {type: 110, dataPort: 46000},
             {type: 96, dataPort: 42820, controlPort: 46440} ] }
```

Only the streams in the request appear, in request order.
`Content-Type: application/x-apple-binary-plist`.

### 1.4 Buffered audio (type 103): not needed

When features bit 40 (SupportsBufferedAudio) is not advertised, current iOS
and macOS clients, including the Music app, fall back to type-96 realtime ALAC
(ct=2, spf=352), so audio-only works without a type-103 handler. Type 103
would add TCP framing,
ChaCha20-Poly1305 with `shk`, PTP timing (bit 41), `SETRATEANCHORTIME` and
`FLUSHBUFFERED`. Keep bit 40 and bit 41 off (§7) and implement only type 96.

### 1.5 RECORD

Body is empty. Respond `200 OK` with headers:

- `Audio-Latency: 11025`: the receiver's added latency in frames at 44.1 kHz
  (0.25 s = 11025 frames). Clients seem to ignore the value. 03c §4.7 explains
  how the receiver applies it.
- `Audio-Jack-Status: connected; type=analog`

RECORD starts nothing that SETUP has not already started. Audio packets can
arrive before RECORD.

### 1.6 ANNOUNCE / SDP: the classic RAOP session (macOS Music)

iOS senders and screen mirroring use the plist SETUP with the feature set
in §7. macOS Music does not: with the same advertisement it uses the
AirPlay 1 sequence ANNOUNCE (SDP) → SETUP (`Transport:` header), observed
2026-11-26, Music/1.7, macOS 27.0.1 (01 §4.3.2). Rozan maps the classic
session onto the realtime type-96 ALAC stream of §1.3.2, the same sockets,
jitter buffer, decryption, decoder, timing client and resend logic. The
control-level rules (SDP bounds, `Transport` bounds, status codes) are in
01 §6.17 and §6.8.1; access control is in 02 §6.4.

| Classic field | Takes the place of | Notes |
|---|---|---|
| `a=fpaeskey` (72 bytes) | session SETUP `ekey` | The same FairPlay key blob (04 §1.5), here FairPlay v2. Decrypted at ANNOUNCE into `fairplay_key` |
| `a=aesiv` (16 bytes) | session SETUP `eiv` | The per-packet CBC IV (§2.2) |
| `a=rtpmap:96 AppleLossless` | stream `ct` = 2 | ALAC only |
| `a=fmtp:96` frameLength | stream `spf` | 1..4096; the other fmtp fields must equal the fixed configuration of 03c §4.8.1 |
| `a=fmtp:96` sampleRate | stream `sr` | 44100 only |
| `a=min-latency`, `a=max-latency` | stream `latencyMin`, `latencyMax` | Optional; clamped as in §8.1. Music 1.7 sent neither |
| `Transport` `timing_port` | session SETUP `timingPort` | The sender's NTP port (03c §4.6); 0 or absent: no timing |
| `Transport` `control_port` | stream `controlPort` | The sender's port for resend requests (03c §4.5); 0 or absent: none sent |
| reply `server_port` | stream reply `dataPort` | |
| reply `control_port` | stream reply `controlPort` | |
| reply `timing_port` | session reply `timingPort` | |

The classic SETUP does in one request what the plist path does in two: the
session SETUP (§1.2 steps 3-5, with the key of §2.1's classic branch), then
one type-96 stream SETUP with `ct` 2 and `usingScreen` false (§1.3.2 steps
2-5). The stream is therefore audio-only and timestamped (03c §4.7).
`isScreenMirroringSession` is false. The plist path's checks (`ct`, `spf`,
`sr`, port ranges) apply to the values above. Before that, at ANNOUNCE,
the receiver has checked the SDP, authenticated the connection and
decrypted `fpaeskey`.

Lifetime: the description (FairPlay key, IV, ALAC parameters) lives on the
control connection from ANNOUNCE until the classic SETUP that uses it, a
newer ANNOUNCE, any TEARDOWN or the connection's close; its key copy is
zeroed then. After the SETUP the session's keys follow §2.4.

A legacy ALAC SDP looks like this. The latency lines are optional: neither
the one Music sent (01 §6.17) nor iTunes 10.6's in [NTO] and [SPEC] has
them.

```
v=0
o=iTunes 3413821438 0 IN IP4 192.168.1.10
s=iTunes
c=IN IP4 192.168.1.20
t=0 0
m=audio 0 RTP/AVP 96
a=rtpmap:96 AppleLossless
a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
a=fpaeskey:<base64 of a 72-byte FairPlay blob>      (or a=rsaaeskey:<base64 RSA-OAEP blob>)
a=aesiv:<base64 of 16 bytes>
a=min-latency:11025
a=max-latency:88200
```

After the payload type, the 11 `fmtp` numbers are the `ALACSpecificConfig`
fields in order (03c §4.8.1): frameLength, compatibleVersion, bitDepth, pb,
mb, kb, numChannels, maxRun, maxFrameBytes, avgBitRate, sampleRate. Music
sends exactly `352 0 16 40 10 14 2 255 0 0 44100`, without `min-latency` or
`max-latency`, and `aesiv` as 22 base64 characters without padding
[MUSIC]. Other legacy rtpmaps are `mpeg4-generic/44100/2` with
`mode=AAC-main; constantDuration=1024` (AAC) or `mode=AAC-eld;
constantDuration=480` (AAC-ELD), and `L16/44100/2` (PCM); [SPEC] shows iOS
5-era senders announcing AAC and AAC-ELD with H.264 video this way. Rozan
answers them `415`. `rsaaeskey` needs the AirPort Express RSA private key
and is out of scope (`415`). (Sources: SteeBono wiki, [NTO], [SPEC].)

---

## 2. Key derivation

Derived from: java-airplay `FairPlayVideoCipher.java`,
`FairPlayAudioDecryptor.java`; PhairPlay `MirrorCrypto.kt`. Both agree.
Worked numbers are in 03d §A–§C.

### 2.1 Session key (shared by audio and video)

```
if ecdh_secret is available (pair-verify completed on this connection):
    session_key = SHA-512( fairplay_key ‖ ecdh_secret )[0..16)     # 16 + 32 = 48 input bytes
else:
    session_key = fairplay_key
```

- The hash is **SHA-512**, not SHA-256, truncated to its first 16 bytes.
- `ecdh_secret` is the 32-byte raw X25519 output of pair-verify. If 02 skips
  pairing (features bit 27 off), use the unhashed branch.
- Some third-party senders (AirMyPC) pair but expect the unhashed key. They
  can be recognised by their User-Agent. Apple clients always use the hashed
  form when they have paired.

Classic RAOP session (§1.6), a separate derivation:

```
session_key = fairplay_key          # from a=fpaeskey, never hashed
```

Classic RAOP has no pair-verify, and [NTO] and [SPEC] describe the key in
`fpaeskey` as the AES key of the audio. Rozan uses it as is even on a
connection that ran pair-verify, and keeps the two derivations apart in
code. **UNVERIFIED:** a classic sender that pair-verifies first has not
been seen; if one appears, its key derivation must be found by test. The
log line "audio key wrong" (03c §4.2) shows a wrong derivation.

### 2.2 Audio: AES-128-CBC per packet

- Key: `session_key`. IV: `eiv` from the session SETUP, exactly as received.
- Encrypted region: the RTP payload, which is everything after the 12-byte RTP
  header. Only the first `floor(len/16)*16` bytes are encrypted. The trailing
  `len mod 16` bytes travel **in clear** and are copied through unchanged.
  There is no padding.
- **The CBC state restarts with `eiv` for every packet.** Do not chain across
  packets. With `javax.crypto`, call `init(DECRYPT_MODE, key, IvParameterSpec(eiv))`
  (or re-init) before each packet's `doFinal`/`update` on
  `AES/CBC/NoPadding`. Carrying the CBC context from one packet to the next
  breaks the AAC decoder.
- The same rule applies to the RTP packet embedded in a retransmission (03c
  §4.4). Sync, timing and resend-request packets are never encrypted.
- Payloads shorter than 16 bytes, such as the 4-byte AAC-ELD no-data marker
  `00 68 34 00`, are therefore entirely in clear.

### 2.3 Video: AES-128-CTR, one keystream per type-110 stream

```
id        = Long.toUnsignedString(streamConnectionID)        # ASCII decimal, no sign, no padding
video_key = SHA-512( "AirPlayStreamKey" ‖ id ‖ session_key )[0..16)
video_iv  = SHA-512( "AirPlayStreamIV"  ‖ id ‖ session_key )[0..16)
```

- The label strings are ASCII, case-sensitive and have no terminator:
  `AirPlayStreamKey` (16 bytes) and `AirPlayStreamIV` (15 bytes, capital
  `IV`). In the hash input the label and id come **first** and the 16-byte
  `session_key` follows. This is the opposite order from §2.1.
- Cipher: AES-128 in CTR mode. The initial counter block is the full 16-byte
  `video_iv`. It is incremented as a single 128-bit big-endian integer per
  16-byte keystream block, wrapping modulo 2^128. This is the behaviour of
  Java's `AES/CTR/NoPadding` and OpenSSL `aes-128-ctr`. 03d §B includes a
  carry check.
- **Keystream continuity:** one keystream serves all encrypted payloads of the
  stream. Each type-0 (video) payload of `n` bytes consumes exactly the next
  `n` keystream bytes, starting where the previous type-0 payload stopped, even
  when that was in the middle of a 16-byte block. Codec-config (type 1),
  heartbeat (type 2), report (type 5) and unknown packets are not encrypted and
  consume **no** keystream. The 128-byte headers are never encrypted.
  (Per-packet bookkeeping of the unused rest of the last keystream block
  gives exactly this byte-continuous stream. java-airplay and PhairPlay state
  the same equivalence.)
- A new type-110 SETUP brings a new `streamConnectionID`, so derive a new key
  and IV and **restart** the keystream. Any leftover partial block from the
  previous stream is discarded.
- Implementation options:
  1. `Cipher.getInstance("AES/CTR/NoPadding")`, initialised once, with
     `update()` per payload. SunJCE keeps the partial-block offset across
     `update()` calls. UNVERIFIED for Android's default provider (Conscrypt on
     BoringSSL). Confirm with 03d §B: packet 1 is 21 bytes, so packet 2 must
     decrypt correctly from keystream offset 21. If it fails, use option 2.
  2. Manual CTR: keep a 16-byte counter block, a 16-byte keystream buffer and
     an offset 0..16. Refill the buffer with `AES/ECB/NoPadding` encryption of
     the counter block, increment the counter, and XOR byte by byte (or block
     by block when aligned). This is about 30 lines, has no provider
     dependency, and is what 03d §B was computed with.
- Efficiency: AES-CTR at mirroring bitrates (≤ ~3 MB/s) is cheap even without
  ARMv8 crypto extensions. Decrypt in place in a reused buffer, or straight
  into the `MediaCodec` input `ByteBuffer`. Do not allocate per packet.

### 2.4 Lifetime

| Key | Created | Destroyed |
|---|---|---|
| `fairplay_key`, `session_key`, `eiv` | session SETUP | full TEARDOWN, RTSP connection close, or the next session SETUP |
| classic description (`fairplay_key` from `fpaeskey`, `aesiv`) | ANNOUNCE | the classic SETUP that uses it (copied into the session), the next ANNOUNCE, any TEARDOWN, RTSP connection close |
| `video_key`, `video_iv`, CTR state | each type-110 SETUP | TEARDOWN 110, full TEARDOWN, the next type-110 SETUP |
| audio CBC key | each type-96 SETUP copies `session_key`/`eiv` | TEARDOWN 96, full TEARDOWN |

Zero the key arrays when they are destroyed.

---

## 6. Volume, FLUSH, TEARDOWN, feedback, reconnect

### 6.1 SET_PARAMETER volume

```
SET_PARAMETER rtsp://… RTSP/1.0
Content-Type: text/parameters

volume: -11.250000\r\n
```

- Value: decimal float in dB. Normal range is **-30.0 (quietest) to 0.0 (full)**.
  **-144.0 means mute.** Parse with `toFloatOrNull()` after `"volume: "`. If the
  value is not finite or not a number, ignore it. Clamp values above 0 to 0,
  and treat values below -30 (other than -144) as -30.
- Mapping to linear gain (default, "flat"):
  - `v == -144` or `v <= -30` → gain 0 (mute).
  - otherwise `gain = 10^(v/20)`. -15 dB gives 0.1778 and -7.5 dB gives 0.4217.
    03d §I has a table.
- Optional "tapered" profile. With slider fraction `f = (v + 30) / 30`, use
  `dB = max(10·log2(f), v)`, so each halving of the slider costs 10 dB.
- Apply with `AudioTrack.setVolume(gain)`, which takes a linear 0..1 value and
  costs nothing on the decode path. Remember the last value and apply it to an
  AudioTrack created later (volume often arrives before the audio stream).
- The same request may arrive in mirroring and audio-only sessions. Respond `200`.

Other `SET_PARAMETER` bodies. None of them affects media decoding. Respond
`200` to each:

| Content-Type | Body | Use |
|---|---|---|
| `text/parameters` | `progress: <start>/<current>/<end>\r\n` (RTP timestamps, u32 decimal) | Track progress UI: elapsed = (current − start)/44100 s |
| `image/jpeg`, `image/png` | artwork bytes | Now-playing UI (bound in §8) |
| `application/x-dmap-tagged` | DMAP metadata | Now-playing UI (bound in §8) |
| no Content-Type | | Respond `451 Parameter Not Understood` |

`GET_PARAMETER` with body `volume\r\n` (text/parameters): respond `200`,
`Content-Type: text/parameters`, body `volume: <current dB, e.g. -11.250000>\r\n`.
`POST /audioMode` with bplist `{audioMode: "default"}`: respond `200` and ignore it.

### 6.2 FLUSH (audio)

```
FLUSH rtsp://… RTSP/1.0
RTP-Info: seq=24561;rtptime=1844137298
```

The client sends FLUSH on pause, seek, track skip and stop in audio-only mode.
Meaning: discard all audio before the given point. The stream continues at
sequence number `seq` with RTP timestamp `rtptime`. Receiver actions:

1. Parse `seq` (0..65535) and `rtptime` (0..2^32−1) if present. Either can
   be missing.
2. Drop every queued encoded packet whose RTP timestamp is before `rtptime`
   (serial-number comparison, 03c §4.3) and whose sequence number is before `seq`.
   Without RTP-Info, drop everything.
3. Stop output right away: `AudioTrack.pause()` then `flush()`. Drop decoded
   PCM that was not yet written, and flush the codec (`MediaCodec.flush()` for
   AAC-ELD, or reset the ALAC decoder state).
4. Set the next expected sequence number to `seq`. Keep the NTP offset. Mark
   the playout anchor stale and wait for the next sync packet before playing
   ALAC again. The first sync after a flush has byte 0 = `0x90` (03c §4.4).
5. Respond `200`.

`FLUSHBUFFERED` belongs to type 103 only and is not needed.

### 6.3 TEARDOWN

Body: optional bplist. Classify:

| Body | Action |
|---|---|
| `{streams: [{type: 96}]}` | Stop the audio stream: close its data and control sockets, release the decoder and AudioTrack, clear its jitter buffer. Keep `session_key`/`eiv` for a later SETUP 96 on the same connection. |
| `{streams: [{type: 110}]}` | Stop mirroring video: close the data connection and listener, release MediaCodec, destroy the CTR state |
| both types in one array | Both actions |
| no body, or no `streams` | **End the session:** stop every stream, including type 110 (recent iOS may stop mirroring with only this request), stop the timing client, destroy all keys, return the UI to idle |

Respond `200`. UNVERIFIED: whether clients need `Connection: close` on a
TEARDOWN response. Send it only for the full-session TEARDOWN, and never close
the RTSP connection yourself after a partial TEARDOWN. Parse the body
defensively. A body that fails to parse counts as a full TEARDOWN.

### 6.4 /feedback heartbeat

`POST /feedback` arrives about every 2 seconds during any active session. The
body is empty, or holds a stats bplist when `/info` advertises
`keepAliveSendStatsAsBody`. Respond `200 OK` with no body, and do not parse the
body. Use it as the liveness signal. If no `/feedback` arrives for **15 s**
while a session is active, treat the client as gone and run the full-session
TEARDOWN actions locally. Log a warning after 3 s of silence.

A classic RAOP session (§1.6) is held to this only after its first
`/feedback`, because the classic flow is not known to send one; its audio
packets keep its control connection open instead (01 §3.1). UNVERIFIED
U26.

### 6.5 Reconnect and resume

| Situation | What the client does | What the receiver does |
|---|---|---|
| Client screen locks or sleeps during mirroring | Sends a codec packet with the "suspend" option (03b §3.8). Video packets stop. `/feedback` may continue. | Freeze or blank the video and keep the session |
| Client wakes | Codec packet with the normal option, then an IDR | Reconfigure if the SPS changed, then continue |
| Music paused or seeked | FLUSH, then new packets and a new 0x90 sync | §6.2 |
| Mirroring → audio-only switch on one connection | TEARDOWN 96, TEARDOWN 110 (maybe only a full TEARDOWN), SETUP 96 with ct=2 | Tear down as asked, then a fresh type-96 stream |
| Mirroring TCP connection closes without TEARDOWN | — | Release the decoder. Keep the listener open for the stream's lifetime and accept one new connection from `client_ip`. Continue the CTR keystream where it stopped. UNVERIFIED: whether clients ever reconnect without a new SETUP 110. A fresh SETUP 110 always restarts the keystream. |
| Network lost | `/feedback` stops | Full teardown after 15 s. The client starts over (pair-verify, fp-setup, SETUP). |
| A second client tries to connect during a session | — | Policy (02). Default: the current client keeps exclusive use until it disconnects. An option may instead drop the current client. |

---

## 7. What to advertise (steering clients to the simple path)

01 owns the full features value and the TXT records. This section lists only
the media-relevant parts and the reason for each.

Features bits (bit n of the 64-bit features value; TXT form `0x<low32>,0x<high32>`).
A value that current clients accept is low = `0x5A7FFEE6` and high = `0x0`:

| Bit | Name | Set | Why |
|---|---|---|---|
| 7 | Screen mirroring | **on** | Required for type 110 |
| 9 | Audio | **on** | Required for type 96 |
| 11 | Audio packet redundancy | on | Mirroring clients then send `redundantAudio: 2` and repeat AAC-ELD packets. Dedupe by sequence number. |
| 12, 14, 22 | FairPlay SAPv2.5 / FairPlay authentication | **on** | Gives the `ekey`/FairPlay path that this spec assumes |
| 18, 19, 20, 21 | Audio format bits | on | All four set. Not tested individually. UNVERIFIED: which subset is needed for ALAC realtime. |
| 27 | Supports legacy pairing | per 01 (Mode D: off, Mode P: on) | Media handles both. If pair-verify ran on the connection, `ecdh_secret` exists and the session key is hashed. If not, `session_key = fairplay_key` (§2.1). A sender can run pair-verify on its own initiative even with bit 27 off, so key on "pair-verify completed", never on the bit. |
| 30 | RAOP | on | Audio is served by the same service |
| 38, 43, 46, 48, 51 | Unified media control / system pairing / HomeKit pairing / CoreUtils pairing / unified pair-setup + MFi | **off** | Would move the client to HomeKit pairing with ChaCha20-Poly1305 media, which is out of scope |
| 40 | Supports buffered audio | **off** | Keeps audio-only on type 96 (§1.4) |
| 41 | Supports PTP | **off** | Keeps timing on NTP (`timingProtocol: "NTP"`) |
| 42 | Supports screen multi-codec (H.265) | **off** | H.264 only. With bit 42 off, a client that would have sent HEVC sends a type-1 codec packet with **payload size 0** (03b §3.3). Avoid this by not advertising a display taller than 1080. |
| 61 | Supports RFC 2198 redundancy | **off** | Would change the RTP payload to PT 97 with redundancy block headers |

Should H.265 be advertised? No. It doubles the codec-config parsing work
(03b §3.3), and clients use it only for displays taller than 1080. H.264
already covers the 1080p TVs this launcher targets on all target hardware.
HEVC could be added later behind bit 42 only on devices with a hardware
`video/hevc` decoder, together with a 2160p display entry.

`/info` items that affect media. 01 §"GET /info"
is authoritative for the exact keys and types. This table gives only the media
reasoning, and where the two differ, 01 wins:

| Key | Value to use | Effect |
|---|---|---|
| `displays[0].width`, `widthPixels` | `1920` | Upper bound on the mirroring width. The client keeps the aspect ratio, and **height** is the controlling dimension. |
| `displays[0].height`, `heightPixels` | `1080` (never more without HEVC) | Keeps the client on H.264 |
| `displays[0].maxFPS` | `30`. 60 only on hardware that can decode and present 1080p60 smoothly. | Halves decode and present load on a Fire TV Stick Lite class device |
| `displays[0].refreshRate` | real, `1/60` | |
| `displays[0].overscanned` | `false` | |
| `displays[0].rotation` | `false` | |
| `displays[0].features` | `14` | Meaning unknown |
| `displays[0].uuid`, `widthPhysical`/`heightPhysical` | a stable UUID string, `0`/`0` | |
| `audioFormats` | two dicts, `type` 100 and 101, each `audioInputFormats` = `audioOutputFormats` = `0x3fffffc` | |
| `audioLatencies` | two dicts, `type` 100 and 101, `audioType` `"default"`, `inputLatencyMicros` 0, `outputLatencyMicros` as in 01 (boolean `false` works; AirServer sends integer 0) | No known effect on media timing. Clients appear to take latency from the sync packets. |
| `initialVolume` | real, dB in -30..0 | Initial client slider position |
| `keepAliveSendStatsAsBody` | `true` | `/feedback` may carry a stats body (ignored) |

RAOP TXT media keys (01 owns them): `cn=0,1,2,3`, `et=0,3,5`, `ch=2`,
`sr=44100`, `ss=16`, `tp=UDP`, `md=0,1,2`, `vn=65537`.

Outcome of these choices: mirroring uses H.264 + AAC-ELD 44.1 kHz stereo
(480 spf) over type 110 + type 96. Audio-only uses ALAC 44.1 kHz/16-bit/stereo
(352 spf) over type 96. Timing uses NTP. Encryption is AES-CTR for video and
AES-CBC for audio.

---

## 8. Robustness: attacker-controlled fields and bounds

Everything below comes from the network. Any client on the LAN can open these
sockets, and a paired client can still be hostile. Enforce every bound before
allocating or indexing. "Drop" means discard and count. "Close" means close
that media socket and stop the stream, and the RTSP session decides what
happens next.

### 8.1 SETUP and control requests

| Field | Bound / rule | On violation |
|---|---|---|
| RTSP body length for SETUP/TEARDOWN/SET_PARAMETER text | ≤ 64 KiB (02 enforces Content-Length) | 413/400 |
| Artwork (`image/*`) body | ≤ 8 MiB. Decode with `BitmapFactory` bounds (`inSampleSize`) to ≤ 1920 px. | 413, ignore |
| DMAP metadata body | ≤ 256 KiB. Walk tags with length checks. | ignore |
| `ekey` | exactly 72 bytes | 400 |
| `eiv` | exactly 16 bytes | 400 |
| `streams` | array with ≤ 4 elements | 400 |
| `type` | 96 or 110, at most one of each per request | 400 |
| `streamConnectionID` | present, integer | 400 |
| `ct`/`spf`/`sr` | per §1.3.2 | 400 |
| `controlPort`, `timingPort` | 1..65535. Send only to `client_ip`, never to an address taken from a payload. This prevents use as a UDP reflector. | Treat as absent |
| `latencyMin`/`latencyMax` | clamp to 0..176400 (4 s) | clamp |
| `volume` | finite float. Clamp per §6.1. | ignore |
| FLUSH `RTP-Info` | `seq` decimal 0..65535, `rtptime` decimal 0..4294967295, ≤ 10 digits each | ignore the field |
| `progress` | three u32 decimals | ignore |
| Order | type 96/110 before a session SETUP | 400 |
| Classic ANNOUNCE (SDP) | body ≤ 8 KiB, ≤ 64 lines of ≤ 1024 bytes, one audio media line, AppleLossless with 03c §4.8.1's configuration, `fpaeskey` 72 bytes, `aesiv` 16 bytes (01 §6.17) | 413 (size), 400 (malformed), 415 (unsupported) |
| Classic SETUP `Transport` | first spec only, ≤ 16 parameters, UDP unicast `mode=record`; ports as `controlPort`/`timingPort` above; `destination` never used (01 §6.8.1) | 400, 461 |
| Classic order | classic SETUP without an ANNOUNCE on the connection | 455 |

### 8.2 Mirroring TCP stream (03b)

| Field | Bound / rule | On violation |
|---|---|---|
| Connection source | `client_ip` only. One active connection per stream. Accept within 10 s of SETUP 110. | Reject the connection |
| `payload_size` (u32le, header offset 0) | type 0: ≤ 4 MiB. type 1: 1..4096 (0 means unsupported codec, see 03b §3.3). type 5: ≤ 256 KiB. type 2: 0. Unknown types: ≤ 1 MiB, then skip. | **Close.** A type-0 payload that is not decrypted would desynchronise the keystream, so it cannot be skipped. Never allocate from an unchecked size. |
| Read timeouts | Header and payload reads must make progress: no more than 10 s without bytes inside a packet. Idle between packets is allowed: rely on `/feedback` and TCP keepalive, because a sleeping client sends nothing. | Close |
| avcC (type 1) | `version == 1`, `lengthSizeMinusOne == 3`, `numSPS` 1..31, `numPPS` 1..255, each SPS/PPS length 1..512 and inside the payload, total parameter-set bytes ≤ 2048 | Drop the codec packet. Keep the previous config, or do not start the decoder. |
| SPS-derived size | width and height 16..1920 × 16..1088 for H.264 (MediaCodec support checked with `isSizeSupported`) | Close the stream |
| Header geometry floats | finite, 0 < value ≤ 8192. Informational only. | ignore |
| NAL length walk (type 0, after decryption) | each length ≥ 1 and ≤ remaining bytes − 4, first NAL byte has forbidden bit 0, lengths sum exactly to `payload_size` | Drop the packet (the keystream is still consumed). After 30 consecutive invalid packets, close: the key is probably wrong. |
| Report payload (type 5) | do not parse (bplist plus an unknown 25,000-byte trailer) | skip |
| Decoder input queue | bounded: block reading when `dequeueInputBuffer` has no buffer, which gives TCP backpressure. Do not buffer unbounded. | — |

### 8.3 Audio UDP (03c)

| Field | Bound / rule | On violation |
|---|---|---|
| Source address | `client_ip` only, on data, control and timing sockets | Drop |
| Datagram length | data: 12..2060 (12-byte header + ≤ 2048 payload). Read into a reused 64 KiB buffer so oversize datagrams are detected rather than truncated. Control: sync exactly 20, resend response 16..2064, others drop. Timing: ≥ 32. | Drop |
| RTP byte 0 | `(b0 & 0xC0) == 0x80` (version 2) | Drop |
| Payload type | `b1 & 0x7F`: data socket 0x60, control 0x54/0x56, timing 0x53 (and 0x52 if answering requests) | Drop |
| Sequence number | within the jitter window (03c §4.3). Far outside it means a reset. | Drop or resync |
| ALAC decode | the decoder must refuse frames that would produce more than `frameLength` samples per channel, element channel counts > 2, sample sizes > 16 bits plus the shift allowed by the config, or reads past the payload end | Output silence for that packet |
| AAC-ELD AU | ≤ 2048 bytes. MediaCodec errors → flush the codec and continue. | Silence |
| Sync packet times | RTP and NTP pairs must move forward consistently. A jump of more than 5 s from the prediction means re-anchoring, not playing at an absurd time. | Re-anchor |
| Timing reply | origin (offset 8) must equal the transmit time of an outstanding request (last 8 kept). RTT must be > 0 and ≤ 1 s. Reject duplicate T3 values. | Drop |
| Resend requests (outgoing) | at most 2 requests per missing sequence number, at most 50 resend packets per second | — |

### 8.4 Resource limits on weak hardware (1 GB RAM)

- At most one mirroring stream, one audio stream and one timing client per
  receiver, which matches the single-client policy.
- Buffers allocated once per stream: 4 MiB video payload buffer, 512-entry
  audio ring of 2 KiB each (1 MiB), and one 64 KiB UDP receive buffer per
  socket.
- No per-packet allocation on the video path (≈ 30–60 packets/s) or the audio
  path (≈ 125 packets/s for ALAC, ≈ 92/s × 3 copies for AAC-ELD).

---

## Implementation checklist

### A. Minimum for working mirroring with audio

1. [ ] Session SETUP (§1.2): validate `ekey` and `eiv`, call FairPlay (04),
       derive `session_key` (§2.1). Check against 03d §A.
2. [ ] Timing socket plus NTP client (03c §4.6): burst, then every 3 s.
       Offset/delay filter. Local clock `System.nanoTime()`.
3. [ ] SETUP 110 (§1.3.1): unsigned `streamConnectionID`, `video_key`/`video_iv`
       (03d §A), TCP listener, response with `dataPort`.
4. [ ] Mirroring reader (03b): exact reads of 128-byte header + payload, all
       bounds from §8.2.
5. [ ] Type 1: avcC parse → `csd-0`/`csd-1` (03d §D). Configure `video/avc`
       MediaCodec on the Surface. Reconfigure when SPS/PPS change.
6. [ ] Type 0: byte-continuous AES-CTR (03d §B) → AVCC-to-Annex-B rewrite in
       place → one input buffer per packet, key-frame flag when an IDR is
       present. Ignore types 2 and 5.
7. [ ] Video render in live mode: `releaseOutputBuffer(index, true)` when each
       frame is decoded (03c §5.2).
8. [ ] SETUP 96 with ct=8 (§1.3.2): data and control UDP sockets, per-packet
       AES-CBC with the clear tail (03d §C), sequence dedupe (each packet
       arrives about 3 times), skip `00 68 34 00` no-data payloads.
9. [ ] AAC-ELD `MediaCodec` (`audio/mp4a-latm`, profile 39, `csd-0 = F8 E8 50 00`)
       → AudioTrack 44.1 kHz stereo 16-bit, live playout with buffer-depth
       drift control (03c §4.7, §5).
10. [ ] RECORD response headers (§1.5), SET/GET_PARAMETER volume (§6.1),
        `/feedback` 200 plus the 15 s liveness timeout (§6.4).
11. [ ] TEARDOWN 96 / 110 / full, where full also stops video (§6.3).
12. [ ] Codec-packet suspend/resume handling (03b §3.8).
13. [ ] Advertising (§7): bits 40/41/42 off, display ≤ 1920×1080, `maxFPS` 30.
14. [ ] Measure the A/V offset on a Fire TV with a lip-sync test clip and set the
        default audio offset (03c §5.4).

### B. Then audio-only AirPlay (Music app, macOS sound output)

1. [ ] SETUP 96 with ct=2, spf=352: ALAC decoder initialised from 03c §4.8.1
       (cookie in 03d §H). Sanity check: first payload byte 0x20.
2. [ ] 512-entry jitter buffer with serial-number arithmetic, duplicate and late
       handling (03c §4.3).
3. [ ] Sync packets (03c §4.4): anchor (rtp, client time). Hold ALAC until the
       first sync.
4. [ ] Timestamped playout (03c §4.7): local time from the NTP offset plus
       `Audio-Latency`, silence for missing packets, drift correction against
       `AudioTrack.getTimestamp()`.
5. [ ] Resend requests and handling of retransmitted packets (03c §4.4–§4.5).
6. [ ] FLUSH (§6.2) with AudioTrack pause/flush and re-anchoring on the next
       0x90 sync.
7. [ ] Mode switch on one connection: mirroring → audio-only → mirroring (§6.5).
8. [ ] Optional: `progress`, DMAP metadata and artwork for a now-playing screen,
       with the bounds in §8.1.
9. [ ] Classic RAOP (§1.6): ANNOUNCE and the classic SETUP onto the same
       type-96 ALAC stream, the unhashed key of §2.1, and the liveness
       rule of §6.4.

---

## UNVERIFIED items (all files)

Each item says what would confirm it. Numbers are referenced from 03b/03c.

| # | Statement | What would confirm it |
|---|---|---|
| U1 | SETUP `et` = 32 is an encryption-type bitmask (bit 5 = FairPlay SAPv2.5) | Captures against receivers that advertise different RAOP `et` lists |
| U2 | Android's default `AES/CTR/NoPadding` (Conscrypt) keeps partial-block state across `update()` calls | Run 03d §B on a target device (packet 2 starts at keystream offset 21) |
| U3 | The video header timestamp and the audio sync-packet time are directly comparable presentation times on the client clock, so no fixed offset is needed between them | A lip-sync test clip played on the client while mirroring, with the TV filmed. The answer may vary, so 03c §5.1 keeps a user-tunable offset. |
| U4 | In audio-only mode the receiver should add its reported `Audio-Latency` (11025 frames) on top of the sync-packet mapping, at least for senders of AirPlay version 371 or later | Play a video in a client app with audio-only AirPlay to the receiver and compare client picture with TV sound |
| U5 | Each type-0 mirroring packet carries exactly one access unit (one picture) | Count slices per packet over a long capture. The single timestamp per packet implies it. |
| U6 | avcC `lengthSizeMinusOne` is always 3 (4-byte NAL lengths) | Captures from macOS 26 and iOS 26 |
| U7 | Codec-packet option byte 6, bit 0x40, means "stream suspending" (values 0x56/0x5E versus 0x16/0x1E) | Lock and unlock the client screen while capturing |
| U8 | Meaning of geometry floats at header offsets 48 and 52 (possibly an x/y offset) | Captures with portrait and landscape iPhones and a Mac with notch |
| U9 | HEVC codec-config layout (parameter-set arrays at payload offset 0x75), only needed if bit 42 is ever enabled | Captures with bit 42 on |
| U10 | The repeated 44-byte ALAC packets at stream start (32-byte payload) carry no audio, or decode to silence | Decode them with the ALAC decoder and inspect the output |
| U11 | Bytes 2–3 of the 4-byte prefix of a 0xD6 retransmission (sequence number of the resend reply) | Capture of a resend exchange |
| U12 | `redundantAudio: 2` means each AAC-ELD packet is sent 3 times (n, then twice more interleaved) | Capture with sequence numbers logged |
| U13 | Clients never reconnect the mirroring TCP socket without a new SETUP 110 (if they do, the keystream continues) | Force-break the TCP connection during mirroring |
| U14 | Clients never send 0xD2 timing requests to the receiver's timing port | Log every datagram on the timing socket |
| U15 | GOP structure of mirroring H.264: how often IDRs occur other than at start and format changes (affects whether frames can be dropped before decode) | Log NAL types over 10 minutes of mirroring |
| U16 | `shk`/`shiv` never appear in type-96 SETUP on the FairPlay path | Log SETUP plists from iOS 26 and macOS 26 |
| U17 | Whether `Connection: close` on TEARDOWN is required | Compare client behaviour with and without it |
| U18 | Best RTSP status for an unsupported stream type | Deliberately reject a type-96 SETUP and watch the client |
| U19 | Every target Fire TV / Android TV device decodes AAC-ELD (AOT 39, 480 samples) in `MediaCodec` | `MediaCodecList` query plus a decode of the 03d §H config on each device |
| U20 | The RTP marker bit on audio data packets carries no needed meaning. [NTO] and [SPEC] say iTunes sets it on the first packet after RECORD or FLUSH | Log byte 1 across stream starts and flushes |
| U21 | `latencyMs` in SETUP 110 is a hint for video playout delay | Correlate with measured client behaviour |
| U22 | The order of RECORD relative to the type-96 SETUP in mirroring | Log any session. The implementation must accept any order anyway. |
| U23 | Which of features bits 18–21 are actually needed for ALAC and AAC-ELD realtime | Toggle each bit and test |
| U24 | The plist library chosen in 02 decodes 16-byte bplist integers (large `streamConnectionID`) | A unit test with a hand-built bplist |
| U25 | The best default target playout delay for mirroring on Fire TV hardware (03c §5) | Measurement on a Fire TV Stick Lite |
| U26 | Classic RAOP liveness: whether Music 1.7 sends `/feedback`, and what it sends on the control connection and the UDP sockets during a long track and while paused (01 §3.1) | A Music session of several minutes with a pause of more than a minute, logging `/feedback` and requests |
| U27 | The resend request layout (03c §4.5). [NTO] and [SPEC] describe 12 bytes (an 8-byte header without SSRC, then the first sequence number and the count); this spec sends 8 (`80 d5`, counter, first, count). No sender has yet been seen answering either | Drop packets on purpose during a Music session and look for retransmissions (0xD6) arriving on the control port |
| U28 | Whether Music needs `RTP-Info` in the FLUSH reply, as AirTunes/130.14 sent it to iTunes [NTO] (01 §6.12) | Pause and seek in a Music session |
| U29 | The classic key derivation of §2.1 (FairPlay key as is) for Music 1.7 | Audio plays, with no "audio key wrong" in the log |

---

## Sources

- java-airplay (serezhka, MIT): `RtspMediaSetup.java`, `AudioStreamInfo.java`,
  `FairPlayVideoCipher.java`, `FairPlayAudioDecryptor.java`, `MirrorPacketHeaders.java`,
  `MirrorVideoFraming.java`, `AirPlayStreamType.java`.
- PhairPlay (Apache-2.0): `MirrorCrypto.kt`.
- openairplay airplay-spec: `src/screen_mirroring/stream_packets.md` (example
  codec packet used in 03d §D), `time_synchronization.md`, `audio/rtsp_requests/setup.md`.
  Read online for §1.6, §2.1 and 03c §4: https://openairplay.github.io/airplay-spec/
  (`audio/rtsp_requests/announce.html`, `setup.html`, `audio/rtp_streams.html`). [SPEC]
- "Unofficial AirPlay Protocol Specification", https://nto.github.io/AirPlay.html
  (ANNOUNCE, SETUP with `Transport`, RECORD, FLUSH, Digest on ANNOUNCE, UDP
  packet types). [NTO]
- Device test 2026-11-26, Music/1.7 on macOS 27.0.1: wire capture and Music's
  log (01 §4.3.2). [MUSIC]
- Apple ALAC (macosforge, Apache-2.0): `ALACMagicCookieDescription.txt`.
- Public write-ups: [SteeBono airplayreceiver wiki](https://github.com/SteeBono/airplayreceiver/wiki/AirPlay2-Protocol),
  [emanuelecozzi.net AirPlay 2 RTSP](https://emanuelecozzi.net/docs/airplay2/rtsp/).
