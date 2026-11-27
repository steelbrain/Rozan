# Roku: why Rozan cannot run there

Research for the question "can Rozan support Roku TVs?". Research date:
2026-11-26. The answer is no, for the launcher and for the AirPlay receiver.
Most Roku TVs and players have Apple AirPlay built in, which covers what Rozan
AirPlay does, and a Roku TV can start on an HDMI input where a Fire TV or
Android TV device runs Rozan. Nothing here was tried on a Roku device.

Sources are Roku's developer documentation, legal agreements and support
pages unless a claim says otherwise. **UNCONFIRMED** marks claims that rest on
a secondary source or on inference.

## 1. Roku OS is not Android

- Roku describes Roku OS as "a highly customized user experience built on
  top of an embedded Linux kernel"
  ([Roku engineering](https://engineering.roku.com/you-need-a-build-system);
  the page needs JavaScript, so the quote was read from a search engine's
  copy) and as "a cross-platform television operating system built on top of
  the Linux kernel"
  ([Roku engineering](https://engineering.roku.com/running-out-of-file-descriptor)).
  Roku's developer docs say the `chanperf` command of Roku OS 15.2 "reports
  the raw Linux CPU and processing status information (/proc/pid/stat)"
  ([External Control API](https://developer.roku.com/dev/docs/external-control-api)).
- Roku apps are written in BrightScript with the SceneGraph UI framework
  ([How apps work](https://developer.roku.com/dev/docs/how-channels-work)),
  and "BrightScript compiles code into bytecode that is run by an
  interpreter"
  ([language reference](https://developer.roku.com/dev/docs/brightscript-language-reference)).
  A TypeScript SDK in beta needs Roku OS 16.0
  ([Roku blog](https://blog.roku.com/developer/rsg-2-0-sdk-beta)). There is no
  Android runtime, so an APK cannot be installed. A post on Roku's developer
  forum says "Roku OS is not based on Android and does not support Android
  tools or applications"
  ([forum](https://forum.developer.roku.com/t/can-you-use-android-tv-apks/11396);
  the poster is not marked as Roku staff, so UNCONFIRMED as an official
  statement).

## 2. No app can replace the home screen

- The Home key "is handled by the global Roku firmware event handler in a
  default manner that cannot be modified by application code"
  ([onKeyEvent](https://developer.roku.com/dev/docs/onkeyevent)), and
  "Pressing the Home button will always exit the application and
  unconditionally return control back to the UI application shell"
  ([development environment](https://developer.roku.com/dev/docs/dev-environment)).
  Apps with Instant Resume are suspended instead (section 3).
- No app starts at boot. The manifest has no boot or autostart attribute;
  the only automatic launch it documents is `usb_media_handler=1`, when a USB
  device is inserted
  ([manifest](https://developer.roku.com/dev/docs/channel-manifest)). None of
  the documented launch sources is boot (development environment page). On
  Roku TVs, the Power on setting offers the Home screen, the last used input
  and named TV inputs, and no apps
  ([Roku TV User Guide 14.0](https://image.roku.com/c3VwcG9ydC1B/Roku-TV-User-Guide-14-0-en-US.pdf),
  p. 105).
- An app cannot list or start other apps: `roAppManager` has only
  `IsAppInstalled`
  ([ifAppManager](https://developer.roku.com/dev/docs/ifappmanager)), and
  "Support for sending ECP commands from within a Roku app has been
  discontinued" (External Control API page).
- The rules forbid it as well. Certification forbids "nested content or
  applications ... deep links to other applications, or any cross-app
  functionality" (2.4), requires Back on an app's first screen to exit to the
  Roku home screen (4.6), and forbids interfering with the system screensaver
  (4.5) ([certification](https://developer.roku.com/dev/docs/certification)).
  The Distribution Agreement forbids apps that "provide access to any other
  application" (Exhibit A 3.2(d);
  [agreement](https://docs.roku.com/published/DeveloperDistribution)).

## 3. A receiver cannot be built as a Roku app

An AirPlay receiver needs a TCP server, UDP sockets, mDNS, AES-CTR, X25519
and Ed25519, and a decoder fed with raw H.264 access units and AAC-ELD frames
as they arrive ([protocol/](../protocol/)). What Roku offers:

- Sockets: `roStreamSocket` can `Listen` and `Accept` TCP connections
  ([roStreamSocket](https://developer.roku.com/dev/docs/rostreamsocket)), and
  `roDataGramSocket` sends and receives UDP, with multicast `JoinGroup`
  ([roDataGramSocket](https://developer.roku.com/dev/docs/rodatagramsocket),
  [ifSocketCastOption](https://developer.roku.com/dev/docs/ifsocketcastoption)).
  Addresses are IPv4 only
  ([roSocketAddress](https://developer.roku.com/dev/docs/rosocketaddress)).
  There is no DNS-SD API, and no port list. On Roku models with AirPlay,
  Roku OS itself answers on TCP 7000
  ([nmap scans](https://github.com/RoseSecurity/Abusing-Roku-APIs); UNCONFIRMED)
  and runs an mDNS responder on UDP 5353, so a receiver app would compete for
  both ports and senders would list two receivers at one address.
- Running in the background: on suspend an app "loses access to the video
  decoder", and background tasks "are automatically blocked after 5 seconds"
  ([Instant Resume](https://developer.roku.com/dev/docs/instant-resume)). A
  receiver would answer only while its app is on screen.
- Video plays only from a URL in a container or streaming format: the
  Video node's `streamFormat` takes `mp4`, `hls`, `ism`, `dash` and `mkv`
  ([content metadata](https://developer.roku.com/dev/docs/content-metadata),
  [Video](https://developer.roku.com/dev/docs/video)). The elementary streams
  Roku plays are AAC with ADTS framing, AC3, EAC3 and PCM; H.264 is accepted
  only inside a container, and AAC-ELD is not listed at all
  ([media support](https://developer.roku.com/dev/docs/media)). No API feeds
  buffers to the decoder. An app can serve HLS to its own Video node from a
  local socket (one developer report on Roku OS 15.3.4,
  [Stitch-Revitalized-For-Roku#14](https://github.com/jeremy-albinet/Stitch-Revitalized-For-Roku/issues/14)),
  but live streams must stay "a minimum of 30 seconds away from the live
  edge" (media support page) and no low-latency mode is documented, so the
  delay would be seconds against mirroring's fraction of a second.
- Audio: there is no API that plays PCM samples generated by the app, only
  file and URL players
  ([roAudioResource](https://developer.roku.com/dev/docs/roaudioresource),
  [roAudioPlayer](https://developer.roku.com/dev/docs/roaudioplayer),
  [Audio](https://developer.roku.com/dev/docs/audio)), and nothing lets an
  app follow the sender's clock. Mirroring sound (AAC-ELD) could not be
  played at all.
- Crypto: `roEVPCipher` offers AES in CBC, CFB, ECB and OFB modes, and GCM
  from Roku OS 15.2, but not CTR, which mirrored video uses, or
  ChaCha20-Poly1305
  ([roEVPCipher](https://developer.roku.com/dev/docs/roevpcipher)). No
  X25519, Ed25519 or big-integer arithmetic is documented, so pairing would
  have to be written in interpreted BrightScript, which has no XOR operator
  ([expressions](https://developer.roku.com/dev/docs/expressions-variables-types)).

## 4. Sideloading and distribution

- Sideloading needs developer mode and allows "only one app ... at a time"
  ([developer setup](https://developer.roku.com/dev/docs/developer-setup)).
- The Developer Tools License allows loading an app onto devices "only to
  test your Applications on an internal, non-public, non-commercial basis"
  (4.2) and forbids giving such apps to third parties (4.6)
  ([licence](https://docs.roku.com/published/developersdk/en/us)). Handing
  out a package to sideload, as Rozan does with its APKs, is outside it.
- Beta apps expire after 120 days and allow at most 20 testers
  ([publishing guide](https://developer.roku.com/dev/docs/channel-publishing-guide)).
  Publishing an app that works with third-party technology needs Roku's
  written approval (Distribution Agreement, Exhibit A 3.2(b)).

## 5. What Roku owners can use

### Built-in AirPlay

- Roku OS 9.4 (2020) brought AirPlay 2 and HomeKit to some 4K devices, and
  Roku OS 10.0 (2021) to most Roku devices "with the exception of Roku TV
  model numbers beginning with "6" (6XXXX), "5" (5XXXX), and Roku player
  model numbers 2700X, 2710X, 2720X, 3500X, 3700X, 3710X, and 4400X"
  ([OS release notes](https://support.roku.com/article/roku-os-release-notes)).
  The Roku TV User Guide says the same for TVs: "supported by all Roku TVs
  except hardware IDs beginning with '6' (6XXXX) and '5' (5XXXX)"
  ([Roku TV User Guide 14.0](https://image.roku.com/c3VwcG9ydC1B/Roku-TV-User-Guide-14-0-en-US.pdf)).
- Roku's article "Which Roku devices support Apple AirPlay and HomeKit?"
  ([support.roku.com](https://support.roku.com/article/360057488733), last
  updated 2026-06-23):
  - "Roku Select and Plus Series TVs support Apple AirPlay and HomeKit
    regardless of Roku OS version."
  - Roku OS 9.4 or later: Roku TV models Axxxx, Cxxxx, CxxGB and 7xxxx;
    Streambar 9102, Streambar Pro 9101R2, Smart Soundbar 9101, onn. Roku
    Smart Soundbar 9100; Streaming Stick+ 3810 and 3811, Streaming Stick 4K
    3820, Streaming Stick 4K+ 3821, Express 4K 3940, Express 4K+ 3941,
    Premiere 3920 and 4620, Premiere+ 3921 and 4630, Ultra 4600, 4640, 4660,
    4661, 4670, 4800, 4802 and 4850, Ultra LT 4662 and 4801.
  - Its Roku OS 10 table lists Roku TV 5xxx and 6xxx, Express 3700,
    Express+ 3710 and Roku 4 440, which are the models the release notes and
    the user guide exclude. Two Roku sources agree against it, so that table
    looks mislabelled (UNCONFIRMED).
  - It plays video, photos and audio from an iPhone, iPad or Mac, mirrors
    their screens, and needs iOS 12.3 or later or macOS 10.14.5 or later.
    Turn it on under Settings > Apple AirPlay and HomeKit.
- The 2025 Roku Streaming Stick (3840) and Streaming Stick Plus (3830) are
  not in the article; their product pages say "Works with Apple AirPlay and
  HomeKit" ([Stick](https://www.roku.com/products/players/roku-streaming-stick),
  [Stick Plus](https://www.roku.com/products/players/roku-streaming-stick-plus)).

### Rozan on a Roku TV's HDMI input

A Fire TV or Android TV device running Rozan can be plugged into a Roku TV.
Setting the TV's Settings > System > Power > Power on to that input makes the
TV start on it (Roku TV User Guide 14.0, p. 105). Rozan's wake on connect
turns a TV on only over HDMI-CEC ([AIRPLAY.md](../AIRPLAY.md#wake-on-connect)).

## 6. Conclusion

Rozan is not offered for Roku, and no Roku version is planned: the launcher
cannot exist there (section 2), a receiver app could not run in the
background, decode the stream or do the pairing crypto natively (section 3),
and a sideloaded app could not be handed out (section 4). Roku owners should
use the AirPlay built into their device, or a Fire TV or Android TV device on
one of the TV's HDMI inputs (section 5). [INSTALL.md](../INSTALL.md) says
this briefly.
