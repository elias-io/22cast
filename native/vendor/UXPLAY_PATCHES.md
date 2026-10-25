# Local changes to the pinned UxPlay core

Base revision: `116e39069e40f09350a372233937a55c890a02f8` (see `UXPLAY_REVISION`).

- `lib/raop.c`, `lib/raop_handlers.h`: keep PIN state per connection; require
  PIN authentication and completed signature verification before stream setup
  when PIN mode is enabled. Reject the non-PIN pairing route in this mode.
- `lib/raop_handlers.h`, `lib/pairing.c`, `lib/pairing.h`: validate PIN message
  lengths and ordering before copying bytes or using SRP state. Use the legacy
  protocol's 20-byte SHA-1 proof; reject identity confirmation before proof
  validation. Release temporary salt, verifier and public-key buffers.
- `lib/srp.c`: generate a salt with exactly 16 bytes; `pairing.c` accepts and
  zero-pads shorter unsigned verifier representations.
- `lib/httpd.c`: suppress SIGPIPE per socket/write, handle interrupted writes,
  and preserve listening sockets after transient accept/select errors or a
  failed per-client socket option.
- `lib/mdnsd/mdnsd.c`: prefer an active multicast-capable LAN over point-to-point
  VPN routes; apply the same filter to IPv6 selection. Expose the selected IPv4
  address through `mdnsd.h`, `dnssd_mdnsd.c` and `dnssd.h` for the Network screen.

These changes are shared by Android and desktop. `tests/receiver_smoke.py` tests
the actual desktop process. `tests/android_smoke.py` runs the same PIN checks
against the Android library and performs reset-socket tests directly on the
emulator, avoiding the different semantics of ADB's forwarded transport.
