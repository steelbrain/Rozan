#!/usr/bin/env python3
"""Packs docs/protocol/vectors/fairplay-v3-tables.json into the binary resource read by V3Tables.kt.

Usage (from the repository root):

    python3 airplay/fairplay/tools/gen_v3_tables.py docs/protocol/vectors/fairplay-v3-tables.json \
        airplay/fairplay/src/main/resources/ai/aneesiqbal/rozan/airplay/fairplay/fairplay-v3-tables.bin

The output is the fields below concatenated in this order with no header or
padding; V3Tables.kt reads the same layout and checks the file's SHA-256, which
this script prints (paste it into V3Tables.SHA256 when the data changes).
"""

import hashlib
import json
import sys

# (path in the JSON, key holding the hex, expected byte length). Revision 1's
# superseded decrypt_message tables (table_s2, table_s9, table_s10, message_key)
# are left out: spec revision 2 section 4.6 needs only round_keys and message_iv.
LAYOUT = [
    (("decryptMessage", "round_keys"), "bytes", 4 * 176),
    (("decryptMessage", "message_iv"), "bytes", 4 * 16),
    (("sap_hash", "buffer0_seed"), "bytes", 20),
    (("sap_hash", "buffer2_seed"), "bytes", 35),
    (("sap_hash", "buffer4_seed"), "bytes", 21),
    (("sap_hash", "i0_index"), "values", 11),
    (("session_key", "static_source_1"), "bytes", 17),
    (("session_key", "static_source_2"), "bytes", 47),
    (("session_key", "initial_session_key"), "bytes", 16),
    (("session_key", "default_sap_tail"), "bytes", 128),
    (("final_aes", "z_key"), "bytes", 16),
    (("final_aes", "x_key"), "bytes", 16),
    (("final_aes", "delta0"), "bytes", 16),
    (("final_aes", "key_xor"), "bytes", 16),
]


def field_bytes(entry, key):
    value = entry[key]
    if key == "values":
        if any(not 0 <= v <= 255 for v in value):
            raise SystemExit("index out of byte range")
        return bytes(value)
    if isinstance(value, list):
        return b"".join(bytes.fromhex(v) for v in value)
    return bytes.fromhex(value)


def main():
    source, target = sys.argv[1], sys.argv[2]
    with open(source, encoding="utf-8") as f:
        tables = json.load(f)
    out = bytearray()
    for (section, name), key, size in LAYOUT:
        data = field_bytes(tables[section][name], key)
        if len(data) != size:
            raise SystemExit("%s.%s: expected %d bytes, got %d" % (section, name, size, len(data)))
        out += data
    with open(target, "wb") as f:
        f.write(out)
    print("%d bytes, SHA-256 %s" % (len(out), hashlib.sha256(out).hexdigest()))


if __name__ == "__main__":
    main()
