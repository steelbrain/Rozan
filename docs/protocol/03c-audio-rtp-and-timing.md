# 03c — Audio RTP, control and timing ports, playout, A/V sync

Part of the 03 media spec. Conventions, SETUP (§1), keys (§2.2) and bounds
(§8.3) are in `03-media-streams.md`. Test vectors are in `03d-media-test-vectors.md`.

Derived from: Apple ALAC `ALACMagicCookieDescription.txt` (ALAC
configuration); [NTO] and [SPEC] for the classic flow.

## 4. Audio over RTP/UDP

Everything in this section also serves the classic RAOP session of macOS
Music (03 §1.6): the same packets on the same sockets, set up by the
classic SETUP instead of the plist SETUPs. The public descriptions of the
classic flow, [NTO] and [SPEC] (01's legend), give the same packet types:
96 for audio on `server_port`, 84 sync and 85/86 resend request and reply
on `control_port`, 82/83 timing query and answer on `timing_port`, with
the marker bit set on control and timing packets. Where they differ from
this section, it is said below.

### 4.1 Sockets

| Socket | Created at | Local port returned as | Receives | Sends |
|---|---|---|---|---|
| timing | session SETUP | `timingPort` (session response) | 0xD3 timing replies | 0xD2 timing requests to `client_ip:timingPort` |
| data | SETUP 96 | `dataPort` | RTP audio packets (PT 0x60) | nothing |
| control | SETUP 96 | `controlPort` (stream response) | sync (0xD4) and retransmitted packets (0xD6) | resend requests (0xD5) to `client_ip:controlPort(request)` |

In the classic session the three local ports go back in the classic SETUP
reply as `timing_port`, `server_port` (data) and `control_port`, and the
sender's ports come from its `Transport` request as `timing_port` and
`control_port` (01 §6.8.1). All three sockets are opened by that one
SETUP.

- UDP over IPv4 or IPv6, matching the RTSP connection's address family. Bind
  to the interface address that the RTSP connection arrived on, or to the
  wildcard address.
- Drop every datagram whose source address is not `client_ip`. Source ports
  are not checked against the advertised ones, because the client's sending
  port can differ from the port it advertised. Instead each socket learns the
  client's port from the first datagram it accepts (data: an RTP packet it
  stores; control: a well-formed sync, or a retransmission it stores; timing:
  a reply that matches one of its outstanding requests) and from then on
  drops datagrams from any other port. A timing request (0xD2) is answered
  only from that port, or before it is known, from the advertised
  `timingPort`. Rozan policy, not wire behaviour: a UDP source address is
  trivially spoofed on a LAN, and without the port check a host that did so
  could restart or flush the audio and feed bytes to the platform decoder.
  It still has to win the race to the first datagram or guess the client's
  port.
- One receive thread per socket. Alternatively, a single thread can serve
  the data and control sockets with a `DatagramChannel` selector. Reuse one
  64 KiB receive buffer per socket and copy only accepted payloads into the
  jitter ring.
- Data socket receive buffer: ≥ 256 KiB (`setReceiveBufferSize`).

### 4.2 Data packets

| Offset | Size | Field | Value / rule |
|---|---|---|---|
| 0 | 1 | V/P/X/CC | `0x80`: version 2, no padding, no extension, no CSRC. Drop packets where `b0 & 0xC0 != 0x80`. |
| 1 | 1 | M/PT | Payload type = `b1 & 0x7F` = **0x60 (96)**. The marker bit `b1 & 0x80` may be set at stream start; [NTO] and [SPEC] say iTunes sets it on the first packet after RECORD or FLUSH. Ignore it (UNVERIFIED U20). |
| 2 | 2 | sequence number | u16be, +1 per packet, wraps at 65536 |
| 4 | 4 | RTP timestamp | u32be, frames at 44,100 Hz, of the first frame in the packet. +`spf` per packet (352 ALAC, 480 AAC-ELD). Wraps at 2^32. |
| 8 | 4 | SSRC | u32be, observed 0. Ignore it. |
| 12 | rest | payload | One codec frame, AES-CBC encrypted per main file §2.2 (first `floor(n/16)*16` bytes encrypted, tail in clear) |

Payload contents:

| ct | Payload (after decryption) | Typical size | Packets/s |
|---|---|---|---|
| 2 ALAC | one ALAC frame of `spf` = 352 stereo 16-bit frames | ≤ ~1,420 bytes (1,408 bytes of PCM when the frame is stored uncompressed, plus a header) | 125.3 |
| 8 AAC-ELD | one raw AAC-ELD access unit (no ADTS/LATM header) of 480 frames | ~100–400 bytes | 91.9, each sent about 3 times |

Special packets:

1. **No payload (12 bytes).** Seen from old senders. Treat it as a "no data"
   packet: it occupies its sequence number and is never decoded.
2. **AAC-ELD no-data marker.** The payload is exactly the 4 bytes `00 68 34 00`.
   Being shorter than 16 bytes it is in clear. It is sent at the start of a
   mirroring audio stream before real audio, with sequence numbers and
   timestamps advancing normally (and repeated like real packets). Store it in
   the jitter buffer so the sequence stays continuous, but never send it to
   the decoder. Its timeline slot is silence.
3. **ALAC start packets.** 44-byte packets (32-byte payload) with identical
   content, sent at stream start before the first sync packet. Recommended:
   let them through the jitter buffer and decoder like any other packet. If
   the decoder rejects them, output silence for the slot. UNVERIFIED U10:
   whether they decode to silence.
4. **Redundant copies (mirroring).** With `redundantAudio` set, every AAC-ELD
   packet has been observed sent three times in a staggered order. The
   sequence numbers on the wire run `n, n, n+1, n, n+1, n+2, n+1, n+2, n+3, …`.
   The jitter buffer drops a sequence number it already holds. Decrypt only
   the first copy that arrives.

Decryption sanity check (cheap, catches wrong keys early). Look at the first
byte of the decrypted payload:

| ct | Expected first byte | Reason |
|---|---|---|
| 2 ALAC | `0x20` | The first 3 bits are element tag 1 (channel pair), then instance tag 0 |
| 8 AAC-ELD | `0x8C`, `0x8D`, `0x8E` (current clients) or `0x80`, `0x81`, `0x82` (iOS 9/10) | Observed |

On a mismatch, skip the frame and count it. If more than 50 frames in a row
mismatch, log "audio key wrong" with the presence of `shk` (main file U16). Do
not tear the stream down.

### 4.3 Jitter buffer

- A ring of **512 entries**, indexed by `seq & 511`. 512 ALAC packets is
  4.1 s, which covers the 2 s client latency. 512 AAC-ELD packets is 5.6 s.
  Each entry holds: filled flag, seq, RTP timestamp, payload length and a
  2,048-byte payload slot (allocated once).
- 16-bit serial comparison: `sdiff16(a, b) = ((a − b) & 0xFFFF)` interpreted as
  a signed 16-bit value. 32-bit serial comparison of RTP timestamps works the
  same way: `sdiff32(a, b) = (a − b).toInt()` on `UInt`s.
- State: `head` (next sequence number to play), `tail` (highest received),
  `empty`.

Insert packet `s`:

1. If the buffer is empty, set `head = tail = s`.
2. `d = sdiff16(s, head)`. If `d < 0`: the packet is late, drop it.
3. If `d ≥ 512`: the sender jumped far ahead, for example after a restart.
   Flush the buffer, then `head = tail = s`.
4. If the slot holds the same `s`: duplicate, drop.
5. Store it. If `sdiff16(s, tail) > 1`, sequence numbers `tail+1 … s−1` are
   missing (§4.5). If `sdiff16(s, tail) > 0`, set `tail = s`.

Remove for playout: covered in §4.7, because the rule differs between the
timestamped mode and the live mode.

### 4.4 Control port: packets received

All control packets have the RTP-like layout `b0 = 0x80 or 0x90`, then
`b1 = 0x80 | type`. Dispatch on `b1 & 0x7F`.

**Sync (type 0x54, `b1 = 0xD4`), exactly 20 bytes, about once per second:**

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `0x90` on the first sync after stream start or FLUSH (the X bit is set), else `0x80` | Treat 0x90 as "re-anchor now" |
| 1 | 1 | `0xD4` | |
| 2 | 2 | u16be flags | `0x0004` from AirPlay clients. `0x0007` was seen from iTunes; with it, 11,025 frames of receiver latency are added (as §4.7 does for audio-only). |
| 4 | 4 | u32be `rtp_play` | RTP timestamp of the frame that should be **heard** at `ntp_time` |
| 8 | 8 | u64be `ntp_time` | NTP format, client NTP domain (includes +2,208,988,800 s) |
| 16 | 4 | u32be `rtp_now` | RTP timestamp of the next packet the client is about to send |

[NTO] and [SPEC] describe the same 20 bytes for iTunes: an 8-byte header
without SSRC (so bytes 4..7 are the RTP timestamp), the NTP time, then
the RTP timestamp of the next packet, with the extension bit (`0x90`) on
the first sync after RECORD or FLUSH.

`latency_frames = sdiff32(rtp_now, rtp_play)` is the client's playout delay.
Observed values: 77,175 (1.75 s) for ALAC audio-only and 7,497 (0.17 s) for
AAC-ELD mirroring. The first field is the timestamp less the latency, the
second the raw timestamp.

On each sync packet: set the anchor `(anchor_rtp = rtp_play,
anchor_client_ns = ntp_to_ns(ntp_time))`. Section §4.7 uses it. Keep the
previous anchor to check consistency. A jump of more than 5 s from the
predicted mapping is a discontinuity: re-anchor, and treat the stream as
newly started.

**Retransmitted packet (type 0x56, `b1 = 0xD6`), ≥ 16 bytes:**

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x80` |
| 1 | 1 | `0xD6` |
| 2 | 2 | u16be. UNVERIFIED U11: probably the resend reply's own counter. Ignore it. |
| 4 | rest | the original data packet, complete: 12-byte RTP header plus encrypted payload |

Process bytes 4.. exactly like a data packet (§4.2, §4.3). Packets of exactly
8 bytes (an empty retransmission, with no data available on the client) have
been observed. Ignore them.

Ignore anything else.

### 4.5 Control port: resend requests (sent)

Send only if the stream SETUP gave a non-zero `controlPort`. The destination
is `client_ip:controlPort`, sent from the control socket so the
retransmissions come back to it. 8 bytes:

| Offset | Size | Value |
|---|---|---|
| 0 | 1 | `0x80` |
| 1 | 1 | `0xD5` (0x80 | 0x55) |
| 2 | 2 | u16be: the receiver's own request counter, +1 per request |
| 4 | 2 | u16be: first missing sequence number |
| 6 | 2 | u16be: count of consecutive missing packets |

Example for 258..260: `80 d5 00 01 01 02 00 03` (03d §G).

[NTO] and [SPEC] describe this request for iTunes as 12 bytes: an 8-byte
RTP header without SSRC, then the first missing sequence number and the
count. Neither gives an example, and no sender has yet been seen answering
either form. Rozan sends the 8-byte form above. **UNVERIFIED** (03 U27): a
Music session with packets dropped on purpose shows whether
retransmissions (0xD6) come back.

Policy:

- Request a gap when it is detected (§4.3 step 5), but only if the first
  missing packet's play deadline is more than `2 × RTT + 20 ms` away. RTT
  comes from the timing client.
- Retry once if the gap is still open after `RTT + 20 ms`. Never more than
  twice per sequence number.
- Mirroring AAC-ELD (85 ms latency, packets triplicated) gains little. Resends
  are optional there. Audio-only ALAC (2 s latency) should always request.

### 4.6 Timing port (NTP-style clock sync)

The **receiver** is the requester. The client answers. Start at the session
SETUP and stop at the full TEARDOWN.

Request, 32 bytes, from the timing socket to `client_ip:timingPort`:

| Offset | Size | Value |
|---|---|---|
| 0 | 1 | `0x80` |
| 1 | 1 | `0xD2` (0x80 | 0x52) |
| 2 | 2 | `00 07` |
| 4 | 4 | zero |
| 8 | 8 | reference timestamp: zero, or the T3 of the last reply |
| 16 | 8 | receive timestamp: zero, or the T4 of the last reply |
| 24 | 8 | **transmit timestamp T1**: local send time, NTP format, u64be |

Reply, 32 bytes, from the client to the timing socket:

| Offset | Size | Value |
|---|---|---|
| 0 | 1 | `0x80` |
| 1 | 1 | `0xD3` |
| 2 | 2 | `00 07` |
| 4 | 4 | zero |
| 8 | 8 | origin = T1 echoed |
| 16 | 8 | T2 = client receive time (client NTP domain) |
| 24 | 8 | T3 = client transmit time (client NTP domain) |

T4 = local receive time, read from `System.nanoTime()` right after
`receive()` returns. Java has no kernel receive timestamps.

**Local clock.** Use `System.nanoTime()`, converted to NTP format
(`sec = ns / 1e9`, `frac = ((ns % 1e9) << 32) / 1e9`). The client only echoes
T1, so the base does not matter. The same base is used by
`AudioTrack.getTimestamp()` (`AudioTimestamp.nanoTime`) and by
`MediaCodec.releaseOutputBuffer(index, renderTimestampNs)`, so all
scheduling stays in one clock. Do not use wall-clock time, which jumps. On
Android `nanoTime()` is `CLOCK_MONOTONIC` (time since boot) and is always
positive. On a desktop JVM it can be negative. There, encode
`nanoTime() − base`, with `base` captured at startup minus 1 s, and add `base`
back when decoding the echoed origin. Keep all other arithmetic in raw
`nanoTime()` values.

**Schedule.** Send the first request right away, then 7 more at 250 ms
intervals, then one every 3 s. A shorter burst of 3 at 300 ms, then one every
3 s, also works.

**Per reply:**

1. Check the length (≥ 32) and `b1 == 0xD3`. Check that the origin equals the T1 of
   one of the last 8 outstanding requests, and remove that request. Drop a
   reply that repeats an already-seen T3.
2. Convert T1..T4 to nanoseconds as signed 64-bit (the client values are
   ~2.2e18 ns, which fits in `Long`).
3. `delay δ = (T4 − T1) − (T3 − T2)`. Drop the reply if `δ ≤ 0` or `δ > 1 s`.
4. `offset θ = ((T2 − T1) + (T3 − T4)) / 2`. This is `client_ntp_ns − local_ns`.
5. Keep the last 8 samples (T4, θ, δ). The estimate is the θ of the sample
   with the **smallest δ** among them (NTP clock filter). Once an estimate
   exists, smooth it: `θ_used += 0.05 × (θ_best − θ_used)`. The first estimate
   is used directly.
6. Expose `θ_used`, the smallest δ (RTT) and `synced = (count ≥ 1)`.

If the client never replies (no `timingPort`, or a firewall), mirroring in
live mode (§5.2) still works. Audio-only playout then falls back to
arrival-time scheduling (§4.7, "fallback").

Optional: if a datagram with `b1 == 0xD2` arrives on the timing socket, reply
with `0xD3`, origin = its offset 24..31, T2/T3 = local times. UNVERIFIED U14:
whether any client sends these. It does no harm.

### 4.7 Playout timing

Definitions:

```
client_play_ns(R) = anchor_client_ns + sdiff32(R, anchor_rtp) * 1_000_000_000 / 44100
local_play_ns(R)  = client_play_ns(R) − θ_used + extra_ns
```

`R` is a packet's RTP timestamp and `client_play_ns` the client's intended
hearing time. `extra_ns` is the receiver's added latency:

| Mode | `extra_ns` | Reason |
|---|---|---|
| Audio-only (ct=2) | 250 ms (= `Audio-Latency: 11025` sent in RECORD) | For senders of AirPlay version 371 or later, adding 11,025 frames makes the output match what the client assumes. UNVERIFIED U4: make it a setting, default 250 ms. |
| Mirroring timestamped mode (ct=8) | `D + A` from §5.3 | |

**Audio-only (timestamped) algorithm:**

1. AudioTrack: 44,100 Hz, `CHANNEL_OUT_STEREO`, `ENCODING_PCM_16BIT`,
   `MODE_STREAM`, usage MEDIA / content MUSIC, buffer =
   max(`getMinBufferSize` × 2, 250 ms). Fire TV's mixer resamples 44.1 kHz to
   its output rate. No app-side resampling is needed.
2. Wait for the first sync packet and for `synced` from the timing client.
3. Start: let `R0` be the RTP timestamp of the packet at `head`. If
   `local_play_ns(R0) < now + 20 ms`, drop whole packets from the head until
   one is in the future. Start the track, write
   `(local_play_ns(R0) − now − est_output_latency) × 44100 / 1e9` frames of
   silence (`est_output_latency` starts at 0 and is refined in step 5), then
   write decoded packets in sequence order.
4. Writer loop: for the packet at `head`:
   - present: decode and write it, then `head++`.
   - missing, and `local_play_ns(R_head)` minus the frames already queued in
     the track is less than 10 ms away: write `spf` frames of silence, then
     `head++`. Keep the timeline intact. Never skip timeline.
   - missing but not yet due: wait for it (resend may arrive).
5. Drift and latency correction, about once per second: get
   `AudioTimestamp(framePosition, nanoTime)`. Keep a map from written-frame
   index to RTP timestamp, so `framePosition` maps to an RTP value `Rp`.
   `error = nanoTime − local_play_ns(Rp)` (positive = playing late).
   - `|error| > 50 ms`: correct in bulk. Drop that many frames from the next
     packets, or insert silence.
   - `1 ms < |error| ≤ 50 ms`: correct gradually. Drop one frame per written
     packet while late, or duplicate one frame per packet while early, until
     `|error| < 0.5 ms`. One frame is 22.7 µs, and client-versus-receiver drift
     is normally below 100 ppm (≈ 4.4 frames/s), which this rate covers.
     Dropping or repeating a single sample per 8 ms is inaudible.
6. Re-anchor on every sync packet. Re-anchor and restart from step 3 after a
   FLUSH (main file §6.2) or a 0x90 sync that moves the mapping by more than
   50 ms.

**Fallback without timing replies:** play on arrival with a fixed 250 ms
jitter buffer, using the buffer-depth control of §5.2.

**Device observation (iOS 27.2.1, Music, ALAC, 2026-11-26):** the receiver
waits 1 s in step 2 before falling back. With an iPhone it fell back after
the stream started and again after a FLUSH (a track skip): no usable sync
mapping existed 1 s after the first packet, although timing replies were
arriving (5 request/reply pairs in 15 s) and the iPhone sent sync packets
(type 0x54 with byte 0 = 0x80, 8 in the same 15 s, from a different source
port than its RTP data). How long after a start or FLUSH the first sync
comes is not yet measured; the receiver logs it ("audio sync (first=…) …
ms after the first packet; sender latency … frames"). **UNVERIFIED:** the
right wait. If iOS's sender latency is about 2 s, holding up to about that
long loses no audio.

### 4.8 Codecs

#### 4.8.1 ALAC (ct = 2)

The decoder is Rozan's own Kotlin ALAC decoder. Initialise it with this
`ALACSpecificConfig` (24 bytes, big-endian, per Apple's
`ALACMagicCookieDescription.txt`). The values are fixed for AirPlay realtime
audio, except `frameLength`, which comes from SETUP `spf`:

| Field | Type | Value | Source |
|---|---|---|---|
| frameLength | u32 | 352 (= SETUP `spf`) | SETUP |
| compatibleVersion | u8 | 0 | fixed |
| bitDepth | u8 | 16 | `audioFormat` 0x40000 |
| pb | u8 | 40 | fixed (Rice tuning parameter, used by the decoder) |
| mb | u8 | 10 | fixed (Rice initial history) |
| kb | u8 | 14 | fixed (Rice k limit) |
| numChannels | u8 | 2 | `audioFormat` 0x40000 |
| maxRun | u16 | 255 | fixed |
| maxFrameBytes | u32 | 0 (unknown) | fixed |
| avgBitRate | u32 | 0 (unknown) | fixed |
| sampleRate | u32 | 44100 | SETUP `sr` |

As bytes: `00 00 01 60 00 10 28 0a 0e 02 00 ff 00 00 00 00 00 00 00 00 00 00 ac 44`.
Decoders that expect the 36-byte `'alac'` atom form put
`00 00 00 24 61 6c 61 63 00 00 00 00` in front (03d §H). These are the same
values as the legacy SDP `a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100`.

Frame-level facts the decoder must handle (structure per Apple's ALAC format.
The bitstream decoding itself is outside this document):

- Each packet holds exactly one ALAC frame: one channel-pair element (tag 1)
  followed by the end tag (7). That makes the first byte `0x20`.
- A frame can carry the "partial frame" flag with an explicit sample count
  smaller than `frameLength`. Output exactly that many frames, and count the
  missing ones as part of the timeline.
- A frame can be an "escape" (uncompressed) frame: raw interleaved samples
  instead of predicted residuals.
- Output: 352 frames × 2 channels of signed 16-bit PCM, interleaved L/R, in
  the platform's native byte order for `AudioTrack.write(ShortArray, …)` or
  `write(ByteBuffer, …)` with `ByteOrder.nativeOrder()`. That is 1,408 bytes per
  packet.
- Cost: ALAC decoding at 44.1 kHz stereo is light (Rice decode plus a short
  LPC per channel), well within a Cortex-A35 core. Reuse the output arrays.

#### 4.8.2 AAC-ELD (ct = 8), via MediaCodec

| MediaFormat key | Value |
|---|---|
| MIME | `"audio/mp4a-latm"` (`MediaFormat.MIMETYPE_AUDIO_AAC`) |
| `KEY_SAMPLE_RATE` | 44100 |
| `KEY_CHANNEL_COUNT` | 2 |
| `KEY_AAC_PROFILE` | 39 (`MediaCodecInfo.CodecProfileLevel.AACObjectELD`) |
| `KEY_IS_ADTS` | 0 |
| `csd-0` | `F8 E8 50 00` (AudioSpecificConfig) |

`F8 E8 50 00` decoded (ISO/IEC 14496-3; 03d §H):

| Bits | Field | Value |
|---|---|---|
| 5 | audioObjectType | 31 (escape) |
| 6 | audioObjectTypeExt | 7, so AOT = 32 + 7 = **39 (ER AAC ELD)** |
| 4 | samplingFrequencyIndex | 4 = **44100 Hz** |
| 4 | channelConfiguration | **2** |
| 1 | frameLengthFlag | 1 = **480** samples per frame |
| 3 | section/scalefactor/spectral data resilience flags | 0, 0, 0 |
| 1 | ldSbrPresentFlag | 0 |
| 4 | eldExtType | 0 = ELDEXT_TERM |
| 4 | padding | 0 |

- Feed each decrypted payload (one AU) as one input buffer. Never feed the
  no-data marker. `presentationTimeUs` = extended RTP timestamp × 1e6 / 44100,
  where the RTP timestamp is extended to 64 bits across wraps.
- Output: 480 frames × 2 ch × 16-bit = 1,920 bytes per AU. Read the PCM
  encoding from the output format (`KEY_PCM_ENCODING`, default 16-bit).
- The first output buffer(s) may be decoder priming. Account for the
  difference in the timeline, but do not shift the anchor.
- Use the platform decoder (`c2.android.aac.decoder` /
  `OMX.google.aac.decoder`, both FDK-AAC based and ELD-capable). UNVERIFIED
  U19 per device. Check with `MediaCodecList.findDecoderForFormat()` at
  startup and log the result.

#### 4.8.3 Other `ct` values (optional)

- ct = 4, AAC-LC 44.1 kHz stereo, 1024 spf: `csd-0 = 12 10`, profile 2.
  Not observed from Apple clients on this path.
- ct = 1, LPCM: not observed from Apple clients. Byte order UNVERIFIED. Reject it
  at SETUP.

---

## 5. Mirroring A/V sync

### 5.1 The two clocks

- Video: header timestamp `ts` (u64le Q32.32, client uptime, no 1900 offset).
  `video_client_ntp_ns = ns(ts) + 2,208,988,800 × 1e9` (03b §3.5).
- Audio: `client_play_ns(R)` from the AAC-ELD sync packets (§4.7). Mirroring
  latency is ≈ 7,497 frames (0.17 s): AAC-ELD packets leave the client about
  0.17 s before their intended hearing time.
- Both are in the client NTP domain. Subtract `θ_used` to get local
  `System.nanoTime()` values.

UNVERIFIED U3: whether video `ts` (capture time) and `client_play_ns` (intended
hearing time) are meant to be presented together, or whether the client
expects video to be shown later by the audio latency. Rozan maps both to one
clock with **no** offset between them and adds a user setting `A`: an
adjustable audio delay in ms, ±1 s, default 0. Measure it on the target
device before choosing the default (main checklist A14).

### 5.2 Live mode (recommended default for screen mirroring)

Lowest latency and robust on weak hardware. It suits desktop and
second-monitor mirroring.

- Video: `releaseOutputBuffer(index, true)` as soon as each frame is decoded.
  No timestamp scheduling.
- Audio: decode AAC-ELD in sequence order when packets arrive, with a
  reorder window of 2 packets (~22 ms). If `head` is missing and 2 later
  packets are present, treat `head` as lost and play 480 frames of silence.
  Write to an AudioTrack created with `PERFORMANCE_MODE_LOW_LATENCY` (API 26+)
  and a buffer of `getMinBufferSize` × 2.
- Drift control by buffer depth: `queued = framesWritten − AudioTimestamp.framePosition`.
  Keep `queued` near a target (start at 40 ms). Above target + 10 ms, drop one
  frame per packet. Below target − 10 ms, repeat one. When
  `AudioTrack.getUnderrunCount()` increases, raise the target by 10 ms, up to
  100 ms.
- A/V offset: delay audio by `A` ms by raising the depth target, which is
  simple and bounded. If the measured skew needs video delayed instead, use
  timestamped mode.
- Needs neither the NTP offset nor the sync packets. Those still run for
  diagnostics and for switching to timestamped mode.

### 5.3 Timestamped mode (for video playback inside a mirrored screen)

1. One mapping from the client NTP domain to local time: `local = client_ntp − θ_used`.
   Before the NTP filter has a sample, use the arrival offset of the first
   video packet: `θ0 = video_client_ntp_ns(first) − local_arrival_ns(first)`.
2. Fixed target delay `D` (start at 150 ms, UNVERIFIED U25). It must exceed
   network jitter plus decode latency.
3. Video: when a decoded output buffer is available with
   `due = local(video_client_ntp_ns) + D`:
   - if `due < now − 1 frame period`, drop it (`releaseOutputBuffer(index, false)`)
     and count it. If more than 10 % of frames over 2 s are dropped, raise `D`
     by 20 ms, up to 500 ms.
   - otherwise hold the buffer until `due − 2 vsync periods`, then
     `releaseOutputBuffer(index, due)`.
4. Audio: schedule as in §4.7 with `extra_ns = D + A`.
5. Before the first AAC-ELD sync packet (up to ~1 s after start), audio has no
   mapping. Either hold audio until it arrives, which is simplest and loses at
   most ~1 s at start, or estimate it: assume that a packet
   arriving now has `client_play_ns = (local_now + θ0) + 0.2 s`, and replace the
   estimate at the first sync.

### 5.4 Choosing and measuring

- Default: live mode, `A = 0`. Offer "sync video playback" (timestamped mode)
  as a setting, or switch on it automatically when the client reports media
  playback. No reliable signal for that is known, so keep it a setting.
- Measure on a Fire TV Stick Lite with an A/V sync test video played in Safari
  on the client while mirroring. Record the TV with a phone camera at 240 fps,
  then set the default `A` (and `D`) from the result.
- Audio-only mode always uses timestamped playout (§4.7) because the client
  delays its own picture by the latency.
