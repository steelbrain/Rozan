#!/usr/bin/env bash
# Presses one game controller button, or pushes the left stick, on an emulator or device through a
# virtual USB gamepad, so the key goes through the same key layout and fallback map as a real
# controller.
#
# usage: scripts/emulator-gamepad.sh <A|B|X|Y|SELECT|START|RIGHT|DOWN> [hold_ms]
#        ANDROID_SERIAL picks the device when more than one is connected.
#
# `adb shell input gamepad keyevent` is not enough for this: injected keys always use
# Virtual.kcm, where X falls back to DPAD_CENTER and Y to BACK, while a real controller uses
# Generic.kcm, where X falls back to DEL and Y to SPACE (docs/research/nvidia-shield.md, 4.1).
# This script registers a HID gamepad with the shell's `hid` tool (needs /dev/uhid; tried on the
# API 28, 30 and 31 emulator images), presses the button, and unregisters it when the tool exits.
# The vendor id is the pid.codes test id, which has no key layout of its own, so Generic.kl and
# Generic.kcm apply. HID buttons 1 to 16 become BTN_A, B, C, X, Y, Z, TL, TR, TL2, TR2, SELECT,
# START, MODE, THUMBL, THUMBR.
set -euo pipefail

hold_ms=${2:-100}
case "${1:-}" in
  A) report='[0x01, 0x00, 0x00, 0x00]' ;;
  B) report='[0x02, 0x00, 0x00, 0x00]' ;;
  X) report='[0x08, 0x00, 0x00, 0x00]' ;;
  Y) report='[0x10, 0x00, 0x00, 0x00]' ;;
  SELECT) report='[0x00, 0x04, 0x00, 0x00]' ;;
  START) report='[0x00, 0x08, 0x00, 0x00]' ;;
  RIGHT) report='[0x00, 0x00, 0x7f, 0x00]' ;;
  DOWN) report='[0x00, 0x00, 0x00, 0x7f]' ;;
  *)
    echo "usage: $0 <A|B|X|Y|SELECT|START|RIGHT|DOWN> [hold_ms]" >&2
    exit 2
    ;;
esac

# Report: 16 one-bit buttons, then X and Y as signed bytes. The 1.5 s delay lets the input system
# add the device before the first report.
adb shell hid - >/dev/null <<EOF
{"id": 1, "command": "register", "name": "Test gamepad", "vid": 0x1209, "pid": 0x0001, "bus": "usb",
 "descriptor": [0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01,
                0x75, 0x01, 0x95, 0x10, 0x81, 0x02, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x15, 0x81, 0x25, 0x7f,
                0x75, 0x08, 0x95, 0x02, 0x81, 0x02, 0xc0]}
{"id": 1, "command": "delay", "duration": 1500}
{"id": 1, "command": "report", "report": $report}
{"id": 1, "command": "delay", "duration": $hold_ms}
{"id": 1, "command": "report", "report": [0x00, 0x00, 0x00, 0x00]}
{"id": 1, "command": "delay", "duration": 700}
EOF
