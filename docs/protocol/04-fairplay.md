# 04 — FairPlay (receiver side)

Scope: how the Rozan AirPlay receiver answers the two `/fp-setup` exchanges and
how it turns the 72-byte FairPlay-encrypted media key (`ekey` in the mirroring
SETUP plist, `fpaeskey` in the RAOP/audio SDP) into the 16-byte AES key used to
decrypt the stream. Session flow and where these bytes appear on the wire are in
`01-discovery-and-session.md`; the stream ciphers that consume the 16-byte key
are in `03b-mirroring-video.md` and `03c-audio-rtp-and-timing.md`.

Revision 2 (2026-11-26) answers implementer questions: `decrypt_message` now
reduces to the AES-128 inverse cipher with explicit round keys (§4.6; revision
1 wrongly called `table_s9` plain InvMixColumns, see appendix A); session-state
rules for repeated, failed and out-of-order messages (§1.6); op 71's `% 20` is
kept as written and is provably unobservable (§4.9); the pitfall list in
§6 is now backed by mutation testing; new vector files
`fairplay-v3-coverage.json` and `fairplay-sessions.json` (§5).

The companion data files are in `vectors/`. Section 7 lists how each
reduction was verified.

Conventions:

- Hex byte strings are MSB first, two hex digits per byte. `be32`/`le32` mean
  big-/little-endian unsigned 32-bit. `x ^ y` is XOR. `a || b` is concatenation.
- "Receiver" = Rozan. "Client"/"sender" = the Apple device.
- All arithmetic described as "32-bit" wraps modulo 2^32. All byte stores keep
  the low 8 bits. Integer division is truncating. Pitfalls are collected in
  section 6 — read it before coding section 4.
- Two variants exist, selected by the version byte at offset 4 of every
  `/fp-setup` message:
  - **v2** (`0x02`): Apple Music / iTunes, RAOP audio (`fpaeskey`). Section 3.
  - **v3** (`0x03`): screen mirroring and AirPlay video (`ekey`). Section 4.
  The receiver must support both; the client picks one per session.


## 1. The `/fp-setup` exchange and the key blob (both variants)

A session does FairPlay in three steps, all before any media key is sent:

1. **Setup.** Client POSTs a 16-byte message to `/fp-setup`; receiver replies
   with 142 bytes.
2. **Handshake.** Client POSTs a 164-byte message to `/fp-setup`; receiver
   replies with 32 bytes.
3. **Key decrypt.** Later, the client sends a 72-byte blob (in a plist or SDP,
   not on `/fp-setup`); the receiver turns it into a 16-byte key. This step can
   repeat within the session without repeating 1–2.

The receiver keeps one FairPlay state per RTSP connection. What happens when a
step fails, repeats or arrives out of order is in §1.6. The two variants differ:
a v2 handshake needs a v2 setup first, but a v3 handshake is accepted with or
without a setup.

### 1.1 16-byte setup request (step 1)

| offset | len | value | meaning |
|-------:|----:|-------|---------|
| 0  | 4 | `46 50 4c 59` (`"FPLY"`) | magic |
| 4  | 1 | `02` or `03` | **version** (selects v2/v3) |
| 5  | 1 | `01` | fixed |
| 6  | 1 | `01` | record type = 1 (setup) |
| 7  | 1 | `00` | fixed |
| 8  | 4 | `00 00 00 04` | be32 payload length = 4 |
| 12 | 1 | `02` | fixed (selector 2) |
| 13 | 1 | `00` | fixed |
| 14 | 1 | `00`..`03` | **mode** |
| 15 | 1 | `bb` | fixed |

The v3 receiver validates only offset 4 (`== 0x03`) and offset 14 (`< 4`). The
v2 receiver validates the whole header (magic, `02 01 01 00`, length `4`,
`02 00`, mode `< 4`, `bb`). Reject (close) anything else.

### 1.2 142-byte setup reply (step 1)

Both variants return a 14-byte prefix then a 128-byte body:

- prefix: `46 50 4c 59  <ver> 01 02 00  00 00 00 82  02 <mode>` — `"FPLY"`,
  the version (`02` or `03`, same as the request), `01`, record type 2, `00`,
  be32 length `130` (`0x82`), then `02`, then the mode byte at offset 13.
- body (offsets 14..141): 128 bytes.
  - **v2:** the encrypted receiver SAP — see §3.3.
  - **v3:** fixed per-mode data. The four full 142-byte replies are constants
    (§4.1); byte 13 is the mode.

### 1.3 164-byte handshake request (step 2)

| offset | len | meaning |
|-------:|----:|---------|
| 0  | 4 | `"FPLY"` |
| 4  | 1 | version (`02`/`03`) |
| 5  | 1 | `01` |
| 6  | 1 | record type = 3 |
| 7  | 1 | `00` |
| 8  | 4 | be32 length = `152` (`0x98` at offset 11) |
| 12 | 1 | **mode** (v2: must equal the setup mode; v3: not compared with the setup mode, §1.6) |
| 13 | 3 | v2: fixed `8f 1a 9c`; v3: part of the client payload |
| 16 | 128 | encrypted client SAP / client message (the 8 cipher blocks) |
| 144 | 20 | v2: client confirmation tag; v3: 20 bytes echoed into the reply |

v3 validates only offset 4 and offset 12 (`< 4`). v2 validates magic,
`02 01 03 00`, length `152`, mode equals the setup mode, and `8f 1a 9c`.

### 1.4 32-byte handshake reply (step 2)

12-byte header then 20 bytes:

- header: `46 50 4c 59  <ver> 01 04 00  00 00 00 14` — record type 4, be32
  length `20`.
- 20 bytes:
  - **v2:** the confirmation tag the receiver recomputed (§3.4).
  - **v3:** a verbatim copy of handshake-request bytes 144..163.

The v3 header is exactly `46504c590301040000000014` (hex).

### 1.5 72-byte key blob (step 3: `ekey` / `fpaeskey`)

| offset | len | meaning |
|-------:|----:|---------|
| 0  | 8 | `46 50 4c 59  01 02 01 00` |
| 8  | 4 | be32 = `60` |
| 12 | 4 | be32 = `0` |
| 16 | 16 | v2: key blind (XORed into the recovered key); v3: **chunk1** |
| 32 | 4 | be32 = `16` |
| 36 | 20 | v2: HMAC-SHA1 tag; v3: unused |
| 56 | 16 | v2: AES-ECB-wrapped block; v3: **chunk2** |

v2 validates offsets 0..7, `[8]==60`, `[12]==0`, `[32]==16`. **v3 ignores all
framing** and uses only chunk1 (16..31) and chunk2 (56..71).


### 1.6 Session state, failures and repeated messages

Each rule below is covered by a scenario in `vectors/fairplay-sessions.json`.

State per connection: the active variant (none, v2 or v3). For v2 it also holds
`mode`, `receiver_sap`, `sender_sap` and two flags, *set up* and *handshaken*.
For v3 it holds the stored `message3` (present or absent).

**Setup (16-byte request).**

1. Discard the whole current state first, whichever variant it belongs to.
2. Then validate (§1.1). If validation fails, the call fails and the state stays
   empty. Any setup therefore ends the previous session, valid or not.
3. v2 success: a fresh random `receiver_sap`, *set up* = true, *handshaken* =
   false. v3 success: variant = v3, no `message3`.

**Handshake (164-byte request).** Choose the first matching case:

1. Byte 4 is `02` and the current variant is v2 (a v2 setup succeeded): run
   §3.4. At the start of the call, set *handshaken* to false and clear
   `sender_sap`. If the handshake fails, any earlier successful handshake is
   therefore gone too, but the setup state (`mode`, `receiver_sap`) is kept
   and the client may retry. A replayed valid handshake succeeds again. A second
   successful handshake replaces `sender_sap`, so blobs made for the earlier
   one stop decrypting.
2. Otherwise, if byte 4 is `03` and byte 12 is less than 4: discard the whole
   state, including any v2 state, set variant = v3 and store the request as
   `message3`. No setup is needed, and the mode need not equal the setup mode.
   Key recovery uses `message3[12]`. A second v3 handshake replaces
   `message3`.
3. Otherwise fail and discard the whole state. This covers a v2 handshake with
   no v2 setup, a v2 handshake on a v3 session, a v3 mode byte of 4 or more,
   and any other version byte.

**Decrypt (72-byte blob).**

1. Variant v2: run §3.5. It fails unless *handshaken* is true. **A failure here
   (bad framing, bad HMAC, corrupted blind or wrapped block) fails only that
   call. The session state is unchanged, and later valid blobs still decrypt.**
2. Variant v3 with `message3` present: run §4.3. It cannot fail, ignores the
   blob framing, and gives the same key for the same blob every time.
3. Otherwise fail. This covers no session, and v3 after a setup with no
   handshake since.

**HTTP layer.** The receiver answers `400` if the `/fp-setup` body is neither
16 nor 164 bytes, and `501` if byte 4 is neither `02` nor `03`. Both checks run
before the FairPlay state is touched, so those two rejections leave the state as
it was. A FairPlay rejection of an invalid message gives `400`, an internal
error (out of memory, crypto library failure) gives `500`, and success gives
`200` with `Content-Type: application/octet-stream` and the reply as the body.
On a failed key decrypt, reject that SETUP rather than carry on with an
all-zero key. This is a Rozan policy choice.

## 2. Shared cryptographic primitives

Both variants use **standard AES-128** (the FIPS-197 cipher: standard S-box,
ShiftRows, MixColumns, key schedule) and standard SHA-1, MD5 and HMAC-SHA1.
Where a step is AES-128 with an ordinary 16-byte key (v2's ECB unwrap §3.5,
v3's block decrypt §4.3), a platform AES implementation can be used directly.
Two steps run the AES *round function* with 11 given round keys instead: v2's
SAP cipher (§3.2) and v3's `decrypt_message` (§4.6). Implement that round
function by hand once and use it for both, because platform APIs always derive
the schedule from a single key.

State/byte order for the hand-rolled AES round function is the standard
AES column-major layout: state byte `s[4*col + row]`, `row,col ∈ 0..3`.
ShiftRows/MixColumns are the standard forward operations; their inverses are
InvShiftRows/InvMixColumns.


## 3. FairPlay v2 (Apple Music / RAOP audio)

v2 is a challenge/response over two 128-byte "SAP" blobs — one the receiver
generates, one the client sends — followed by an authenticated unwrap of the
media key. All constants are in `vectors/fairplay-v2.json` (round keys, masks,
prefix/suffix strings) and restated inline where short.

### 3.1 Receiver SAP

At setup the receiver creates a 128-byte `receiver_sap`: byte 0 = `0x00`,
byte 1 = `0x01`, bytes 2..127 = cryptographically random. It is kept for the
whole session. (For the test vectors a fixed SAP is injected; see §5.)

### 3.2 The SAP block cipher: AES round function with given round keys

The SAP transform encrypts/decrypts 128 bytes as eight 16-byte blocks. The
block primitive is the **standard AES-128 round function** (standard S-box,
ShiftRows, MixColumns, and their inverses) but keyed with **11 independent
16-byte round keys per mode**, listed in `round_keys_per_mode` (mode 0..3, 176
bytes = 11×16 each). These are **not** a standard AES key schedule (they do not
satisfy the key-expansion recurrence), so you cannot feed them to a one-key
platform AES; run the round function yourself with `keys[0..10]`:

- `encrypt_block(state, keys)`: `state ^= keys[0]`; for r=1..9: `SubBytes;
  ShiftRows; MixColumns; state ^= keys[r]`; then `SubBytes; ShiftRows;
  state ^= keys[10]`.
- `decrypt_block` is the exact inverse: `state ^= keys[10]`; for r=9..1:
  `InvShiftRows; InvSubBytes; state ^= keys[r]; InvMixColumns`; then
  `InvShiftRows; InvSubBytes; state ^= keys[0]`.

(These are standard AES-128 encrypt/decrypt with a custom, non-derived schedule.)

Three 16-byte mask tables per mode drive a CBC-like chaining:
`input_masks`, `output_masks`, `chain_masks` (in the JSON). `input_masks` is
all-zero except mode 2; the others are non-zero for every mode.

**`sap_encrypt(mode, plaintext[128]) -> cipher[128]`** (receiver uses this in
setup; the sender uses it to build a handshake — §5):

```
prev = 0^16
for block b in 0..7:
    s = plaintext[16b .. 16b+15] ^ input_mask[mode]
    if b > 0: s ^= prev ^ chain_mask[mode]
    s = encrypt_block(s, round_keys[mode])
    prev = s                               # chaining value = pre-mask ciphertext
    cipher[16b .. 16b+15] = s ^ output_mask[mode]
```

**`sap_decrypt(mode, cipher[128]) -> plaintext[128]`** (receiver uses this in
the handshake to recover the client SAP):

```
prev = 0^16
for block b in 0..7:
    cur = cipher[16b .. 16b+15] ^ output_mask[mode]
    s = decrypt_block(cur, round_keys[mode])
    p = s ^ input_mask[mode]
    if b > 0: p ^= prev ^ chain_mask[mode]
    plaintext[16b .. 16b+15] = p
    prev = cur
```

### 3.3 Setup (step 1)

Validate the request (§1.1). `mode = request[14]`. Build the 142-byte reply:
14-byte prefix `46 50 4c 59 02 01 02 00 00 00 00 82 02 <mode>` followed by
`sap_encrypt(mode, receiver_sap)`. Store `mode` and `receiver_sap`.

### 3.4 Handshake (step 2)

Validate the request (§1.3). Then:

1. `sender_sap = sap_decrypt(mode, request[16..143])`.
2. `mac_key = SHA1( mac_prefix || sender_sap || receiver_sap || mac_suffix )`
   (20 bytes). `mac_prefix = "asdiuAD8AKA9a>/?"`, `mac_suffix = "!D4H&2*203kjgha9"`
   (16 ASCII bytes each; hex in the JSON).
3. `confirmation = HMAC-SHA1( mac_key, 02 02 )` — the HMAC message is the two
   bytes `0x02 0x02`. 20 bytes.
4. Compare `confirmation` to `request[144..163]` in constant time. On mismatch,
   fail, keeping the setup state but no handshake (§1.6).
5. Reply = `46 50 4c 59 02 01 04 00 00 00 00 14 || confirmation` (32 bytes).

Keep `sender_sap` for step 3.

### 3.5 Key decrypt (step 3)

Validate the blob framing (§1.5). Then:

1. `wrapping_key = MD5( wrapping_prefix || sender_sap || receiver_sap ||
   wrapping_suffix )` (16 bytes). `wrapping_prefix = "#l*GY&cqnwo*&5sd"`,
   `wrapping_suffix = "57bv&%niulqpdg3z"`.
2. `recovered = AES-128-ECB-decrypt( wrapping_key, ekey[56..71] )` — one block,
   no padding. This ECB step **is** ordinary AES-128 (platform API fine).
3. `recovered ^= ekey[16..31]` (XOR the 16 bytes at offset 16).
4. `mac_key` as in §3.4.
5. `authenticated = ekey[0..35] || recovered` (36 + 16 = 52 bytes).
6. `expected = HMAC-SHA1( mac_key, authenticated )` (20 bytes). Compare to
   `ekey[36..55]` in constant time; fail on mismatch.
7. The 16-byte AES media key is `recovered`.

Any failure in this section fails only this call. Leave the session state
unchanged (§1.6).


## 4. FairPlay v3 (screen mirroring / AirPlay video)

v3's setup and handshake replies are fixed data; all the real work is in the
key decrypt. **Both of v3's block ciphers reduce to AES-128.** The final
key-recovery cipher is single-key AES-128 decryption (§4.3), so a platform AES
can be used. The client-message cipher `decrypt_message` is the AES-128
inverse cipher with 11 given round keys per mode, in CBC mode (§4.6). None of
the white-box tables are needed. What remains is data: round keys,
IVs, a few 16-byte constants, and the `sap_hash` seeds.

### 4.1 Setup (step 1): four static replies

For `mode = request[14] ∈ 0..3`, return the corresponding fixed 142-byte reply
verbatim (byte 13 is the mode). The four replies (hex):

- mode 0:
  `46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba`
- mode 1:
  `46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a`
- mode 2:
  `46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce`
- mode 3:
  `46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359`

### 4.2 Handshake (step 2)

Store the whole 164-byte request as `message3`. Reply with the 12-byte header
`46504c590301040000000014` followed by `message3[144..163]` (20 bytes echoed),
32 bytes in total. No cryptography happens here. The handshake mode need not
equal the setup mode, and a handshake with no prior setup is accepted. Key
recovery uses `message3[12]` (§1.6).

### 4.3 Key decrypt (step 3): overview

Let `chunk1 = ekey[16..31]`, `chunk2 = ekey[56..71]`. Compute:

```
sapKey   = generate_session_key(message3)          # §4.5, the hard part
aes_key  = sapKey ^ 7979…79  (0x79 repeated 16x)   # §6 note (f)
block    = AES128_Decrypt(aes_key, chunk2) ^ delta0
media_key = block ^ chunk1 ^ x_key ^ z_key
```

Constants (hex):

- `z_key  = 1a64f9606ce301a954481bd4ab81fcc6`
- `x_key  = 8eba07ccb65af62033cff842e5d55a7d`
- `delta0 = 94defeacdab9f7896787e3964e54a6bb`

`AES128_Decrypt` is ordinary AES-128 single-block decryption (platform API
fine). This whole step is three XORs around one AES-128 block decrypt. The
derivation (§4.4: that the white-box table cipher `T` equals
`AES128_Decrypt(sapKey ^ 0x79…, x ^ z_key) ^ delta0`) was verified on 3000
random (key, block) pairs; see §7.

### 4.4 Reduction used in §4.3 (reference only)

In its white-box form, this step computes
`media_key = T(chunk2 ^ z_key) ^ chunk1 ^ x_key ^ z_key`, where `T` is a
10-round table cipher keyed by a schedule derived from `sapKey`. That
schedule's effective round keys equal the AES-128 key expansion of
`sapKey ^ 0x79…79`, with two fixed residuals folded in, so
`T(X) = AES128_Decrypt(aes_key, X ^ z_key) ^ delta0`. Substituting and
cancelling the inner `z_key` gives §4.3. Implement §4.3; §4.4 is just why it is
correct.

### 4.5 `generate_session_key(message3) -> sapKey[16]`

```
dm = decrypt_message(message3)                       # §4.6, 128 bytes
newSap[0..319] =  static_source_1[0..16]             # 17 bytes
               || dm[0..127]                          # 128 bytes
               || default_sap_tail[0..127]            # 128 bytes (constant)
               || static_source_2[0..46]              # 47 bytes
sapKey = initial_session_key                          # 16-byte constant
for round r in 0..4:
    base = newSap[64r .. 64r+63]                       # 64 bytes
    m    = modified_md5(base, sapKey)                  # §4.7, 16 bytes
    h    = sap_hash(base)                              # §4.8, 16 bytes
    for word i in 0..3 (little-endian 32-bit):
        sapKey.word[i] = (h.word[i] + m.word[i]) mod 2^32
# byte-reverse each 32-bit word of sapKey:
for i in 0,4,8,12: swap(sapKey[i],sapKey[i+3]); swap(sapKey[i+1],sapKey[i+2])
for i in 0..15: sapKey[i] ^= 0x79
```

Note `m` uses the running `sapKey` as its IV; `h` depends only on `base`. The
16-byte constants `static_source_1`, `static_source_2`, `default_sap_tail`,
`initial_session_key` are in `vectors/fairplay-v3-tables.json` under
`session_key`.

### 4.6 `decrypt_message(message3) -> dm[128]`

`mode = message3[12]`. The ciphertext is the eight 16-byte blocks
`C[i] = message3[16 + 16i .. 31 + 16i]`, i = 0..7, in their natural order for
every mode. Decrypt in CBC mode:

```
prev = message_iv[mode]
for i in 0..7:
    dm[16i .. 16i+15] = InvCipher(RK[mode], C[i]) ^ prev
    prev = C[i]
```

`InvCipher(RK, x)` is the standard AES-128 inverse cipher driven by 11 given
round keys `RK[0..10]`. It is the same `decrypt_block` as v2 (§3.2):
`x ^= RK[10]`; for r = 9 down to 1: `InvShiftRows; InvSubBytes; x ^= RK[r];
InvMixColumns`; then `InvShiftRows; InvSubBytes; x ^= RK[0]`.

The round keys are data (`decryptMessage.round_keys` in the tables file; 176
bytes per mode, round key r at bytes 16r..16r+15). `RK[0]` and `RK[10]` are the
same for all four modes. `RK[1..9]` differ per mode and are not an AES key
schedule, so a single-key platform AES cannot be used here. The IVs (hex):

- mode 0: `5752f1b7549d8f870c10485a6088cadb`
- mode 1: `df7b1563f005587752a90402b9a39295`
- mode 2: `68b54611fb04de676c968efb8c9db0c9`
- mode 3: `27078b2123361e7adc9d0b115354690d`

Processing mode 3's blocks in reverse order, as appendix A does, cannot change
the result, because each CBC output block depends only on `C[i]` and
`C[i-1]`.

Verification: this formulation matched the table-driven form of appendix A on
4000 random messages (1000 per mode). The round keys were recovered by peeling
the white-box layers of that table form. The cipher's input and output bytes are
plain, so each layer's encoding is known from the previous one, and every round
key is the unique value that makes that layer an affine-encoded AES S-box. If
you already implemented revision 1's table-driven form, it is still correct; it
is kept in appendix A.

### 4.7 `modified_md5(block[64], key[16]) -> out[16]`

This is the MD5 compression function with four modifications: (a) the initial
state A,B,C,D is `key` read as four little-endian 32-bit words (not MD5's fixed
IV); (b) the 16 message words are read **big-endian** from `block`; (c) one
message-word permutation is injected at round 31; (d) the output is the
Davies-Meyer feed-forward `key.word[i] + final_state.word[i]`. The additive
constants `K[i] = floor(2^32 * |sin(i+1)|)` (i in radians), the per-round shift
amounts, the F/G/H/I selection functions and the message-index schedule `g` are
all exactly standard MD5.

```
M[0..15] = big-endian 32-bit words of block[0..63]
(A,B,C,D) = little-endian words of key
(a0,b0,c0,d0) = (A,B,C,D)
for i in 0..63:
    if   i<16: g=i;            f=(B&C)|(~B&D);  s=[7,12,17,22][i%4]
    elif i<32: g=(5i+1) mod16; f=(B&D)|(C&~D);  s=[5,9,14,20][i%4]
    elif i<48: g=(3i+5) mod16; f=B^C^D;         s=[4,11,16,23][i%4]
    else:      g=(7i)  mod16;  f=C^(B|~D);       s=[6,10,15,21][i%4]
    Z = (A + M[g] + K[i]) mod 2^32
    Z = (rol32(Z + f, s) + B) mod 2^32     # rol32 = 32-bit left rotate
    (A,D,C,B) = (D,C,B,Z)                   # standard MD5 state rotation
    if i == 31:                             # modification (c), AFTER the rotate
        n0(x)=x&15; n1(x)=(x>>4)&15; n2(x)=(x>>8)&15; n3(x)=(x>>12)&15
        swap(M[n0(A)], M[n0(B)]); swap(M[n0(C)], M[n0(D)])
        swap(M[n1(A)], M[n1(B)]); swap(M[n2(A)], M[n2(B)]); swap(M[n3(A)], M[n3(B)])
out.word[0..3] = little-endian( (a0+A), (b0+B), (c0+C), (d0+D) ), each mod 2^32
```

### 4.8 `sap_hash(block[64]) -> out[16]`

Fixed seeds (hex, in the tables file under `sap_hash`): `buffer0` (20 bytes),
`buffer2` (35 bytes), `buffer4` (21 bytes), and `i0_index =
[18,22,23,0,5,19,32,31,10,21,30]`.

```
# 1. load block (byte-swapped per 32-bit word, block repeated with period 64):
for i in 0..209: buffer1[i] = block[ 4*((i mod 64) div 4) + (3 - (i mod 4)) ]

# 2. scramble, 840 iterations (indices are UNSIGNED 32-bit before mod 210 — §6a):
for i in 0..839:
    x = buffer1[ ((i-155) mod 2^32) mod 210 ]
    y = buffer1[ ((i-57)  mod 2^32) mod 210 ]
    z = buffer1[ ((i-13)  mod 2^32) mod 210 ]
    w = buffer1[ i mod 210 ]
    buffer1[i mod 210] = ( rol8(y,5) + (rol8(z,3) ^ w) - rol8(x,7) ) mod 256

# 3. the garble step (§4.9) rewrites buffer0,buffer1,buffer2,buffer3,buffer4:
(buffer0,buffer1,buffer2,buffer3,buffer4) = garble(buffer0,buffer1,buffer2,buffer3,buffer4)
#    buffer3 starts as 132 arbitrary bytes; the final out[16] is independent of
#    their value (verified across fill values), so initialise them to 0.

# 4. combine into out[16]:
out = E1 repeated 16 times
for i in 0..10:
    if i == 3: out[3] = 0x3d
    else:      out[i] = (out[i] + buffer3[ i0_index[i]*4 ]) mod 256
for i in 0..19:  out[i mod 16] ^= buffer0[i]
for i in 0..34:  out[i mod 16] ^= buffer2[i]
for i in 0..209: out[i mod 16] ^= buffer1[i]

# 5. in-place reverse-scramble, 16 passes (lower i already updated this pass):
for pass in 0..15:
    for i in 0..15:
        x = out[(i-7)   mod 16]; y = out[i]
        z = out[(i-37)  mod 16]; w = out[(i-177) mod 16]
        out[i] = rol8(x,1) ^ y ^ rol8(z,6) ^ rol8(w,5)
```

`rol8(v,c)` is an 8-bit left rotate: `((v<<c)&0xff) | ((v&0xff)>>(8-c))`.

### 4.9 `garble(...)` — fixed, irreducible operation sequence

`garble` has no known reduction to a standard primitive. It is a fixed
sequence of 173 byte/word operations over the five buffers; treat it as data.
It was verified on 300 random inputs, the full `sap_hash` on 300 more, and one
input step by step (the trace in `fairplay-v3-coverage.json`). **Semantics (read before transcribing):**

- `b0,b1,b2,b3,b4` are the five buffers (lengths 20,210,35,132,21). `bK[e]`
  reads a byte (0..255); assigning to `bK[e]` keeps the low 8 bits.
- Every operand and intermediate is a 32-bit unsigned value. `+ - *` wrap mod
  2^32. `÷` is **unsigned** 32-bit integer division (truncating). `>>` is
  logical (zero-fill). `~`, `<<` are 32-bit. `%` is on the 32-bit value.
  Indices like `b1[206] % 21` read a byte (non-negative) first, so those
  moduli are ordinary.
- Helper rotates (argument is a byte value `v`, `c` is a shift count):
  - `rol8(v,c)   = ((v<<c)&0xff) | ((v&0xff)>>(8-c))`             (8-bit result)
  - `rol8x(v,c)  = (v<<c) | ((v&0xff)>>(8-c))`                    (32-bit, not masked to 8)
  - `weird_ror8(v,c)  = 0 if c==0 else ((v>>c)&0xff) | ((v&0xff)<<(8-c))`   (32-bit)
  - `weird_rol8(v,c)  = 0 if c==0 else ((v<<c)&0xff) | ((v&0xff)>>(8-c))`   (8-bit result)
  - `weird_rol32(v,c) = 0 if c==0 else (v<<c) ^ (v>>(8-c))`                 (32-bit)
- Uppercase single letters `A,B,...` and `tmp,tmp2,tmp3` are 32-bit scratch
  variables, carried between steps in the order written.
- `^=`, `+=`, `-=` mean read-modify-write of the left byte (low 8 bits kept).

The 173 operations, in order (`÷` = unsigned integer division):

```
  1. b2[12] = 0x14 + (((b1[64] & 92) | ((b1[99] ÷ 3) & 35)) & b4[rol8x(b4[(b1[206] % 21)],4) % 21])
  2. b1[4] = (b1[99] ÷ 5) * (b1[99] ÷ 5) * 2
  3. b2[34] = 0xb8
  4. b1[153] ^= (b2[b1[203] % 35] * b2[b1[203] % 35] * b1[190])
  5. b0[3] -= (((b4[b1[205] % 21]>>1) & 80) | 0xe6440)
  6. b0[16] = 0x93
  7. b0[13] = 0x62
  8. b1[33] -= (b4[b1[36] % 21] & 0xf6)
  9. tmp2 = b2[b1[67] % 35]
 10. b2[12] = 0x07
 11. tmp = b0[b1[181] % 20]
 12. b1[2] -= 3136
 13. b0[19] = b4[b1[58] % 21]
 14. b3[0] = 92 - b2[b1[32] % 35]
 15. b3[4] = b2[b1[15] % 35] + 0x9e
 16. b1[34] += (b4[((b2[b1[15] % 35] + 0x9e) & 0xff) % 21] ÷ 5)
 17. b0[19] += 0xfffffee6 - ((b0[b3[4] % 20]>>1) & 102)
 18. b1[15] = (3*(((b1[72] >> (b4[b1[190] % 21] & 7)) ^ (b1[72] << ((7 - (b4[b1[190] % 21]-1)&7)))) - (3*b4[b1[126] % 21]))) ^ b1[15]
 19. b0[15] ^= b2[b1[181] % 35] * b2[b1[181] % 35] * b2[b1[181] % 35]
 20. b2[4] ^= b1[202]÷3
 21. A = 92 - b0[b3[0] % 20]
 22. E = (A & 0xc6) | (~b1[105] & 0xc6) | (A & (~b1[105]))
 23. b2[1] += (E*E*E)
 24. b0[19] ^= ((224 | (b4[b1[92] % 21] & 27)) * b2[b1[41] % 35]) ÷ 3
 25. b1[140] += weird_ror8(92, b1[5] & 7)
 26. b2[12] += ((((~b1[4]) ^ b2[b1[12] % 35]) | b1[182]) & 192) | (((~b1[4]) ^ b2[b1[12] % 35]) & b1[182])
 27. b1[36] += 125
 28. b1[124] = rol8x((((74 & b1[138]) | ((74 | b1[138]) & b0[15])) & b0[b1[43] % 20]) | (((74 & b1[138]) | ((74 | b1[138]) & b0[15]) | b0[b1[43] % 20]) & 95), 4)
 29. b3[8] = ((((b0[b3[4] % 20] & 95)) & ((b4[b1[68] % 21] & 46) << 1)) | 16) ^ 92
 30. A = b1[177] + b4[b1[79] % 21]
 31. D = (((A >> 1) | ((3 * b1[148]) ÷ 5)) & b2[1]) | ((A >> 1) & ((3 * b1[148])÷5))
 32. b3[12] =  ((-34 - D))
 33. A = 8 - ((b2[22] & 7))
 34. B = (b1[33] >> (A & 7))
 35. C = b1[33] << (b2[22] & 7)
 36. b2[16] += ((b2[b3[0] % 35] & 159) | b0[b3[4] % 20] | 8) - ((B^C) | 128)
 37. b0[14] ^= b2[b3[12] % 35]
 38. A = weird_rol8(b4[b0[b1[201] % 20] % 21], ((b2[b1[112] % 35] << 1) & 7))
 39. D = (b0[b1[208] % 20] & 131) | (b0[b1[164] % 20] & 124)
 40. b1[19] += (A & (D÷5)) | ((A | (D÷5)) & 37)
 41. b2[8] = weird_ror8(140, ((b4[b1[45] % 21] + 92) * (b4[b1[45] % 21] + 92)) & 7)
 42. b1[190] = 56
 43. b2[8] ^= b3[0]
 44. b1[53] = ~((b0[b1[83] % 20] | 204)÷5)
 45. b0[13] += b0[b1[41] % 20]
 46. b0[10] = ((b2[b3[0] % 35] & b1[2]) | ((b2[b3[0] % 35] | b1[2]) & b3[12])) ÷ 15
 47. A = (((56 | (b4[b1[2] % 21] & 68)) | b2[b3[8] % 35]) & 42) | (((b4[b1[2] % 21] & 68) | 56) & b2[b3[8] % 35])
 48. b3[16] = (A*A) + 110
 49. b3[20] = 202 - b3[16]
 50. b3[24] = b1[151]
 51. b2[13] ^= b4[b3[0] % 21]
 52. B = ((b2[b1[179] % 35] - 38) & 177) | (b3[12] & 177)
 53. C = ((b2[b1[179] % 35] - 38)) & b3[12]
 54. b3[28] = 30 + ((B | C) * (B | C))
 55. b3[32] = b3[28] + 62
 56. A = ((b3[20] + (b3[0] & 74)) | ~b4[b3[0] % 21]) & 121
 57. B = ((b3[20] + (b3[0] & 74)) & ~b4[b3[0] % 21])
 58. tmp3 = (A|B)
 59. C = ((((A|B) ^ 0xffffffa6) | b3[0]) & 4) | (((A|B) ^ 0xffffffa6) & b3[0])
 60. b1[47] = (b2[b1[89] % 35] + C) ^ b1[47]
 61. b3[36] = ((rol8((tmp & 179) + 68, 2) & b0[3]) | (tmp2 & ~b0[3])) - 15
 62. b1[123] ^= 221
 63. A = ((b4[b3[0] % 21]) ÷ 3) - b2[b3[4] % 35]
 64. C = (((b3[0] & 163) + 92) & 246) | (b3[0] & 92)
 65. E = ((C | b3[24]) & 54) | (C & b3[24])
 66. b3[40] = A - E
 67. b3[44] = tmp3 ^ 81 ^ (((b3[0] >> 1) & 101) + 26)
 68. b3[48] = b2[b3[4] % 35] & 27
 69. b3[52] = 27
 70. b3[56] = 199
 71. b3[64] = b3[4] + (((((((b3[40] | b3[24]) & 177) | (b3[40] & b3[24])) & ((((b4[b3[0] % 20] & 177) | 176)) | ((b4[b3[0] % 21]) & ~3))) | ((((b3[40] & b3[24]) | ((b3[40] | b3[24]) & 177)) & 199) | ((((b4[b3[0] % 21] & 1) + 176) | (b4[b3[0] % 21] & ~3)) & b3[56]))) & (~b3[52])) | b3[48])
 72. b2[33] ^= b1[26]
 73. b1[106] ^= b3[20] ^ 133
 74. b2[30] = ((b3[64] ÷ 3) - (275 | (b3[0] & 247))) ^ b0[b1[122] % 20]
 75. b1[22] = (b2[b1[90] % 35] & 95) | 68
 76. A = (b4[b3[36] % 21] & 184) | (b2[b3[44] % 35] & ~184)
 77. b2[18] += ((A*A*A) >> 1)
 78. b2[5] -= b4[b1[92] % 21]
 79. A = (((b1[41] & ~24)|(b2[b1[183] % 35] & 24)) & (b3[16] + 53)) | (b3[20] & b2[b3[20] % 35])
 80. B = (b1[17] & (~b3[44])) | (b0[b1[59] % 20] & b3[44])
 81. b2[18] ^= (A*B)
 82. A = weird_ror8(b1[11], b2[b1[28] % 35] & 7) & 7
 83. B = (((b0[b1[93] % 20] & ~b0[14]) | (b0[14] & 150)) & ~28) | (b1[7] & 28)
 84. b2[22] = (((((B | weird_rol8(b2[b3[0] % 35], A)) & b2[33]) | (B & weird_rol8(b2[b3[0] % 35], A))) + 74) & 0xff)
 85. A = b4[(b0[b1[39] % 20] ^ 217) % 21]
 86. b0[15] -= ((((b3[20] | b3[0]) & 214) | (b3[20] & b3[0])) & A) | ((((b3[20] | b3[0]) & 214) | (b3[20] & b3[0]) | A) & b3[32])
 87. B = (((b2[b1[57] % 35] & b0[b3[64] % 20]) | ((b0[b3[64] % 20] | b2[b1[57] % 35]) & 95) | (b3[64] & 45) | 82) & 32)
 88. C = ((b2[b1[57] % 35] & b0[b3[64] % 20]) | ((b2[b1[57] % 35] | b0[b3[64] % 20]) & 95)) & ((b3[64] & 45) | 82)
 89. D = ((((b3[0]÷3) - (b3[64]|b1[22]))) ^ (b3[28] + 62) ^ ((B|C)))
 90. T = b0[(D & 0xff) % 20]
 91. b3[68] = (b0[b1[99] % 20] * b0[b1[99] % 20] * b0[b1[99] % 20] * b0[b1[99] % 20]) | b2[b3[64] % 35]
 92. U = b0[b1[50] % 20]
 93. W = b2[b1[138] % 35]
 94. X = b4[b1[39] % 21]
 95. Y = b0[b1[4] % 20]
 96. Z = b4[b1[202] % 21]
 97. V = b0[b1[151] % 20]
 98. S = b2[b1[14] % 35]
 99. R = b0[b1[145] % 20]
100. A = (b2[b3[68] % 35] & b0[b1[209] % 20]) | ((b2[b3[68] % 35] | b0[b1[209] % 20]) & 24)
101. B = weird_rol8(b4[b1[127] % 21], b2[b3[68] % 35] & 7)
102. C = (A & b0[10]) | (B & ~b0[10])
103. D = 7 ^ (b4[b2[b3[36] % 35] % 21] << 1)
104. b3[72] = (C & 71) | (D & ~71)
105. b2[2] += (((b0[b3[20] % 20] << 1) & 159) | (b4[b1[190] % 21] & ~159)) & ((((b4[b3[64] % 21] & 110) | (b0[b1[25] % 20] & ~110)) & ~150) | (b1[25] & 150))
106. b2[14] -= ((b2[b3[20] % 35] & (b3[72] ^ b2[b1[100] % 35])) & ~34) | (b1[97] & 34)
107. b0[17] = 115
108. b1[23] ^= ((((((b4[b1[17] % 21] | b0[b3[20] % 20]) & b3[72]) | (b4[b1[17] % 21] & b0[b3[20] % 20])) & (b1[50]÷3)) | ((((b4[b1[17] % 21] | b0[b3[20] % 20]) & b3[72]) | (b4[b1[17] % 21] & b0[b3[20] % 20]) | (b1[50] ÷ 3)) & 246)) << 1)
109. b0[13] = ((((((b0[b3[40] % 20] | b1[10]) & 82) | (b0[b3[40] % 20] & b1[10])) & 209) | ((b0[b1[39] % 20] << 1) & 46)) >> 1)
110. b2[33] -= b1[113] & 9
111. b2[28] -= ((((2 | (b1[110] & 222)) >> 1) & ~223) | (b3[20] & 223))
112. J = weird_rol8((V | Z), (U & 7))
113. A = (b2[16] & T) | (W & (~b2[16]))
114. B = (b1[33] & 17) | (X & ~17)
115. E = ((Y | ((A+B) ÷ 5)) & 147) | (Y & ((A+B) ÷ 5))
116. M = (b3[40] & b4[((b3[8] + J + E) & 0xff) % 21]) | ((b3[40] | b4[((b3[8] + J + E) & 0xff) % 21]) & b2[23])
117. b0[15] = (((b4[b3[20] % 21] - 48) & (~b1[184])) | ((b4[b3[20] % 21] - 48) & 189) | (189 & ~b1[184])) & (M*M*M)
118. b2[22] += b1[183]
119. b3[76] = (3 * b4[b1[1] % 21]) ^ b3[0]
120. A = b2[((b3[8] + (J + E)) & 0xff) % 35]
121. F = (((b4[b1[178] % 21] & A) | ((b4[b1[178] % 21] | A) & 209)) * b0[b1[13] % 20]) * (b4[b1[26] % 21] >> 1)
122. G = (F + 0x733ffff9) * 198 - (((F + 0x733ffff9) * 396 + 212) & 212) + 85
123. b3[80] = b3[36] + (G ^ 148) + ((G ^ 107) << 1) - 127
124. b3[84] = ((b2[b3[64] % 35]) & 245) | (b2[b3[20] % 35] & 10)
125. A = b0[b3[68] % 20] | 81
126. b2[18] -= ((A*A*A) & ~b0[15]) | ((b3[80] ÷ 15) & b0[15])
127. b3[88] = b3[8] + J + E - b0[b1[160] % 20] + (b4[b0[((b3[8] + J + E) & 255) % 20] % 21] ÷ 3)
128. B = ((R ^ b3[72]) & ~198) | ((S * S) & 198)
129. F = (b4[b1[69] % 21] & b1[172]) | ((b4[b1[69] % 21] | b1[172] ) & ((b3[12] - B) + 77))
130. b0[16] = 147 - ((b3[72] & ((F & 251) | 1)) | (((F & 250) | b3[72]) & 198))
131. C = (b4[b1[168] % 21] & b0[b1[29] % 20] & 7) | ((b4[b1[168] % 21] | b0[b1[29] % 20]) & 6)
132. F = (b4[b1[155] % 21] & b1[105]) | ((b4[b1[155] % 21] | b1[105]) & 141)
133. b0[3] -= b4[weird_rol32(F, C) % 21]
134. b1[5] = weird_ror8(b0[12], ((b0[b1[61] % 20] ÷ 5) & 7)) ^ (((~b2[b3[84] % 35]) & 0xffffffff) ÷ 5)
135. b1[198] += b1[3]
136. A = (162 | b2[b3[64] % 35])
137. b1[164] += ((A*A)÷5)
138. G = weird_ror8(139, (b3[80] & 7))
139. C = ((b4[b3[64] % 21] * b4[b3[64] % 21] * b4[b3[64] % 21]) & 95) | (b0[b3[40] % 20] & ~95)
140. b3[92] = (G & 12) | (b0[b3[20] % 20] & 12) | (G & b0[b3[20] % 20]) | C
141. b2[12] += ((b1[103] & 32) | (b3[92] & ((b1[103] | 60))) | 16)÷3
142. b3[96] = b1[143]
143. b3[100] = 27
144. b3[104] = (((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]) ^ 119
145. b3[108] = 238 & ((((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]) << 1)
146. b3[112] = (~b3[64] & (b3[84] ÷ 3)) ^ 49
147. b3[116] = 98 & ((~b3[64] & (b3[84] ÷ 3)) << 1)
148. A = (b1[35] & b2[8]) | (b3[40] & ~b2[8])
149. B = (A & b3[64]) | (((b3[84] ÷ 3) & ~b3[64]))
150. b1[143] = b3[96] - ((B & (86 + ((b1[172] & 64) >> 1))) | (((((b1[172] & 65) >> 1) ^ 86) | ((~b3[64] & (b3[84] ÷ 3)) | (((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]))) & b3[100]))
151. b2[29] = 162
152. A = ((((b4[b3[88] % 21]) & 160) | (b0[b1[125] % 20] & 95)) >> 1)
153. B = b2[b1[149] % 35] ^ (b1[43] * b1[43])
154. b0[15] += (B&A) | ((A|B) & 115)
155. b3[120] = b3[64] - b0[b3[40] % 20]
156. b1[95] = b4[b3[20] % 21]
157. A = weird_ror8(b2[b3[80] % 35], (b2[b1[17] % 35] * b2[b1[17] % 35] * b2[b1[17] % 35]) & 7)
158. b0[7] -= (A*A)
159. b2[8] = b2[8] - b1[184] + (b4[b1[202] % 21] * b4[b1[202] % 21] * b4[b1[202] % 21])
160. b0[16] = (b2[b1[102] % 35] << 1) & 132
161. b3[124] = (b4[b3[40] % 21] >> 1) ^ b3[68]
162. b0[7] -= (b0[b1[191] % 20] - (((b4[b1[80] % 21] << 1) & ~177) | (b4[b4[b3[88] % 21] % 21] & 177)))
163. b0[6] = b0[b1[119] % 20]
164. A = (b4[b1[190] % 21] & ~209) | (b1[118] & 209)
165. B = b0[b3[120] % 20] * b0[b3[120] % 20]
166. b0[12] = (b0[b3[84] % 20] ^ (b2[b1[71] % 35] + b2[b1[15] % 35])) & ((A & B) | ((A | B) & 27))
167. B = (b1[32] & b2[b3[88] % 35]) | ((b1[32] | b2[b3[88] % 35]) & 23)
168. D = (((b4[b1[57] % 21] * 231) & 169) | (B & 86))
169. F = (((b0[b1[82] % 20] & ~29) | (b4[b3[124] % 21] & 29)) & 190) | (b4[(D÷5) % 21] & ~190)
170. H = b0[b3[40] % 20] * b0[b3[40] % 20] * b0[b3[40] % 20]
171. K = (H & b1[82]) | (H & 92) | (b1[82] & 92)
172. b3[128] = ((F & K) | ((F | K) & 192)) ^ (D÷5)
173. b2[25] ^= ((b0[b3[120] % 20] << 1) * b1[5]) - (weird_rol8(b3[76], (b4[b3[124] % 21] & 7)) & (b3[20] + 110))
```

**Op 71 and its `% 20`.** Op 71 really does read `b4[b3[0] % 20]` while every
other `b4` read uses `% 21`; keep it as written.
It cannot affect the output. The operand is `(b4[·] & 177) | 176`, so only
its bit 0 can vary. The enclosing expression is then ANDed with `~b3[52]`, and
op 68 has just set `b3[52] = 27`, which clears bit 0. So `% 20` and `% 21` give
the same result for every input, and no test vector can tell them apart.
Mutation testing found the same for six more reads: op 47's `b4[b1[2] % 21]`,
two more of op 71's `b4[b3[0] % 21]` reads, op 87's and op 88's `b2[b1[57] % 35]`,
and op 100's `b2[b3[68] % 35]`.

**Which details matter (mutation testing).** Each of the following single-point
mistakes was applied at every place it could occur, giving 559 variants:

- the wrong modulus among 20, 21 and 35
- signed instead of unsigned `÷`
- an arithmetic instead of a logical `>>`
- an 8-bit instead of a 32-bit `~`
- 8-bit scratch variables
- plain rotates in place of the `weird_*` helpers
- a masked `rol8x`

390 of them change `sap_hash` output, and every one is caught by the vectors
through `output_key` alone (§5). The other 169 never changed output in 86,000
random inputs and are treated as harmless. The details that do matter:

- the modulus of every buffer read, apart from the seven listed above;
- unsigned `÷` at op 134 (its dividend is `~b2[…]` as a 32-bit value);
- a scratch variable wider than 8 bits at op 30 (`A` reaches 510 there and op 31
  uses `A >> 1`);
- the zero-count rule (`c == 0` gives 0) of `weird_ror8` at ops 25, 41, 82,
  134, 138 and 157, and of `weird_rol8` at ops 38, 84, 101, 112 and 173;
- the unmasked width of `rol8x` at op 1, and of `weird_rol32` at op 133 (its
  result feeds a `% 21` index).

Implementing everything exactly as written above is always correct.


## 5. Test vectors

- `vectors/fairplay-v2.json` — v2 constants plus 8 cases (2 per mode): the four
  `/fp-setup` messages, the 72-byte blob, the 16-byte key, and intermediates
  (receiver/sender SAP, `mac_key`, `wrapping_key`, confirmation, the AES-ECB
  result, the recovered key).
- `vectors/fairplay-v3.json` — the four static setup replies, the handshake
  header, and 8 cases (2 per mode) with per-stage intermediates (decrypted
  client message, the five session-key rounds with `modified_md5`/`sap_hash`
  outputs, the session key, the AES-128 key and its round keys, the block
  decrypt, the output key).
- `vectors/fairplay-v3-coverage.json` (new in revision 2) — 64 more v3 cases,
  16 per mode, with per-round `modified_md5`/`sap_hash` outputs. In each mode,
  cases 00–07 each embed one of 8 coverage blocks as the round-1 `sap_hash`
  input, and cases 08–15 are random. The 8 blocks were chosen by set cover over
  the mutation results in §4.9. Revision 1's 8 cases caught only 385 of the 390
  output-changing variants, missing ops 47, 79, 82 and 87. With this file all
  390 are caught by `output_key` alone. The file also has 16 `sap_hash` unit
  cases (block, buffer after load and scramble, all five buffers after
  `garble`, output) and `garble_trace`: the value written by each of the 173
  operations for one input. Use the trace to find the first wrong step.
- `vectors/fairplay-sessions.json` (new in revision 2) — 18 scenarios, 80
  steps, of setup/handshake/decrypt sequences with expected success/failure and
  exact outputs. They cover every rule in §1.6. v2 setup steps carry
  `inject_receiver_sap`, the SAP your implementation must use for that setup.
- `vectors/fairplay-v3-tables.json` — v3 constants. In revision 2,
  `decryptMessage.round_keys` (new) and `decryptMessage.message_iv` are the
  normative data for §4.6. Revision 1's `table_s2`, `table_s9`, `table_s10` and
  `message_key` stay at their old keys, marked superseded (appendix A), so
  existing loaders keep working.

These inputs are **structurally valid but synthetic**: no real Apple-client
captures were available, so payloads are random with correct headers/lengths.
They have not been checked against live Apple clients. The v2 receiver SAP is random per session in production; the vectors
inject a fixed SAP so they are reproducible, and build the client handshake and
blob with the sender-side procedure below.

**Sender-side construction (how the v2 vectors were built; needed only to make
your own test inputs, never at runtime):**

- handshake request = `46 50 4c 59 02 01 03 00 00 00 00 98 || mode || 8f 1a 9c
  || sap_encrypt(mode, sender_sap) || HMAC-SHA1(mac_key, 02 02)`.
- 72-byte blob: pick a 16-byte `media_key` and a 16-byte `blind`.
  `wrapped = AES-128-ECB-encrypt(wrapping_key, media_key ^ blind)`;
  `tag = HMAC-SHA1(mac_key, header36 || media_key)` where `header36` is
  `46 50 4c 59 01 02 01 00 || be32(60) || be32(0) || blind || be32(16)`;
  blob = `header36 || tag || wrapped`.

An implementation built from this document (standard AES, SHA-1/MD5/HMAC, the
§4.9 operation list, and the tables file) reproduces all the cases.

The coverage cases needed handshakes whose decrypted message holds a chosen
block. Because §4.6 is a plain CBC decryption, a test can build one: encrypt
the chosen 128-byte `dm` with the forward AES-128 cipher under the same round
keys (`state ^= RK[0]`; rounds 1–9 `SubBytes; ShiftRows; MixColumns;
^= RK[r]`; then `SubBytes; ShiftRows; ^= RK[10]`), in CBC with
`message_iv[mode]`. Put the result at bytes 16..143. This is test tooling
only.


## 6. Implementation notes and pitfalls

(a) **Unsigned modulo in `sap_hash` §4.8 step 2.** `(i-155)` etc. are negative
for small `i`. They are taken modulo 2^32 (unsigned) *before* `% 210`,
and `2^32 mod 210 = 46 ≠ 0`, so a signed `%` gives wrong indices. Compute
`((i-155) & 0xffffffff) % 210`. (The §4.8 step-5 moduli are `% 16` and
`2^32 mod 16 = 0`, so there it does not matter — but do it the same way.)

(b) **8-bit vs wider results in §4.9.** `rol8x`, `weird_ror8` and
`weird_rol32` return values wider than 8 bits. A buffer store truncates them;
a `%` index or further arithmetic uses the full 32-bit value. Keep them as
32-bit ints and mask to 8 bits only on store. Ops 1 and 133 are where this
changes the output.

(c) **Unsigned division in §4.9.** Make `÷` unsigned. In Kotlin use `UInt`, or
widen to `Long` with `and 0xffffffffL`, then divide. Only op 134 gives a
different result with a signed `/`, but use unsigned everywhere.

(d) **Signed bytes (Kotlin).** `Byte` is signed. Read every buffer/message byte
as `b.toInt() and 0xFF` before use, and store `(value and 0xFF).toByte()`. This
applies throughout, especially the §4.9 list and all table lookups.

(e) **Endianness is mixed on purpose.** `modified_md5` loads message words
**big-endian** but outputs and feeds-forward **little-endian**. `sap_hash`'s
word reinterpretations and the session-key word adds are little-endian. The `/fp-setup` length fields are
big-endian. Get each one right independently.

(f) **The `^ 0x79` and `sapKey`.** §4.5 ends by XORing every `sapKey` byte with
`0x79`; §4.3 then XORs again with `0x79…79` to form `aes_key`. These do not
cancel in code because other steps sit between them; follow the
two formulas as written (equivalently, `aes_key` is the pre-`^0x79` session key,
but compute it as given to match the vectors).

(g) **Constant-time compares, and what a failure resets.** Compare the v2
confirmation and HMAC tags (§3.4, §3.5) in constant time. Revision 1 said to
reset all FairPlay state on any failure. That is wrong. A v2 decrypt failure
changes no state. A v2 handshake failure keeps the setup but drops any
handshake. Setup and v3 handshake rejections clear everything. §1.6 has the
full rules, and `fairplay-sessions.json` tests them.

(h) **Explicit round keys are not a schedule.** v2's SAP cipher (§3.2) and v3's
`decrypt_message` (§4.6) both run the AES-128 round function with 11 given
round keys that no single key expands to. Hand-roll that round function once
and use it for both. The single-key AES-128 steps, where a platform AES works,
are the §3.5 ECB unwrap and the §4.3 block decrypt.

(i) **`sap_hash` `buffer3`.** Its initial content does not matter: the output is
provably independent of it (verified across fill bytes). Initialise to zero.


## 7. Data and verification

Constants, tables, wire strings and vectors are Apple-derived data and are
required for interoperability.

- **Reductions verified on this machine:**
  - the v3 white-box table cipher `T` (§4.4) plus its key schedule ≡
    `AES128_Decrypt(sapKey^0x79…, x^z_key)^delta0` (3000 random pairs).
  - v3 `decrypt_message` ≡ the AES-128 inverse cipher with the §4.6 round keys
    in CBC (4000 random messages, all modes). The round keys were recovered by
    peeling the white-box layers.
  - v2 SAP block ≡ the AES-128 round function with the given independent round
    keys (all modes).
  - `modified_md5` ≡ the MD5 variant in §4.7 (500 inputs).
  - `sap_hash`, `garble`, `generate_session_key` and the full key recovery were
    each checked on hundreds of random inputs.
  - Mutation testing of `garble`: 559 variants, run as in §4.9.

### What did not reduce / open points

- `sap_hash`/`garble` has no standard-primitive form; it is given as the fixed
  173-operation sequence in §4.9 (irreducible).
- No live Apple-client vectors were available; §5 vectors are synthetic and
  have not been checked against live Apple clients.


## Appendix A. Table-driven `decrypt_message` (revision 1, superseded)

This form gives exactly the same output as §4.6 (checked on 4000 messages) and
is kept only for implementations written against revision 1. Its tables are
`decryptMessage.table_s2` (144 S-boxes),
`table_s9` (1024 words), `table_s10` (16 S-boxes) and `message_key` (4 × 144
bytes). `message_iv` is the same as in §4.6.

Let `ISR = [0,13,10,7, 4,1,14,11, 8,5,2,15, 12,9,6,3]` (output position `p`
reads input position `ISR[p]`), `s2box(k) = table_s2[((97*k) mod 144)*256 :
+256]` and `s10box(p) = table_s10[p*256 : +256]`.

```
for block i in 0..7:
    if mode == 3: buf = message3[0x80-0x10*i .. +15]      # reverse order
    else:         buf = message3[0x10*(i+1) .. +15]       # forward
    for j in 0..8:
        base = 0x80 - 0x10*j
        for p in 0..15: nb[p] = s2box(base+p)[ buf[ISR[p]] ] ^ message_key[mode][base+p]
        buf = nb
        for col c in 0..3:
            w = table_s9[ buf[4c] ] ^ table_s9[256 + buf[4c+1]]
              ^ table_s9[512 + buf[4c+2]] ^ table_s9[768 + buf[4c+3]]
            buf[4c .. 4c+3] = le32(w)
    for p in 0..15: nb[p] = s10box(p)[ buf[ISR[p]] ]
    buf = nb
    if mode == 3:
        prev = (i < 7) ? message3[0x70-0x10*i .. +15] : message_iv[mode]
        dm[0x70-0x10*i .. +15] = buf ^ prev
    else:
        prev = (i > 0) ? message3[0x10*i .. +15] : message_iv[mode]
        dm[0x10*i .. +15] = buf ^ prev
```

**What `table_s9` is.** Revision 1 called `table_s9` the standard InvMixColumns
T-boxes and said it could be computed. Both claims were wrong; the implementer
was right that `table_s9[0] = 0x7cba6f01`. In the JSON, word `k` is
`le32(blob[4k:4k+4])`. The exact structure, verified on all 1024 entries:

```
table_s9[256*t + x] = rotl32( L(x), 8*t ) ^ c[t]          t = 0..3, x = 0..255
L(x) = XOR of basis[i] over the set bits i of x
basis = 50bcd835 5eb4a941 fe7e0286 d812c604 54ee953f 1d85b109 7411fdd8 90e7ea1d
c     = 7cba6f01 cdbd827d d78d63dd 052ae490      (XOR of all four = 63a06a31)
```

So each table is affine. Tables 1–3 have the linear part of table 0 rotated
left by 8, 16 and 24 bits, but their own constants. Inside the white box, this
is InvMixColumns sandwiched between per-byte affine encodings of the
intermediate state; that is why it is not a plain T-box. You only need this
paragraph if you build `table_s9` instead of loading it. Implementations
following §4.6 need none of it.

