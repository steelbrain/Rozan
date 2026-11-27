# 02 — Pairing, the on-screen code, and access control

Scope: everything that authenticates a sender to the Rozan receiver.

- Legacy `pair-setup` and `pair-verify` (Ed25519 + X25519 + AES-CTR).
- The legacy PIN flow: `pair-pin-start`, then `pair-setup-pin` (SRP-6a,
  SHA-1, 2048-bit group, then an AES-GCM key exchange).
- The RTSP Digest "per-connection code" used as the password.
- HomeKit-style pairing, for reference only.
- How to force a code entry on **every** connection while storing nothing
  about senders.

The control framing is in `01-discovery-and-session.md`. The media keys that
consume the pair-verify secret are in `03-*`. FairPlay is in `04-*`.

Source tags are as in 01: [PYATV] `pyatv/protocols/airplay/srp.py`,
`auth/legacy.py`, `server_auth.py`, `auth/hap_transient.py`; [PAIRAP]
pair_ap `pair_homekit.c`, `pair-tlv.h`; [SPEC]
`openairplay_airplay-spec/src/pairing/hkp.md`. Every number in §10 was
computed by `/tmp/rozan-spec-a/vectors.py`, a stdlib-only script that is outside the
repo. That script implements AES, GCM, Ed25519, X25519 and SRP from their
public specifications and checks them against FIPS-197, the GCM spec,
RFC 7748 and RFC 8032. The pairing-specific outputs were also checked
against OpenSSL 4.1: GCM with a 16-byte IV, the CTR keystream at offset 64,
the Ed25519 signature and the X25519 shared secret all match.

---

## 1. Which flow runs, and the three receiver modes

### 1.1 What a sender does, given the advertisement

This is the sender's choice against a receiver with this profile
(`AppleTV3,2`, `srcvers=220.68`, HomeKit bits 38/43/46/48/51 clear). Sources
are [PYATV `protocols.md`] and the macOS 27.0.1 device tests of 2026-11-26
(01 §2.5). The first four rows have the same `pw` in both TXT records.

| Feature bit 27 | `_airplay` `pw` / `flags` | RAOP `pw` / `sf` | /info `statusFlags` | Sender flow |
|---|---|---|---|---|
| 0 | false / 0x4 | false / 0x4 | 68 | No pairing: `GET /info` → `fp-setup` → SETUP. Rozan's Mode O. It also connects faster: pairing adds about 5 s |
| 0 | true / 0x4 | true / 0x84 | 68 | No pairing. SETUP is answered `401` with an RTSP Digest challenge, so the sender asks the user for the "AirPlay password". Rozan's Mode D |
| 1 | false / 0x4 | false / 0x4 | 68 | Legacy transient pairing: `POST /pair-setup` (raw Ed25519 keys) → `pair-verify` ×2 → `fp-setup` → SETUP |
| 1 | true / 0x4 | true / 0x8c | 68 | **Legacy PIN**: `pair-pin-start` → `pair-setup-pin` ×3 → `pair-verify` ×2 → `fp-setup` → SETUP. A sender that already trusts the receiver's `pk` skips straight to `pair-verify`. macOS 27.0.1 mirroring asks for the code in a "password" text field with a "Remember password" option |
| 1 | false / 0xc | false / 0xc | 76 | macOS 27.0.1 mirroring: **legacy PIN**, the same requests as the row above, with the four-box entry for an on-screen code |
| 1 | false / 0xc | true / 0x8c | 76 | Rozan's Mode P. macOS 27.0.1 mirroring: **legacy PIN** with the four-box code entry, as the row above (§8.6 test 6). **UNVERIFIED** on iOS/iPadOS and on macOS 15/26 (§8.6 test 5) |
| any | any | any | any | MDM-managed senders may always run the PIN flow, even with `pw=false` |
| any | any | any | any | macOS 27.0.1 **Music**: no pairing and no `GET /info`. Classic RAOP: `fp-setup` (v2) → `ANNOUNCE` → `SETUP` with a `Transport` header (01 §4.3.2). Only the Digest password of §6.4 can gate it |

**HomeKit-style pairing** (TLV8 bodies, `X-Apple-HKP` header, SRP-3072/SHA-512,
an encrypted control channel) is only used when the receiver advertises
bits 38/46/48 (CoreUtils, HomeKit or transient pairing). Current macOS and
iOS do not use it against this profile. Rozan must not set those bits.
See §7 for reference only.

### 1.2 Receiver modes

| | **Mode D** | **Mode P** (default) | **Mode O** |
|---|---|---|---|
| Purpose | A code on every connection, entered as the AirPlay password | A code on every connection through PIN pairing, with RTSP Digest as the fallback | Open; debug only |
| Feature bit 27 | 0 | 1 | 0 |
| `_airplay` `pw` / `flags` | `true` / `0x4` | `false` / `0xc` | `false` / `0x4` |
| `_raop` `pw` / `sf` | `true` / `0x84` | `true` / `0x8c` | `false` / `0x4` |
| /info `statusFlags` | 68 | 76 | 68 |
| Pairing handlers | Present (for MDM senders) | Present | Present |
| SETUP gate | Digest unless the connection PIN-paired | PIN pairing on this connection, otherwise Digest | None |
| Classic RAOP gate (macOS Music) | Digest on `ANNOUNCE` (§6.4) | Digest on `ANNOUNCE` (§6.4) | None |
| Identity key | Per install or per boot | **Rotated after every session** (§8.2) | Any |
| Media key bound to ECDH | Only if a sender pairs voluntarily | Yes, except classic RAOP sessions (§9.3) | Only if a sender pairs voluntarily |
| Evidence it works | Mirroring: **UNVERIFIED** (§8.6 test 1) | On macOS 27.0.1 (2026-11-26): a new code after each session's rotation (§8.6 test 2), and these values give the four-box code entry with the same requests (§8.6 test 6, 01 §2.5). macOS Music: classic RAOP with the §6.4 Digest gate, not yet tried on a Mac (§8.6 test 3). iOS: **UNVERIFIED** | Sender flow in §1.1, first row |

The PIN-every-time conclusion and the recommendation are in §8.

---

## 2. Primitives, encodings and the receiver identity key

### 2.1 Primitives

| Primitive | Exact definition | JVM / Android notes |
|---|---|---|
| SHA-1, SHA-512, MD5 | Standard | `MessageDigest` |
| AES-128-CTR | The 16-byte IV is the initial counter block. The **whole 128-bit block increments as a big-endian integer** (OpenSSL semantics) | `Cipher("AES/CTR/NoPadding")`, `IvParameterSpec(iv16)`. SunJCE and Conscrypt increment the full block |
| AES-128-GCM | 128-bit tag, **16-byte IV** (non-96-bit IV, so J0 = GHASH(IV ‖ 0⁶⁴ ‖ [128]₆₄) per SP 800-38D), **no AAD** | JVM: `GCMParameterSpec(128, iv16)` works. **UNVERIFIED** on Android's Conscrypt: if it rejects a 16-byte IV, use BouncyCastle `GCMBlockCipher`, which accepts any nonce length |
| Ed25519 | RFC 8032 pure Ed25519. 32-byte seed, 32-byte public key, 64-byte signature | JDK 15+ `Signature("Ed25519")`. On Android TV/Fire TV (API 25-34) use BouncyCastle `org.bouncycastle.math.ec.rfc8032.Ed25519` or Tink. The platform provider is not available on older APIs |
| X25519 | RFC 7748, 32-byte little-endian keys | JDK 11+ `KeyAgreement("X25519")`. On Android use BouncyCastle `rfc7748.X25519` |
| SRP big integers | `java.math.BigInteger(1, bytes)` for unsigned input | **`BigInteger.toByteArray()` can prepend a 0x00 sign byte.** Write two helpers: `minimal(n)` = big-endian with no leading zero bytes (one 0x00 byte for n = 0), and `pad256(n)` = left-zero-padded to 256 bytes |

### 2.2 Receiver Ed25519 identity key

There is one long-term Ed25519 key pair. Its public half is `pk` in both TXT
records (64 lowercase hex chars) and in `/info` (`pk`, data, 32 bytes). The
same key is used in three places:

- the `/pair-setup` response;
- the GCM-encrypted `epk` in `pair-setup-pin` step 3;
- the pair-verify signature.

All of them **must** be the same key at any moment, or senders fail
verification.

Generate the seed with `SecureRandom` (32 bytes). Do **not** derive the key
from the device id (for example as SHA-512 of a salt and the device id): the
device id is public, so such a key is effectively public too.

Lifetime by mode: Mode D or O can keep one key per install, or make a new
one per boot. In Mode P the key is rotated after each session (§8.2) and is
never written to disk.

### 2.3 Byte layouts used below

- `‖` = concatenation.
- `PAD(n)` = n as a 256-byte big-endian number.
- `BYTES(n)` = minimal big-endian, no leading zeros.
- `H` = SHA-1 inside SRP. SHA-512 is named explicitly elsewhere.

---

## 3. Legacy pair-setup (no PIN)

Used when bit 27 = 1, `pw = false` and no PINRequired bit (0x8) is
advertised (§1.1). Receivers in other modes must still handle it. [PYATV
`legacy.py`]

```
Request : POST /pair-setup RTSP/1.0
          Content-Type: application/octet-stream
          Content-Length: 32
          body = sender Ed25519 public key (32 bytes)
Response: 200 OK
          Content-Type: application/octet-stream
          body = receiver Ed25519 public key (32 bytes)
```

Receiver actions:

1. Body length ≠ 32 → `400 Bad Request`.
2. Remember the sender key on this connection as `setupKey`.
3. Move the connection to `SETUP_DONE`.

This step authenticates nobody. It only announces keys. In Mode P, a
connection that only did `/pair-setup` is **not** authenticated (§8.2): the
pair-verify that a sender runs next is refused with 470 and the connection
closed (§4.3 step 1 rule 2), so such a sender cannot connect. A connection
that did `/pair-setup` and then skips pair-verify gets the Digest challenge
at SETUP (§8.2 rule 4).

---

## 4. Legacy pair-verify

Sources: [PYATV `srp.py` `verify1`/`verify2`].

### 4.1 Messages

| # | Direction | Bytes | Layout |
|---|---|---|---|
| 1 | sender → receiver | 68 | `01 00 00 00` ‖ senderEphX25519Pub(32) ‖ senderEd25519Pub(32) |
| 1r | receiver → sender | 96 | receiverEphX25519Pub(32) ‖ AES-CTR(key, iv, sigR)(64) |
| 2 | sender → receiver | 68 | `00 00 00 00` ‖ AES-CTR continued(sigS)(64) |
| 2r | receiver → sender | 0 | empty body, `Content-Type: application/octet-stream`, `Content-Length: 0` |

Both requests are `POST /pair-verify RTSP/1.0` with
`Content-Type: application/octet-stream`. Byte 0 selects the step: 1 = first
message, 0 = second. Bytes 1-3 are 0.

### 4.2 Derivations

```
shared  = X25519(receiverEphPriv, senderEphPub)                         32 bytes
aesKey  = SHA-512( "Pair-Verify-AES-Key" ‖ shared )[0..15]              16 bytes
aesIV   = SHA-512( "Pair-Verify-AES-IV"  ‖ shared )[0..15]              16 bytes
sigR    = Ed25519Sign(receiverIdentity, receiverEphPub ‖ senderEphPub)  64 bytes
sigS    = Ed25519Sign(senderIdentity,   senderEphPub ‖ receiverEphPub)  64 bytes
```

The salt strings are ASCII with no terminator: `Pair-Verify-AES-Key` (19
bytes) and `Pair-Verify-AES-IV` (18 bytes). They come first, and the shared
secret follows.

**One AES-CTR keystream covers both directions.** The receiver encrypts
`sigR` with keystream bytes 0..63. The sender encrypts `sigS` with keystream
bytes 64..127, which is the same cipher instance continued. To decrypt
message 2, either keep the encrypting `Cipher` object and feed it the 64
bytes, or start a new CTR cipher with the same key and IV and throw away the
first 64 keystream bytes (for example by encrypting 64 zero bytes). pyatv's
client feeds the receiver's 64 bytes through the cipher before its own
signature.

### 4.3 Receiver procedure

Step 1:

1. Body length ≠ 68 or byte 0 ∉ {0, 1} → `400`. If it is a step-1 message
   received while a verify is already in progress, restart the verify.
2. **Mode P acceptance rule (§8.2).** Accept only if one of these holds:
   (a) this connection completed `pair-setup-pin` step 3 and
   `senderEd25519Pub` equals the key decrypted there; (b) `senderEd25519Pub`
   is in the in-memory session trust set. Otherwise reply
   `470 Connection Authorization Required` and close.
   In Mode D/O: accept any key. The verify then only agrees a key, which is
   still useful because it binds the media key to ECDH.
   If `/pair-setup` ran on this connection, `senderEd25519Pub` must equal
   `setupKey`; otherwise 470.
3. Make a fresh X25519 key pair. Compute `shared`, `aesKey`, `aesIV` and
   `sigR`. Encrypt `sigR` with keystream bytes 0..63.
4. Reply 200 with the 96-byte body.

Step 2:

1. No step 1 on this connection → `470`.
2. Body length ≠ 68 → `400`.
3. Decrypt bytes 4..67 with keystream bytes 64..127 to get `sigS`. Verify
   `sigS` over `senderEphPub ‖ receiverEphPub` with `senderEd25519Pub` from
   step 1. On failure: `470` and close.
4. Mark the connection `VERIFIED`. Store `shared` for SETUP (§4.5). Reply 200
   with an empty body.

**Legacy pair-verify does not encrypt the RTSP connection.** After the empty
step-2 response, every later request and response on the connection (fp-setup,
SETUP, RECORD, ...) stays plaintext RTSP. There is no "channel decryption
layer" to strip in this profile. Only HomeKit pairing (§7.4) encrypts the
control channel. The shared secret from this step is used only for the media
key (§4.5).

### 4.4 What the sender checks

The sender verifies `sigR` against the receiver key it trusts. That is the
key from the PIN pairing record, or from the TXT/`/info` `pk` and
`/pair-setup` response. pyatv's client does not check it, but Apple senders
are expected to. **UNVERIFIED:** confirm on a device by sending a signature
made with a key other than the advertised `pk`. This is why §2.2 requires all
of them to be the same key.

### 4.5 Export to the media layer

If and only if the connection reached `VERIFIED`, the 16-byte AES key that
FairPlay unwraps from SETUP `ekey` becomes:

```
mediaKey = SHA-512( fairplayKey(16) ‖ shared(32) )[0..15]
```

It is used for both audio (with `eiv`) and mirroring. The mirroring key and
IV are derived further in 03-*. Otherwise, `mediaKey = fairplayKey`
unchanged. The hash is SHA-512, not SHA-256. Some old Windows senders
(User-Agent `AirMyPC`) expect the hash to be skipped. That is out of scope.

---

## 5. Legacy PIN pairing: `pair-pin-start` + `pair-setup-pin`

Sources: [PYATV `srp.py` `AtvSRPContext`, `step1-3`; `auth/legacy.py`].
pyatv is a client of the same protocol and uses every formula below.

### 5.1 `POST /pair-pin-start`

The request has no body. Receiver actions:

1. If the source IP is locked out (§9), or another client is streaming
   (01 §3.1), reply 470 (or 409) and show nothing. While another IP's code
   is on screen, reply 409, close, and show nothing (one prompt at a time,
   §9.1).
2. Make a **new** 4-digit code: uniform 0000-9999 from `SecureRandom`, always
   4 ASCII digits with leading zeros (`"0042"`), with no modulo bias. Store
   it **receiver-wide**, keyed by the
   sender IP, with a 120 s expiry. The sender continues on a **new TCP
   connection**, so the code must not be tied to this connection.
3. Show it on the TV ("AirPlay code: 0042"). Add the sender name if
   `X-Apple-Client-Name` is present (UNVERIFIED that Apple senders send it).
4. Reply `200 OK` with no body.

The sender then shows a prompt and, once the user types the code, sends
`pair-setup-pin` step 1. Which prompt depends on the advertisement: for
mirroring, macOS 27.0.1 shows a "password" text field with `_airplay`
`pw=true` and `flags=0x4`, and the four-box code entry with `pw=false` and
PINRequired (§1.1, 01 §2.5). Note: macOS has been reported to retry in a
loop against a receiver that answers 200 here but never completes
`pair-setup-pin`. Always implement the full flow.

### 5.2 SRP-6a parameters (Apple legacy variant)

| Item | Value |
|---|---|
| Group | RFC 5054 Appendix A, **2048-bit** prime N, generator **g = 2** |
| Hash H | **SHA-1** (20-byte output) |
| Username I | The `user` string from step 1, as raw UTF-8 bytes, used verbatim. Apple senders send their device id, e.g. `AA:BB:CC:DD:EE:FF` (17 chars; case as sent). pyatv sends 16 uppercase hex chars. Reject anything over 64 bytes |
| Password P | The 4 ASCII digits of the displayed code, e.g. `0042` |
| Salt s | **16 random bytes** |
| Server secret b | 32 random bytes |
| Sender secret a | 32 bytes (the sender's choice) |

N (256 bytes, hex):

```
ac6bdb41324a9a9bf166de5e1389582faf72b6651987ee07fc3192943db56050
a37329cbb4a099ed8193e0757767a13dd52312ab4b03310dcd7f48a9da04fd50
e8083969edb767b0cf6095179a163ab3661a05fbd5faaae82918a9962f0b93b8
55f97993ec975eeaa80d740adbf4ff747359d041d5c33ea71d281e446b14773b
ca97b43a23fb801676bd207a436c6481f1d2b9078717461a5b9d32e688f87748
544523b524b0d57d5ea77a2775d2ecfa032cfbdbf52fb3786160279004e57ae6
af874e7303ce53299ccc041c7bc308d82a5698f3a8d0c38271ae35f8e9dbfbb6
94b5c803d89f7ae435de236d525f54759b65e372fcd68ef20fa7111f9e4aff73
```

Formulas. All arithmetic is mod N unless noted.

```
x  = H( s ‖ H( I ‖ ":" ‖ P ) )                         as an unsigned integer
v  = g^x
k  = H( PAD(N) ‖ PAD(g) )                                PAD(g) = 255 zero bytes ‖ 0x02
B  = ( k·v + g^b ) mod N
A  = (from sender)
u  = H( PAD(A) ‖ PAD(B) )
S  = ( A · v^u ) ^ b                                     receiver side
     ( (B − k·g^x) ^ (a + u·x) )                         sender side (same value)
K  = H( BYTES(S) ‖ 00 00 00 00 ) ‖ H( BYTES(S) ‖ 00 00 00 01 )    40 bytes
M1 = H( (H(BYTES(N)) XOR H(BYTES(g))) ‖ H(I) ‖ BYTES(s) ‖ BYTES(A) ‖ BYTES(B) ‖ K )   20 bytes
M2 = H( BYTES(A) ‖ M1 ‖ K )                                                       20 bytes
```

Where this differs from a "textbook" RFC 5054 / RFC 2945 SRP-6a library:

1. **K is 40 bytes.** K = SHA-1(S ‖ 0x00000000) ‖ SHA-1(S ‖ 0x00000001),
   with S in minimal big-endian. Libraries normally use K = H(S) (20 bytes).
   [PYATV `AtvSRPContext.get_common_session_key`]
2. **M1 and M2 hash the full 40-byte K.**
3. **k and u use RFC 5054 padding.** Both A and B are padded to 256 bytes
   for u, and g is padded to 256 bytes for k. Some libraries (the original
   csrp mode, for example) do not pad.
4. **M1 hashes H(g) of the single byte `0x02`, not PAD(g).** s, A and B are
   also hashed **unpadded** (minimal big-endian), and H(N) uses the
   256-byte N. Most libraries pad here, which gives the wrong M1.
5. The salt is 16 bytes and the hash is SHA-1. x is the standard RFC 5054
   `H(s ‖ H(I ‖ ":" ‖ P))`.

**Leading-zero rules.** These make both sides agree on byte strings. Some of
them are workarounds for cases where Apple's behaviour is unknown.

- **Salt:** if `s[0] == 0x00`, generate it again. A 16-byte salt then has
  only one encoding, and a leading zero byte cannot be lost or sent wrongly.
- **B:** send `BYTES(B)`. If B < 2²⁰⁴⁰ (it would encode in fewer than 256
  bytes), pick a new b and recompute. B is then always exactly 256 bytes
  with no ambiguity. This happens with probability about 1/256.
- **A:** accept 1..256 bytes. Interpret as unsigned. Use `PAD(A)` for u and
  `BYTES(A)` (leading zeros stripped) for M1 and M2, as csrp does.
  Reject if A mod N == 0. **UNVERIFIED:** whether an Apple sender whose A
  starts with a zero byte hashes it padded. That case occurs 1 time in 256
  and would show up as a rare M1 mismatch.
- **S:** use `BYTES(S)` in K. **UNVERIFIED:** whether Apple pads S when its
  top byte is zero. If it does, about 1 in 256 PIN attempts would fail with
  a correct PIN; the user retries.
- Reject u == 0. It never happens in practice.

### 5.3 Step 1: start

```
Request : POST /pair-setup-pin RTSP/1.0
          Content-Type: application/x-apple-binary-plist
          { "method": "pin" (string), "user": "<sender device id>" (string) }
Response: 200 OK, Content-Type: application/x-apple-binary-plist
          { "pk": <B> (data, 256 bytes), "salt": <s> (data, 16 bytes) }
```

The steps are told apart by which keys are present: `method`+`user` (both
strings) is step 1, `pk`+`proof` (data) is step 2, `epk`+`authTag` (data) is
step 3. Anything else gets 470. `method` must be `"pin"`; anything else gets
470. Compare the whole string, not a prefix, and never answer 200 with no
body.

Receiver actions:

1. Look up the code for this sender IP (§5.1). If there is none or it has
   expired, reply 470.
2. Make s and b (with the §5.2 rules), then compute x, v, k and B. Store
   (I, s, v, b, B) on **this connection**. Drop any earlier SRP state on it.

### 5.4 Step 2: proofs

```
Request : { "pk": <A> (data, ≤256 bytes), "proof": <M1> (data, 20 bytes) }
Response: { "proof": <M2> (data, 20 bytes) }
```

Receiver actions:

1. No step-1 state on this connection, or `proof` is not 20 bytes → 470.
2. Compute u, S, K and the expected M1. Compare with the received proof in
   constant time.
3. Mismatch: reply 470 and close the connection. Apple TV 3 sends
   `470 Connection Authorization Required`, Apple TV 4K sends `500`. Count a
   failure against the code and the IP (§9). Clear the SRP state.
4. Match: compute M2, keep K (40 bytes) on the connection, and reply.

### 5.5 Step 3: exchange of long-term keys (AES-128-GCM)

```
Request : { "epk": <32 bytes> (data), "authTag": <16 bytes> (data) }
Response: { "epk": <32 bytes> (data), "authTag": <16 bytes> (data) }
```

Derivation from K (40 bytes):

```
gcmKey = SHA-512( "Pair-Setup-AES-Key" ‖ K )[0..15]       salt string 18 ASCII bytes
IV0    = SHA-512( "Pair-Setup-AES-IV"  ‖ K )[0..15]       salt string 17 ASCII bytes
IVc    = IV0 with byte[15] := (byte[15] + 1) mod 256       sender → receiver message
IVr    = IV0 with byte[15] := (byte[15] + 2) mod 256       receiver → sender message
```

- Request: `epk ‖ authTag = AES-128-GCM-Encrypt(gcmKey, IVc, plaintext = sender
  Ed25519 public key (32 bytes), AAD = empty)`.
- Receiver: decrypt with `IVc`. If the tag fails → 470 and close. On success
  the 32-byte plaintext is the sender's **long-term Ed25519 public key**.
  Keep it on the connection as `pinKey`. In Mode P also add it to the
  in-memory session trust set (§8.2). Never write it to disk.
- Response: `AES-128-GCM-Encrypt(gcmKey, IVr, plaintext = receiver Ed25519
  public key (32 bytes))`. The receiver's key is the current identity key,
  the same as TXT `pk`.
- Clear the SRP state (b, v, K) and remove the displayed code (it is used
  up). Move the connection to `PIN_PAIRED`. pair-verify comes next on the
  same connection (§4).

The IV increments are confirmed by pyatv. Apple senders disconnect from a
receiver that does not update the IV correctly. Byte 15 increments on its
own, with **no carry into byte 14**: it is an 8-bit value that wraps.
**UNVERIFIED:** what Apple does when byte 15 is 0xFE or 0xFF (carry or
wrap). This affects about 2 in 256 pairings, and a device test that forces K
values with IV0[15] ≥ 0xFE would settle it. Since K is fresh on every
attempt, a failure here only costs a retry.

### 5.6 What the sender stores

After PIN pairing, Apple senders store **only the receiver's Ed25519 public
key**, not its device id. Later, if the receiver's advertised `pk` is in
that list, the sender skips `pair-pin-start` and goes straight to
`pair-verify`. The pairing persists as long as neither side changes its
Ed25519 key pair, whatever device id the receiver reports, and the sender
does not need to authenticate again while both public keys stay the same.
§8 is built on this.

---

## 6. Mode D: per-connection code via RTSP Digest

Sources: RFC 2617.

Mode D uses the AirPlay **password** prompt to carry a code that is new for
every connection and shown on the TV.

### 6.1 Challenge

Trigger: the first SETUP (the one with `ekey`/`eiv`) on a connection that is
not authenticated and has no valid `Authorization` header. Receiver actions:

1. Look up or create the code for the key (sender IP, SETUP `deviceID`
   string). It is 4 random digits, as in §5.1. If a code exists for a
   different deviceID from the same IP, replace it. Expiry: 120 s. While
   another IP's code is on screen, reply 409 and close instead (§9.1).
2. Show it: "AirPlay code for <SETUP `name`>: 4821".
3. Make a nonce: 16 random bytes as 32 lowercase hex chars. Keep it on the
   connection; only the latest nonce is valid.
4. Reply:

```
RTSP/1.0 401 Unauthorized
WWW-Authenticate: Digest realm="raop", nonce="a1b2c3d4e5f60718293a4b5c6d7e8f90"
CSeq: <echo>
Server: AirTunes/220.68
Audio-Jack-Status: connected; type=digital
Content-Length: 0
```

The realm is the literal `raop`. Do not send `qop`, `opaque` or `stale`:
send exactly the two parameters above.

### 6.2 Response from the sender

The sender sends the same SETUP again with
`Authorization: Digest username="<u>", realm="raop", nonce="<nonce>", uri="<uri>", response="<32 hex>"`.
It may also add `qop`, `nc` and `cnonce` (accept them if present). The
username is chosen by the sender; take it verbatim. Parse the comma-separated
`name=value` / `name="value"` pairs, ignoring order and extra whitespace.

Verification:

```
HA1      = MD5hex( username ":" "raop" ":" code )
HA2      = MD5hex( METHOD ":" uri )                         METHOD = "SETUP" (the request method); uri = the header's uri value, verbatim
response = MD5hex( HA1 ":" nonce ":" HA2 )                  without qop
response = MD5hex( HA1 ":" nonce ":" nc ":" cnonce ":" qop ":" HA2 )   with qop
```

`MD5hex` = lowercase hex of MD5. Compare in constant time. Also require
realm = `raop` and nonce = the latest nonce issued on this connection: exactly
32 chars, equal to the stored one.

- Success: mark the connection authenticated, consume the code (a later
  connection gets a new one), close the code screen, and run the SETUP
  (01 §6.8).
- Failure: count it, issue a new nonce, and send a 401 again (the same code
  stays on screen). After **4** failures with one code, make and show a new
  code. The first failure is usually the sender trying a stale remembered
  password, so 4 leaves the user 3 real tries. Apply the IP lockout from §9.

### 6.3 Sender behaviour

After a successful authentication, the sender stores the password and uses
it first for later authentications, without prompting, as long as the
receiver's deviceID has not changed. Because the code changes on every
connection, that stored password always fails, and the sender prompts
again. That is the "code every time" property. **UNVERIFIED** on iOS 18/26
and macOS 15/26 specifically. The device test in §8.6 confirms it.

### 6.4 Classic RAOP: the code as the password of `ANNOUNCE`, in both modes

Who: macOS Music (01 §4.3.2), and iTunes before it. Its play session is
`fp-setup` (v2) → `ANNOUNCE` → `SETUP` with a `Transport` header. It sends
no pairing request and no `GET /info` in any mode, so neither PIN pairing
nor the identity rotation of §8.2 reaches it. The RAOP password is what is
left: RFC 2617 Digest on `ANNOUNCE`, realm `raop`, user `iTunes`, which
[NTO] (01) shows for iTunes 10.6 against an AirTunes/130.14 receiver: a
`401` to the first `ANNOUNCE`, then the same `ANNOUNCE` again on the same
connection with the next CSeq and an `Authorization` header. Observed
2026-11-26, Music/1.7, macOS 27.0.1: a Digest `401` from an experimental
receiver build made Music ask for a password; Music answered with
`username="iTunes"`, `realm="raop"` and a `response` whose HA2 is MD5 of
`METHOD ":" uri`, and the receiver's check (§6.2) accepted it.

Rule (Rozan policy), in **Mode D and Mode P** alike: the classic `ANNOUNCE`
carries the Digest exchange, as the first SETUP does in §6.1.

1. `fp-setup` and `OPTIONS` need no authentication and show nothing. They
   are Music's whole selection probe (01 §4.3.2), and a code there would
   pop a prompt every time the receiver is selected.
2. The `ANNOUNCE` SDP is parsed first (bounded, 01 §6.17), so a sender
   whose stream cannot be played gets a `400`/`415` and no code.
3. Without a valid `Authorization`, the receiver runs §6.1 steps 1-4: the
   code is keyed by (sender IP, the `DACP-ID` header) because the classic
   flow has no `deviceID`; a fresh nonce is kept on this connection; the
   reply is `401` with `WWW-Authenticate: Digest realm="raop", nonce="..."`.
   While another IP's code is on screen it is `409` and close; under
   lockout a plain `401` (§9.2).
4. The retry is checked as in §6.2, with METHOD = `ANNOUNCE` and the `uri`
   of the `Authorization` header (Music uses the request URI,
   `rtsp://<sender-ip>/<session id>`). Success authenticates the
   connection and consumes the code; a failure counts, issues a new nonce,
   and after 4 failures the code is replaced.
5. The classic SETUP, RECORD, GET/SET_PARAMETER and FLUSH need this
   connection's authentication: `470` otherwise (01 §5). A connection
   authenticated by PIN pairing and pair-verify (Mode P) is not challenged
   again at `ANNOUNCE`.
6. Nothing is remembered. A whole-session TEARDOWN or the connection
   closing ends the authentication (01 §6.13), and Music opens a new
   connection for each playback start (01 §4.4), whose `ANNOUNCE` gets a
   new code. A password Music remembered is a used code, so it fails and
   Music asks again (§6.3), at the cost of one of the 4 tries.

Mode P stays the default for everything else; for Music it behaves as
Mode D does. The media key of a classic session is the FairPlay key alone
(03 §2.1), so §9.3's Mode D properties apply to it. **UNVERIFIED:** that
Music 1.7 retries on the same connection, how it shows a wrong code, and
whether it tries a remembered password first (01 §9 item 10). A test
vector is in §10.4.

---

## 7. HomeKit-style pairing (reference only; not used by this profile)

Sources: [PAIRAP `pair_homekit.c`, `pair-tlv.h`], [PYATV `server_auth.py`,
`hap_transient.py`], [SPEC `pairing/hkp.md`]. Senders use this only when the
receiver advertises feature bits 38 (CoreUtils pairing and encryption),
46 (HomeKit pairing and access control), 48 (transient pairing) or 43
(system pairing). A receiver that implements it must also encrypt the
control channel (§7.4) and usually PTP and buffered audio (an AirPlay 2
speaker profile). **Current macOS and iOS do not use HomeKit pairing against
a receiver with this profile**, and Rozan does not need it. It is summarised here
so nobody turns those bits on without knowing what follows.

### 7.1 Transport

`POST /pair-setup` and `POST /pair-verify` with header `X-Apple-HKP: 3`
(normal pairing) or `X-Apple-HKP: 4` (transient). The body is **TLV8**: a
sequence of `type(1) len(1) value(len)`. A value over 255 bytes is split
into consecutive items of the same type, which are concatenated when read.
Types: 0 Method, 1 Identifier, 2 Salt, 3 PublicKey, 4 Proof, 5
EncryptedData, 6 State, 7 Error, 8 RetryDelay, 9 Certificate, 10 Signature,
11 Permissions, 12 (0x0C) FragmentData, 13 (0x0D) FragmentLast, 19 Flags,
0xFF Separator (numbering per the published HomeKit Accessory Protocol
specification).
Error values: 1 Unknown, 2 Authentication, 3 Backoff, 4 MaxPeers, 5
MaxTries, 6 Unavailable, 7 Busy. A PIN-mode HomeKit pairing is preceded by
`POST /pair-pin-start`.

### 7.2 pair-setup (M1-M6)

- SRP-6a, RFC 5054 **3072-bit group, g = 5**, hash **SHA-512**, username
  `Pair-Setup`, password = PIN (transient: the fixed PIN `3939`), 16-byte
  salt.
- k = H(N ‖ PAD(g)), u = H(PAD(A) ‖ PAD(B)), **K = SHA-512(S)** (64 bytes),
  M1 = H(H(N) ⊕ H(g) ‖ H(I) ‖ s ‖ A ‖ B ‖ K), M2 = H(A ‖ M1 ‖ K).
- M1: State=1, Method=0. Transient adds Flags (type 19) = `0x10`.
- M2: State=2, Salt(16), PublicKey(B, 384 bytes).
- M3: State=3, PublicKey(A), Proof(M1, 64).
- M4: State=4, Proof(M2), or Error=2 if the proof is wrong. **Transient pairing ends here.** Both sides
  derive control keys from K (§7.4) and switch to encryption right after M4.
- M5: State=5, EncryptedData = ChaCha20-Poly1305 with key `HKDF-SHA512(ikm=K,
  salt="Pair-Setup-Encrypt-Salt", info="Pair-Setup-Encrypt-Info", 32)`,
  nonce = `00 00 00 00` ‖ `"PS-Msg05"` (12 bytes), and the 16-byte tag
  appended. The plaintext is a TLV of Identifier (controller id), PublicKey
  (controller Ed25519) and Signature over `HKDF(K, "Pair-Setup-Controller-Sign-Salt",
  "Pair-Setup-Controller-Sign-Info", 32) ‖ controllerId ‖ controllerLTPK`.
- M6: State=6, EncryptedData (nonce `"PS-Msg06"`). The plaintext is a TLV of
  Identifier (accessory id), PublicKey (accessory Ed25519) and Signature over
  `HKDF(K, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", 32)
  ‖ accessoryId ‖ accessoryLTPK`.

### 7.3 pair-verify (M1-M4)

- M1: State=1, PublicKey = controller's X25519 ephemeral key.
- M2: State=2, PublicKey = accessory's ephemeral key, and EncryptedData
  (key `HKDF(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32)`,
  nonce `"PV-Msg02"`) of a TLV with Identifier and Signature over
  `accessoryEph ‖ accessoryId ‖ controllerEph`.
- M3: State=3, EncryptedData (nonce `"PV-Msg03"`) of a TLV with Identifier
  and Signature over `controllerEph ‖ controllerId ‖ accessoryEph`.
- M4: State=4, sent in plaintext. Encryption starts after it.

### 7.4 Encrypted control channel (HomeKit pairing only)

```
writeKey (sender→receiver) = HKDF-SHA512(ikm=shared or K, salt="Control-Salt", info="Control-Write-Encryption-Key", 32)
readKey  (receiver→sender) = HKDF-SHA512(ikm=shared or K, salt="Control-Salt", info="Control-Read-Encryption-Key", 32)
frame = len(2 bytes, little-endian, ≤ 1024) ‖ ChaCha20-Poly1305(key, nonce, aad = the 2 length bytes, plaintext) ‖ tag(16)
nonce = 00 00 00 00 ‖ counter(8 bytes little-endian), one counter per direction starting at 0, +1 per frame
```

The receiver decrypts with writeKey and encrypts with readKey. Events use
`Events-Salt` / `Events-Write-Encryption-Key` / `Events-Read-Encryption-Key`.
None of this applies to Rozan's profile (01 §3.6).

---

## 8. "Code on every connection, nothing remembered"

### 8.1 Conclusion

- The receiver must not keep sender trust between sessions, and it must
  advertise so that the sender has **no usable credential** at the next
  connection.
- **Mode D (the alternative; this spec's first recommendation).** Use bit 27 = 0, `pw=true` and RAOP
  `sf=0x84`, with a new 4-digit code for each connection checked by RTSP
  Digest on the first SETUP. The sender's remembered password is always
  stale, so it prompts every time. No receiver identity rotation is needed,
  and the identity key may be stable. It also covers senders that cannot
  PIN-pair.
- **Mode P (the default since the 2026-11-26 device tests; stronger).** Use bit 27 = 1, `_airplay` `pw=false`
  and `flags=0xc` (PINRequired), /info `statusFlags` 76, and RAOP
  `pw=true` and `sf=0x8c` (01 §2.5, §8.6 test 6). Run legacy PIN
  pairing, and **rotate the receiver's
  Ed25519 identity key after every session**. Apple senders remember the
  receiver only by that key, so a new key means "never paired" and the
  sender runs `pair-pin-start` again. Senders that do not PIN-pair
  get the Mode D Digest challenge. The receiver identity must be **stable
  within a session** (from `/info` through the PIN flow, pair-verify and
  any reconnects in that session) and **must change between sessions**.
  macOS 27.0.1 re-prompts when the `pk` changes (§8.6 test 2, 2026-11-26).
  **UNVERIFIED:** that iOS 18/26 and macOS 15/26 re-prompt (rather than
  erroring) when the `pk` of a known `deviceid` changes, and that a
  mirroring sender that does not PIN-pair still prompts on the Digest `401`
  now that `_airplay` `pw` is `false` (§8.6 tests 2, 3 and 5).
- In both modes `deviceid`, `pi` and the receiver name stay stable. They
  identify the TV, not a trust relationship.

### 8.2 Mode P in detail

State, all in RAM:

- `identity`: the current Ed25519 key pair.
- `sessionTrust`: a set of sender Ed25519 public keys that completed PIN
  step 3 since the last rotation, each with the time of its step 3.
- `activeSession`: whether a client currently has a session.

Rules:

1. Create `identity` at process start. Advertise its `pk` in both TXT records
   and `/info`.
2. `pair-pin-start` → show a code (§5.1). The full PIN flow (§5.3-5.5) adds
   the sender key to `sessionTrust`.
3. `pair-verify` step 1 is accepted only for a key that was PIN-authenticated
   on this connection, or is in `sessionTrust` (§4.3). In Mode P a stale
   key from an earlier session is refused with `470` and the connection is
   closed. A `sessionTrust` entry counts for **30 s** after its step 3, or
   for as long as a session is active. The sender's pair-verify follows
   step 3 within moments (the 10 s rotation grace in rule 5 assumes as
   much), so 30 s leaves room, and a sender that PIN-pairs and then does not
   start a session cannot come back later without a code.
4. SETUP gate: if the connection is `VERIFIED` with a PIN-authenticated key,
   run SETUP. Otherwise run the Mode D Digest challenge (§6). Then a sender
   that skips PIN pairing (`/pair-setup` only, or no pairing) still has to
   type the on-screen code.
5. **Rotate** when the session ends: whole-session TEARDOWN, or the last
   control connection of the session closes, or 60 s idle. Rotation means:
   make a new `identity`, clear `sessionTrust`, rebuild both TXT records with
   the new `pk`, re-announce or re-register them, and serve the new `pk` from
   `/info`. Never rotate while a PIN flow is in progress (from
   `pair-pin-start` until step 3 completes or times out, 120 s) or while a
   session is active.
   - Clear `sessionTrust` when the session ends **even when the rotation
     itself has to wait** for a PIN flow. Otherwise any host on the LAN
     that keeps a code of its own live (one `pair-pin-start` every 100 s
     is enough) defers the rotation, and with it the forgetting, forever.
   - "Idle" means no PIN step 3 and no active session for 60 s. Requests
     that authenticate nothing (`/pair-setup`, `pair-verify`, `/info`, a
     `pair-pin-start` from any host) are not activity: an unauthenticated
     32-byte `/pair-setup` every 50 s from another host would otherwise
     keep the identity from ever rotating.
   This is Rozan policy, not wire behaviour; senders see only the `pk` and
   the 470 of rule 3.
6. Nothing in this state is written to disk.

What the sender sees at the next connection: it reads `pk` from the
`txtAirPlay` blob in the first `GET /info` (01 §6.1), finds no stored pairing
for it, and starts `pair-pin-start`.

Risk: a sender might decide from its **mDNS-cached** TXT (old `pk`) and try
`pair-verify` first. The receiver then refuses (rule 3). The sender may
then fall back to the PIN flow, or report an error. **UNVERIFIED.** The
sender always sends `GET /info` with the `txtAirPlay` qualifier first, which
makes the first outcome likely.

### 8.3 Why Mode P is the default, and what Mode D offers

Mode P is the default. On 2026-11-26 it worked with macOS 27.0.1 and iOS
27.2.1 for mirroring, and with iOS Music for audio (§8.6 tests 2, 3, 5 and
6). It binds the media key to the ECDH secret of pair-verify, so a passive
observer on the LAN cannot recover the media key (§9.3), and with the
values in 01 §2.5 macOS shows its on-screen code entry instead of a
password field with "Remember password". Its costs: the identity rotation
re-registers the TXT records after every session, and iOS keeps using the
previous key for about 2 minutes, showing "Unable to connect" (§8.6 test
4).

This spec first recommended Mode D, for these reasons, which still hold
for Mode D as the alternative:

- One mechanism covers every sender, including those that cannot PIN-pair.
  Classic RAOP senders (macOS Music) cannot authenticate with a PIN, so
  they need a per-session password. That is Mode D. macOS 27.0.1 Music
  sends no pairing request: it speaks classic RAOP (01 §4.3.2), so Rozan
  gates it with the Digest password in both modes (§6.4).
- It needs no TXT churn. With NsdManager, every TXT change means
  re-registering, and the receiver briefly disappears from pickers.
- Costs are in §9.3: the media key is not bound to ECDH, so a passive LAN
  observer can recover it. Digest MD5 is weak, which matters little for a
  code used once.

### 8.4 A sender with a cached pairing that the receiver no longer recognises

| Situation | Sender behaviour |
|---|---|
| Legacy PIN, receiver `pk` unchanged, receiver accepts any pair-verify | Skips the PIN. pair-verify succeeds. Connected with no prompt |
| Legacy PIN, `pk` unchanged, receiver refuses the sender at pair-verify (for example with **200 and an empty body** to step 1, or an error and a disconnect) | The sender cannot complete verify. What it does next is **UNVERIFIED** (re-pair, error, or retry loop) |
| Legacy PIN, receiver `pk` changed | Treated as never paired: PIN flow. Seen on macOS 27.0.1 (§8.6 test 2, 2026-11-26); **UNVERIFIED** on iOS 18/26 and macOS 15/26 |
| Digest, remembered password now wrong | Tries the stored password, gets 401, prompts the user |

### 8.5 Must the identity key be stable?

- Discovery does not need a stable `pk`. Senders find and list the receiver
  by service instance and `deviceid`. `pk` is only consumed by pairing.
- Mode D: stability does not matter. A per-install key is simplest.
  Rotating it is harmless; it only re-prompts MDM senders that PIN-pair on
  their own.
- Mode P: the key must be stable within a session and must change between
  sessions (§8.2). A stable key would let the sender skip the code next
  time.
- Trust in senders is never remembered. `sessionTrust` lives in RAM and is
  cleared at rotation. No sender key, deviceID or name is ever persisted.

### 8.6 Receiver options and device tests

| Receiver option | Advertisement | Behaviour |
|---|---|---|
| No pairing, no password | bit 27 = 0, `pw=false`, `sf=0x4` | No pairing, no code |
| PIN pairing, persistent receiver key | bit 27 = 1, `pw=true`, `sf=0x8c`, `flags=0x4` | New random PIN at each `pair-pin-start`. **One-time**: the receiver key persists, so a paired sender returns through pair-verify with no prompt, and the receiver accepts any returning key |
| Fixed PIN | same | The same PIN every time. Otherwise as above |
| PIN pairing with a register of sender keys | same | A successfully PIN-paired sender's Ed25519 key, timestamp, deviceID and name are stored on disk at SETUP. A returning sender's pair-verify key is checked against the register; unknown keys get an empty 200 (pairing fails). Deleting an entry deregisters that sender. This is the opposite of "nothing remembered" |
| Per-connection Digest code | bit 27 = 0, `pw=true`, `sf=0x84` | **Mode D**: random 4-digit code per new connection via Digest |
| Fixed Digest password | same | The sender remembers it, so there is no prompt after the first time |

Rozan's Mode P does not use the `_airplay` and /info values of the
persistent-key PIN option: it sends `_airplay` `pw=false` and `flags=0xc`,
and `statusFlags` 76. The RAOP record keeps that option's `pw=true` and
`sf=0x8c` (01 §2.5).

Device test plan. Run it on iOS 18, iOS 26, macOS 15 and macOS 26 before
enabling Mode P. Mode D needs only test 1.

1. Mode D: mirror twice in a row. Both times the code prompt appears, the
   right code works, and a wrong code gets a new 401.
2. Mode P: mirror, stop (TEARDOWN), then mirror again. Expect
   `pair-pin-start` both times. The second session's `pk` differs.
   **Passed on macOS 27.0.1** (MacBook Pro Mac15,9, build 26A434, Control
   Center > Screen Mirroring, against `:tools:devserver`), 2026-11-26.
   After the whole-session TEARDOWN the advertised `pk` changed, and the
   next connection ran `pair-pin-start` again with a new code. That run
   used the persistent-key PIN option's values (`pw=true`, `flags=0x4`,
   `sf=0x8c`, `statusFlags` 68), with which macOS asks for the code as a
   "password".
   The rotation was seen again the same day with `pw=false` and
   PINRequired on both records: after each whole-session TEARDOWN the
   advertised `pk` changed (`fe7e5e59…` to `a8af7194…`), and the next
   connection ran `pair-pin-start` with a new code. With the current
   values, test 6 repeated the four-box code flow.
3. Mode P: Music (macOS) and Music (iOS) audio. Expect PIN pairing, or a
   Digest prompt as the fallback, and no silent connect. **macOS 27.0.1**
   (2026-11-26): Music speaks classic RAOP (01 §4.3.2). The connection that
   closes after `fp-setup` ×2 and `OPTIONS *` is its selection probe;
   at playback a second connection sends `ANNOUNCE` and a classic `SETUP`,
   which the receiver of that day refused with `400`. The Digest gate on
   `ANNOUNCE` (§6.4) and the classic SETUP (01 §6.8.1) await a run on
   the Mac: expect an AirPlay password prompt at
   playback, the on-screen code accepted as the password, and audio.
   **Passes on iOS 27.2.1** (Music,
   2026-11-26): the PIN flow, then SETUP, RECORD and a type 96 stream
   SETUP with ALAC 44.1 kHz stereo; track changes arrived as FLUSH plus
   new metadata, and volume as SET_PARAMETER.
   While one sender streams, another (macOS 27.0.1) gets 409 on its first
   request (`GET /info`) and shows "Unable to connect"; the first session
   carries on.
4. Mode P: force a pair-verify with a stale key and record what the sender
   does. **Seen on iOS 27.2.1** (iPhone18,2), 2026-11-26, without forcing
   it: after a session ended with TEARDOWN (and the identity rotated and
   was re-announced), the iPhone's next three attempts, 5 s, 13 s and 39 s
   later, went straight to `POST /pair-verify` with the old key, without
   `GET /info`. Each got 470 and iOS showed "Unable to connect". About
   2 minutes after the TEARDOWN it sent `GET /info` and
   `POST /pair-pin-start`, got a new code and paired. macOS 27.0.1 sent
   `GET /info` and `pair-pin-start` on its next attempt (21 s after a
   TEARDOWN) every time. Open usability issue: an iOS user who stops and
   restarts mirroring within about 2 minutes sees "Unable to connect".
5. Mode P: mirror from iOS/iPadOS and from macOS 15/26 with the current
   values. Expect `pair-pin-start` and an on-screen code entry. A
   `/pair-setup` instead means the sender ignores PINRequired and reads
   `pw=false` as transient pairing, which Mode P refuses at pair-verify
   (§4.3); record the prompt the sender shows either way. **Passed on iOS
   27.2.1** (iPhone18,2), 2026-11-26: `pair-pin-start`, then the code in a
   single password-style field with a number-only keyboard (01 §2.5).
   After one wrong code (470 on `pair-setup-pin` step 2) the same code was
   accepted on the next try. macOS 15/26 and iOS 18/26 not yet tried.
6. Mode P, current values (`_airplay` `pw=false` `flags=0xc`, /info
   `statusFlags` 76, RAOP `pw=true` `sf=0x8c`): mirror from macOS. Expect
   the four-box code entry, not a password field. **Passed on macOS
   27.0.1** (Mac15,9), 2026-11-26, against a dev receiver built from these
   values on control port 7000: the four-box code entry, the code accepted
   on the first try, `pair-pin-start` → `pair-setup-pin` ×3 →
   `pair-verify` ×2 → `fp-setup` ×2 → SETUP, RECORD, SETUP, and H.264
   1920x1080 mirroring. The connection came over IPv6 (a ULA address).

---

## 9. Security notes

### 9.1 What an attacker on the LAN can send before pairing

Anything at all. The listener is reachable by every host on the LAN, and
over IPv6 link-local too. The unauthenticated surface and its controls:

| Request | Cost or risk | Control |
|---|---|---|
| `GET /info` | Discloses name, deviceid, pk and features (all public via mDNS anyway) | None needed. Rate-limit per IP (e.g. 10/s) |
| `POST /pair-pin-start` | Pops a code on the TV: a nuisance, and phishing if spammed | At most one active code per IP (a new one replaces it, PIN or Digest). No new code within 5 s of the IP's code still on screen; once that prompt is over, a new code at once. At most 3 codes per IP in any 10 minutes that did not pair (see below). Answer 409 while another client streams or another IP's code is on screen. Global cap of 6 codes/minute. Lockout per §9.2 |
| `POST /pair-setup-pin` step 1 | Two 2048-bit modexps (CPU) | Needs a valid code for this IP. Rate-limit |
| `POST /pair-setup-pin` steps 2/3 | Online PIN guessing | §9.2 |
| `POST /pair-setup`, `/pair-verify` | Cheap X25519/Ed25519 | Mode P rule §4.3. Size checks |
| `POST /fp-setup` | Runs the FairPlay code on attacker input | Strict length checks (16/164). Fuzz the implementation (04-*) |
| First SETUP | Mode D: pops a code | As for `pair-pin-start` |
| `ANNOUNCE` (classic RAOP) | Parses an SDP body; pops a code (§6.4) | SDP bounds and the 8 KiB body cap (01 §6.17) before anything else; the code controls of `pair-pin-start` |
| bplist bodies | Parser exhaustion and crashes | Bounded parser (01 §3.5). Header/body limits (01 §3.2). At most 12 concurrent connections, max 4 per IP |

Per-IP code budget (Rozan policy, not wire behaviour). The 5 s spacing alone
lets one IP take 12 codes a minute, twice the global cap, without one wrong
guess: it takes every slot the moment the minute frees it, every other sender
is refused, and its prompt never leaves the screen. So a code counts against
its IP from the moment it is shown until it pairs, for 10 minutes. Codes that
expire, are superseded by the same IP, are used up or are cancelled all
count; a code that pairs is given back. A fourth unpaired code within 10
minutes is refused like a throttled one (470 for `pair-pin-start`, a plain
401 for Digest). A person who asks for a code three times in a row without
typing it waits at most 10 minutes. A host with many addresses (any IPv6
host) is still bounded by the global cap. The 5 s spacing only protects the
code on screen from being replaced at once by a repeated request; after a
prompt ends (paired, cancelled, expired or used up) the same IP may get a
new code straight away, so a user who mirrors again right after a session
is not kept waiting, and abuse is bounded by the 10-minute budget instead.

One prompt at a time (Rozan policy). While a code is on screen for one IP,
every other IP that asks for one (`pair-pin-start`, or the SETUP that would
get a Digest challenge) is answered `409` and the connection is closed, the
same answer as while another client streams (01 §3.1), and nothing is shown.
Otherwise a second host could replace the prompt a person is reading with
its own code, or put a look-alike prompt next to it under a copied sender
name, and the person would type the wrong code into their own device. The
code's own IP may still replace it (§5.1). The per-IP budget above bounds how
long one address can hold the screen: about 6 of every 10 minutes.

Must be refused before authentication (01 §5): stream SETUP, the classic
SETUP, RECORD, GET/SET_PARAMETER, FLUSH, `/audioMode`, `/command` → `470`.
Also refuse `pair-verify` step 2 without step 1, and `pair-setup-pin` steps
out of order.

Pitfalls:

- A receiver that formats the stored PIN with `%04u` and resets a random
  PIN to 0 once it is used runs SRP with the password `"0000"` for a
  `pair-setup-pin` step 1 that arrives without a fresh `pair-pin-start`.
- If pair-verify does not check that the key equals the PIN-authenticated
  one, the protection of §4.3 rule 2 and §9.3 is lost.

Rozan must run `pair-setup-pin` only against a live code that
`pair-pin-start` issued for that IP (§5.3 step 1). It must never fall back
to a default value, and it must apply §4.3 rule 2.

### 9.2 PIN / code guessing: rate limiting and lockout

The code has 10⁴ values. SRP and Digest both force online guessing against
the receiver: each guess is one request, so a guess cannot be tested
offline. Limits:

- **Per code:** at most 3 failed SRP proofs, or 4 Digest failures (§6.2).
  Then the code is used up, and a new one is shown only on a new
  `pair-pin-start` or SETUP. An attacker's success chance is ≤ 4/10⁴ per
  code, and every new code is visible on the TV.
- **Per source IP** (the full address, IPv4 or IPv6): failures 1-5 are
  free. The 6th failure locks the IP out for 30 s, and each further failure
  doubles the lockout, up to 10 min. A success clears the record. Records
  with 1 h of inactivity are dropped. While locked out, `pair-pin-start` and
  `pair-setup-pin` get 470 and Digest gets a plain 401, with no code shown
  and nothing checked. Locked records must never be evicted to make room
  (fixed table, e.g. 64 entries, evicting only unlocked ones).
  Rozan policy, not wire behaviour: an earlier revision keyed IPv6 by its
  /64 prefix. Every host on a link shares `fe80::/64`, and every SLAAC host
  on a home LAN shares the LAN's /64, so one host's 6 wrong guesses locked
  every IPv6 sender on the network out, renewable for as long as it kept
  guessing. A host that switches IPv6 addresses to get more free failures
  is still held by the per-code limit above and the global counter below.
- **Global:** more than 20 failures in 10 min across all IPs (IP rotation
  attack) → stop issuing codes for 10 min, and say so on screen.
- Codes expire after 120 s.
- Never log codes, nonces, K, shared secrets or sender keys. Release builds
  should log neither sender names nor IPs.

### 9.3 Properties of each mode

- **Mode D without pairing.** The media AES key is wrapped only by
  FairPlay. The receiver's fp-setup messages are fixed, and the unwrapping
  algorithm is public (04 §3 and §4). A passive observer who
  records fp-setup and SETUP can therefore recover the key and decrypt
  mirroring and audio. This is my analysis from the public algorithms, not
  a tested attack. Digest MD5 over a 4-digit code can be cracked instantly
  from a sniffed exchange, but the code is used once and already consumed.
  An active man-in-the-middle can relay the challenge.
- **Mode P.** The media key is hashed with the X25519 secret (§4.5), so a
  passive observer learns nothing. The long-term keys are exchanged under
  the SRP key K, so a man-in-the-middle needs the code. This holds only if
  the receiver checks that the pair-verify key equals the PIN-authenticated
  key (§4.3 rule 2).
- **Classic RAOP, in both modes (§6.4).** The same as Mode D without
  pairing: the media key is the FairPlay v2 key from `fpaeskey` (03 §2.1),
  and FairPlay v2's algorithms are public (04 §3), so a passive observer
  who records `fp-setup` and `ANNOUNCE` can recover the key and decrypt the
  audio. My analysis, not a tested attack. Mode P's binding of the media
  key to pair-verify does not cover Music on a Mac. Access still needs the
  on-screen code.
- Mode D and P both resist replay: every nonce, SRP b, salt and ephemeral
  key is fresh. A Digest answer is valid only for the latest nonce on its
  own connection, so an answer seen on one connection is worthless on
  another.

---

## 10. Test vectors

All values below are from `/tmp/rozan-spec-a/vectors.py` (outside the
repo). Inputs are fixed byte patterns; real implementations use random
values.

### 10.1 SRP-6a (Apple legacy variant)

```
I (user) = "AA:BB:CC:DD:EE:FF"
P (pin)  = "1234"
s  = 0102030405060708090a0b0c0d0e0f10
b  = 202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
a  = 404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f
H(I ":" P) = d0e5b423e76a09e8a917a08d90f51fc3e50bb7b4
x  = d22bef04575da46231610b99e4b03b1b03e19b19
v  = 3670769661ab94292ee2c88ff01bbd2b3d294c1e7c5965dc1de0cf1564f2d711
     42a7f96ead279f469eac42d46be258f0b80a41dcf424cf4d7410c4b8f654673f
     65d50ef8132ddb7924644003d6669e6467af53eb4399dc332f813181b645d610
     bcc398afd1ceedcf6704a9651f3a9ee58cffdcb5e2e6fdace2861d17831c4c54
     64c3dc84b7166a1b770b6eb91b82b67cb8c83e9f5b2044834eadd6ff4b70722e
     e93beb96c91ecfa75574c8adcc7e75f7b56042347b677d0b5c628157eca3a334
     90979fed31d2d7d75280a5c8147c58896025ace492a2fda9121cc311e6c45248
     45bad3452bf44cf7a8fab18a2b7db15fe1c950a57358c4228d8d7a2d1234d637
k  = a56303f32c60e599e82c396f0d57f1b344a7313c
B  = 2c526c063c05323b1d745de49fc362e25d87039874521bf4c030d749cfba4382
     80c0b6fff41add825ded8edafd7ebe200608c7cd8b962326516870ac800771bd
     606013954f5940a4a68ad29856f0e08d761944cda4abd7f300a1b43ee4d7eba4
     1fde03c497cee3e61e37fac93f8d397cabd126f0ac504112c7d382d2ae5d42c5
     18ab55eac38dd5cdf37d7f824e8325dd5d36c5413f54ccd75c4f588c266057f8
     67d8f6bd722b75a13527cc61a4470ca6040e6f44b828b398eb65c3c4727f6702
     5111498947ad8e6f6ddb617ecaeec9ca17b1e5ff9848fa025dcae11bb14d748e
     ebad38ea33d8690c71fb657ab1c6d6df7abc4ef0b04c4723abe43bf6649e8659
A  = 937c7b0538da06d03f23cdbc517a103be9e9bcc12d10ff4739a6d2ad4bf4659d
     15a34d340b4ebfff7004fbc2eed5572552bd03e4fdfc3be08cbf9156eff56637
     34e1a012a2a155e09259571d4f4541211480155063258c3861a98625b843d775
     206031fa1aa00d919ea734c984ac067654baca3007677125bcb4b187979306a5
     24c6b231a665dec628a7a03cd4cf4078e794a3d76b9f9134523adbf66393ec43
     49b38888d198159b7200abdb1383870fced29e72c8a79f04bab0cd39d5dc1bad
     e5ee28b627f22ef6078f72be3b583e799ac199bb6c7fd411debd53ceb120f5c5
     8c14ec4b58d5b26a6fb2c906d4f10ebc1acbd92793a4c86e341cafbf6bfa41f1
u  = 3b590c86553e5318074dc89de1fd094fd4684508
S  = 66f751cd81ad650814625b58cc15600f0b16485023e89ef5ab9595255cd31559
     51fb7370d6da573d96fa58f90f9bb3fa47cbdf3bf5aa2fd6799b2aa868c4f0e8
     0b7ac2e1c00600d7fbfdc2090b0d9e7b339c814dd17dafec6f8dce534b2ee481
     8e3d1545a58196999b39f2acee665976074a0ffe09419ff65a2153b330d6af41
     cb2876d03076d0b0d26b8eea38713c58c042118a21a5c6aa7b760297fcb0c5fc
     c6e1c8588ee4bca1814bc8f2456efb5eddb084a37a3da6809505d471920a4f22
     7027fd07a9dbb3596dca739e7e37915fe20726443758bcb96f09416faaefa1eb
     74686ad0d28d6877bdd664f96c3d14f6cbc6b138f643c9329faf6cafc76bf70d
     (client-side and server-side S agree)
K  = d55b8d6406a174139f90b41473460ae2a0f55c2c753d8b2a7da31acca9420de28d2f7a5a43b447e4   (40 bytes)
H(N) xor H(g) = d18804efde5672636b7c613bafdad5df8e505f3c
M1 = 11fa191ea7c999c8029eba1ade3eeeefbe19f11f
M2 = d2a84ae2724b7fb5b0447d6ecce62ef74a865337
```

### 10.2 pair-setup-pin step 3 (AES-128-GCM, 16-byte IV, no AAD)

```
gcmKey = SHA-512("Pair-Setup-AES-Key" ‖ K)[0..15] = 1123f1b97cba2fb15eeca8c3474a992d
IV0    = SHA-512("Pair-Setup-AES-IV"  ‖ K)[0..15] = e5c2fd58ee8df195da8e5758af1f50c9
IVc (byte15+1)                                    = e5c2fd58ee8df195da8e5758af1f50ca
IVr (byte15+2)                                    = e5c2fd58ee8df195da8e5758af1f50cb
sender Ed25519 seed    = 606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f
sender Ed25519 pubkey  = 174553b456dddfc6908ecab1c101fe6ab21e2baa0617795b7d43a63482993fd5
request  epk           = ea4fb875944de961bc74b3beb6337a5b5222dc13af795213989529587758b6b6
request  authTag       = 2a74d8217486c694ab0674f529e9835b
receiver Ed25519 seed  = 808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f
receiver Ed25519 pubkey= cd14b37f956e953194ff7fb73b3d81dcc561d61a7538094b7c3e1a643ee5f3aa
response epk           = 0d71689aeee69d453899fbf0883339825c4a291eaaef7fde146c1db8aee46851
response authTag       = ce15bf83b46484febee3d98af0747f56
```

### 10.3 Legacy pair-verify

Long-term keys are those of 10.2.

```
sender X25519 eph priv   = a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf
sender X25519 eph pub    = 605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c
receiver X25519 eph priv = c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf
receiver X25519 eph pub  = dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44

request 1 (68) = 01000000
                 605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c
                 174553b456dddfc6908ecab1c101fe6ab21e2baa0617795b7d43a63482993fd5
shared         = 3b87c5035cce13678dd93d2dbc3a3d42039ebec86444a1ff82cd61b84c6fe968
aesKey         = 17aea8a8febc8464dd79dcb9b66979c9
aesIV          = 29699cd358d299706e694ffac817b6ba
sigR (receiver signs receiverEph ‖ senderEph)
               = 3a728c2c17b9cfb8d1348f603b451aa3d2dd1e1d65fcd1927634e2b75857069f
                 054713161795a8cb1904b371aa2572553e62e386ef55b779daa2ccea19922c0b
response 1 (96)= dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44
                 cd6aa83e570869d7247b7e30f5f7a1af53b5cd73a3275b6eb9bb0772c4002e79
                 41d3b5876514c5eb385bb45daec98c1dcb86d5378ceb418f94544e748f0ec389
sigS (sender signs senderEph ‖ receiverEph)
               = 42859a8b0f3aa818657baa0a630ed77ae1eeb9a3a72a03327b4a1976c27ca31a
                 1a5d1d9f4a261e4091e77f3ef786e14fd663050d32cb3467b48194f68a1dff0a
request 2 (68) = 00000000
                 dc4f6eeab56146ae0338dd729f58437d66b9f4e14011b28afcbdaaf856a941d1
                 53b6901aab5499b42e1db996b58287b663ee651c2fe986ed4c7a05ebb0ad9d20
                 (= sigS XOR keystream bytes 64..127)
```

Media key example (§4.5): `mediaKey = SHA-512(fairplayKey16 ‖ 3b87c503…e968)[0..15]`.
The media agent supplies its own vectors for fairplayKey.

### 10.4 Digest (Mode D)

```
username="AirPlay" realm="raop" password(code)="4821"
nonce="a1b2c3d4e5f60718293a4b5c6d7e8f90" method="SETUP" uri="rtsp://192.168.1.20/2934859221"
HA1 = MD5("AirPlay:raop:4821")                                  = 09cbff77416aa1298b86ca6a5a24019e
HA2 = MD5("SETUP:rtsp://192.168.1.20/2934859221")               = 7c0a6dca01983f72a20cbf7ac62bb002
response = MD5(HA1 ":" nonce ":" HA2)                           = 8ff0d9e4586ff1fe0bdf00259c3078d6
```

The RFC 2617 §3.5 example ("Mufasa", response `6629fae49393a05397450978507c4ef1`)
checks the qop path.

Classic RAOP `ANNOUNCE` (§6.4), computed with Python's `hashlib`:

```
username="iTunes" realm="raop" password(code)="4821"
nonce="a1b2c3d4e5f60718293a4b5c6d7e8f90" method="ANNOUNCE" uri="rtsp://192.168.1.10/3413821438"
HA1 = MD5("iTunes:raop:4821")                                   = ecf01412782e979164b4ebe1b9c0445a
HA2 = MD5("ANNOUNCE:rtsp://192.168.1.10/3413821438")            = 5bc41bef58ac2ac9c639975eaecfdd39
response = MD5(HA1 ":" nonce ":" HA2)                           = 6ad5bb05dc6cc7c658eea3845eef02da
```

---

## 11. UNVERIFIED points in this file

1. Mode P: that iOS 18/26 and macOS 15/26 run `pair-pin-start` again when the
   receiver's `pk` changes for a known `deviceid` (§8.1, §8.2). macOS 27.0.1
   does (§8.6 test 2, 2026-11-26). It is also unknown what a sender does
   when pair-verify is refused (§8.4). Device tests 2 and 4 in §8.6 would
   confirm both.
2. Mode D: that the remembered-password retry and the re-prompt work on
   iOS 18/26 and macOS 15/26 (§6.3). Device test 1.
3. The classic RAOP gate (§6.4) on a real Mac: that Music 1.7 retries the
   `ANNOUNCE` on the same connection, accepts the code as its password, and
   asks again after a wrong code. macOS 27.0.1 Music sends no pairing
   request in any mode tried (01 §4.3.2); device test 3.
4. The behaviour when IV byte 15 overflows in `pair-setup-pin` step 3
   (§5.5), and Apple's padding of S or A when the top byte is zero (§5.2).
   Each has a probability of about 1/256 per attempt. A device test that
   forces those cases would settle them.
5. That Apple senders verify `sigR` against the trusted `pk` (§4.4). Send a
   mismatched signature on a device.
6. That Android's Conscrypt accepts a 16-byte GCM IV (§2.1). Run a unit test
   on Android API 25 and 34.
7. `X-Apple-Client-Name` on `pair-pin-start` from Apple senders (§5.1).
8. Mode D+: whether bit 27 = 1 with `pw=true` and `sf=0x84` (password
   without the PIN bit) makes senders do transient `/pair-setup` +
   `pair-verify` (giving ECDH-bound media keys) and then accept the Digest
   challenge. Not tried by anyone I know of. It is an experiment, not part
   of the spec.
9. Mode P's advertisement (`_airplay` `pw=false` with PINRequired in
   `flags` and `statusFlags`, RAOP at `pw=true` `sf=0x8c`, §1.1): that
   iOS/iPadOS 18/26 and macOS 15/26 run the PIN flow with it rather than
   transient `/pair-setup` (test 5), and that a sender that does not
   PIN-pair still prompts on the Digest `401` (test 3). All in §8.6.
   macOS 27.0.1 mirroring works with it (test 6).

## 12. Implementation checklist (pairing and access control)

- [ ] Primitives: SHA-1/512, MD5, AES-128-CTR (full-block counter),
      AES-128-GCM with a **16-byte IV**, Ed25519 and X25519 (BouncyCastle
      on Android), and BigInteger SRP with `minimal()`/`pad256()` helpers.
      Pass all §10 vectors in unit tests.
- [ ] A receiver Ed25519 identity from `SecureRandom`, used identically for
      TXT `pk`, `/info` `pk`, the `/pair-setup` reply, the step-3 `epk` and
      pair-verify signatures.
- [ ] Mode D: bit 27 = 0, `pw=true`, RAOP `sf=0x84`. On the first
      SETUP, send a Digest `401` with `realm="raop"` and a fresh nonce, show
      a new 4-digit code per connection, verify MD5 Digest, and use each
      code once.
- [ ] `/pair-pin-start` and `/pair-setup-pin` (3 steps) exactly as in §5,
      in every mode: K is 40 bytes; M1 uses unpadded s/A/B and H(0x02);
      k and u are padded; the GCM IV gets +1 and +2 on byte 15. Regenerate
      the salt if `s[0]` = 0, and b if B < 2²⁰⁴⁰.
- [ ] `/pair-setup` (32 ↔ 32) and legacy `/pair-verify` (68 → 96, 68 → 0).
      One CTR stream (offsets 0 and 64), and signatures over
      `ownEph ‖ peerEph`.
- [ ] Export the pair-verify `shared` to SETUP:
      `mediaKey = SHA-512(fpKey ‖ shared)[0..15]` only when VERIFIED.
- [ ] Gate post-SETUP requests. Send 470 on pairing failures, then close.
- [ ] Classic RAOP (§6.4): in both modes, Digest on `ANNOUNCE` (method
      `ANNOUNCE`), the code keyed by IP and `DACP-ID`, `fp-setup` and
      `OPTIONS` open, the classic SETUP and later requests gated.
- [ ] Rate limits and lockouts (§9.2). Code expiry 120 s. Never log secrets.
- [ ] Persist nothing about senders. Optional Mode P: advertise bit 27 = 1,
      `_airplay` `pw=false` and `flags=0xc`, `statusFlags` 76, RAOP
      `pw=true` and `sf=0x8c`, rotate the identity after each session,
      re-announce TXT, keep `sessionTrust` only in RAM, and run the §8.6
      device tests before shipping it.
