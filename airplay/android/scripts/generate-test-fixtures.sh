#!/bin/sh
# Regenerates the media fixtures of :airplay:android.
#
# Video (Annex-B H.264 elementary streams, one access unit delimiter per
# frame so tests and the debug hooks can split access units without a
# container parser):
#   src/debug/assets/airplay/test-clip-720p.h264   1280x720, 30 fps, 2 s, baseline,
#                                                  used by the debug mirroring hook
#                                                  and the instrumented video test
#   src/androidTest/assets/clip-360p.h264          640x360, 30 fps, 1 s, main profile,
#                                                  used to test a mid-stream resolution change
#
# Audio ("RZAU" files: the codec config plus raw access units, the way the
# receiver hands them to an AudioSink; see AudioFixture.kt):
#   src/androidTest/assets/aac-lc.rzau    AAC-LC 44.1 kHz stereo, ffmpeg's aac encoder
#   src/androidTest/assets/aac-eld.rzau   AAC-ELD 44.1 kHz stereo, 512-sample frames,
#                                         macOS AudioToolbox (aac_at, profile 38): ffmpeg's
#                                         own encoder cannot produce ELD
#
# The committed fixtures were made with ffmpeg 9.0.2 on macOS. ffmpeg is used
# only as an encoder; nothing here ships.
#
# Usage: airplay/android/scripts/generate-test-fixtures.sh
set -eu

cd "$(dirname "$0")/.."
debug=src/debug/assets/airplay
test=src/androidTest/assets
mkdir -p "$debug" "$test"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

ff() {
    ffmpeg -hide_banner -loglevel error -y "$@"
}

# h264 <out> <size> <seconds> <profile> <bitrate>
h264() {
    ff -f lavfi -i "testsrc2=size=$2:rate=30" -t "$3" -pix_fmt yuv420p \
        -c:v libx264 -profile:v "$4" -preset veryfast -tune zerolatency \
        -b:v "$5" -maxrate "$5" -bufsize "$5" \
        -x264-params "aud=1:keyint=30:min-keyint=30:scenecut=0" \
        -bsf:v h264_mp4toannexb -f h264 "$1"
}

h264 "$debug/test-clip-720p.h264" 1280x720 2 baseline 900k
h264 "$test/clip-360p.h264" 640x360 1 main 300k

# rzau <in.m4a> <out.rzau>: AudioSpecificConfig and packets from an MP4, via ffprobe.
rzau() {
    ffprobe -v error -select_streams a:0 -show_streams -show_data "$1" > "$tmp/stream.txt"
    ffprobe -v error -select_streams a:0 -show_entries packet=pos,size -of json "$1" > "$tmp/packets.json"
    python3 -I - "$1" "$tmp/stream.txt" "$tmp/packets.json" "$2" <<'PY'
import json, re, struct, sys
media, stream, packets, out = sys.argv[1:5]
text = open(stream).read()
rate = int(re.search(r"^sample_rate=(\d+)", text, re.M).group(1))
channels = int(re.search(r"^channels=(\d+)", text, re.M).group(1))
block = text.split("extradata=", 1)[1].split("extradata_size=", 1)[0]
config = bytes.fromhex("".join(m.group(1).replace(" ", "") for m in re.finditer(r"^[0-9a-f]{8}: ((?:[0-9a-f]{2,4} ?)+)", block, re.M)))
data = open(media, "rb").read()
units = []
for packet in json.load(open(packets))["packets"]:
    pos, size = int(packet["pos"]), int(packet["size"])
    units.append(data[pos:pos + size])
with open(out, "wb") as f:
    f.write(b"RZAU" + struct.pack(">BIBH", 1, rate, channels, len(config)) + config)
    f.write(struct.pack(">I", len(units)))
    for unit in units:
        f.write(struct.pack(">I", len(unit)) + unit)
PY
}

sine="sine=frequency=440:sample_rate=44100:duration=2"
ff -f lavfi -i "$sine" -ac 2 -c:a aac -b:a 128k "$tmp/lc.m4a"
rzau "$tmp/lc.m4a" "$test/aac-lc.rzau"
ff -f lavfi -i "$sine" -ac 2 -c:a aac_at -profile:a 38 -b:a 64k "$tmp/eld.m4a"
rzau "$tmp/eld.m4a" "$test/aac-eld.rzau"

ls -l "$debug" "$test"
