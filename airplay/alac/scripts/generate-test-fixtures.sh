#!/bin/sh
# Regenerates the ALAC decoder test fixtures in src/test/resources/fixtures.
#
# Each fixture is a raw PCM file (<name>.raw, s16le or s24le, 44.1 kHz,
# interleaved) and the same samples encoded as ALAC in a CAF container
# (<name>.caf). The decoder tests compare their output with the raw PCM
# byte for byte. ffmpeg is used only as an encoder here.
#
# ffmpeg's ALAC encoder always uses 4096-frame packets (it ignores
# -frame_size), so the AirPlay-shaped fixture is made by encoding each
# 352-frame slice on its own: every airplay352-NN.caf holds one 352-frame
# packet.
#
# The committed fixtures were made with ffmpeg 9.0.2. Another version may
# encode differently; the tests still compare with the raw PCM, and
# AlacDecoderFixtureTest fails if the files stop covering uncompressed,
# shifted, mixed or partial elements.
#
# Usage: airplay/alac/scripts/generate-test-fixtures.sh
set -eu

cd "$(dirname "$0")/.."
out=src/test/resources/fixtures
mkdir -p "$out"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

ff() {
    ffmpeg -hide_banner -loglevel error -y "$@"
}

# pcm <name> <s16le|s24le> <filtergraph>: render a lavfi graph to raw PCM.
pcm() {
    codec=pcm_$2
    ff -filter_complex "$3" -c:a "$codec" -f "$2" "$out/$1.raw"
}

# alac <raw name> <s16le|s24le> <mono|stereo> <caf name> [encoder options...]
alac() {
    raw=$1 fmt=$2 layout=$3 caf=$4
    shift 4
    ff -f "$fmt" -ar 44100 -ch_layout "$layout" -i "$out/$raw.raw" \
        -c:a alac "$@" -fflags +bitexact -flags:a +bitexact -f caf "$out/$caf.caf"
}

rate=44100

# 16-bit stereo sine sweeps, fixed prediction order 8.
pcm sweep-stereo16 s16le \
    "aevalsrc='0.8*sin(2*PI*(40+6000*t)*t)|0.6*sin(2*PI*(9000-8000*t)*t+0.5)':s=$rate:d=0.2"
alac sweep-stereo16 s16le stereo sweep-stereo16 -min_prediction_order 8 -max_prediction_order 8

# 16-bit mono sine sweep, default encoder settings.
pcm sweep-mono16 s16le "aevalsrc='0.7*sin(2*PI*(100+12000*t)*t)':s=$rate:d=0.1"
alac sweep-mono16 s16le mono sweep-mono16

# 16-bit stereo white noise (independent channels): compressed and verbatim
# (compression level 0 emits escape/uncompressed frames).
pcm noise-stereo16 s16le \
    "anoisesrc=d=0.12:c=white:r=$rate:a=0.3:seed=11[l];anoisesrc=d=0.12:c=white:r=$rate:a=0.3:seed=22[r];[l][r]amerge=inputs=2"
alac noise-stereo16 s16le stereo noise-stereo16
alac noise-stereo16 s16le stereo noise-stereo16-verbatim -compression_level 0

# Digital silence.
pcm silence-mono16 s16le "aevalsrc='0':s=$rate:d=0.1"
alac silence-mono16 s16le mono silence-mono16
pcm silence-stereo16 s16le "aevalsrc='0|0':s=$rate:d=0.1"
alac silence-stereo16 s16le stereo silence-stereo16

# Square waves: mono near full scale with every prediction order ffmpeg
# allows, stereo at full scale (hits -32768 and 32767) with order 4.
pcm square-mono16 s16le "aevalsrc='0.999*sgn(sin(2*PI*1000*t))':s=$rate:d=0.12"
alac square-mono16 s16le mono square-mono16 -min_prediction_order 1 -max_prediction_order 30
pcm square-stereo16 s16le \
    "aevalsrc='sgn(sin(2*PI*500*t+0.1))|0.98*sgn(sin(2*PI*1500*t+0.2))':s=$rate:d=0.1"
alac square-stereo16 s16le stereo square-stereo16 -min_prediction_order 4 -max_prediction_order 4

# 24-bit: stereo sweeps (compressed with one shifted-off byte, and verbatim)
# and mono noise.
pcm sweep-stereo24 s24le \
    "aevalsrc='0.9*sin(2*PI*(200+3000*t)*t)|0.7*sin(2*PI*(300+2000*t)*t)+0.001*sin(2*PI*7000*t)':s=$rate:d=0.12"
alac sweep-stereo24 s24le stereo sweep-stereo24
alac sweep-stereo24 s24le stereo sweep-stereo24-verbatim -compression_level 0
pcm noise-mono24 s24le "anoisesrc=d=0.1:c=white:r=$rate:a=0.5:seed=33"
alac noise-mono24 s24le mono noise-mono24 -min_prediction_order 4 -max_prediction_order 4

# AirPlay-shaped: stereo 16-bit music-like signal cut into 16 packets of
# 352 frames (1408 bytes of s16le each), each encoded separately.
frames=352
packets=16
pcm airplay352 s16le \
    "aevalsrc='0.4*sin(2*PI*220*t)+0.2*sin(2*PI*1320*t)+0.05*(2*random(0)-1)|0.35*sin(2*PI*330*t+1)+0.25*sin(2*PI*880*t)+0.05*(2*random(1)-1)':s=$rate:d=1,atrim=end_sample=$((frames * packets))"
i=0
while [ "$i" -lt "$packets" ]; do
    name=$(printf 'airplay352-%02d' "$i")
    dd if="$out/airplay352.raw" of="$tmp/$name.raw" bs=$((frames * 4)) skip="$i" count=1 2>/dev/null
    ff -f s16le -ar 44100 -ch_layout stereo -i "$tmp/$name.raw" \
        -c:a alac -fflags +bitexact -flags:a +bitexact -f caf "$out/$name.caf"
    i=$((i + 1))
done

ls -l "$out"
