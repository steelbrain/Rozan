#!/bin/sh
# Renders the TV launcher banner (320x180 dp) at every density bucket from one
# drawing: the app icon's mark (a screen with signal arcs, see
# res/drawable/ic_app.xml) and the app name, on the app's background colour.
# Needs ImageMagick 7 and oxipng; uses macOS's Helvetica Neue for the text.
#
# Usage: app/airplay/scripts/render-banner.sh
set -eu

cd "$(dirname "$0")/../src/main/res"
font=/System/Library/Fonts/HelveticaNeue.ttc

# render <bucket> <scale relative to mdpi>
render() {
    dir="drawable-$1"
    s=$2
    mkdir -p "$dir"
    magick -size "$(echo "320*$s" | bc)x$(echo "180*$s" | bc)" xc:'#0F1013' \
        -fill none -stroke '#7DC0FF' \
        -draw "scale $s,$s translate 17,36 scale 2.2,2.2 stroke-width 2.5 stroke-linecap round path 'M12,11h24a3,3 0 0,1 3,3v16a3,3 0 0,1 -3,3h-24a3,3 0 0,1 -3,-3v-16a3,3 0 0,1 3,-3z M19,37h10 M19.76,23.76A6,6 0 0,1 28.24,23.76 M16.22,20.22A11,11 0 0,1 31.78,20.22'" \
        -stroke none -fill '#7DC0FF' \
        -draw "scale $s,$s translate 17,36 scale 2.2,2.2 circle 24,28 26,28" \
        -font "$font" \
        -fill '#F2F3F5' -pointsize "$(echo "32*$s" | bc)" -annotate "+$(echo "131*$s" | bc)+$(echo "86*$s" | bc)" 'Rozan' \
        -fill '#A4A9B4' -pointsize "$(echo "19*$s" | bc)" -annotate "+$(echo "132*$s" | bc)+$(echo "113*$s" | bc)" 'AirPlay receiver' \
        -strip -define png:bit-depth=8 "$dir/banner.png"
    oxipng -o 3 -q "$dir/banner.png"
}

render mdpi 1
render hdpi 1.5
render xhdpi 2
render xxhdpi 3
