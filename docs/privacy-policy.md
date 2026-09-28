# Privacy Policy — RF Test App

**Effective date: September 28, 2026**

RF Test App ("the app") is published by NHN Engineering & Consultants ("we," "us"). The app is a
field-survey tool for measuring and recording Wi-Fi and cellular radio conditions. This policy
explains what it collects, where that data goes, and what stays on your device.

## The short version

Everything the app measures — location, Wi-Fi, cellular signal — is recorded to your device's own
storage and stays there unless you explicitly export or share it yourself. The app does not run
ads, does not use analytics or crash-reporting SDKs, does not have user accounts, and does not send
your survey data to us, to any advertiser, or to any other third party. The only network traffic
the app generates on its own is a throughput test against a public speed-test endpoint (or a server
you point it at yourself), and that traffic carries generated test data, never your location or
survey results.

## What the app collects, and why

| Data | Why the app needs it |
|---|---|
| Precise location (GPS) | Every measurement is tagged with where it was taken — that's the point of a survey tool. Also required by Android to return real Wi-Fi network names/addresses and cell identifiers at all; without it, the OS itself redacts them. |
| Wi-Fi scan results (network names, addresses, signal strength, channel load) | The core Wi-Fi survey function. |
| Cellular signal and cell identity (signal strength, band, cell ID, 4G/5G state) | The core cellular survey function. |
| Phone state (`READ_PHONE_STATE`) | Distinguishes 5G non-standalone from standalone mode, which matters for what a survey is actually reporting. |
| Precise phone state and modem control (`READ_PRECISE_PHONE_STATE`, `MODIFY_PHONE_STATE`) | Only usable at all when the app is installed as a privileged system app — refused on an ordinary install, which is the normal case for anyone using the Play Store version. Where available, it reads additional modem detail (carrier aggregation, physical channel configuration) and, on a rooted device with the app's optional advanced features enabled, allows locking the radio to a specific band or technology for controlled testing. See "Root-gated features" below. |

All of the above is written to a file inside the app's own private storage. It is not uploaded
anywhere automatically. You decide when and whether to export it (as PDF, CSV, KML, GeoJSON, or
GeoPackage) and who you share that export with.

## What the app does not do

- **No accounts, no sign-in.** There is nothing to link your data to an identity beyond what's on
  your own device.
- **No analytics or crash-reporting SDKs.** Nothing about your usage is reported back to us.
- **No advertising**, and no ad-related SDKs or identifiers.
- **No background collection without a visible indicator.** Survey recording that continues with
  the screen off runs as a foreground service, which Android requires to show a persistent
  notification — it is never silent.
- **The app does not request `ACCESS_BACKGROUND_LOCATION`.** It doesn't need to: a foreground
  service with a declared location type can already continue recording location while the app is
  backgrounded, so the broader always-available background permission is never asked for.

## Network traffic the app generates

- **Throughput ("speed") testing**, an explicit, user-initiated feature: the app sends and
  receives generated test data to measure download/upload speed and latency. By default this talks
  to a public speed-test endpoint (speed.cloudflare.com); you can point it instead at a server on
  your own local network. Either way, only synthetic test bytes are exchanged — no location,
  identity, or survey data is included in that traffic.
- **The live view server**, used to mirror the app's screen to a laptop during a walk test, binds
  only to the device's loopback address and is reached over a direct USB cable connection (`adb
  forward`), never over Wi-Fi or any other network. It cannot be reached by anything else on a
  shared network, including other devices at the same venue.

## Root-gated features

On a rooted device, the app offers optional advanced features — band and technology locking, VoNR
control, and detailed radio-layer decoding — gated behind root access and off by default. These
features control the phone's own modem directly; none of them transmit data anywhere. They are
absent or clearly disabled on any non-rooted device, which is the normal case for a Play Store
install. See `docs/rooted-vs-unrooted-capabilities.md` in the project repository for the full
capability breakdown.

## Data retention and deletion

Survey data lives in the app's private storage until you delete it — either from within the app or
by uninstalling it, which removes all app data along with it (unless you've separately exported a
copy). We do not hold a copy of your data anywhere; there is nowhere for us to hold it, since it is
never sent to us.

## Children's privacy

The app is a professional RF field-survey tool, not directed at children, and we do not knowingly
collect data from children.

## Changes to this policy

If this policy changes, the updated version will be posted at the same location with a new
effective date.

## Contact

Questions about this policy: **jerwilson11@gmail.com**
