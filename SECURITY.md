# Security

## Reporting a vulnerability

Please report security issues privately to the repository owner through
GitHub's private vulnerability reporting for this repository, not in public
issues. Include the affected version, device model and OS version, and steps
to reproduce.

## Threat model

Rozan runs on a TV that is often always on and always on the network. The
AirPlay receiver listens on the local network, so anyone on that network
(a guest, a compromised IoT device) can send it bytes. The design goal is that
such a person cannot run code on the TV, cannot show content on it without
seeing the on-screen PIN, and cannot crash it into an unusable state.

### Trust boundaries

| Input | Who controls it | Parsed by |
| --- | --- | --- |
| mDNS queries | anyone on the LAN | Android `NsdManager` (platform) |
| RTSP/HTTP requests, binary plists, TLV8 | anyone on the LAN, before pairing | Rozan Kotlin code (`:airplay:core`) |
| Pairing messages (SRP, Ed25519, X25519) | anyone on the LAN, before pairing | Rozan Kotlin code + BouncyCastle (pure Java) |
| FairPlay setup messages (`POST /fp-setup`) | anyone on the LAN, before pairing | Rozan Kotlin code (`:airplay:fairplay`) |
| Stream descriptions (SDP in `ANNOUNCE`, the classic AirPlay audio of Music on a Mac) | anyone on the LAN, before the PIN | Rozan Kotlin code (`:airplay:core`) |
| `Transport` headers of the classic `SETUP` | a client that typed the PIN as its password | Rozan Kotlin code (`:airplay:core`) |
| FairPlay media keys (in `SETUP`, or in `ANNOUNCE` for classic audio) | paired client, or one that typed the PIN as its password | Rozan Kotlin code (`:airplay:fairplay`) |
| Encrypted video packets (TCP) | paired client; the data port takes connections from its IP address only | Rozan Kotlin code (framing, decryption) |
| Audio packets (encrypted) and timing packets (UDP) | paired client; also any host that can send datagrams with its IP address and source port | Rozan Kotlin code (framing, decryption) |
| Decrypted H.264/HEVC and AAC bitstreams | paired client (see Known limits) | Android `MediaCodec` (platform decoders) |
| Decrypted ALAC frames | paired client (see Known limits) | Rozan Kotlin code (`:airplay:alac`) |
| Backdrop images | shipped inside the APK | Android `ImageDecoder` (platform) |

### Measures

- **Memory-safe code for everything we parse.** All protocol parsing,
  cryptographic glue and the ALAC decoder are written in Kotlin. Rozan ships
  no native libraries and depends on no third-party native code. The only
  runtime dependencies are the Kotlin standard library, kotlinx-coroutines,
  a few AndroidX libraries and BouncyCastle (pure Java, used for X25519 and
  Ed25519, which Android 9-12 lack).
- **Bounded parsers.** Every length field from the network is checked against
  fixed limits before allocation; malformed input closes the connection.
  Parsers are tested with truncated, oversized and randomised input.
- **PIN on every connection.** A new random PIN is shown on the TV for each
  connection attempt and nothing about a client is remembered afterwards, so
  being on the network is not enough to cast: the person must be able to see
  the screen. Media setup (`SETUP`) needs a connection that paired with that
  PIN. The Music app on a Mac cannot pair: it uses the classic AirPlay audio
  flow, so in both PIN modes the receiver asks it for the PIN as its AirPlay
  password (RTSP Digest on `ANNOUNCE`), and its `SETUP` needs a connection
  that gave the right PIN that way. Music's short connection when the TV is
  picked as an output (`fp-setup` and `OPTIONS` only) shows no PIN. Before
  the PIN the receiver parses only RTSP requests, plists, TLV8, pairing and
  FairPlay setup messages and stream descriptions (SDP, at most 8 KiB), all
  in Kotlin. Media streams are encrypted with keys from that pairing and
  FairPlay setup.
- **Pairing ends with the session.** With PIN pairing (the default), a sender
  that typed the PIN may pair-verify for 30 seconds, or while its session is
  running. The receiver forgets it when the session ends, so the next
  connection needs a new PIN, and rotates its own pairing identity when that
  session ends or 60 seconds after a PIN pairing that started no session.
  Requests that authenticate nothing
  (another host's pairing attempts, `/info`) do not keep that state alive. A
  `TEARDOWN` of the whole session also ends the authentication of its
  connection.
- **Rate limiting.** A code is withdrawn after 3 SRP proof checks, right or
  wrong, or 4 wrong Digest responses. An address's sixth failure locks it out
  for 30 seconds and each further one doubles that, up to 10 minutes; each
  IPv6 address counts on its own. More than 20 failures in 10
  minutes from all addresses together stop new codes for 10 minutes. At most
  6 codes are shown per minute, and an address gets 3 codes in 10 minutes that
  do not end in pairing (a code that pairs is given back), so a legitimate
  sender gets a new code as soon as its last prompt ends.
- **One prompt at a time.** While one address's code is on screen, a request
  for a code from any other address is answered `409` and its connection
  closed. The prompt ends when that sender pairs, uses the code up, asks for a
  new one or (for RTSP Digest codes) disconnects, or when the code expires
  after 120 seconds. The TV hides only the prompt of the client it was for.
- **Connection deadlines.** An unauthenticated connection must finish each
  request within 10 seconds of its first byte and authenticate within 3
  minutes; an authenticated one must start a session within 30 seconds. The
  receiver holds 12 control connections, 4 per address. A new connection from
  an address at its limit replaces that address's oldest unauthenticated
  connection; when the receiver is full, it replaces the oldest
  unauthenticated connection of the address with the most connections.
  Authenticated and streaming connections are never replaced. A classic
  audio session (Music on a Mac) is not ended for lack of `/feedback` until
  it has sent one, and its connection is not closed for being idle while
  its audio packets keep arriving; it closes within two minutes of the
  last request or packet.
- **Stream sockets take one sender.** The mirroring data port accepts TCP
  connections only from the paired client's address. Each UDP audio and
  timing socket accepts datagrams only from that address and, after the first
  valid packet, only from that packet's source port.
- **The screen wakes for a PIN only.** With "wake on connect", the screen
  turns on when a sender asks for a code, not when a host opens a connection
  or probes `/info`.
- **No remote content at runtime.** Backdrops are bundled and decoded from the
  APK; the launcher makes no network requests of its own.
- **Supply chain.** Gradle wrapper pinned by checksum, every dependency,
  plugin and build-tool artifact pinned by SHA-256 through Gradle dependency
  verification (`gradle/verification-metadata.xml`), GitHub Actions pinned by
  commit SHA and linted with zizmor and actionlint, minimal workflow
  permissions, and release builds that never use the shared build cache.

### Known limits

- The H.264/HEVC and AAC decoders are the device's own (often hardware)
  decoders. A malicious *paired* client could target bugs in them; keeping the
  device's OS updated is the mitigation.
- The decoders are not reachable by paired clients alone. A host that can
  send UDP datagrams with the paired client's IP address, and either guesses
  its source port or gets a packet in before the client does, can inject
  audio packets during a session. They are decrypted with the session key,
  which that host does not have, so it cannot choose the bytes the AAC or
  ALAC decoder sees, but it can feed them noise. A host on the path between
  sender and TV (or another program on the sender) can do the same for the
  mirroring stream.
- Rate limits and lockouts count per address. A host that holds many
  addresses (several IPv4 addresses, or IPv6 privacy addresses) gets a budget
  for each; the receiver-wide limits (6 codes a minute, the global failure
  counter) still apply to all of them together.
- One prompt at a time means a host that keeps asking for codes can keep
  other senders waiting for the screen. It cannot cast without the code;
  the per-address budget and the global code cap bound how long it can hold
  the screen.
- FairPlay is an interoperability implementation of a proprietary scheme. It
  provides no security of its own; the security of a session rests on the PIN
  pairing.
- Classic AirPlay audio (the Music app on a Mac) has no pairing, so its audio
  key is protected only by FairPlay. Someone who records the network traffic
  of such a session from its start can decrypt its audio. The PIN still
  decides who may play to the TV. With PIN pairing, mirroring and iPhone
  audio are not affected.
- The receiver must keep the network reachable while the screen is off when
  "wake on connect" is enabled, which keeps it listening at all times.
