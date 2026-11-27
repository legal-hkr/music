#!/bin/sh
# ALAC test vectors for AlacDecoderTest: synthetic signals encoded by ffmpeg's ALAC encoder, and the SHA-256 of
# ffmpeg's own decode of each file (PCM little-endian, 16 or packed 24 bit, interleaved in ffmpeg's channel order).
# Usage: gen.sh <output dir>   (writes <name>.m4a and vectors.txt)
set -eu
out=$1
mkdir -p "$out"
: > "$out/vectors.txt"

# name, bits, rate, seconds, layout, lavfi source, extra encoder options, filter on ffmpeg's decode (optional)
make() {
    name=$1 bits=$2 rate=$3 secs=$4 layout=$5 src=$6 opts=$7 decoded=${8:-anull}
    if [ "$bits" = 16 ]; then fmt=s16p; pcm=s16le; else fmt=s32p; pcm=s24le; fi
    # shellcheck disable=SC2086
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$src" \
        -af "aformat=sample_fmts=$fmt:sample_rates=$rate:channel_layouts='$layout',atrim=end=$secs" \
        -c:a alac -bits_per_raw_sample "$bits" $opts -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$out/$name.m4a"
    frames=$(ffprobe -v error -count_packets -show_entries stream=duration_ts -of csv=p=0 "$out/$name.m4a")
    sum=$(ffmpeg -hide_banner -loglevel error -i "$out/$name.m4a" -af "$decoded" -f "$pcm" -c:a "pcm_$pcm" - | sha256sum | cut -d' ' -f1)
    channels=$(ffprobe -v error -show_entries stream=channels -of csv=p=0 "$out/$name.m4a")
    echo "$name $bits $rate $channels $frames $sum" >> "$out/vectors.txt"
}

tone() { echo "$1*sin(2*PI*$2*t)"; }

# Two different tones and a little noise-like detail per channel: prediction and stereo decorrelation at work
make s16_stereo_44k 16 44100 0.6 stereo \
    "aevalsrc=$(tone 0.4 440)+$(tone 0.2 3001)+0.05*sin(2*PI*17*t*t*t)|$(tone 0.3 660)+$(tone 0.25 5003):s=44100" ""
# The same with every prediction order the format allows
make s16_stereo_orders 16 44100 0.4 stereo \
    "aevalsrc=$(tone 0.5 220)+$(tone 0.3 7000)|$(tone 0.45 330)+$(tone 0.1 9000):s=44100" "-min_prediction_order 1 -max_prediction_order 30"
# One prediction order each (the other vectors reach only some)
for order in 1 2 3 8 30; do
    make s16_order_$order 16 44100 0.1 stereo \
        "aevalsrc=$(tone 0.5 220)+$(tone 0.3 7000)|$(tone 0.45 330)+$(tone 0.1 9000):s=44100" "-min_prediction_order $order -max_prediction_order $order"
done
# Uncompressed frames only (ffmpeg's lowest level; the config's Rice parameters are all 0)
make s16_mono_raw 16 44100 0.1 mono "aevalsrc=$(tone 0.7 523):s=44100" "-compression_level 0"
make s24_stereo_raw 24 48000 0.1 stereo "aevalsrc=$(tone 0.4 440)|$(tone 0.3 660):s=48000" "-compression_level 0"
# Correlated channels: the encoder mixes them (mid/side with a nonzero weight)
make s16_mid_side 16 44100 0.3 stereo \
    "anoisesrc=c=pink:a=0.4:seed=1:r=44100[a];anoisesrc=c=pink:a=0.4:seed=2:r=44100[b];[a][b]amerge,pan=stereo|c0=c0+0.5*c1|c1=c0-0.5*c1" ""
# Hi-res, 24 bit
make s24_stereo_96k 24 96000 0.25 stereo \
    "aevalsrc=$(tone 0.4 440)+$(tone 0.2 3001)|$(tone 0.3 660)+$(tone 0.25 21003):s=96000" ""
# Both channels the same (the side channel is silence)
make s16_stereo_same 16 48000 0.3 stereo \
    "aevalsrc=$(tone 0.6 1000)|$(tone 0.6 1000):s=48000" ""
# Mono
make s16_mono_22k 16 22050 0.5 mono "aevalsrc=$(tone 0.7 523)+$(tone 0.1 4000):s=22050" ""
# Full-scale white noise: frames the encoder stores uncompressed
make s16_noise 16 44100 0.12 stereo "anoisesrc=d=1:c=white:a=1:seed=7:r=44100,aformat=channel_layouts=stereo" ""
make s24_noise 24 48000 0.1 stereo "anoisesrc=d=1:c=white:a=1:seed=11:r=48000,aformat=channel_layouts=stereo" ""
# Digital silence
make s16_silence 16 44100 0.2 stereo "anullsrc=r=44100:cl=stereo" ""
# Every channel layout ALAC has, each channel its own tone, so the order shows
make s16_3_0 16 48000 0.2 3.0 "aevalsrc=$(tone 0.5 300)|$(tone 0.5 500)|$(tone 0.5 700):s=48000" ""
# Four channels (C L R Cs) go out as seven, FL FR FC LFE BL BR BC, the LFE and the back pair silent (Alac.kt ORDER)
make s16_4_0 16 48000 0.2 4.0 "aevalsrc=$(tone 0.5 300)|$(tone 0.5 500)|$(tone 0.5 700)|$(tone 0.5 900):s=48000" "" \
    "pan=7c|c0=c0|c1=c1|c2=c2|c3=0*c0|c4=0*c0|c5=0*c0|c6=c3"
make s16_5_0 16 48000 0.2 5.0 "aevalsrc=$(tone 0.5 300)|$(tone 0.5 500)|$(tone 0.5 700)|$(tone 0.5 900)|$(tone 0.5 1100):s=48000" ""
make s24_5_1 24 48000 0.15 5.1 "aevalsrc=$(tone 0.5 300)|$(tone 0.5 500)|$(tone 0.5 700)|$(tone 0.5 90)|$(tone 0.5 1100)|$(tone 0.5 1300):s=48000" ""
make s16_6_1 16 48000 0.2 6.1 "aevalsrc=$(tone 0.5 300)|$(tone 0.5 500)|$(tone 0.5 700)|$(tone 0.5 90)|$(tone 0.5 1100)|$(tone 0.5 1300)|$(tone 0.5 1500):s=48000" ""
make s16_7_1 16 48000 0.2 '7.1(wide)' "aevalsrc=$(tone 0.5 300)|$(tone 0.5 500)|$(tone 0.5 700)|$(tone 0.5 90)|$(tone 0.5 1100)|$(tone 0.5 1300)|$(tone 0.5 1500)|$(tone 0.5 1700):s=48000" ""
