# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-11-26

The first release: the Rozan launcher and the standalone Rozan AirPlay
receiver. Both need Android 9 or later, or Fire OS 7 or later. Rozan has
run only on the Android TV emulator so far, not on TV hardware; see Known
issues.

### Added

#### Home screen
- A home screen for Android TV, Google TV and Fire TV: four built-in tiles
  (Settings, Network, AirPlay, Screensaver) above all installed apps in
  alphabetical order, a clock that follows the system's 12-hour or 24-hour
  setting, and Rozan's settings behind the gear in the top-right corner.
- Menu, or holding Select, on an app opens "App info" and "Uninstall".
- Game controllers work like the remote: A selects, B goes back, and the
  left stick and the D-pad move focus.
- On Fire TV, the Settings and Network tiles open Fire OS's own settings
  screens.
- An Inputs button between the clock and the gear. On TVs that report their
  HDMI and other video inputs to apps, it lists the inputs under the names
  given to them in the TV's settings, greys out inputs in standby or with
  nothing connected, and switches the TV to the one selected. Where Rozan
  can't switch, the list says so and offers the TV's own input list. On
  Fire TV Edition and Fire TV Omni TVs and the Fire TV Cube, it opens Fire
  OS's own input list. Streaming sticks and boxes show no button.
- 24 bundled public-domain and CC0 landscape backdrops that crossfade at a
  chosen interval (10 minutes by default), with credits in the settings and
  in docs/BACKDROPS.md.
- "Keep screen awake" on the home screen, and a full-screen backdrop
  screensaver after a chosen idle time (5 minutes by default).
- Home button redirect: an optional accessibility service, "Rozan home
  button", that opens Rozan when the stock home screen comes to the front,
  and never acts on Settings, inputs or profile screens. The settings show
  its state and the adb commands that turn it on. On the NVIDIA Shield and
  other devices with the classic Android TV home, Rozan can instead be made
  the default home.
- Translations into Simplified Chinese, French, Italian, Japanese,
  Brazilian Portuguese, Russian and Spanish. Rozan follows the device's
  language and uses English when it is none of these. A Language setting at
  the top of the settings lists each language by its own name and applies
  at once to the launcher and the AirPlay screens; on Android 13 and later
  the system's per-app language settings show the same choice. Rozan
  AirPlay follows the device's language.

#### AirPlay
- An AirPlay receiver in its own process: screen mirroring (H.264, at most
  1080p at 30 frames per second) with its sound, and audio on its own
  (AAC-ELD, ALAC, AAC, PCM), from a Mac or iPhone.
- A new 4-digit code on the TV for every connection, shown over other apps
  when Rozan may draw over them. A Mac takes it in macOS's four-box code
  entry. Music on a Mac asks for it as its AirPlay password when a track
  starts; picking the TV as an output shows no code. The receiver remembers
  no devices.
- Mirroring takes over the screen after the right code, and Back on the
  remote ends it. Audio on its own shows the sender's name, and the title,
  artist, album and artwork when the sender sends them.
- Audio stays within 5 ms of the video timeline: after an output hiccup,
  sync errors are corrected in steps of at most 2 ms within about a second.
- "Wake TV when someone connects" (on by default) wakes the device when a
  Mac or iPhone asks for a code.
- A configurable receiver name, by default four random fruit names.
- The receiver status says "Announced on the network" when Apple devices
  can find it, and explains when port 7000 is taken by another app; it
  tries again whenever Home or the status screen comes back.
- The settings show whether AirPlay may draw over other apps, and the adb
  command that allows it.
- Receiver limits against other hosts on the network: one code prompt at a
  time (other addresses get 409 while a code is on screen), three codes per
  address per 10 minutes that don't end in pairing, wrong-code lockouts per
  address (IPv6 per full address), 10 s per request and 3 minutes to
  authenticate for unpaired connections, 30 s to start a session after
  pairing, trust that ends with the session (or 30 s after pairing without
  one), and audio and timing UDP sockets that accept only the sender's
  port. SECURITY.md lists every limit.
- Rozan AirPlay: the same receiver as a standalone app with a status
  screen, for keeping the current home screen.

#### Other
- The receiver uses no CPU while nothing is connected, and the launcher
  wakes only for the clock and the backdrop change while idle.
- Install guide for Fire TV, Google TV, NVIDIA Shield and other Android TV
  devices (docs/INSTALL.md).
- Both APKs are signed with one release key. SHA256SUMS in the release
  lists their checksums, and docs/RELEASING.md gives the certificate's
  SHA-256 fingerprint.

### Known issues
- Rozan has run only on the Android TV emulator, not yet on a TV, a Fire
  TV, a Google TV device or an NVIDIA Shield. The Home button redirect, the
  Fire TV tiles and the Inputs button have not been tried on hardware.
- The receiver has mirrored a real Mac and iPhone, and played Music from
  the iPhone, only while running on a computer, not yet on a TV device, and
  not with an iPad.
- Audio-only AirPlay from the Music app on a Mac has played only in a test
  build that skipped the code, so Music's password prompt has not been
  tried with a Mac yet. Audio while mirroring a Mac, and Music on an
  iPhone, have played with their codes in the tests above.
- After a session ends, an iPhone shows "Unable to connect" for about two
  minutes before it asks for a new code.
- Videos and photos sent with an app's own AirPlay button, such as a video
  player's, are not supported.
