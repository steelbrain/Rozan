# 01 — Discovery, control channel and session flow

Scope: how the Rozan AirPlay receiver is found on the network (mDNS/DNS-SD), how
the RTSP/HTTP control connection is framed, and every control request a current
macOS (15/26) or iOS/iPadOS (18/26) sender sends for screen mirroring and for
audio. Pairing and the on-screen code are in `02-pairing.md`. Media streams
(mirroring TCP stream, audio RTP, NTP timing) are in `03-*`. FairPlay internals
are in `04-*`.

Conventions used in this document:

- "Receiver" = Rozan. "Client" or "sender" = the Apple device.
- Hex byte strings are written MSB first, two hex digits per byte.
- `uint`, `bool`, `real`, `string`, `data`, `array`, `dict` are property-list
  types. Integers in plists are usually signed 64-bit on the wire. Unsigned
  values above 2^63-1 arrive as 16-byte integers (e.g. `streamConnectionID`,
  see 03 U24); decode those as unsigned 64-bit when the high 8 bytes are zero
  and reject them otherwise.
- **UNVERIFIED:** marks a statement that no reference confirms for iOS 18/26
  or macOS 15/26. Each one says what would confirm it. All of them are also
  listed in section 9.
- "Mode D", "Mode P" and "Mode O" are the three access-control profiles that
  are defined in `02-pairing.md` section 1. In short:
  - **Mode D** (Digest code): a new 4-digit code is
    shown for every connection and entered as the AirPlay password. RTSP
    Digest authentication runs on the first SETUP.
  - **Mode P** (PIN pairing, the default): legacy SRP PIN pairing. The receiver identity
    key is rotated after each session, and Mode D is the fallback for
    senders that cannot PIN-pair.
  - **Mode O** (open): no code. Debug builds only.

Sources legend (a path is `owner_repo/` and a path inside that GitHub
repository):

| Tag | Reference | License |
|---|---|---|
| [SPEC] | `openairplay_airplay-spec/src/`, published at https://openairplay.github.io/airplay-spec/ (iTunes 10.6, Apple TV 5.0 era) | CC / docs |
| [NTO] | "Unofficial AirPlay Protocol Specification", https://nto.github.io/AirPlay.html (iTunes 10.6 against AirTunes/130.14), read 2026-11-26 | docs |
| [MUSIC] | Device test 2026-11-26: Music/1.7 on macOS 27.0.1 (MacBook Pro Mac15,9) against `:tools:devserver` in Mode P on port 7000. Wire capture on the receiver and Music's own log (`log stream`, categories `remoteaudio`, `ampplay`) | observation |
| [PYATV] | `postlund_pyatv/` | MIT |
| [PHP] | `mazer666_PhairPlay/` (untested on real TVs, as its own README says) | Apache-2.0 |

---

## 1. Receiver identity and fixed profile constants

The receiver presents itself as an **Apple TV 3rd generation that runs
AirPlay server 220.68**. This is the profile that current Apple senders
accept for this protocol variant: NTP timing, a type-110 mirroring stream, a
type-96 realtime audio stream, legacy (non-HomeKit) pairing, and no PTP or
buffered audio. Changing `model`, `srcvers` or the feature bits
moves senders onto protocol variants (HomeKit pairing, encrypted RTSP, PTP,
buffered audio) that this spec does not cover.

| Item | Value | Notes |
|---|---|---|
| Model | `AppleTV3,2` | `model` (TXT and /info), `am` (RAOP TXT) |
| Source version | `220.68` | `srcvers`, `vs`, /info `sourceVersion`, `Server: AirTunes/220.68` |
| `vv` | `2` | string in TXT, `uint` in /info |
| `pi` | UUID string, lowercase, 36 chars | Generate a random v4 UUID once per install and keep it. |
| `deviceid` | 6-byte MAC-format id, `xx:xx:xx:xx:xx:xx` | See below |
| Identity key | Ed25519 key pair. The public key is `pk`: 64 lowercase hex chars in TXT, 32 raw bytes in /info | Lifetime depends on the mode (02 §2) |
| Display uuid | UUID string | Generate one per install. |
| Receiver name | User-configurable UTF-8 string | See §2.1 for length limits |

**deviceid.** Android apps cannot read the real Wi-Fi MAC (they get
`02:00:00:00:00:00`). Generate 6 random bytes once per install. Then set
bit 1 of byte 0 (locally administered) and clear bit 0 (unicast). Keep the
result. It is a device identity, not a pairing record, so storing it does
not break "nothing remembered". The text forms are:

- TXT `deviceid` and /info `deviceID`/`macAddress`: lowercase hex with colons,
  e.g. `02:11:22:33:44:55`. Current clients accept lowercase. Apple's own
  devices use uppercase.
- RAOP instance-name prefix: 12 uppercase hex digits without separators, e.g.
  `021122334455`.

The deviceid must stay stable. Senders associate the remembered password and
the picker entry with it. Two receivers with the same
deviceid overwrite each other in the sender's caches [PHP
`docs/spec/TECHNICAL_SPEC.md`, issue #23].

---

## 2. mDNS / DNS-SD advertisement

Sources: [SPEC `service_discovery.md`, `features.md`, `status_flags.md`], [PYATV
`docs/documentation/protocols.md`], [PHP `docs/spec/TECHNICAL_SPEC.md`].

### 2.1 Services, instance names and ports

Register **two** services. Both point to the **same TCP port**, the RTSP
control port. Use 7000 (what Apple TVs use).

| Service type | Instance name | Port |
|---|---|---|
| `_airplay._tcp.local.` | `<name>` | 7000/tcp |
| `_raop._tcp.local.` | `<DEVICEID12>@<name>`, e.g. `021122334455@Living Room` | 7000/tcp |

Rules:

- `<name>` is the configurable receiver name. Cut the name at the first
  `.local` substring. DNS-SD instance labels are at most
  63 bytes of UTF-8 (RFC 6763 §4.1.1). The RAOP prefix uses 13 bytes
  (`XXXXXXXXXXXX@`), so limit `<name>` to **50 UTF-8 bytes**. Truncate on a
  code-point boundary.
- **Android 14+ name conflict:** the platform registers its own
  `_androidtvremote2._tcp` service under the TV's device name. Android's mDNS
  daemon checks instance names for uniqueness across *all* service types. If
  the AirPlay name equals the TV name, registration fails with a name
  conflict and the receiver never appears in any picker. When registration
  reports a conflict, retry with a suffixed name such as `"<name> (AirPlay)"`
  or `"<name> (2)"`. [PHP `TECHNICAL_SPEC.md` "instance name", their issue
  #23] **UNVERIFIED:** observed by PhairPlay, not by me. A Google TV on
  Android 14 with the TV name equal to the receiver name would confirm it.
- Advertise A **and** AAAA records for the host. NsdManager does this by
  itself. Clients may connect over IPv6 (link-local or ULA), so the RTSP
  listener **must accept IPv6 as well as IPv4** (a dual-stack socket, or
  two sockets). macOS 27.0.1 mirrored over a ULA address in the device
  test of 2026-11-26 (§2.5).
- On a name change, a key rotation (Mode P), or a mode change, update the
  TXT records of both services. Then re-announce, or unregister and
  re-register. NsdManager cannot update TXT in place. Re-registering makes
  the receiver disappear from pickers briefly. A responder that supports TXT
  updates (for example JmDNS `ServiceInfo.setText`) avoids that.
- Hold a Wi-Fi `MulticastLock` while advertising, and re-register after
  Wi-Fi reconnect or standby. [PHP README]

### 2.2 `_airplay._tcp` TXT record

Keys in the order Rozan emits them. DNS-SD does not require an order. All
values are ASCII strings.

| Key | Value (Rozan) | Meaning / why |
|---|---|---|
| `deviceid` | `02:11:22:33:44:55` | Receiver id (§1) |
| `features` | `0x527FFEE6,0x0` (Mode D/O) or `0x5A7FFEE6,0x0` (Mode P) | 64-bit feature mask written as `0x<low32>,0x<high32>`: uppercase hex, `0x` prefix, no leading zeros, and the high word always present. See §2.4 |
| `pw` | `true` in Mode D, `false` in Mode P and Mode O | A password is required. With bit 27 clear, `pw=true` makes the sender expect a password (Digest) challenge. With bit 27 set, `pw=true` makes it start the PIN flow (`/pair-pin-start`), but macOS 27 then asks for the code in a "password" text field. Mode P sends `pw=false` with the PINRequired bit in `flags` instead, which on macOS 27 gives the same PIN flow with the on-screen code entry for mirroring (§2.5, device test 2026-11-26). The RAOP record keeps its own `pw` (§2.3) |
| `flags` | `0x4` (Mode D/O), `0xc` (Mode P) | Status flags, 20-bit, hex. `0x4` in every mode; Mode P adds 0x8 PINRequired. See §2.5 |
| `model` | `AppleTV3,2` | |
| `pk` | 64 lowercase hex chars | Receiver Ed25519 public key (32 bytes). Mode P rotates it (02 §8) |
| `pi` | UUID | Pairing / group identifier |
| `srcvers` | `220.68` | |
| `vv` | `2` | |

Keys that Apple TVs send and that Rozan must **not** send: `psi`, `gid`,
`igl`, `gcgl`, `acl`, `rsf`, `protovers`, `osvers`, `fex`, `btaddr`, `hmid`,
`tsid`. They belong to AirPlay 2 group, HomeKit and PTP features that this
profile does not implement. [SPEC `service_discovery.md`]

### 2.3 `_raop._tcp` TXT record

| Key | Value | Meaning |
|---|---|---|
| `ch` | `2` | Audio channels |
| `cn` | `0,1,2,3` | Codecs: 0 PCM, 1 ALAC, 2 AAC(-LC), 3 AAC-ELD |
| `da` | `true` | RFC 2617 Digest authentication supported (Mode D needs this) |
| `et` | `0,3,5` | Encryption types: 0 none, 3 FairPlay, 5 FairPlay SAPv2.5 |
| `vv` | `2` | |
| `ft` | same string as `features` above | |
| `am` | `AppleTV3,2` | |
| `md` | `0,1,2` | Metadata: 0 text (DMAP), 1 artwork, 2 progress |
| `rhd` | `5.6.0.0` | Unknown meaning. The Apple TV 3 value |
| `pw` | `false` (Mode O), `true` (Mode D, P) | As `_airplay` `pw` (§2.2). Mode P keeps `true` here although `_airplay` has `false`: macOS shows its code entry with this split (§2.5, 02 §8.6 test 6) |
| `sf` | `0x4` (Mode O), `0x84` (Mode D), `0x8c` (Mode P) | Status flags, see §2.5 |
| `sr` | `44100` | Sample rate |
| `ss` | `16` | Sample size |
| `sv` | `false` | Software volume not required |
| `tp` | `UDP` | Audio transport |
| `txtvers` | `1` | |
| `vs` | `220.68` | |
| `vn` | `65537` | 0x00010001 = version 1.1 |
| `pk` | same as `_airplay` `pk` | |

### 2.4 Features bitmask

Bit names from [SPEC `features.md`].

| Bit | Set? | Name (spec) | Why it is set or clear |
|---|---|---|---|
| 0 | 0 | Video (HTTP `/play`) | Video/HLS is not supported. Setting it makes senders open HTTP `/play`, `/reverse`, `/server-info` sessions |
| 1 | 1 | Photo | Part of the known-working mask. Photo requests are not implemented (see §6.20) |
| 2 | 1 | VideoFairPlay | Known-working mask |
| 3 | 0 | VideoVolumeControl | |
| 4 | 0 | VideoHTTPLiveStreams | HLS not supported |
| 5 | 1 | Slideshow | Known-working mask |
| 6 | 1 | (unnamed) | Known-working mask |
| 7 | 1 | Screen (mirroring) | **Required for mirroring** |
| 8 | 0 | ScreenRotate | |
| 9 | 1 | Audio | **Required for audio** |
| 10 | 1 | (unnamed) | Known-working mask |
| 11 | 1 | AudioRedundant | |
| 12 | 1 | FPSAPv2pt5_AES_GCM | FairPlay SAP v2.5. **Required** (fp-setup) |
| 13 | 1 | PhotoCaching | Known-working mask |
| 14 | 1 | Authentication4 (FairPlay) | **Required** |
| 15 | 1 | MetadataFeature1 — artwork | |
| 16 | 1 | MetadataFeature2 — progress | |
| 17 | 1 | MetadataFeature0 — text | |
| 18-21 | 1 | AudioFormat1..4 | Bits 19 and 20 are needed for the AirPlay 2-era sender path |
| 22 | 1 | (unnamed; possibly "Authentication type 4") | Known-working mask |
| 23 | 0 | Authentication1 (RSA) | No RSA / Apple-Challenge |
| 24 | 0 | | |
| 25 | 1 | (unnamed) | Known-working mask |
| 26 | 0 | HasUnifiedAdvertiserInfo | |
| **27** | **0 in Mode D/O, 1 in Mode P** | SupportsLegacyPairing | When set, the sender runs legacy pairing (`/pair-setup` or the PIN flow) before `fp-setup`. When clear, it skips pairing entirely and goes from `GET /info` straight to `fp-setup` |
| 28 | 1 | (unnamed) | Known-working mask |
| 29 | 0 | | |
| 30 | 1 | RAOP | RAOP is spoken on this port, so no separate AirTunes port is needed |
| 31 | 0 | | |
| 32-63 | 0 | | Bit 42 (SupportsScreenMultiCodec = H.265) is optional and belongs to the media agent. Bits 38, 40, 41, 43, 46, 48 and 51 (CoreUtils/HomeKit/transient/system pairing, buffered audio, PTP, MFi) **must stay clear**. Setting them moves senders to HomeKit pairing with an encrypted control channel and PTP, which this spec does not cover |

Computed values (from `/tmp/rozan-spec-a/vectors.py`):

| Profile | TXT string | /info `features` (uint) |
|---|---|---|
| Mode D / O (bit 27 off) | `0x527FFEE6,0x0` | 1384120038 |
| Mode P (bit 27 on) | `0x5A7FFEE6,0x0` | 1518337766 |
| Mode P + H.265 (bit 42) | `0x5A7FFEE6,0x400` | 4399564848870 |
| Mode D + H.265 | `0x527FFEE6,0x400` | 4399430631142 |

The Mode D/O mask is the Mode P mask `0x5A7FFEE6` with bit 27 cleared:
`0x527FFEE6`.

### 2.5 Status flags (`flags`, `sf`, /info `statusFlags`)

Bit names [SPEC `status_flags.md`]: bit 2 (0x4) audio cable attached, bit 3
(0x8) PINRequired, bit 6 (0x40) SupportsAirPlayFromCloud, bit 7 (0x80)
PasswordRequired, bit 9 (0x200) OneTimePairingRequired, bit 10 (0x400)
DeviceWasSetupForHKAccessControl, bit 17 (0x20000) ReceiverSessionIsActive.

Values Rozan sends, with `pw` (§2.2, §2.3) because it goes with them. Each
record has its own `pw`:

| Where | Mode O | Mode D | Mode P |
|---|---|---|---|
| `_airplay._tcp` `pw` | `false` | `true` | `false` |
| `_airplay._tcp` `flags` | `0x4` | `0x4` | `0xc` (cable + PIN) |
| `_raop._tcp` `pw` | `false` | `true` | `true` |
| `_raop._tcp` `sf` | `0x4` | `0x84` (cable + password) | `0x8c` (cable + PIN + password) |
| `/info` `statusFlags` (uint) | 68 (`0x44`) | 68 | 76 (`0x4C`: 68 + 0x8 PINRequired) |

The Mode O and Mode D values are known to work with current clients.
Neither puts the PIN or password bits into `_airplay` `flags` or /info
`statusFlags`. Mode P's first values were `pw=true` in both records,
`flags=0x4`, RAOP `sf=0x8c` (cable + PIN + password) and `statusFlags` 68.

Mode P changed `_airplay` and /info from those first values because of
device tests on 2026-11-26. Sender: a MacBook Pro (Mac15,9) on macOS 27.0.1
(build 26A434), against `:tools:devserver` in Mode P on a Linux host on the
same LAN.

- Mirroring (Control Center > Screen Mirroring) with the first Mode P
  values: macOS asked for an AirPlay "password" in a text field with a
  "Remember password" option. Typing the on-screen code there worked. The
  requests were the PIN flow: `GET /info`, `POST /pair-pin-start` (the
  sender closed the connection), then on a new connection
  `POST /pair-setup-pin` ×3, `POST /pair-verify` ×2, `POST /fp-setup` ×2,
  SETUP, RECORD, SETUP (mirroring). Mirroring ran (H.264 1920x1080 at about
  30 fps, AAC-ELD 44.1 kHz stereo audio).
- Mirroring with `pw=false` in **both** records, `flags=0xc`, RAOP
  `sf=0xc` and `statusFlags` 76: macOS showed the four-box entry for an
  on-screen code, as it does for an Apple TV that requires one, and sent
  the same requests in the same order. The code was accepted on the first
  try and mirroring worked.
- Mirroring with the Mode P values in the table (the RAOP record back at
  the first values, `pw=true`, `sf=0x8c`; 02 §8.6 test 6): the four-box code
  entry again, no password field, the code accepted on the first try, the
  same requests, and H.264 1920x1080 mirroring. That connection came over
  IPv6 (a ULA address).
- Music (audio-only AirPlay from macOS) does not use the AirPlay 2 flow
  at all. It speaks classic RAOP: a short probe connection when the
  receiver is selected, which ends after `OPTIONS *` by design, then
  `ANNOUNCE` and a `SETUP` with a `Transport` header when playback starts
  (§4.3.2) [MUSIC]. The earlier runs, with the first Mode P values, with
  `pw=false` in both records and in Mode D, only looked at the probe, so
  they say nothing about which of these values Music needs. The run that
  reached `ANNOUNCE` used the Mode P values in the table.

Feature bit 27 was set and RAOP `sf` carried 0x8 PINRequired in every
mirroring run, and macOS ran the PIN flow every time. The RAOP record was
the same in the first run (password field) and the last (code entry), so
the change that selects the code entry is among `_airplay` `pw`,
`_airplay` `flags` and `statusFlags`; which one is not known. Rozan keeps
the RAOP record at the first values because the code entry does not need
them changed.

iOS 27.2.1 (iPhone18,2, Control Center > Screen Mirroring, same day) ran
the PIN flow with the Mode P values in the table and also with `pw=false`
in both records: `GET /info`, `POST /pair-pin-start`, then
`pair-setup-pin` ×3, `pair-verify` ×2, `fp-setup` ×2, SETUP, RECORD,
SETUP, and mirrored (H.264 498x1080). With both advertisements it asked
for the code in a single password-style field with a number-only keyboard,
not in four boxes. So iOS honours PINRequired, but neither advertisement
gets it to the four-box entry; which value would is not known.
**UNVERIFIED:** iOS/iPadOS 18/26 and macOS 15/26. A sender that ignores
PINRequired would read bit 27 with `pw=false` as transient pairing
(02 §1.1), and Mode P refuses its pair-verify (02 §4.3), so it could not
connect. 02 §8.6 test 5 checks this.

**HomeKit** PIN pairing uses `0x204`/`0x604` instead (OneTimePairingRequired,
then HK access control once paired). That belongs to the HomeKit profile and
is **not** used here.

ReceiverSessionIsActive (0x20000) is optional while busy. Rozan does not set
it.

### 2.6 TXT wire format and the `txtAirPlay` blob

TXT rdata is a sequence of `<len:1 byte><key=value bytes>` strings (RFC 6763 §6).
The same bytes are returned inside `GET /info` as `txtAirPlay`/`txtRAOP` (§6.1),
so build them once and keep them in sync with what is announced. Example
`_airplay._tcp` rdata for **Mode P** (features bit 27 on, `pw=false`,
`flags=0xc`). It is 215 bytes, and pk is the Ed25519 public key of the
example seed `80 81 .. 9f`:

```
1a 'deviceid=02:11:22:33:44:55'  17 'features=0x5A7FFEE6,0x0'  08 'pw=false'
09 'flags=0xc'  10 'model=AppleTV3,2'  43 'pk=cd14b37f...f3aa'
27 'pi=2e388006-13ba-4041-9a67-25dd4a43d536'  0e 'srcvers=220.68'  04 'vv=2'
hex:
1a64657669636569643d30323a31313a32323a33333a34343a35351766656174
757265733d307835413746464545362c3078300870773d66616c736509666c61
67733d307863106d6f64656c3d4170706c655456332c3243706b3d6364313462
3337663935366539353331393466663766623733623364383164636335363164
3631613735333830393462376333653161363433656535663361612770693d32
653338383030362d313362612d343034312d396136372d323564643461343364
3533360e737263766572733d3232302e36380476763d32
```

---

## 3. Control connection

Sources: [SPEC `audio/rtsp_requests/*.md`, `data/RTSP-get-info-req.bin`], [PYATV
`pyatv/protocols/airplay/auth/legacy.py`, `server_auth.py`].

### 3.1 Transport

- A single TCP listener on port 7000 carries **both** RTSP/1.0 and HTTP/1.1
  messages. They are told apart by the protocol token on the request line.
- A sender opens a connection, sends requests one at a time, and waits for
  each response before the next. Keep one parser per connection. Process
  requests strictly in order. Never reorder responses.
- Connections are long-lived: one control connection lasts the whole
  mirroring or audio session, and the sender sends `POST /feedback` about
  every 2-3 s as a heartbeat. The receiver closes a connection after
  **60 s** without any data (the idle timeout).
- Deadlines (Rozan policy, not wire behaviour). The idle timeout bounds one
  read, not a request: a peer that sends one header byte every 59 s keeps
  its slot for weeks, and three such addresses fill the 12 slots of 02 §9.1.
  So, until a connection is authenticated (01 §5), each request must arrive
  whole, headers and body, within **10 s** of its first byte, and the
  connection must authenticate within **3 min** of opening (a code lives
  120 s, 02 §5.1, so this leaves time for a person to read a code, let it
  expire and type the next). A connection past either deadline is closed
  without a reply. An authenticated connection must have a session within
  **30 s** (senders send the session SETUP right after pair-verify, and
  Digest authenticates that SETUP itself, or the classic `ANNOUNCE` that
  comes right before the classic `SETUP`), or it is closed too; once its
  session runs, only the idle timeout and the `/feedback` timeout apply.
  A whole-session TEARDOWN ends the connection's authentication (§6.13),
  which starts the 3 min again.
- Classic RAOP sessions (§4.3.2) and liveness (Rozan policy). The public
  descriptions of the classic flow [NTO][SPEC] show no `/feedback`, and
  whether Music 1.7 sends it is not known. So the `/feedback` timeout
  (§6.14) applies to a classic session only from its first `/feedback`.
  And when a classic session's control connection has been silent for the
  idle timeout, it stays open as long as the session's audio sockets
  accepted a packet (RTP data, a retransmission or a sync) during that
  time; it closes after an idle period with neither. A sender that
  vanished is therefore noticed 60-120 s after its last packet or
  request.
  **UNVERIFIED:** what Music sends on the control connection during a long
  track and while paused (§9 item 10).
- When the table is full (02 §9.1), a new connection takes the place of an
  unauthenticated one instead of being refused: an address at its own cap
  replaces its oldest unauthenticated connection; otherwise the oldest
  unauthenticated connection of the address with the most connections is
  closed. Authenticated or streaming connections are never closed to make
  room; with nothing to replace, the new connection is closed on accept.
- The PIN flow (Mode P) uses more than one connection. The sender sends
  `GET /info` + `POST /pair-pin-start` on one connection, closes it, then
  opens a new connection for `pair-setup-pin`. Keep the displayed PIN
  receiver-wide (or per client IP), **not per connection**. Pairing state
  after `pair-setup-pin` is per connection.
- Single active client. While a session is streaming, answer requests from
  **other** client IPs with `409 Conflict`. Never answer 409 to the IP that
  owns the session (it may open extra connections, for example the PIN
  flow), and never answer it while no session is streaming. A product option may instead let a new
  sender take over (the old session is torn down).

### 3.2 Message framing

```
<METHOD> SP <request-URI> SP <RTSP/1.0 | HTTP/1.1> CRLF
<Header-Name>: <value> CRLF
...
CRLF
<body: exactly Content-Length bytes>
```

- Header names are case-insensitive. Values have leading whitespace trimmed.
- The body length is given only by `Content-Length`. No chunked encoding is
  used. If `Content-Length` is missing, the body is empty.
- Request-URI forms seen: absolute path (`/info`, `/pair-setup`,
  `/fp-setup`, `/feedback`, ...), the `*` form for OPTIONS, and absolute
  `rtsp://<receiver-ip>/<decimal session number>` for SETUP, RECORD,
  GET_PARAMETER, SET_PARAMETER, FLUSH and TEARDOWN (e.g.
  `rtsp://192.168.1.20/2934859221`). Route RTSP methods by method, and POSTs
  by path.
- `GET /info` may carry a query: `GET /info?txtAirPlay&txtRAOP RTSP/1.0`
  [SPEC `data/RTSP-get-info-req.bin`]. Match the path with or without a query.
- Limits (anti-DoS): max 32 header fields, name ≤ 64 bytes, value ≤ 1024
  bytes (above these, `431 Request Header Fields Too Large`), request line
  ≤ 2048 bytes. Body ≤ 1 MiB, except `SET_PARAMETER` with an `image/*` type
  (≤ 8 MiB) and `ANNOUNCE` (≤ 8 KiB, §6.17). Above the limit, reply `413`
  and close.

### 3.3 Request headers senders use

| Header | Example | Meaning / receiver use |
|---|---|---|
| `CSeq` | `0`, `1`, ... | RTSP sequence number. Must be echoed. Present on all RTSP/1.0 requests from AirPlay senders. A non-negative decimal integer. It restarts at 0 or 1 on each new connection |
| `X-Apple-ProtocolVersion` | `1` | Informational |
| `User-Agent` | `AirPlay/<ver>`, e.g. `AirPlay/380.10.1`, `AirPlay/980.67.2` (iOS 27) | Sender's AirPlay version. Informational; the old Windows sender AirMyPC can be told apart by it |
| `DACP-ID` | `ADA239F4521B1802` | 64-bit hex id of the sender's DACP remote-control service (`iTunes_Ctrl_<DACP-ID>` on `_dacp._tcp`). Music 1.7 sends it on every request of the classic flow; Rozan uses it to key the classic Digest code (02 §6.4), since that flow has no `deviceID` |
| `Client-Instance` | `F7C2CDC26FC71581` | Music 1.7 and iTunes: the same value as `DACP-ID` [MUSIC][NTO]. Informational |
| `Active-Remote` | `753030410` | Decimal token to send back in DACP remote-control requests. Optional |
| `Content-Type` | see §3.5 | |
| `Content-Length` | | |
| `Connection` | `close` (seen on the first `GET /info`), `keep-alive` | Close after replying when it is `close` |
| `X-Apple-Client-Name` | `Living Room iPad` | **UNVERIFIED:** seen from third-party senders on `/pair-pin-start` to OS-27 Apple receivers. Apple senders probably send it too. Show it on the code screen when present |
| `X-Apple-Device-ID`, `X-Apple-Session-ID` | `0xa4d1d2800b68`, UUID | Only on HTTP/1.1 video/photo requests (§6.20) |
| `X-Apple-HKP` | `3`, `4` | HomeKit pairing only (`3` normal, `4` transient). Not sent to this profile (02 §7) |
| `Authorization` | `Digest username="...", realm="raop", nonce="...", uri="...", response="..."` | Mode D, on the SETUP retry (02 §6). Classic RAOP: on the ANNOUNCE retry, with `username="iTunes"` (02 §6.4) |
| `RTP-Info` | `seq=1234;rtptime=5678` | FLUSH and RECORD |
| `Range` | `npt=0-` | RECORD |
| `Transport` | `RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002` | Classic SETUP only (§6.8.1) [MUSIC][NTO] |
| `Session` | `1` | Classic flow: the receiver sends `Session: 1` in the classic SETUP reply; iTunes repeats it on later requests [NTO]. Not checked |

### 3.4 Response requirements

Every RTSP response:

```
RTSP/1.0 <code> <reason> CRLF
CSeq: <same value as the request> CRLF
Server: AirTunes/220.68 CRLF
Audio-Jack-Status: connected; type=digital CRLF      (all RTSP responses except RECORD)
[Content-Type: <type> CRLF]                           (when a body is present)
Content-Length: <n> CRLF                              (always; 0 when empty)
[other headers] CRLF
CRLF
<body>
```

- **CSeq echo is mandatory.** Senders match responses by CSeq. Parse the
  request's CSeq as an integer and echo its decimal form. If the request has
  no CSeq or an invalid one (negative or not a number), reply
  `400 Bad Request` without CSeq and close.
- The `Server` header carries the profile's source version (§1).
  **UNVERIFIED:** whether any sender checks it.
- `Audio-Jack-Status: connected; type=digital` goes on every RTSP response
  except RECORD. RECORD uses `connected; type=analog` (§6.10). Legacy
  behaviour.
- Senders accept empty responses without `Content-Length` when they have no
  `Content-Type`. Sending `Content-Length: 0` is standard RTSP.
  **UNVERIFIED:** no Apple sender has been seen to reject it, but that every
  sender accepts it on every empty reply is not confirmed.
- HTTP/1.1 responses use `HTTP/1.1 <code> <reason>` and carry no `CSeq` or
  `Audio-Jack-Status`.

Status codes used:

| Code | Reason phrase | When |
|---|---|---|
| 200 | `OK` | Success, including requests that are accepted but ignored (§6.18, §6.19) |
| 400 | `Bad Request` | Malformed body, wrong fp-setup length, bad SETUP `ekey`/`eiv` sizes, malformed SDP or `Transport` |
| 401 | `Unauthorized` | Digest challenge on the first SETUP or the classic ANNOUNCE, with `WWW-Authenticate` (02 §6) |
| 403 | `Forbidden` | Optional: client explicitly blocked |
| 409 | `Conflict` | Another client owns the receiver (§3.1) |
| 413 | `Payload Too Large` | Body limit |
| 415 | `Unsupported Media Type` | ANNOUNCE with a `Content-Type` other than `application/sdp`, or an SDP for something this receiver does not play (§6.17) |
| 431 | `Request Header Fields Too Large` | Header limits |
| 451 | `Parameter Not Understood` | GET/SET_PARAMETER without `Content-Type` |
| 453 | `Not Enough Bandwidth` | Not used |
| 455 | `Method Not Valid in This State` | Classic SETUP on an authenticated connection without an ANNOUNCE (§6.8.1) |
| 461 | `Unsupported Transport` | Classic SETUP with a `Transport` this receiver cannot serve (TCP, multicast, a mode other than `record`, §6.8.1) |
| 470 | `Connection Authorization Required` | Pairing failure (wrong PIN proof, bad GCM tag), a pairing request while locked out, or a request that needs pairing/auth first (02 §9). Apple TV 3 uses this phrase |
| 500 | `Internal Server Error` | Internal failure (fp-setup) |
| 501 | `Not Implemented` | Unknown RTSP method, unsupported FairPlay version, unsupported HTTP requests (§6.20) |

### 3.5 Body types

| Content-Type | Used by |
|---|---|
| `application/x-apple-binary-plist` | `GET /info` request (qualifier) and response, `pair-setup-pin` both ways, SETUP both ways, TEARDOWN request, `/audioMode`, `/command`, `/feedback` (if any body) |
| `application/octet-stream` | `/pair-setup`, `/pair-verify`, `/fp-setup` both ways |
| `text/parameters` | GET_PARAMETER and SET_PARAMETER (`volume`, `progress`) |
| `application/x-dmap-tagged` | SET_PARAMETER track metadata (DMAP) |
| `image/jpeg`, `image/png`, `image/none` | SET_PARAMETER artwork. `image/none` (empty body) means no artwork. The type is unreliable, so sniff the magic bytes (`FF D8 FF` JPEG, `89 50 4E 47` PNG) |
| `application/sdp` | `ANNOUNCE` in the classic RAOP flow, which macOS Music uses (§4.3.2, §6.17) |

Binary plists are Apple `bplist00`. Parse with a bounded parser: limit object
count (for example 10 000), nesting depth (32) and total size. Treat any
parse failure as `400`. Never let parser exceptions (including stack
overflow) escape the connection handler. [PHP SEC-11/SEC-16]

#### 3.5.1 Binary plist (`bplist00`) essentials

This is the parser and writer that 03-* and 04-* rely on. Use an existing
library, for example dd-plist (`com.googlecode.plist:dd-plist`, MIT), or
implement this subset. The format is Apple's public CoreFoundation format.

- The file starts with the 8 ASCII bytes `bplist00`. It ends with a 32-byte
  trailer:
  - 6 unused bytes;
  - `offsetIntSize` (1 byte);
  - `objectRefSize` (1 byte);
  - `numObjects` (8 bytes, big-endian);
  - `topObject` (8 bytes, big-endian; the index of the root object);
  - `offsetTableOffset` (8 bytes, big-endian).
- The offset table holds `numObjects` big-endian integers of
  `offsetIntSize` bytes each. Each is the byte offset of one object.
- Each object starts with a marker byte. The high nibble is the type; the
  low nibble is a size or count. A low nibble of `0xF` means the real count
  follows as an int object.

| Marker | Type | Notes |
|---|---|---|
| `0x08` / `0x09` | false / true | |
| `0x1n` | integer, 2ⁿ bytes, big-endian | 1, 2 and 4 bytes are unsigned. 8 bytes is **signed** two's complement: `streamConnectionID` can arrive negative, so reinterpret it as unsigned 64-bit. 16 bytes is used only for values ≥ 2⁶³ |
| `0x22` / `0x23` | real, float32 / float64, big-endian | `initialVolume` and `refreshRate` are written as float64 |
| `0x33` | date | Not used here |
| `0x4n` | data, n bytes | |
| `0x5n` | ASCII string, n bytes | |
| `0x6n` | UTF-16BE string, n code units | Sender `name` values with non-ASCII characters arrive this way |
| `0xAn` | array | Followed by n object references of `objectRefSize` bytes each |
| `0xDn` | dict | n key references, then n value references. Keys are strings |

When writing: use the ASCII form for ASCII strings and UTF-16BE otherwise.
Use the smallest integer width that fits (unsigned 1/2/4 bytes, signed 8
bytes) and float64 for reals.

### 3.6 Is the control channel encrypted after pairing?

**No, not in this profile.** After legacy pair-verify (Mode P, or senders
that pair by themselves), RTSP stays plaintext for the whole session: the
receiver never encrypts the control channel, and current senders keep
speaking plaintext to it. What a receiver would have to do to make a sender
switch to an encrypted control channel in this profile is not known.

The pair-verify shared secret only affects media keys: the audio/mirroring
AES key is hashed with it (02 §4.5, for the media agent).

Encrypted RTSP (ChaCha20-Poly1305 frames, keys from HKDF with `Control-Salt`)
exists only for **HomeKit-style** pairing. That needs feature bits
38/46/48, which Rozan does not set. It is described for reference in
02 §7.4. A Rozan receiver that never sets those bits never has to decrypt
RTSP.

---

## 4. Typical sessions

Each arrow is one request/response on the control connection unless marked.
Requests in brackets are optional or depend on the sender.

### 4.1 Mirroring, Mode D (bit 27 off, `pw=true`)

```
conn A: GET /info  (bplist {"qualifier":["txtAirPlay"]})  -> 200 {txtAirPlay: <rdata>}
conn A: GET /info  (no body)                              -> 200 {full info plist}
        [OPTIONS *                                         -> 200 Public: ...]
conn A: POST /fp-setup (16 bytes)                         -> 200 (142 bytes)
conn A: POST /fp-setup (164 bytes)                        -> 200 (32 bytes)
conn A: SETUP rtsp://.../<n>  {ekey, eiv, timingPort, ...} (no Authorization)
                                                          -> 401 WWW-Authenticate: Digest realm="raop", nonce="..."
        (receiver shows a fresh 4-digit code; the sender may first retry with a
         stale remembered password -> 401 again; then it prompts the user)
conn A: SETUP ... Authorization: Digest ...               -> 200 {eventPort:0, timingPort:<udp>}
conn A: RECORD                                            -> 200 Audio-Latency: 11025
conn A: SETUP {streams:[{type:110, streamConnectionID,...}]} -> 200 {streams:[{type:110, dataPort:<tcp>}]}
        (sender connects to dataPort; mirroring starts — see 03)
conn A: SET_PARAMETER text/parameters "volume: -11.5"     -> 200
conn A: SETUP {streams:[{type:96, ct, spf, controlPort,...}]} -> 200 {streams:[{type:96, dataPort, controlPort}]}
conn A: [GET_PARAMETER "volume"]                          -> 200 "volume: -11.500000"
conn A: POST /feedback  (every ~2-3 s)                    -> 200
...
conn A: TEARDOWN {streams:[{type:96}]}                    -> 200   (audio stops, mirroring continues)
conn A: TEARDOWN {streams:[{type:110}]}  or TEARDOWN (no body) -> 200; sender closes the connection
```

RECORD can come before or after the stream SETUPs. macOS 27.0.1 and iOS
27.2.1 sent `SETUP, RECORD, SETUP(streams)` (§2.5), while pyatv sends
`SETUP, SETUP(streams), RECORD`. Accept RECORD at any point after the first
SETUP.

### 4.2 Mirroring, Mode P (the default; bit 27 on, `_airplay` `pw=false` and `flags=0xc`)

```
conn A: GET /info (qualifier), GET /info                 -> 200 (pk = current identity key)
conn A: POST /pair-pin-start (no body)                    -> 200 (empty); receiver shows PIN
        sender closes conn A; the sender shows its code prompt (macOS 27: the
        four-box code entry, §2.5) and the user types the PIN
conn B: POST /pair-setup-pin {method:"pin", user:<sender deviceID>} -> 200 {pk:B, salt:s}
conn B: POST /pair-setup-pin {pk:A, proof:M1}             -> 200 {proof:M2}
conn B: POST /pair-setup-pin {epk, authTag}               -> 200 {epk, authTag}
conn B: POST /pair-verify (68 bytes, byte0 = 1)           -> 200 (96 bytes)
conn B: POST /pair-verify (68 bytes, byte0 = 0)           -> 200 (empty)
conn B: POST /fp-setup x2, SETUP, RECORD, SETUP(streams) ... as in 4.1, but SETUP is not challenged
```

A sender that already trusts the receiver's current `pk` skips
`/pair-pin-start` and `/pair-setup-pin` and starts with `/pair-verify`. This
is why Mode P rotates the identity key (02 §8). A sender that does not
PIN-pair reaches SETUP without pairing and gets the Mode D challenge.
macOS Music does not use this flow at all; it speaks classic RAOP (§4.3.2).

### 4.3 Audio only (Music app, system audio)

There are two flows. iOS Music and the audio of a mirroring session use the
AirPlay 2 flow of §4.1/§4.2 (§4.3.1). macOS Music uses classic RAOP, the
AirTunes flow with `ANNOUNCE` and a `Transport` header (§4.3.2).

#### 4.3.1 The AirPlay 2 flow (iOS Music)

Same as 4.1 (or 4.2) without the type-110 stream: the first SETUP (often with
`isScreenMirroringSession` false or absent), then
`SETUP {streams:[{type:96,...}]}`, RECORD, `SET_PARAMETER` (volume, progress,
DMAP metadata, artwork), FLUSH on pause/seek/track change, and TEARDOWN.
iOS 27.2.1 Music ran it after the PIN flow, with an ALAC 44.1 kHz stereo
stream (02 §8.6 test 3). **UNVERIFIED:** the exact request order from iOS
18/26. Accept these requests in any order.

#### 4.3.2 Classic RAOP (macOS Music)

Observed 2026-11-26, Music/1.7, macOS 27.0.1 [MUSIC]
(`User-Agent: Music/1.7 (Macintosh; OS X 27.0.1) AppleWebKit/625.1.29.11.28`),
against a receiver advertising the Mode P values of §2.5. Music's log calls
the output "legacy AirPlay" (`POD change eSystemSoundOutput ->
eLegacyAirPlay`). It sends no `GET /info` and no pairing request in any
mode tried, and no sender name or `deviceID` anywhere. Every request
carries `Client-Instance` and `DACP-ID` (the same 64-bit hex value) and
`Active-Remote`. There are two connections.

**Selection probe.** When the user picks the receiver as the output, Music
opens a connection and:

1. sends `POST /fp-setup` twice: FairPlay **v2** (byte 4 = `0x02`; screen
   mirroring and iOS use v3). The first body was
   `46504c590201010000000004020001bb`; its byte 14 is the FairPlay mode, 1
   in this run and 0 in an earlier one (04 §1.1). Both got 200;
2. sends `OPTIONS *` with no `Apple-Challenge` header, which got 200;
3. closes the connection within about 10 ms of that reply.

Music's log reports success: `selection task complete. status:0`, then
`added device ... to selection array`. Earlier runs on the same day took
this probe for the failure; it is the normal end of a selection. Rozan
answers both requests without authentication and shows no code (§5),
because a code at this point would pop a prompt every time the receiver is
selected and nothing would consume it.

**Play session.** When playback starts, Music opens a new connection
(observed up to the SETUP; the rest from [NTO] and [SPEC], which describe
iTunes 10.6 against AirTunes/130.14):

```
conn B: POST /fp-setup (16 bytes, v2)                -> 200 (142 bytes)
conn B: POST /fp-setup (164 bytes)                   -> 200 (32 bytes)
conn B: ANNOUNCE rtsp://<sender-ip>/<n>  Content-Type: application/sdp, SDP body (§6.17)
                                                     -> 401 WWW-Authenticate: Digest realm="raop", nonce="..."
        (Rozan shows a code; Music asks for the AirPlay password; the user types the code)
conn B: ANNOUNCE ... Authorization: Digest username="iTunes", realm="raop", ...
                                                     -> 200
conn B: SETUP rtsp://<sender-ip>/<n>, no body,
        Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002
                                                     -> 200 Transport: RTP/AVP/UDP;unicast;mode=record;
                                                            server_port=<udp>;control_port=<udp>;timing_port=<udp>
                                                        Session: 1
conn B: RECORD (Range, RTP-Info)                     -> 200 Audio-Latency: 11025
        (RTP audio to server_port, sync packets to control_port, NTP requests
         from the receiver to the sender's timing_port: 03c)
conn B: SET_PARAMETER volume / DMAP / artwork / progress, FLUSH on pause
conn B: TEARDOWN                                     -> 200 Connection: close
```

What was observed on conn B: the two `fp-setup` requests; an `ANNOUNCE`
without `Authorization` (an experimental receiver build answered it 200
without a challenge); then the `SETUP` above, which the receiver of that
day answered `400` ("SETUP without a body"), after which Music logged
`RTSP failure ... RTSP code:400 status:-15008` and `sending teardown ...
after it successfully connected but setup failed`, and sent a `TEARDOWN`
without a body. The SETUP request URI is the ANNOUNCE URI, the sender's own
address and the SDP session id (`o=` line). Separately, the same day, an
experimental build that answered with a Digest `401` made Music ask for a
password; Music answered with `username="iTunes"`, `realm="raop"` and a
response whose HA2 is MD5 of `METHOD ":" uri`, and the receiver's Digest
check (02 §6.2) accepted it. [NTO] shows iTunes sending the Digest retry
of an `ANNOUNCE` on the same connection with the next CSeq.

Rozan's rules for this flow: the code gates `ANNOUNCE` with RTSP Digest in
**both** modes (02 §6.4); the classic SETUP needs that authentication
(§5, §6.8.1); the stream maps onto the realtime type-96 ALAC stream with
the FairPlay key used as is (03 §1.6, §2.1). Observed 2026-11-26 with
Music 1.7 on macOS 27.0.1, against a dev receiver built with the code
check skipped for `ANNOUNCE` (an experiment build, not committed): the
selection probe, then on play `ANNOUNCE` (accepted: ALAC, 352 frames),
the classic SETUP and its `Transport` reply, `RECORD`, `SET_PARAMETER`
for volume and track info, `FLUSH` and `TEARDOWN`. The receiver kept
3,746 ALAC packets (29.9 s); wrapped in a CAF file with the `fmtp`
values and decoded by macOS's own ALAC decoder they were the 440 Hz test
tone at a steady level from start to end, so the `fpaeskey` unwrap, the
key used as is and the CBC IV are right. The first sync packet came
before the first audio packet. **UNVERIFIED:** Music's password prompt
and its Digest retry of `ANNOUNCE` with this build (INTEROP checks 18,
19), and a pause longer than the idle timeout (check 20).

Music's log also shows `**ERROR**: AirPlay Device selected that has no
SystemID!` right after the selection; playback went on regardless. What
it refers to is not known.

Port: in earlier runs that day, while the receiver advertised 47000, and
once while it advertised 7000, Music connected to TCP 5000 instead, where
the host's firewall dropped the connection. Later, with 7000 advertised, it
connected to 7000. In the run above its first connection attempts failed
(`status=-3260`) and a later one succeeded. Why it chose 5000 is not known.

### 4.4 Reconnect

Every new mirroring or audio start is a new TCP connection that begins again
at `GET /info`. In Mode D the receiver issues a new code for it (02 §6). In
Mode P the identity key was rotated when the last session ended, so the
sender runs the PIN flow again (02 §8). Classic RAOP has no `GET /info`:
each playback start is a new connection (§4.3.2) whose ANNOUNCE gets a new
code (02 §6.4).

---

## 5. Pre-authentication gate (control level)

Requests the receiver **must** answer before the connection is authenticated
(authenticated = a Digest answer succeeded on this connection, at the first
SETUP or at a classic ANNOUNCE, or PIN pairing + pair-verify succeeded on
this connection):

`GET /info`, `OPTIONS`, `POST /pair-pin-start`, `POST /pair-setup-pin`,
`POST /pair-setup`, `POST /pair-verify`, `POST /fp-setup`, the first `SETUP`
(it carries the Digest exchange), `ANNOUNCE` (it carries the Digest
exchange of the classic flow, 02 §6.4), `POST /feedback` (reply 200, do
nothing), and `TEARDOWN` (reply 200, drop state).

Everything else on an unauthenticated connection (stream SETUP, the classic
SETUP with a `Transport` header, RECORD, GET/SET_PARAMETER, FLUSH,
`/audioMode`, `/command`) gets `470 Connection Authorization Required` and
is otherwise ignored. In Mode O the gate is open. Details and rate limits
are in 02 §9.

---

## 6. Request reference

For each request: what the sender sends, what the receiver does, what it
replies, and what goes wrong without a correct reply.

### 6.1 `GET /info` (RTSP/1.0)

There are two variants on the same connection, usually back to back:

**(a) Qualifier request.** It has `Content-Type: application/x-apple-binary-plist`
and the body `{"qualifier": ["txtAirPlay"]}` (an array of strings; may be
`"txtRAOP"`). The query form `?txtAirPlay&txtRAOP` is also possible. The
response contains **only** the requested blobs:

```
{ "txtAirPlay": <data: current _airplay._tcp TXT rdata> }      (and/or "txtRAOP": <data>)
```

The sender reads the current TXT values (including `pk`, `pw` and `features`)
from here instead of relying on its mDNS cache. Mode P depends on this when
`pk` rotates.

**(b) Full request** (no body). Response `Content-Type: application/x-apple-binary-plist`:

| Key | Type | Rozan value | Required? |
|---|---|---|---|
| `deviceID` | string | `02:11:22:33:44:55` | yes |
| `macAddress` | string | same as deviceID | known-working set |
| `pk` | data (32) | Ed25519 public key, same as TXT `pk` | yes |
| `features` | uint | 1384120038 (Mode D/O) or 1518337766 (Mode P) | yes, must equal TXT |
| `name` | string | receiver name (without the RAOP prefix) | yes |
| `pi` | string | same as TXT `pi` | known-working set |
| `vv` | uint | 2 | known-working set |
| `statusFlags` | uint | 68 (Mode D/O) or 76 (Mode P), see §2.5 | known-working set |
| `keepAliveLowPower` | bool | true | known-working set |
| `keepAliveSendStatsAsBody` | bool | true | known-working set. Senders then send stats in `/feedback` bodies |
| `sourceVersion` | string | `220.68` | yes |
| `model` | string | `AppleTV3,2` | yes |
| `initialVolume` | real | current volume in AirPlay dB, e.g. `-15.0` (range −30.0..0.0, −144.0 = mute) | known-working set |
| `audioLatencies` | array of 2 dicts | `{type:100 (uint), audioType:"default" (string), inputLatencyMicros:0 (uint), outputLatencyMicros:false (bool)}` and the same with `type:101` | known-working set. `outputLatencyMicros` as a **bool** is a quirk that works. AirServer sends integer 0 |
| `audioFormats` | array of 2 dicts | `{type:100, audioInputFormats:67108860, audioOutputFormats:67108860}` and the same with `type:101` (0x3FFFFFC) | known-working set |
| `displays` | array of 1 dict | see below | **required for mirroring** (no display → no screen-mirroring target). **UNVERIFIED:** inferred, not tested |

`displays[0]` (values for a 1920x1080 TV; the media agent may change the size):

| Key | Type | Value |
|---|---|---|
| `uuid` | string | per-install UUID |
| `widthPhysical` | uint | 0 |
| `heightPhysical` | uint | 0 |
| `width` | uint | 1920 |
| `height` | uint | 1080 |
| `widthPixels` | uint | 1920 |
| `heightPixels` | uint | 1080 |
| `rotation` | bool | false |
| `refreshRate` | real | 1/60 = 0.016666666666666666 (seconds per frame, **not** Hz) |
| `maxFPS` | uint | 30 (default; 60 allowed) |
| `overscanned` | bool | false |
| `features` | uint | 14 |

Which keys are mandatory: deviceID, features, pk, model, sourceVersion and
name are needed for any connection. `displays` is needed for mirroring. The
rest are a known-working set. **UNVERIFIED:** the minimal set for
iOS 18/26 and macOS 15/26; removing keys one at a time on a real device would
establish it. Send the full set.

A `GET /info` with no `CSeq` (the Bluetooth LE discovery path) is out of
scope. Answer it like (b) without the audio/display keys, or ignore it.

Failure: no or invalid /info → the sender reports it cannot connect.

### 6.2 `OPTIONS *`

Reply 200 with
`Public: ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER`,
the RTSP methods this receiver implements. An AirTunes/130.14 receiver
answers `ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS,
GET_PARAMETER, SET_PARAMETER, POST, GET` [NTO]; Rozan leaves out `PAUSE`,
which it answers 501, and `POST`/`GET`, which are routed by path. Whether
Music needs `ANNOUNCE` in the list is not known: the selection probe ended
the same way with and without it, and the run that reached `ANNOUNCE`
listed it [MUSIC]. Modern senders may skip OPTIONS. If an `Apple-Challenge`
header is present (RSA authentication, et=1), ignore it and send no
`Apple-Response`. RSA is not advertised (bit 23 = 0, `et` has no 1). macOS
27.0.1 Music sends `OPTIONS *` after `fp-setup`, with no `Apple-Challenge`,
as the last request of its selection probe, and closes the connection right
after the 200 (§4.3.2). It needs no authentication (§5).

### 6.3 `POST /pair-pin-start`

Empty body, no Content-Type. Receiver: generate or show the 4-digit code
(02 §5.1). Reply `200` with no body. The sender then closes the connection
and continues on a new one. Under lockout (02 §9), reply 470 and show
nothing. Handle it in **every** mode, including Mode D/O: some MDM-managed
senders always PIN-pair, even when `pw=false`. Details: 02 §5.1.

### 6.4 `POST /pair-setup-pin`

Three binary-plist steps. See 02 §5. Reply 200 with a bplist, or 470.

### 6.5 `POST /pair-setup`

32-byte octet-stream body (the sender's Ed25519 public key). The reply is
the receiver's 32-byte Ed25519 public key. See 02 §3. Wrong length: 400.

### 6.6 `POST /pair-verify`

68-byte octet-stream bodies. Byte 0 = 1 for step 1 (reply 96 bytes), byte 0 =
0 for step 2 (reply empty). See 02 §4.

### 6.7 `POST /fp-setup` (FairPlay SAP; internals in 04-*)

Position: after pairing (if any), before the first SETUP. Two round trips,
`Content-Type: application/octet-stream` both ways:

| Step | Request | Response |
|---|---|---|
| 1 | 16 bytes, starts with `46 50 4C 59` ("FPLY"). Byte 4 = FairPlay major version (`0x03`, or `0x02`, which macOS Music sends, §4.3.2) | 142 bytes |
| 2 | 164 bytes | 32 bytes |

Error replies: length not 16/164 → 400. Byte 4 not 2/3 → 501. Internal
failure → 500. The FairPlay state is per connection. It is needed later to
decrypt SETUP `ekey`, or the classic ANNOUNCE's `fpaeskey` (§6.17), which
is the same 72-byte key blob (04 §1.5). Without a valid fp-setup the sender
aborts the connection. Music's v2 replies were checked against a real Mac
on 2026-11-26: with the last 8 bytes of the 32-byte step 2 reply inverted,
Music reported the receiver "not compatible with this version of Music",
and with the 142-byte step 1 reply changed the same way, its handshake
check failed [MUSIC].

### 6.8 `SETUP` (two phases)

`Content-Type: application/x-apple-binary-plist`, URI `rtsp://<ip>/<n>`. The
same handler serves both phases. A request can contain phase 1 keys, a
`streams` array, or both. A SETUP with **no body and a `Transport`
header** is the classic variant instead (§6.8.1). A SETUP with neither a
body nor a `Transport` header gets 400.

**Phase 1** is recognised by the presence of `ekey` (data) **and** `eiv`
(data). It sets up the session.

Request keys (control-relevant ones are in **bold**; the rest are
informational or for the media agent):

| Key | Type | Notes |
|---|---|---|
| **`ekey`** | data, 72 bytes | FairPlay-wrapped AES key (04-*). Wrong size → 400 |
| **`eiv`** | data, 16 bytes | AES IV (03-*) |
| **`deviceID`** | string | Sender id, e.g. `AA:BB:CC:DD:EE:FF`. Used for the Mode D code binding and logs |
| **`name`** | string | Sender's device name. Show it on the code prompt |
| **`model`** | string | e.g. `iPhone16,2`, `MacBookPro18,3` |
| **`timingProtocol`** | string | `NTP` expected; log anything else as an error. `None`/PTP is not supported |
| **`timingPort`** | uint | Sender's NTP port (03-*) |
| `macAddress`, `sessionUUID`, `sourceVersion`, `osName`, `osVersion`, `osBuildVersion` | string | Informational |
| `isScreenMirroringSession` | bool | true for mirroring |
| `isRemoteControlOnly` | bool | If true, the sender wants an AirPlay 2 remote-control session, which is unsupported; log an error |
| `et`, `statsCollectionEnabled`, `senderSupportsRelay`, `isMultiSelectAirPlay`, `groupContainsGroupLeader`, `combinedGetInfoWithControlSetup` (iOS 27), `updateSessionRequest` (iPadOS 27) | various | Ignore. iOS 27 and iPadOS 27 send the last two; they need no special handling |

Receiver actions for phase 1, in order:

1. Mode D, or Mode P without completed pairing on this connection: run the
   Digest challenge (02 §6). Reply `401` and stop until the request comes
   back with a valid `Authorization` header.
2. Check `ekey`/`eiv` sizes. FairPlay-decrypt `ekey` into the 16-byte AES
   key (04-*). If legacy pair-verify completed on this connection, the media
   key is the first 16 bytes of `SHA-512(aesKey16 || ecdhSharedSecret32)`;
   otherwise it is the raw FairPlay key (02 §4.5). Hand it to the media agent.
3. Start the timing (NTP) client and reserve the local timing port (03-*).
4. Reply `200`:

```
{ "eventPort": 0 (uint), "timingPort": <local UDP timing port> (uint) }
```

`eventPort: 0` means "no event channel". Plus, if the same request
had `streams`, the `streams` array described next.

**Phase 2** is recognised by a `streams` array. Each element is a dict with
`type` (uint):

| `type` | Stream | Request keys seen (for 03-*) | Response element |
|---|---|---|---|
| 110 | Screen mirroring | `streamConnectionID` (uint64; may arrive as a negative signed 64-bit plist integer, so reinterpret it as unsigned), `latencyMs`, `timestampInfo` (array of `{name}`) | `{ "type": 110, "dataPort": <local TCP port> }` |
| 96 | Realtime audio | `ct` (uint, codec), `spf` (samples/frame), `sr`, `audioFormat` (uint bitmask), `audioFormatIndex`, `controlPort` (sender's UDP), `latencyMin`, `latencyMax`, `redundantAudio`, `isMedia` (bool), `usingScreen` (bool), `audioMode` (string), `supportsDynamicStreamID`, `shk` (AirPlay 2 only; ignore) | `{ "type": 96, "dataPort": <local UDP>, "controlPort": <local UDP> }` |
| other (e.g. 103 buffered audio, 130 remote control) | unsupported | — | Reply `200` without an element for that stream and close the connection after the response |

Response body: `{ "streams": [ <elements in request order> ] }`, plus phase-1
keys when present. Always reply with `Content-Type: application/x-apple-binary-plist`.

Failure: if SETUP fails or has no ports, the sender stops ("unable to connect").
If the receiver advertises a port it is not listening on, the sender times
out on the data connection.

#### 6.8.1 Classic `SETUP` (RAOP, `Transport` header)

Sent by macOS Music after its ANNOUNCE (§4.3.2) [MUSIC], as by iTunes
[NTO][SPEC]. No body. Request:

```
SETUP rtsp://192.168.1.152/3878543842 RTSP/1.0
CSeq: 4
Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002
User-Agent: Music/1.7 (Macintosh; OS X 27.0.1) AppleWebKit/625.1.29.11.28
Client-Instance: F7C2CDC26FC71581
DACP-ID: F7C2CDC26FC71581
Active-Remote: 67622618
```

`control_port` is the sender's UDP port for resend requests (03c §4.5) and
`timing_port` its UDP port for the receiver's NTP requests (03c §4.6).
`interleaved=0-1` is sent although the transport is UDP; the AirTunes
receiver in [NTO] leaves it out of its reply, and it means nothing here.

Receiver actions, in order:

1. The connection must be authenticated (§5): a Digest answer accepted at
   ANNOUNCE (02 §6.4), or PIN pairing and pair-verify. Otherwise `470`.
2. The connection must hold an ANNOUNCE description (§6.17). Otherwise
   `455 Method Not Valid in This State`. The SETUP uses that description
   up: a second classic SETUP needs a new ANNOUNCE.
3. Parse `Transport` with the bounds below. Malformed: `400`. Not
   servable: `461 Unsupported Transport`.
4. Open the session and one type-96 ALAC stream from the description and
   the ports (03 §1.6): a session SETUP followed by a type-96 stream SETUP
   (§6.8, 03 §1.2, §1.3.2) in one step, with `isScreenMirroringSession`
   false and `usingScreen` false. The media key is the FairPlay key as is
   (03 §2.1). A session already open on this connection is replaced, and a
   session of the same sender on another connection is taken over, as for
   a session SETUP.
5. Reply `200` with, in this order [NTO][SPEC]:

```
Transport: RTP/AVP/UDP;unicast;mode=record;server_port=<data>;control_port=<control>;timing_port=<timing>
Session: 1
```

`server_port` is the stream's local UDP data port, `control_port` its local
control port, `timing_port` the session's local timing port: the classic
names for the plist reply's `dataPort`, `controlPort` and `timingPort`. No
body. If the stream cannot be opened after the session was, the session is
closed again and the reply is the error.

`Transport` bounds (Rozan policy; RFC 2326 §12.39 gives the syntax):

| Part | Rule | Otherwise |
|---|---|---|
| Whole value | ≤ 1024 bytes (§3.2). Only the first transport spec, up to the first `,`, is read | 431 (framing) |
| Transport protocol | `RTP/AVP/UDP` or `RTP/AVP` (UDP is the default lower transport), case-insensitive | `RTP/AVP/TCP` and anything else: 461 |
| Parameters | at most 16, separated by `;`, each `name` or `name=value`; names case-insensitive; a name at most once; no empty parameter | 400 |
| `unicast` | optional; unicast is assumed | |
| `multicast` | | 461 |
| `mode` | absent, or `record` (quotes and case ignored) | 461 |
| `control_port`, `timing_port` | decimal 1..65535 | treated as absent (0): no resend requests, no timing (03 §8.1) |
| `interleaved`, `client_port`, `destination`, `source`, `ttl`, `ssrc`, others | ignored | |

Media is only ever sent to the address of the control connection, never to
an address taken from `Transport` (no `destination`), as for the plist
SETUP (03 §8.1).

### 6.9 `RECORD`

Headers `Range: npt=0-`, `RTP-Info: seq=<n>;rtptime=<t>` (optional). The
classic flow adds `Session: 1` [NTO]. Reply `200` with:

```
Audio-Latency: 11025
Audio-Jack-Status: connected; type=analog
```

`Audio-Latency` is the receiver's audio delay in 44.1 kHz samples:
`delay_µs × 44100 / 1 000 000`. The default of 250 000 µs gives 11025.
The media agent owns the real value. No body. The AirTunes/130.14 receiver
in [NTO] answered iTunes with `Audio-Latency: 2205`; Rozan sends 11025 in
the classic flow too.

### 6.10 `GET_PARAMETER`

`Content-Type: text/parameters`, body = parameter names, each ending in
`\r\n`, e.g. `volume\r\n`. Reply:

```
Content-Type: text/parameters
body: "volume: -11.500000\r\n"
```

The format is `volume: <real with 6 decimals>\r\n`. Senders accept the
value with or without padding to 9 characters (0 as ` 0.000000`, with a
leading space, or as `0.000000`). No Content-Type → `451 Parameter Not Understood`. Unknown
parameters: reply `200` with no body.

### 6.11 `SET_PARAMETER`

Reply `200` (empty) in every case below. A non-200 reply may make the sender
drop the session (**UNVERIFIED**). No Content-Type → 451.

| Content-Type | Body | Meaning |
|---|---|---|
| `text/parameters` | `volume: <float>\r\n` | Volume in AirPlay dB: −30.0 (min) .. 0.0 (max). −144.0 = mute. Mapping to TV volume belongs to 03-* |
| `text/parameters` | `progress: <start>/<current>/<end>\r\n` | Three unsigned 32-bit RTP timestamps (44.1 kHz) of track start, now and end |
| `application/x-dmap-tagged` | DMAP | Track metadata (`minm` title, `asar` artist, `asal` album, ...). Parsing belongs to 03-* |
| `image/jpeg` / `image/png` / `image/none` | image bytes or empty | Artwork. Sniff the magic bytes. Empty means clear the artwork |

In the classic flow, iTunes sends DMAP metadata with `RTP-Info: rtptime=<t>`,
the RTP time from which it applies [NTO]. Rozan applies metadata when it
arrives and ignores that header.

### 6.12 `FLUSH`

Header `RTP-Info: seq=<next seq>;rtptime=<ts>` (may be missing). The receiver
drops buffered audio up to that sequence number (03-*). Reply `200`. The
AirTunes/130.14 receiver in [NTO] put `RTP-Info: rtptime=<t>` in its FLUSH
reply to iTunes; Rozan sends none. **UNVERIFIED:** whether Music needs it
(§9 item 10).

### 6.13 `TEARDOWN`

The body may be empty or a bplist `{ "streams": [ {"type": 96} | {"type": 110} ] }`.

| Body | Receiver action |
|---|---|
| `streams` contains type 96 | Stop audio only. Keep mirroring and the session |
| `streams` contains type 110 (and no 96) | Stop mirroring only |
| No body, or no `streams` | End the whole session: stop all streams, release ports, clear session keys. In Mode P, rotate the identity key once the connection closes (02 §8) |

If both 96 and 110 are listed, stop both. Reply `200` with
`Connection: close` (every TEARDOWN reply carries it). After a whole-session TEARDOWN, close the connection
when the sender does, or after the 60-second idle timeout.

A whole-session TEARDOWN also ends the connection's authentication (Rozan
policy, not wire behaviour): the Digest result, the pair-verify secret and
the PIN-paired key are dropped, so a new session SETUP on the same
connection is challenged with a new code like a new connection. Otherwise
whoever typed one code could keep the connection open (one request inside
each idle timeout) and start sessions on it at any later time without the
screen showing anything. Senders close the connection after this TEARDOWN
anyway (§4.1), so they should not notice.

Note: iOS ≥ 27 may skip the type-110 TEARDOWN when mirroring stops and send
the whole-session TEARDOWN instead. Treat a whole-session TEARDOWN as "stop
mirroring too". Validate the plist strictly (§3.5): a malformed TEARDOWN
body gets `400`, never a crash.

Also treat **TCP close** of the control connection as an implicit
whole-session TEARDOWN.

In the classic flow TEARDOWN has no body, so it is always a whole-session
TEARDOWN; it also drops the connection's ANNOUNCE description. Music sent
one without a `Session` header after the receiver refused its SETUP
[MUSIC].

### 6.14 `POST /feedback`

The sender's heartbeat, every ~2-3 s during a session. The body is empty, or
a bplist of stats when `keepAliveSendStatsAsBody` is true. Reply `200`, no
body. Use it to refresh the idle timer and to detect a vanished sender. If
replies stop, the sender ends the session. **UNVERIFIED:** after how long; a
packet capture with the receiver muted would show it.

The classic flow as [NTO] and [SPEC] describe it has no `/feedback`.
Whether Music 1.7 sends it in a classic session is not known, so a classic
session is held to the `/feedback` timeout only after its first
`/feedback` (§3.1). The receiver logs that first `/feedback`.

### 6.15 `POST /audioMode`

Body bplist `{ "audioMode": "default" }` (string; other values have been seen
rarely). Reply `200`, no body, and otherwise ignore it.

### 6.16 `POST /command`

AirPlay 2 remote-control / buffered-audio command (bplist). It is not sent to
this profile in normal use. Reply `200`, no body, as for unknown POST paths
(§6.17.1).

### 6.17 `ANNOUNCE` (classic RAOP)

The first request of the classic play session after `fp-setup` (§4.3.2).
It describes the audio stream in SDP (RFC 4566) and carries the media key.
macOS 27.0.1 Music sent this one [MUSIC] (key and IV redacted; the body is
exactly these ten lines, each ending in CRLF, 324 bytes):

```
ANNOUNCE rtsp://192.168.1.152/3878543842 RTSP/1.0
CSeq: 3
Content-Type: application/sdp
Content-Length: 324
User-Agent: Music/1.7 (Macintosh; OS X 27.0.1) AppleWebKit/625.1.29.11.28
Client-Instance: F7C2CDC26FC71581
DACP-ID: F7C2CDC26FC71581
Active-Remote: 67622618

v=0
o=iTunes 3878543842 0 IN IP4 192.168.1.152
s=iTunes
c=IN IP4 192.168.1.77
t=0 0
m=audio 0 RTP/AVP 96
a=rtpmap:96 AppleLossless
a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
a=fpaeskey:<96 base64 characters: a 72-byte FPLY key blob>
a=aesiv:<22 base64 characters without padding: 16 bytes>
```

The `fpaeskey` blob is FairPlay v2's 72-byte key blob, the same framing as
the SETUP `ekey` (04 §1.5); [NTO] and [SPEC] show it starting
`RlBMWQECAQAAAAA8` (`FPLY 01 02 01 00`, length 60). iOS senders of that era
padded `aesiv` with `==` [SPEC]; Music does not.

Receiver actions, in order:

1. `Content-Type` must be `application/sdp`: otherwise `415`.
2. Parse the SDP with the rules below: malformed is `400`; a well-formed
   description of something this receiver does not play is `415`. This
   comes before access control, so a sender with an unplayable stream gets
   no code.
3. `fp-setup` must have run on this connection: otherwise `400`.
4. Access control (02 §6.4): without a valid `Authorization`, reply `401`
   with a Digest challenge and show the code; under lockout a plain `401`;
   while another address's code is on screen `409` and close.
5. FairPlay-decrypt `fpaeskey` (04 §1.5). Failure: `400`.
6. Keep the description on the connection (FairPlay key, `aesiv`, frame
   length, latencies), replacing an earlier one and zeroing its key.
   Reply `200` with no body.

SDP rules (Rozan policy; the accepted values are those of the realtime ALAC
stream, 03 §1.3.2, 03c §4.8.1):

| Part | Rule | Otherwise |
|---|---|---|
| Body | ≤ 8 KiB (§3.2); at most 64 lines of at most 1024 bytes; lines end in CRLF or LF; each line is `<lowercase letter>=<value>`; no control bytes other than TAB; empty lines ignored | 413 and close (size), else 400 |
| `v=` | exactly once, value `0` | 400 |
| `o=`, `s=`, `c=`, `t=`, `i=`, `b=` and other session lines | ignored. The `c=` address is never used: media goes to the control connection's address | |
| `m=` | exactly one, `m=audio <port> RTP/AVP 96` (the port is ignored) | another media type or a second `m=` line: 415; another profile or format list: 415; unparsable: 400 |
| `a=rtpmap:96` | exactly once, encoding `AppleLossless` | other encodings (`mpeg4-generic/44100/2`, `L16/44100/2`): 415; twice: 400 |
| `a=fmtp:96` | exactly once; 11 decimal fields, each ≤ 10 digits and ≤ 2³²−1: frameLength 1..4096, compatibleVersion 0, bitDepth 16, pb 40, mb 10, kb 14, numChannels 2, maxRun 255, maxFrameBytes any, avgBitRate any, sampleRate 44100 | wrong count or not decimal: 400; other values: 415 |
| `a=fpaeskey:` | exactly once; base64 (`=` padding optional, ≤ 128 characters) of exactly 72 bytes | 400 |
| `a=aesiv:` | exactly once; base64 (padding optional, ≤ 32 characters) of exactly 16 bytes | 400 |
| `a=rsaaeskey:` | must be absent: RSA (`et` 1) needs the AirPort Express private key and is not advertised | 415 |
| neither key | unencrypted audio (`et` 0) is not supported | 415 |
| `a=min-latency:`, `a=max-latency:` | optional; decimal ≤ 10 digits, clamped to 0..176400 frames (03 §8.1) | ignored |
| Other `a=` attributes | ignored | |

The fmtp fields are the `ALACSpecificConfig` of 03c §4.8.1 in order; Music's
`352 0 16 40 10 14 2 255 0 0 44100` is exactly the configuration of the
realtime ALAC stream. A different `pb`, `mb`, `kb` or `maxRun` would need a
different decoder configuration, so it is refused rather than guessed.

`ANNOUNCE` on a connection that already holds a description replaces it; a
running session keeps its keys until the next classic SETUP.

#### 6.17.1 `SETPEERS`, `SETRATEANCHORTIME`, `FLUSHBUFFERED`, other methods

`SETPEERS`, `SETPEERSX`, `SETRATEANCHORTIME`
and `FLUSHBUFFERED` belong to PTP and buffered audio, which are not
advertised. Reply `501 Not Implemented` to any RTSP method not in this
document. Unknown POST paths get `200` (empty).

### 6.18 `GET` with other paths

Reply `200`, empty.

### 6.19 Unknown or unhandled requests in general

Never leave a request unanswered: a sender that gets no reply waits until
it times out.

### 6.20 HTTP/1.1 requests (`/server-info`, `/reverse`, `/play`, `/photo`, `/stream.xml`, ...)

These belong to AirPlay "video" (URL playback / HLS), photos and the obsolete
2012-era mirroring on port 7100 (`GET /stream.xml`, `POST /stream`). With
feature bits 0 and 4 clear, current senders mirror and play audio over RTSP
only. Some apps still probe `GET /server-info` or send `PUT /photo`.

- `POST /reverse` (with `Upgrade: PTTH/1.0`, `Connection: Upgrade`,
  `X-Apple-Purpose: event`) asks for `HTTP/1.1 101 Switching Protocols`,
  which turns the socket into a reverse event channel; it is used only for
  HLS. Reply `HTTP/1.1 501 Not Implemented`.
- `GET /stream.xml`, `POST /stream`: these are not sent by current senders.
  Reply `HTTP/1.1 404 Not Found`.
- Any other HTTP/1.1 request: reply `HTTP/1.1 501 Not Implemented` with
  `Content-Length: 0`.

**UNVERIFIED:** whether photo-capable apps misbehave on a 501 to
`PUT /photo` when bit 1 is set. Clearing bits 1/5/13 departs from the
known-working mask and is not recommended without a device test.

---

## 7. Sample full `/info` response (XML form of the binary plist, Mode D)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>deviceID</key><string>02:11:22:33:44:55</string>
  <key>macAddress</key><string>02:11:22:33:44:55</string>
  <key>pk</key><data>zRSzf5VulTGU/3+3Oz2B3MVh1hp1OAlLfD4aZD7l86o=</data>
  <key>features</key><integer>1384120038</integer>
  <key>name</key><string>Living Room</string>
  <key>pi</key><string>2e388006-13ba-4041-9a67-25dd4a43d536</string>
  <key>vv</key><integer>2</integer>
  <key>statusFlags</key><integer>68</integer>
  <key>keepAliveLowPower</key><true/>
  <key>sourceVersion</key><string>220.68</string>
  <key>keepAliveSendStatsAsBody</key><true/>
  <key>model</key><string>AppleTV3,2</string>
  <key>initialVolume</key><real>-15.0</real>
  <key>audioLatencies</key>
  <array>
    <dict><key>type</key><integer>100</integer><key>inputLatencyMicros</key><integer>0</integer>
          <key>audioType</key><string>default</string><key>outputLatencyMicros</key><false/></dict>
    <dict><key>type</key><integer>101</integer><key>audioType</key><string>default</string>
          <key>inputLatencyMicros</key><integer>0</integer><key>outputLatencyMicros</key><false/></dict>
  </array>
  <key>audioFormats</key>
  <array>
    <dict><key>audioOutputFormats</key><integer>67108860</integer><key>type</key><integer>100</integer>
          <key>audioInputFormats</key><integer>67108860</integer></dict>
    <dict><key>audioOutputFormats</key><integer>67108860</integer><key>type</key><integer>101</integer>
          <key>audioInputFormats</key><integer>67108860</integer></dict>
  </array>
  <key>displays</key>
  <array>
    <dict>
      <key>uuid</key><string>e0ff8a27-6738-3d56-8a16-cc53aacee925</string>
      <key>widthPhysical</key><integer>0</integer>
      <key>heightPhysical</key><integer>0</integer>
      <key>width</key><integer>1920</integer>
      <key>height</key><integer>1080</integer>
      <key>widthPixels</key><integer>1920</integer>
      <key>heightPixels</key><integer>1080</integer>
      <key>rotation</key><false/>
      <key>refreshRate</key><real>0.016666666666666666</real>
      <key>maxFPS</key><integer>30</integer>
      <key>overscanned</key><false/>
      <key>features</key><integer>14</integer>
    </dict>
  </array>
</dict>
</plist>
```

(`pk` here is the base64 of `cd14b37f956e953194ff7fb73b3d81dcc561d61a7538094b7c3e1a643ee5f3aa`,
the public key of the example seed `80 81 ... 9f` used in 02 §10. Real
receivers send their own key. The response must be sent in **binary** plist
form; the XML above only shows the structure.)

---

## 8. Session state machine (per control connection)

```
NEW ──GET /info, OPTIONS──► NEW
NEW ──pair-pin-start──► NEW (PIN shown; receiver-wide)
NEW ──pair-setup-pin 1..3 ok──► PIN_PAIRED (Mode P)
NEW ──pair-setup ok──► SETUP_DONE (legacy no-PIN)
PIN_PAIRED | SETUP_DONE | NEW(*) ──pair-verify 1──► VERIFYING ──pair-verify 2 ok──► VERIFIED
any ──fp-setup 1,2 ok──► (FairPlay ready flag)
(FairPlay ready) ──SETUP phase 1 + auth ok──► SESSION
(FairPlay ready) ──ANNOUNCE (SDP ok) + Digest ok──► ANNOUNCED (classic; key and IV kept)
ANNOUNCED ──SETUP with Transport──► SESSION (classic: one type-96 ALAC stream)
SESSION ──SETUP streams / RECORD / *_PARAMETER / FLUSH / feedback──► SESSION
SESSION ──TEARDOWN (no streams) or TCP close──► CLOSED  (Mode P: rotate identity, re-announce)
any ──auth/pairing failure──► reply 470/401, count failure (02 §9)
(*) In Mode P, pair-verify on a NEW connection is accepted only if the sender's
    Ed25519 key is in the in-memory session trust set (02 §8.2); otherwise 470 + close.
```

---

## 9. UNVERIFIED points in this file

1. Android 14+ cross-service-type name conflict with `_androidtvremote2._tcp`.
   Reported by PhairPlay only. Confirm on a Google TV (Android 14).
2. How senders react to Mode P's `pw=false` and PINRequired bits (§2.5).
   On macOS 27.0.1 (device tests, 2026-11-26), mirroring shows the four-box
   code entry instead of the password field and keeps the PIN flow, both
   with the bits on both records and with the current split (`_airplay`
   and /info changed, RAOP at `pw=true`/`sf=0x8c`; 02 §8.6 test 6). Still
   open: iOS 18/26 and macOS 15/26 (02 §8.6 test 5), and which of
   `_airplay` `pw`, `flags` and `statusFlags` selects the prompt (change
   one at a time to find out).
3. Whether any sender checks the `Server` header, and whether
   `Content-Length: 0` on every empty reply is accepted by all senders. A
   device test would confirm both.
4. `X-Apple-Client-Name` on `/pair-pin-start` from Apple senders. A capture
   would confirm it.
5. The minimal mandatory `/info` key set, and that `displays` is required
   for mirroring. Confirm by removing keys one at a time.
6. The request order of an audio-only Music session on iOS 18/26, and
   whether macOS 15/26 Music uses classic RAOP as macOS 27.0.1 does
   (§4.3.2).
7. The sender's behaviour when `/feedback` replies stop, or when
   SET_PARAMETER gets a non-200.
8. The effect of answering 501 to stray photo/HTTP requests while bits 1/5/13
   are set.
9. Why macOS 27.0.1 Music once connected to TCP 5000 instead of the
   advertised port (§4.3.2). (The connection that "closed after
   `OPTIONS *`" in earlier runs is Music's selection probe, which is
   normal.)
10. The classic RAOP session of Music 1.7 after the classic SETUP's 200
    (§4.3.2). Settled 2026-11-26 (§4.3.2): it accepts the `Transport` and
    `Session` reply, sends RECORD, SET_PARAMETER, FLUSH and TEARDOWN, and
    plays with the FairPlay key used as is (03 §2.1). Still open: whether
    it needs `RTP-Info` in the FLUSH reply after a pause or seek (§6.12); whether it sends `/feedback`
    and what it sends on the control connection during a long track and
    while paused (§3.1); that it sends the Digest retry of ANNOUNCE on the
    same connection, as [NTO] shows for iTunes, and what it does with a
    wrong code. A device test of Music playing for several minutes,
    pausing for more than a minute and resuming would settle these.

## 10. Implementation checklist (control level)

- [ ] Persist per install: deviceid (random, locally administered), `pi`
      UUID, display UUID, and the receiver name. Persist nothing about
      senders.
- [ ] Advertise `_airplay._tcp` `<name>` and `_raop._tcp` `<DEVICEID12>@<name>`
      on TCP 7000 with exactly the TXT keys of §2.2/§2.3 (Mode D values by
      default). Retry with a suffixed name on conflict.
- [ ] Listen on IPv4 **and** IPv6. Parse RTSP/1.0 and HTTP/1.1 on the same
      socket. Enforce header and body limits.
- [ ] Every RTSP reply: echo `CSeq`, `Server: AirTunes/220.68`,
      `Audio-Jack-Status`, and `Content-Length`. Never leave a request
      unanswered.
- [ ] `GET /info`: support both the qualifier (`txtAirPlay`/`txtRAOP`) form
      and the full form with the §6.1 keys. Keep it consistent with the TXT
      records.
- [ ] `/fp-setup` 16→142 and 164→32 (via 04-*), before SETUP.
- [ ] SETUP phase 1 → `{eventPort:0, timingPort}`. Phase 2 → per-stream
      `dataPort` (110) and `dataPort`+`controlPort` (96).
- [ ] RECORD with `Audio-Latency`. GET_PARAMETER volume. SET_PARAMETER
      volume/progress/DMAP/artwork. FLUSH. `/feedback`. `/audioMode` → 200.
- [ ] TEARDOWN with and without `streams`. Treat TCP close as TEARDOWN.
- [ ] Gate post-SETUP requests on authentication (§5). Mode D Digest
      challenge on the first SETUP (02 §6). Optional Mode P pairing (02).
- [ ] 409 for a second client while a session is active. Unknown RTSP
      method → 501.
- [ ] Classic RAOP (§4.3.2): `ANNOUNCE` with the SDP rules of §6.17 and the
      Digest gate of 02 §6.4 in both modes; the classic `SETUP` of §6.8.1
      with its `Transport` reply and `Session: 1`; `ANNOUNCE` in `Public`;
      the probe (`fp-setup`, `OPTIONS`) answered without a code; the
      liveness rules of §3.1.
