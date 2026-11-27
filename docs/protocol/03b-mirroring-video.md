# 03b — Mirroring video stream (type 110)

Part of the 03 media spec. Conventions, key derivation (§2.3) and bounds (§8.2)
are in `03-media-streams.md`. Test vectors are in `03d-media-test-vectors.md`.

Derived from: openairplay airplay-spec `screen_mirroring/stream_packets.md`
(header layout, example packets); java-airplay `MirrorPacketHeaders.java`,
`MirrorPacketLayout.java`, `MirrorVideoFraming.java`.

## 3. The mirroring TCP stream

### 3.1 Connection handling

1. At SETUP 110 the receiver opens a TCP listening socket on an ephemeral port
   (backlog 1) and returns it as `dataPort`.
2. The client connects to it. Accept only if the peer address equals the RTSP
   client's address. Reject any other source by closing the socket at once.
3. Socket options: `SO_KEEPALIVE` on, idle 60 s, interval 10 s, 6 probes.
   On a desktop JVM 11+ set them through
   `jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE`/`TCP_KEEPINTERVAL`/`TCP_KEEPCOUNT`.
   On Android use `android.system.Os.setsockoptInt(fd, IPPROTO_TCP, opt, value)`
   with the Linux option numbers (TCP_KEEPIDLE = 4, TCP_KEEPINTVL = 5,
   TCP_KEEPCNT = 6), or skip them, because `/feedback` covers liveness. Set the
   receive buffer to ≥ 1 MiB so IDR bursts do not stall the client.
4. At most one data connection is active per stream. If it closes, release the
   decoder state and keep the listener open for the stream's lifetime.
   §6.5 of the main file covers reconnect (UNVERIFIED U13).
5. **The receiver never writes anything on this connection.** There is no
   acknowledgement, no IDR request and no flow-control message. Back-pressure
   happens only through TCP.
6. The stream ends with TEARDOWN 110, a full TEARDOWN, or the client closing
   the socket.

Data flow is a plain sequence of records, with no framing between them:

```
[128-byte header][payload: payload_size bytes][128-byte header][payload]...
```

Read exactly 128 bytes, then exactly `payload_size` bytes, looping on short
reads. A `read()` can return part of a header or payload. An end of stream
between records is a normal close. An end of stream inside a record is an
error close.

### 3.2 The 128-byte header

All multi-byte fields in the header are **little-endian**.

| Offset | Size | Type | Field | Notes |
|---|---|---|---|---|
| 0 | 4 | u32le | `payload_size` | Bytes of payload that follow the header. Bound it before allocating (§8.2). |
| 4 | 1 | u8 | `payload_type` | 0 = video (encrypted), 1 = codec config (clear), 2 = legacy heartbeat, 5 = streaming report. Skip any other value (read and discard the payload). |
| 5 | 1 | u8 | `type_flags` | Type 0: `0x10` = the payload contains an IDR picture, `0x00` = non-IDR (observed). Other types: 0. Informational, because the NAL types carry the same information. |
| 6 | 2 | 2 bytes | `option` | Observed values in the table below. Informational except for suspend detection (§3.8). |
| 8 | 8 | u64le | `timestamp` | NTP-format Q32.32 on the client clock **without** the +2,208,988,800 s offset (§3.5). Meaningless for type 5. For type 1 it equals the timestamp of the type-0 packet that follows. |
| 16 | 112 | | type-specific | Type 1: geometry floats (below). Others: zero, ignore. |

Observed `option` bytes (offset 6, offset 7):

| Packet | Bytes 6, 7 | Meaning |
|---|---|---|
| type 0 | `00 00` | encrypted video |
| type 1 | `16 01` | H.264 SPS+PPS, stream running |
| type 1 | `56 01` | H.264 SPS+PPS, stream stopping (client screen off/sleep) |
| type 1 | `1E 01` | H.265 VPS+SPS+PPS, running |
| type 1 | `5E 01` | H.265, stopping |
| type 2 | `1E 00` | legacy once-per-second heartbeat (third-party AirMyPC) |
| type 5 | `00 00` | streaming report |
| any (very old iOS) | `06 00` | old protocol (airplay-spec dump) |

Type-1 geometry fields (f32le). The values are whole numbers stored as floats:

| Offset | Field | airplay-spec iPad example |
|---|---|---|
| 16 | source width | 864.0 |
| 20 | source height | 648.0 |
| 24–39 | zero | |
| 40 | source width (duplicate) | 864.0 |
| 44 | source height (duplicate) | 648.0 |
| 48 | unknown, possibly an x offset (UNVERIFIED U8) | 208.0 |
| 52 | unknown, possibly a y offset (UNVERIFIED U8) | 36.0 |
| 56 | width | 863.0 |
| 60 | height | 647.0 |
| 64–127 | zero | |

The geometry is informational. Take the decode size from the SPS or from the
`MediaCodec` output format (`KEY_WIDTH`/`KEY_HEIGHT` and the crop keys), and
the display aspect ratio from the output format. In Kotlin, read the floats
with `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN).getFloat(off)`.

### 3.3 Type 1: codec configuration

The payload is not encrypted and does not touch the CTR keystream. It is sent:

- at stream start,
- at every change of video format (rotation, resolution, client display
  change),
- when the client stops sending video (suspend option) and again when it
  resumes.

The next type-0 packet has the same timestamp and holds an IDR picture. On
M1/M2 Macs that IDR packet starts with an SEI NAL (type 6).

**Codec selection by payload**

| Condition | Codec |
|---|---|
| `payload_size == 0` | The client wants a codec this receiver did not advertise (HEVC without features bit 42). Stop the mirroring stream, show "unsupported" and log it. Prevent it by advertising a display no taller than 1080. |
| payload bytes 4..7 = `68 76 63 31` (`"hvc1"`) | H.265 (only possible with bit 42 on, out of scope). Layout below. |
| otherwise | H.264 `AVCDecoderConfigurationRecord` (avcC) |

A change of codec in the middle of a stream (H.264 → H.265 or the reverse) is
an error. Close the stream.

**H.264 avcC layout** (ISO/IEC 14496-15). Multi-byte fields here are
**big-endian**:

| Offset | Size | Field | Observed |
|---|---|---|---|
| 0 | 1 | configurationVersion | 1 |
| 1 | 1 | AVCProfileIndication | 100 (High), 77, 66 |
| 2 | 1 | profile_compatibility | e.g. 0xC0 |
| 3 | 1 | AVCLevelIndication | e.g. 40 (4.0). M1/M2 Macs use a higher level. |
| 4 | 1 | `0xFC | lengthSizeMinusOne` | 0xFF, so 4-byte NAL lengths (UNVERIFIED U6 that it is always 3) |
| 5 | 1 | `0xE0 | numOfSequenceParameterSets` | 0xE1 (one SPS) |
| 6 | 2 | SPS length (u16be) | e.g. 16 |
| 8 | n | SPS NAL (starts with byte 0x67, NAL type 7) | |
| … | 1 | numOfPictureParameterSets | 1 |
| … | 2 | PPS length (u16be) | e.g. 4 |
| … | m | PPS NAL (starts with byte 0x68 or 0x28, NAL type 8) | |
| … | rest | High-profile extension (chroma_format etc.) or nothing | Ignore |

Parsing steps:

1. Check `payload_size ≥ 7` and byte 0 == 1. Check `(byte4 & 3) == 3`.
2. `numSPS = byte5 & 0x1F`. For each SPS, read the u16be length, check it
   against the remaining bytes and the §8.2 bounds, and take the bytes.
3. Read `numPPS` (u8) and do the same for each PPS.
4. Ignore trailing bytes.
5. Build Annex-B parameter sets: `csd0 = 00 00 00 01 ‖ SPS` (first SPS),
   `csd1 = 00 00 00 01 ‖ PPS` (first PPS). Extra SPS/PPS, which have not been
   observed, can be appended to `csd0`/`csd1` with their own start codes.

Worked example (airplay-spec iPad codec packet) in 03d §D.

**Applying a codec config** (efficiency-aware policy):

1. If no decoder exists, create `MediaCodec` for `"video/avc"`. Build a
   `MediaFormat` with `KEY_WIDTH`/`KEY_HEIGHT` (from the SPS, or the header
   floats at 56/60, or 1920×1080), `csd-0`, `csd-1`, `KEY_MAX_WIDTH` = 1920 and
   `KEY_MAX_HEIGHT` = 1088 (for adaptive playback), and where the API allows,
   `KEY_LOW_LATENCY` = 1 (API 30+, ignored where unsupported) and
   `KEY_PRIORITY` = 0 (realtime). Configure it on the output `Surface` and
   start it.
2. If a decoder exists and the new SPS **and** PPS bytes equal the current
   ones, do nothing. This is common: the same config is re-sent at resume.
3. If they differ:
   - If the decoder reports `FEATURE_AdaptivePlayback` and the new size fits
     the max size, queue the new parameter sets **in-band**: put
     `00 00 00 01 SPS 00 00 00 01 PPS` at the front of the next type-0 input
     buffer (the IDR that follows).
   - Otherwise call `stop()`, `configure()` with the new csd, then `start()`.
     A full `release()` and re-create also works but is slower on weak
     hardware.
4. Always also prepend the in-band SPS+PPS to the first IDR after any
   (re)configure. It costs nothing and covers decoders that ignore csd on
   reconfigure.

**H.265 layout (optional, only with bit 42).** Observed: payload
bytes 4..7 are `"hvc1"`, and at payload offset 0x75 (117) there are three
parameter-set arrays in hvcC style. Each array is
`[1 byte: 0xA0 / 0xA1 / 0xA2 (array_completeness bit + NAL type 32 VPS / 33 SPS / 34 PPS)]`
`[u16be numNalus = 1][u16be nalLength][NAL bytes]`, in the order VPS, SPS, PPS.
UNVERIFIED (U9): whether the offset is fixed. A robust parser would search for
the `hvcC` box inside the payload. For `MediaCodec` `"video/hevc"`, `csd-0` is the
Annex-B concatenation `00 00 00 01 VPS 00 00 00 01 SPS 00 00 00 01 PPS`.

### 3.4 Type 0: encrypted video

Processing per packet:

1. Read `payload_size` bytes into a reused buffer, or straight into a
   `MediaCodec` input buffer after `dequeueInputBuffer`, leaving room for
   prepended in-band parameter sets.
2. **Decrypt the whole payload** with the stream's CTR keystream, continuing
   where the previous type-0 payload stopped (main file §2.3). There is no
   clear prefix: every payload byte is ciphertext.
3. The plaintext is an **AVCC access unit**: a sequence of
   `[u32be NAL length][NAL bytes]`. Walk it:
   - `pos = 0`. While `pos < payload_size`: need `pos + 4 ≤ payload_size`,
     `len = u32be(pos)`, need `1 ≤ len ≤ payload_size − pos − 4`. Overwrite the 4
     length bytes **in place** with `00 00 00 01`. Check that the first NAL byte
     has bit 7 clear (forbidden_zero_bit). Note the NAL type `byte & 0x1F`.
     Then `pos += 4 + len`.
   - At the end `pos` must equal `payload_size`.
   - Rewriting in place gives Annex-B with no copy and no size change. The
     NAL bytes already contain their emulation-prevention bytes, so nothing
     else changes.
4. If the walk fails, the key or the keystream position is wrong, or the data
   is corrupt. Drop the packet. Its bytes have still consumed keystream, so
   the next packet stays aligned. Count the failures and apply the §8.2 rule.
5. NAL types seen: 1 (non-IDR slice), 5 (IDR slice), 6 (SEI, before IDR on
   M1/M2 Macs), 7/8 only in type-1 packets normally (tolerate them in-band),
   14 (prefix NAL, seen from the third-party AirMyPC). Types 2–4 (data
   partitions) are unexpected, but pass them on.
6. Queue one input buffer per packet (UNVERIFIED U5: one access unit per
   packet). Set `presentationTimeUs` from the header timestamp (§3.5,
   converted to µs, or relative to the first packet). Set
   `BUFFER_FLAG_KEY_FRAME` if a NAL of type 5 is present (or header byte 5 is
   0x10).
7. Until the first type-1 config has been applied, drop type-0 packets, but
   still decrypt them to keep the keystream aligned.

Do not drop type-0 packets before the decoder to save CPU. The stream uses
inter prediction, and IDRs may come only at start and at format changes
(UNVERIFIED U15), so a dropped P-frame corrupts the picture until the next
IDR. If decoding falls behind, block on `dequeueInputBuffer` (TCP
back-pressure makes Apple's encoder adapt) and drop at the **output** stage
instead (`releaseOutputBuffer(index, false)` for frames already late, see 03c
§5).

### 3.5 Timestamps

`timestamp` (u64le at offset 8) is Q32.32 on the client's clock. Observed: the
seconds field is the client's uptime. The airplay-spec example has
seconds = 57071, about 15.9 h, with **no** 1900 offset. The client's NTP replies
and audio sync packets use the same clock **plus** 2,208,988,800 s.

```
video_ns           = (ts >>> 32) * 1e9 + ((ts & 0xFFFFFFFF) * 1e9) >>> 32
video_client_ntp_ns = video_ns + 2_208_988_800 * 1e9     # same domain as NTP replies / sync packets
video_local_ns     = video_client_ntp_ns − θ             # θ = client_ntp − local offset from 03c §4.6
```

(Adding the 1900 offset to video timestamps, and to neither NTP nor sync
timestamps, puts all three on one clock.)
The timestamp is the client's capture/presentation time of the picture. 03c
§5 covers how it relates to audio.

### 3.6 Types 2 and 5, and unknown types

- Type 2: legacy heartbeat, sent about once a second by old protocol senders,
  normally with `payload_size` 0. Read and ignore it.
- Type 5: "streaming report", about once per second. The payload is a bplist
  of client-side video statistics. When the client screen is locked it is
  sometimes followed by a 25,000-byte trailer of unknown content that repeats
  each time. Read the payload (bounded) and ignore it. The header has no valid
  timestamp.
- Unknown type: read the payload (bounded, §8.2) and ignore it. Do not touch
  the keystream.

### 3.7 Resolution and orientation changes

A format change arrives as: a type-1 packet with new SPS/PPS and new geometry
floats, then a type-0 packet with the **same timestamp** containing an IDR.
Apply it as in §3.3. After the decoder emits
`INFO_OUTPUT_FORMAT_CHANGED`, read the new width, height and crop rectangle and
adapt the view's aspect-fit layout. Portrait iPhones produce tall, narrow
pictures (height ≤ the advertised 1080): letterbox them, never stretch.

### 3.8 Suspend and resume

- A type-1 packet whose option byte 6 is `0x56` (H.264) or `0x5E` (H.265) means
  the client is stopping video (screen off, sleep). UNVERIFIED U7: bit 0x40 of
  byte 6 as the general rule. Keep the session, stop expecting frames, and
  either keep the last frame or show a paused state. Do not tear down.
- A later type-1 packet with `0x16`/`0x1E` means resume. Apply its config as in
  §3.3. The IDR follows.
- Pause and resume the video pipeline on exactly these transitions.

### 3.9 Feeding MediaCodec efficiently

- Use asynchronous `MediaCodec` callbacks or a dedicated decode thread. Keep the
  socket reader on its own thread, and hand off input buffers with an index
  queue.
- Decrypt in place in the input buffer. The only copy is socket → buffer.
- Output: `releaseOutputBuffer(index, true)` as soon as the frame is decoded
  (live mode), or `releaseOutputBuffer(index, renderTimeNs)` (timestamped
  mode). `renderTimeNs` is in the `System.nanoTime()` base. See 03c §5.
- Advertise `maxFPS` 30 (main file §7) on Fire TV Stick Lite class hardware.
