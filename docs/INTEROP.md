# Testing with a real Mac or iPhone

The receiver is tested end to end against the test sender in
`:airplay:testing` on the Android TV emulator. This page lists the checks to
run with real Apple devices, the checks for an
[NVIDIA Shield TV](#nvidia-shield-tv) and for
[TVs with inputs](#tvs-with-inputs), and the results so far (at the end). The first
run, on 2026-11-26, kept PIN pairing (Mode P) as the default. The two modes and the device test plan behind checks 6,
9 and 16 are in [protocol/02-pairing.md](protocol/02-pairing.md) §1.2 and
§8.6.

## What you need

- A receiver: a Fire TV or Android TV running a Rozan release build
  (installed as in [INSTALL.md](INSTALL.md)), or the dev receiver on a Mac
  (see below).
- One or more senders: a Mac (macOS 15 or 26) and/or an iPhone or iPad
  (iOS 18 or 26). Write down each sender's OS version.
- The receiver and senders on the same network, with no client isolation
  between them (guest Wi-Fi networks often have it).
- To get logs from a TV: adb connected to it ([INSTALL.md](INSTALL.md) §1).

## Logs

On a TV, start this before the first check and keep it running:

```sh
adb logcat -c
adb logcat -v time -s AirPlayReceiver AirPlayService AirPlayNsd AirPlayPin \
  AirPlayUi AirPlayVideo AirPlayAudio > airplay.log
```

The receiver's own messages contain no codes, keys, sender names or
addresses.

## The dev receiver on a Mac

`:tools:devserver` runs the same receiver code on a desktop JVM, so most
checks can be run without a TV. A Mac does not list a receiver that runs on
itself, so run it on another machine: a second Mac, or Linux with Avahi
(`avahi-publish`). `./gradlew :tools:devserver:installDist` builds it into
`tools/devserver/build/install/devserver`; copy that directory over and run:

```sh
devserver/bin/devserver --name 'Rozan Dev' --mode pin --port 7000
```

- `--mode pin` (the default) is Mode P; `--mode digest` is Mode D.
- The control port defaults to 47000, because on a Mac, macOS's own AirPlay
  receiver holds 7000 and 5000. On Linux, `--port 7000` matches the TV app.
- The senders must see the host's mDNS announcements. On some networks
  multicast does not cross between Wi-Fi and the wired LAN; a host on the
  same Wi-Fi as the senders always works. Check with `dns-sd -B
  _airplay._tcp` on a Mac.
- Firewall: allow the control port over TCP and the ephemeral ports (Linux:
  32768-60999) over TCP and UDP from the LAN, for IPv4 and IPv6; Apple
  senders often connect over IPv6. On a Mac, allow `java` when macOS asks.
- The code is printed in the terminal instead of on a screen. Mirrored
  video is written to `video.h264` or `video.h265` (`ffplay video.h264`
  plays it) and audio to `audio-N.*`, in `rozan-dev` under Java's temp
  directory, or the directory given with `--out`. The path is printed at
  start.

Checks 8, 10 and 13 need a TV.

## Checks

Run checks 1-15 on the default mode, with each sender you have. If a check
fails, note the time, what the sender showed (a screenshot helps), and keep
`airplay.log` or the dev receiver's terminal output.

### Discovery

| # | Do | Expect |
| --- | --- | --- |
| 1 | Mac: Control Center > Screen Mirroring. iPhone: Control Center > Screen Mirroring. | The receiver's name is listed. On a TV, the name is under Receiver name in the AirPlay settings (the AirPlay tile on the home screen); it is four fruit names unless changed. |
| 2 | Open Music, then its AirPlay (audio output) button. | The receiver is listed. |

### Mirroring with a code

| # | Do | Expect |
| --- | --- | --- |
| 3 | Pick the receiver for screen mirroring. | The TV shows "*sender* wants to AirPlay. Enter this code on your device." and four digits. The sender asks for a code. |
| 4 | Enter the code. | Mirroring starts and fills the screen (letterboxed if the aspect ratio differs). Sound from the sender plays on the TV. |
| 5 | Stop mirroring on the sender. | The TV goes back to what it showed before. |
| 6 | Mirror again straight away. | The sender asks for a code again, and the TV shows a new one. If the sender connects without a code, or shows an error, note which. |
| 7 | Start mirroring and enter a wrong code. Then try the right one. | Note what the sender shows after the wrong code, and whether the right code still works. |
| 8 | While mirroring, press Back on the TV remote. Then mirror again. | Back ends mirroring. The next attempt asks for a new code. |

### Audio only

| # | Do | Expect |
| --- | --- | --- |
| 9 | In Music (Mac, then iPhone), play a track and pick the receiver as the output. | A code is asked for, as a code prompt or as an "AirPlay password". Audio never starts without one. |
| 10 | Enter the code. | Audio plays on the TV, which shows "Playing from *sender*" with the track. |
| 11 | Pause, resume, skip a track, move the sender's volume slider. | Each takes effect on the TV. |

### Music on a Mac

Music on a Mac uses classic AirPlay audio, which has its own requests
([protocol/01-discovery-and-session.md](protocol/01-discovery-and-session.md)
§4.3.2). Run these with a Mac in both modes. The lines to look for are in
the dev receiver's terminal or `airplay.log`; `#N` is the connection
number.

| # | Do | Expect |
| --- | --- | --- |
| 17 | In Music, pick the receiver as the output, without playing. | No code is shown. The log shows two `POST /fp-setup -> 200` and `OPTIONS * -> 200` on one connection, then `closed`. |
| 18 | Play a track. | The Mac asks for an AirPlay password and the receiver shows a code. The log shows `ANNOUNCE <uri> -> 401`; after the code, `ANNOUNCE <uri> -> 200`, `ANNOUNCE accepted: ALAC, 352 frames`, `classic session opened`, `classic audio: data ..., control ..., timing ...`, `SETUP <uri> -> 200` and `RECORD <uri> -> 200`. Audio plays. Note any request that gets a 4xx, with the `refused` line before it. |
| 19 | Stop, then play again and enter a wrong code first. | Music asks for the password again after the wrong code (a second `ANNOUNCE <uri> -> 401`), and the right code then plays. |
| 20 | Play two whole tracks, then pause for 3 minutes and resume. | Playback carries on into the second track. Note whether `first /feedback of a classic session` or `idle, kept open` appears, what Music sends while paused, and whether the resume plays at once, asks for a code again, or fails (look for `idle for 60 s, closing`). |

### Over time and at the edges

| # | Do | Expect |
| --- | --- | --- |
| 12 | Mirror a video with speech for 10 minutes. | Sound stays in step with lips the whole time, without dropouts. |
| 13 | TV only: check that Wake TV when someone connects is on in the AirPlay settings (it is by default), put the device to sleep, then pick the receiver on the sender. | The device wakes and shows the code. With HDMI-CEC on, the TV turns on too. |
| 14 | While a code from one sender is on screen, pick the receiver on a second sender. | The first code stays on screen; the second sender is turned away. Note what it shows. |
| 15 | After check 14, cancel on the first sender, then try the second again. | The first code leaves the TV when it expires, at most 2 minutes after it appeared. Then the second sender gets its own code. |

### Mode D (dev receiver with `--mode digest`)

| # | Do | Expect |
| --- | --- | --- |
| 16 | Mirror, stop, and mirror again. Once, enter a wrong code. | Each time the sender asks for an AirPlay password; the code in the terminal works; after a wrong code, the sender asks again. |

## NVIDIA Shield TV

These checks are for an NVIDIA Shield TV. Install the launcher as in
[INSTALL.md](INSTALL.md) steps 1 to 3 and 5, with the redirect on. Do not install
Rozan AirPlay alongside it for these checks: both use port 7000. Write down
the model (2015, 2017, 2019 tube or 2019 Pro), the Build line under
Settings > Device Preferences > About, and which remote and controller you
have.
[research/nvidia-shield.md](research/nvidia-shield.md) section 10 says why
each check is here.

Checks S1 to S6 need only the Shield.

| # | Do | Expect |
| --- | --- | --- |
| S1 | Restart the Shield. When the stock home appears, press Home. Then open any app and press Home, five times over. | Rozan opens every time, including the first press after the restart. Note roughly how long the stock home shows first. |
| S2 | Press Home twice quickly. Then hold Home. | Twice: the recent apps list opens and stays open. Held: note what opens, and whether Rozan replaces it. |
| S3 | Controller: on Rozan's home, move with the stick and the D-pad. On the Screensaver tile press A, then B; Y, then B; Start, then B. Then press X. | The stick and D-pad move focus. A, Y and Start each start the screensaver, and B ends it. X does nothing. |
| S4 | Controller: on an app tile, hold A, then press B. Press the NVIDIA button. Press the controller's Back and Home. | Holding A opens "App info" and "Uninstall", and B closes them. The NVIDIA button starts voice search. Back and Home act like the remote's. |
| S5 | 2019 remote: on an app tile press ≡, then hold Select. Then press Netflix. | ≡ opens the Shield's quick settings and nothing in Rozan. Holding Select opens "App info" and "Uninstall". Netflix opens Netflix. |
| S6 | Optional: make Rozan the real home (INSTALL.md section 6), restart the Shield and press Home. Then undo it (INSTALL.md section 7). | Note whether Home still opens Rozan after the restart, and whether `adb shell pm list packages -d` still lists `com.google.android.tvlauncher`. |

Checks S7 to S14 also need a Mac or iPhone. Start the log first
([Logs](#logs)).

| # | Do | Expect |
| --- | --- | --- |
| S7 | Check that "Wake TV when someone connects" is on in Rozan's AirPlay settings, and that the Shield's setting that turns the TV on is on (Settings > Device Preferences > Display & Sound). With the Shield on Wi-Fi, put it to sleep with the power button, wait 30 minutes, then pick the receiver on the sender. | The Shield wakes and shows the code, and the TV turns on and switches to the Shield. |
| S8 | Only if S7 failed: put the Shield to sleep and wait 30 minutes again. Before picking the receiver, run `adb shell pidof ai.aneesiqbal.rozan.launcher:airplay`, `adb shell dumpsys netpolicy > netpolicy.txt` and `adb shell dumpsys power > power.txt`. | `pidof` prints a number (the receiver is still running). Send both files with the log. |
| S9 | Repeat S7 with the Shield on Ethernet. | As in S7. |
| S10 | Set the Shield's output to 4K if the TV takes it, then mirror a Mac or iPhone. | Mirroring starts. The log has a line that starts "display mode" and one that starts "decoding with"; send both. |
| S11 | While mirroring, press Y, X and A on the controller, then B. | Only B ends mirroring. |
| S12 | 2019 models: mirror a stopwatch running on the sender, and take a photo that shows the sender and the TV together. Do it with AI upscaling on Basic, then on AI-Enhanced. | Note the difference between the two stopwatches in each photo. |
| S13 | Mirror a video with speech for 10 minutes with Dolby audio processing on, then 10 minutes with it off. | Lips stay in step both times. Note any offset. |
| S14 | Set the Shield's display-off timer to its shortest ("Turn off display" under Energy saver on 9.x; "Put device to sleep" under Screen saver on 8.x). Play Music from an iPhone to the receiver and let it run past that time. | Note whether the music keeps playing after the display turns off. |

For the Shield, send back the model, the Build line, the result of each S
check, `airplay.log`, and the photos from S12.

## TVs with inputs

Checks I1 to I11 are for a TV that runs Android TV or Google TV (Sony, TCL,
Hisense, Philips and others) or Fire OS (Fire TV Edition, Fire TV Omni), and
has HDMI or other video inputs. I12 is for a Fire TV Cube, I13 for a
streaming box plugged into a TV, and I14 for a Fire TV Stick. Install the launcher as in
[INSTALL.md](INSTALL.md); on Fire TV, turn the Home button redirect on. Write
down the device's brand and model, its software version, and what is plugged
into each input (note which devices support HDMI-CEC). The emulator has no
inputs, so none of this has been seen on real inputs yet;
[research/tv-inputs.md](research/tv-inputs.md) says why each check is here.
Start this log first:

```sh
adb logcat -c
adb logcat -v time -s TvInputs SystemIntents ActivityTaskManager:I > inputs.log
```

Some checks list which apps answer the system's input list actions:

```sh
adb shell cmd package query-activities --brief -a com.android.tv.action.VIEW_INPUTS > pickers.txt
adb shell cmd package query-activities --brief -a com.amazon.tv.action.NAVIGATE_TO_INPUTS >> pickers.txt
```

| # | Do | Expect |
| --- | --- | --- |
| I1 | Open Rozan's home screen. Run `adb shell dumpsys tv_input > tv_input.txt`. | The Inputs button is between the clock and the gear. Send `tv_input.txt`. |
| I2 | Open the Inputs button. Then open the TV's own input list (the remote's Input key, or Settings > Inputs). | Rozan lists the same inputs as the TV, without the TV tuner and without inputs hidden in the TV's settings, HDMI ports in port order. Note any input that is missing or extra, and which rows have an icon. |
| I3 | In the TV's settings, rename one input and hide another. Press Home and open the Inputs button again. | The new name shows and the hidden input is gone. |
| I4 | With the list open, unplug an HDMI device, plug it back in, then turn a CEC device off and on with its own remote. | The row changes to "Not connected", back, and to "Standby" and back, without closing the list. |
| I5 | Select a connected input. Then press Home. | The TV switches to it. Note how long it takes and what the log shows after `START u0`. Home brings Rozan back. |
| I6 | Select an input whose device is in standby. | Note whether the device wakes and the TV shows its picture. |
| I7 | Select a device listed under its port (found over HDMI-CEC). | The TV switches to that port. Note whether the device wakes. |
| I8 | Only if I5 failed: note the message, then select "Open the TV's input list". | The TV's own input list opens with no "Open with" question. Note which one, whether selecting an input there switches the TV, and send `inputs.log` and `pickers.txt`. |
| I9 | Press the remote's Input key while Rozan is in front. | Note what opens. |
| I10 | Fire TV Edition and Fire TV Omni only: run I1. Then pick an HDMI input's id from `tv_input.txt`, write each `/` in it as `%2F`, and run `adb shell am start -a android.intent.action.VIEW -d content://android.media.tv/passthrough/<id>`. | Note what the command opens: the input, Live TV's settings, or something else. |
| I11 | Fire TV Edition and Fire TV Omni only: write `pickers.txt`. On Rozan's home, press the Inputs button. Choose an input there. Then press Home. | If `pickers.txt` lists an Amazon activity, the button is there and opens Fire OS's Inputs view with no "Open with" question, and the view stays on screen (Rozan does not come back by itself). Choosing an input switches the TV. Note whether Home then brings Rozan back or leaves the Fire TV home on screen. If `pickers.txt` lists none, there is no button. If the message "This TV doesn't let Rozan open its input list." appears, the button goes away; note it. Send `pickers.txt` and `inputs.log`. |
| I12 | Fire TV Cube (3rd gen) with a device on HDMI IN: run `adb shell dumpsys tv_input > tv_input.txt` and `adb shell dumpsys package com.amazon.tv.inputpreference.service > inputpreference.txt`, and write `pickers.txt`. On Rozan's home, press the Inputs button and choose HDMI IN. Then press Home. | The button opens the Cube's input chooser, and choosing HDMI IN shows that device. Home brings Rozan back. If the message "This TV doesn't let Rozan open its input list." appears instead, the button goes away; note it. Send the three files and `inputs.log`. |
| I13 | NVIDIA Shield and Google TV Streamer (also a Chromecast with Google TV): write `pickers.txt`, then open Rozan's home. On the Streamer, also open each activity that `pickers.txt` lists with `adb shell am start -n <component>`, and choose an entry in it. | No Inputs button. If there is one, it opens Rozan's list: note what it lists, and if "Open the TV's input list" appears after a failed switch, what that opens and lists. On the Streamer, note whether choosing an entry in its own list switches the TV's input over HDMI-CEC; if it does, the button could open that list there too. Send `pickers.txt` and `inputs.log`. |
| I14 | Fire TV Stick: write `pickers.txt`, then open Rozan's home. If there is an Inputs button, press it. | If `pickers.txt` lists an Amazon activity, the button is there (Fire OS); note what it opens and whether it lists anything. If it lists none, there is no button. Send `pickers.txt` and `inputs.log`. |

For a TV, send back the brand, model and software version, the result of
each I check, `tv_input.txt`, `pickers.txt` and `inputs.log`.

## What to send back

For each sender: model, OS version, and the result of each check (pass, or
what happened instead), plus the log for any check that failed.

## Results

2026-11-26: a MacBook Pro (Mac15,9) on macOS 27.0.1 and an iPhone
(iPhone18,2) on iOS 27.2.1, against the dev receiver on a Linux host on the
same Wi-Fi, in Mode P on port 7000. Details are in
[protocol/02-pairing.md](protocol/02-pairing.md) §8.6 and
[protocol/01-discovery-and-session.md](protocol/01-discovery-and-session.md)
§2.5 and §4.3. The dev receiver has no screen or speaker, so "Pass" means
the receiver's log showed the expected requests and the recorded video
decoded; what the TV would show still needs a TV.

| # | Mac (macOS 27.0.1) | iPhone (iOS 27.2.1) |
| --- | --- | --- |
| 1 | Pass | Pass |
| 2 | Lists the receiver; see 9 | Pass |
| 3 | Pass: four-box code entry | Pass: the code goes in a number-only field |
| 4 | Pass: H.264 1920x1080 at about 30 fps, plus an AAC-ELD audio stream | Pass: H.264 498x1080 at about 27 fps |
| 5 | The session ends with TEARDOWN | The session ends with TEARDOWN |
| 6 | Pass: a new code every time, and a new receiver key | **Fails for about 2 minutes**: iOS retries the old key and shows "Unable to connect", then asks for a new code |
| 7 | Not run | Pass: after one wrong code, the same code works |
| 8, 13 | Need a TV | Need a TV |
| 9, 10 | See 17-20 (the first run closed after `fp-setup` and `OPTIONS`; that was Music's selection check, and the play request then failed at SETUP) | Pass: a code, then an ALAC 44.1 kHz stereo stream and track info |
| 11 | Volume changes arrive (while mirroring); the rest not run | Skip and volume changes arrive |
| 12 | Not run (needs a TV to judge) | Not run |
| 14 | Run while the iPhone was mirroring, not while its code was up: the Mac gets 409 and shows "Unable to connect"; the iPhone carries on | Not run |
| 15, 16 | Not run | Not run |
| 17 | Pass: two `fp-setup`, `OPTIONS`, `closed`; Music logs the selection as complete | n/a |
| 18 | Audio passes with the code check skipped (experiment build): ANNOUNCE, SETUP, RECORD, track info; the received ALAC decodes to the 440 Hz test tone for all 29.9 s. The password prompt not yet tried | n/a |
| 19, 20 | Not run | n/a |

