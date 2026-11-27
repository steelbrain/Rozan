# :airplay:fairplay

Receiver-side FairPlay v2 (RAOP audio, `fpaeskey`) and v3 (screen mirroring,
`ekey`) for the AirPlay receiver. The public API is `FairPlaySession` (setup,
handshake, key decrypt) and `FairPlayException`. Session behaviour on failed,
repeated and out-of-order messages follows section 1.6 of the spec.

## Specification

This module implements `docs/protocol/04-fairplay.md` (revision 2) with its
data files in `docs/protocol/vectors/`. The constants and tables (v2 round
keys and masks, v3 setup replies, v3 round keys, IVs, seeds and keys) are
interoperability data that the specification describes and provides.

The heavy steps are standard primitives: AES-128 (platform `javax.crypto` for
single-key AES, a hand-written FIPS-197 round function where FairPlay supplies
its own round keys, in v2's SAP cipher and v3's `decrypt_message`), SHA-1, MD5
and HMAC-SHA1 from the JDK.

## Generated files

Two files are generated from the specification and its data; regenerate them
rather than editing them by hand (run from the repository root):

```sh
# Garble.kt from the operation list in section 4.9 of the spec.
python3 airplay/fairplay/tools/gen_garble.py docs/protocol/04-fairplay.md \
    > airplay/fairplay/src/main/kotlin/ai/aneesiqbal/rozan/airplay/fairplay/Garble.kt

# The v3 data resource from the tables JSON. Prints the SHA-256 to paste into V3Tables.SHA256.
python3 airplay/fairplay/tools/gen_v3_tables.py docs/protocol/vectors/fairplay-v3-tables.json \
    airplay/fairplay/src/main/resources/ai/aneesiqbal/rozan/airplay/fairplay/fairplay-v3-tables.bin
```

`gen_garble.py` parses the 173 statements of section 4.9 straight from the spec
text (`garble_ops.py`) and checks that every buffer index is provably in bounds.
The v3 data resource is 1,127 bytes; revision 1's table-driven
`decrypt_message` tables are not shipped. The resource is loaded once, on the
first v3 key decrypt, and rejected unless its SHA-256 matches.

## Tests

`src/test/resources/vectors/` holds copies of the spec's vector files. A test
checks that they still equal `docs/protocol/vectors/`. Every vector runs
through the public API, and stage by stage where the files give intermediates:

- `fairplay-v2.json` and `fairplay-v3.json`: 16 cases.
- `fairplay-v3-coverage.json`: 64 cases, 16 `sap_hash` unit cases and the
  `garble` step trace.
- `fairplay-sessions.json`: 18 scenarios.

The vectors are synthetic (see section 5 of the spec): they check that this
module matches the reference behaviour, not that it works with a live Apple
client.
