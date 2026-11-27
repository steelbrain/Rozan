# 03d — Media test vectors

Part of the 03 media spec. Every value below was computed for this document
by a small Python script. SHA-512 comes from the standard-library `hashlib`.
AES comes from single ECB block calls to the system `openssl` command, because
the Python standard library has no AES. The script first checks the block
function against the FIPS-197 appendix C.1 known answer
(`000102…0f` / `00112233…ff` → `69c4e0d86a7b0430d8cdb78070b4c55a`). It
builds CTR from those block calls, then checks the result against
`openssl enc -aes-128-ctr`. CBC is checked against
`openssl enc -aes-128-cbc -nopad`. The inputs are synthetic and do not come
from any real session.

Use these as unit tests: §A for main-file §2.1/§2.3, §B for §2.3 and 03b §3.4,
§C for main-file §2.2, §D for 03b §3.2–§3.3, §E–§F for 03c §4.6–§4.7, §G for
03c §4.5, §H for 03c §4.8, and §I for main-file §6.1.

## A. Key derivation

Inputs:

```
fairplay_key (16) = 00112233445566778899aabbccddeeff
ecdh_secret  (32) = 404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f
eiv          (16) = f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff
```

Session key (main §2.1):

```
SHA-512(fairplay_key ‖ ecdh_secret) =
  43cc4f5b5ca43586ad422154f65b0e10692037f111c48a42d859c547f85f6fe6
  037686c05f69ccb14b0b62e6715edd7c9329c1f5a83ee217f85b66f7948b7a7d
session_key = 43cc4f5b5ca43586ad422154f65b0e10
(no pairing: session_key = fairplay_key = 00112233445566778899aabbccddeeff)
```

Video key/IV, case 1: streamConnectionID with the top bit set (main §2.3):

```
streamConnectionID      = 0xC0FFEE1234567890
unsigned decimal        = 13907095936298285200        <- use this
signed decimal          = -4539648137411266416        <- WRONG (Long.toString)
key hash input (ASCII)  = "AirPlayStreamKey13907095936298285200" ‖ session_key
iv  hash input (ASCII)  = "AirPlayStreamIV13907095936298285200"  ‖ session_key
video_key = 8bb49b485fce49813f8f44aea781cc0c
video_iv  = 29a8a98d0a98fff567f7493e8a363eab
(no pairing, session_key = fairplay_key)
video_key = 25196fd897a9542649376f356c82f61f
video_iv  = c0a42d423b234d9d6004147074258124
```

Video key/IV, case 2: a streamConnectionID from a published capture (below
2^63):

```
streamConnectionID      = 4964383553955644435 (0x44E5088A36514413)
video_key = b2f2826031075f94c19a4d3c19809eba
video_iv  = a52fdee21b82b9c2b37c04d6d7a9dc33
(no pairing)
video_key = 011f9c98cdcdeb1eab09aac65852c181
video_iv  = 35bba2700b7d27bf711eb861870bc464
```

## B. Mirroring AES-128-CTR, keystream continuous across packets

Key/IV from §A case 1. Two consecutive type-0 payloads, 21 and 40 bytes. The
second packet starts at keystream byte 21, in the middle of block 1.

```
key = 8bb49b485fce49813f8f44aea781cc0c
iv  = 29a8a98d0a98fff567f7493e8a363eab
keystream block 0 = AES_K(iv)     = 36f4bf04052ee1795ff9097b21e679fd
keystream block 1 = AES_K(iv + 1) = fff38ec7728a246ab1d0114c4efff5eb
keystream block 2 = AES_K(iv + 2) = 2491ec82823409729c0c97171719f41b

packet 1 plaintext  (21) = 00 00 00 11 65 88 84 00 21 01 02 03 04 05 06 07 08 09 0a 0b 0c
packet 1 ciphertext (21) = 36 f4 bf 15 60 a6 65 79 7e f8 0b 78 25 e3 7f fa f7 fa 84 cc 7e

packet 2 plaintext  (40) = 00 00 00 24 41 9a 22 44 80 81 82 83 84 85 86 87
                           88 89 8a 8b 8c 8d 8e 8f 90 91 92 93 94 95 96 97
                           98 99 9a 9b 9c 9d 9e 9f
packet 2 ciphertext (40) = 8a 24 6a 95 91 8b 6e 0a 7f 74 69 a7 15 69 04 05
                           bc 80 f8 17 80 1a 99 98 89 65 89 ca 48 59 7c df
                           55 2e af 03 92 78 e7 c0
```

Test: decrypt ciphertext 1 and then ciphertext 2 with **one** CTR state, and
both plaintexts must come back. After AVCC→Annex-B rewriting (03b §3.4), packet
1 becomes `00 00 00 01 65 88 84 00 21 …` (one 17-byte IDR NAL) and packet 2
becomes `00 00 00 01 41 9a 22 44 …` (one 36-byte non-IDR NAL). If packet 2 is
decrypted with a keystream restarted at block 0, the first bytes come out as
`bc d0 d5 91 94 a5 8f 73`: the NAL length walk then fails, which is the
symptom of a broken implementation.

Counter carry: with IV `000102030405060708090a0bffffffff`, the second
keystream block is `AES_K(000102030405060708090a0c00000000)`. The carry
propagates beyond the low 32 bits, because the whole 128-bit block is the
counter. The script verified this against openssl.

## C. Audio AES-128-CBC per packet

Key = `session_key` from §A, IV = `eiv` from §A. Each packet starts again
from `eiv`.

```
key = 43cc4f5b5ca43586ad422154f65b0e10
iv  = f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff

packet A payload plaintext (37 bytes: 2 full blocks + 5-byte tail)
  20 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f 10 11 12 13 14 15 16 17
  18 19 1a 1b 1c 1d 1e 1f 20 21 22 23 24
packet A payload on the wire
  54 d8 c4 b5 8c 36 4a 8b 8f 68 35 0b 94 6b 66 16 3c 7b 7c c5 c4 af 09 3b
  ca e3 a7 a6 25 49 96 03 20 21 22 23 24
                          ^^^^^^^^^^^^^^ tail sent in clear, unchanged

packet B payload plaintext (16 bytes, exactly one block)
  8d 10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e
packet B payload on the wire (IV reset to eiv, not chained from packet A)
  af 95 40 95 a8 3a 9c d1 26 f2 51 da f3 12 db 18

AAC-ELD no-data marker payload: 00 68 34 00 on the wire = 00 68 34 00 (no full block)
```

The 12-byte RTP header in front of each payload is never encrypted.

## D. Mirroring header and avcC

The packet is the public iPad codec-config example from the openairplay
airplay-spec (`src/screen_mirroring/stream_packets.md`). Header (128 bytes;
bytes 64..127 are zero):

```
0000  1f 00 00 00 01 00 06 00 1d 9a 9f 59 ef de 00 00
0010  00 00 58 44 00 00 22 44 00 00 00 00 00 00 00 00
0020  00 00 00 00 00 00 00 00 00 00 58 44 00 00 22 44
0030  00 00 50 43 00 00 10 42 00 c0 57 44 00 c0 21 44
0040  00 … 00
```

Parsed:

```
payload_size (u32le @0)  = 31
payload_type (@4)        = 1 (codec config); byte 5 = 0; option bytes (@6,7) = 06 00 (old protocol)
timestamp (u64le @8)     = 0x0000deef599f9a1d
  seconds 57071, fraction 0x599f9a1d -> 57,071,350,091,583 ns (client uptime ≈ 15.9 h)
  in the client NTP domain: + 2,208,988,800 s -> seconds field 2209045871
f32le @16 = 864.0   @20 = 648.0   @40 = 864.0   @44 = 648.0
f32le @48 = 208.0   @52 = 36.0    @56 = 863.0   @60 = 647.0
```

Payload (avcC, 31 bytes):

```
01 64 c0 28 ff e1 00 10 67 64 c0 28 ac 56 20 0d 81 4f e5 9b 81 01 01 01 01 00 04 28 ee 3c b0
version 1, profile 100 (High), compatibility 0xc0, level 40, NAL length size 4,
numSPS 1, SPS length 16, numPPS 1, PPS length 4

csd-0 = 00 00 00 01 67 64 c0 28 ac 56 20 0d 81 4f e5 9b 81 01 01 01
csd-1 = 00 00 00 01 28 ee 3c b0
```

## E. Timing exchange

The receiver's local clock is `System.nanoTime()`-like, at 5000.000000000 s. The
client's NTP-domain clock reads 2208988800 + 57071.4 s.

```
request (32) = 80 d2 00 07 00 00 00 00 00 00 00 00 00 00 00 00
               00 00 00 00 00 00 00 00 00 00 13 88 00 00 00 00
               (transmit T1 @24 = 0x00001388_00000000 = 5000.0 s)
reply   (32) = 80 d3 00 07 00 00 00 00 00 00 13 88 00 00 00 00
               83 ab 5d 6f 66 66 66 66 83 ab 5d 6f 66 70 3a fb
               (origin @8 = T1, T2 @16, T3 @24)

T1 = 5,000,000,000,000 ns
T2 = 2,209,045,871,399,999,999 ns       (client NTP domain; the conversion truncates the fraction)
T3 = 2,209,045,871,400,149,999 ns       (150 µs client turnaround)
T4 = 5,000,004,149,999 ns               (4.15 ms after T1)
delay  δ = (T4 − T1) − (T3 − T2)       = 3,999,999 ns
offset θ = ((T2 − T1) + (T3 − T4)) / 2 = 2,209,040,871,397,999,999 ns   (client_ntp − local)
```

## F. Sync packet and play time

```
sync (20) = 90 d4 00 04 12 34 00 00 83 ab 5d 70 66 66 66 66 12 35 2d 77
  byte0 0x90 = first sync after start/flush; flags 0x0004
  rtp_play = 0x12340000 = 305,397,760
  ntp_time = 0x83ab5d70_66666666  -> 2,209,045,872,399,999,999 ns (client NTP domain)
  rtp_now  = 0x12352d77 = 305,474,935   -> latency = 77,175 frames (1.75 s)

frame R = rtp_play + 66,150 (1.5 s later) = 305,463,910
client_play_ns(R) = 2,209,045,872,399,999,999 + 66,150 × 1e9 / 44,100 = 2,209,045,873,899,999,999
local_play_ns(R)  = client_play_ns(R) − θ (from §E) = 5,002,502,000,000   (before extra_ns)

video timestamp from §D in the client NTP domain = 2,209,045,871,350,091,583 ns
```

## G. Resend request

```
missing sequence numbers 258, 259, 260; receiver request counter 1
80 d5 00 01 01 02 00 03
```

## H. Codec configuration bytes

ALAC `ALACSpecificConfig` (24 bytes, big-endian: frameLength 352,
compatibleVersion 0, bitDepth 16, pb 40, mb 10, kb 14, channels 2, maxRun 255,
maxFrameBytes 0, avgBitRate 0, sampleRate 44100):

```
00 00 01 60 00 10 28 0a 0e 02 00 ff 00 00 00 00 00 00 00 00 00 00 ac 44
```

The same values in 36-byte `'alac'` atom form (size 0x24, `"alac"`, version/flags 0):

```
00 00 00 24 61 6c 61 63 00 00 00 00 00 00 01 60 00 10 28 0a 0e 02 00 ff
00 00 00 00 00 00 00 00 00 00 ac 44
```

AAC-ELD AudioSpecificConfig `f8 e8 50 00` = bits
`11111 000111 0100 0010 1 000 0 0000 0000`. That is AOT escape 31, extension 7
(AOT 39), sampling index 4 (44,100 Hz), channels 2, frameLengthFlag 1 (480
samples), resilience flags 000, ldSbr 0, eldExtType 0 (TERM), padding.

## I. Volume mapping (flat)

| `volume:` (dB) | linear gain for `AudioTrack.setVolume` |
|---|---|
| 0.00 | 1.000000 |
| −7.50 | 0.421697 |
| −15.00 | 0.177828 |
| −22.50 | 0.074989 |
| −29.90 | 0.031989 |
| −30.00 | 0 (mute) |
| −144.00 | 0 (mute) |
