# RAWDOB security model

## Current implementation (DNS-filtering work in progress)

Implemented in source:
- Local-only URL heuristic checks.
- A pure policy evaluator with exact-host/subdomain boundary matching.
- A local SHA-256 hash-chained audit-log utility and unit tests.
- Android Compose dashboard with user-controlled VPN start/stop.
- Local Android VpnService DNS filter for IPv4 UDP DNS requests routed to the virtual resolver.
- Allowed DNS queries forwarded to Cloudflare DNS (1.1.1.1) through a socket protected from the VPN route.
- Built-in tracker-domain rules plus a user-editable local domain blocklist.
- Local counters and last DNS allow/block decision displayed in the live radar.

Not implemented:
- Full VPN packet forwarding for arbitrary traffic, TCP/UDP forwarding, IPv6 DNS handling, or device-wide traffic inspection. Current implementation is DNS filtering only.
- A remote URL reputation service or malware scanning engine.
- Cross-application private-file inspection.
- A release-grade trusted audit-head anchor, encrypted audit storage, PDF export, or verified anti-tamper pipeline.
- Root/emulator detection or a claim of protection against a privileged attacker.

## Threat assumptions

The app helps users review local signals and policy decisions. A compromised OS, rooted device with a privileged attacker, modified binary, or attacker with write access to app-private storage may defeat local-only checks. R8 raises reverse-engineering costs but is not a security boundary.

## Audit log

The hash chain detects common record modifications, reordering, and internal deletions when checked against an intact chain. An attacker who can rewrite the entire file can recalculate the chain. Stronger truncation detection requires a trusted head hash anchored outside the writable log.

## VPN safety gate

The current VPN service is a limited DNS filter, not a full traffic tunnel. It only handles IPv4 UDP DNS packets addressed to its virtual resolver; it does not forward arbitrary IP traffic, inspect HTTPS content, or reliably block apps using DNS-over-HTTPS/DNS-over-TLS or other resolvers. Allowed DNS requests are sent to Cloudflare DNS (1.1.1.1), so those DNS queries leave the device for that provider. A complete firewall requires physical-device tests for IPv4/IPv6, TCP/UDP, MTU, network changes, app exclusions, lifecycle revocation, and failure behavior. Do not claim full-device protection until those gates pass.

## Privacy rules

- Keep processing local by default.
- Do not collect credentials, message contents, or browsing history for analytics.
- Provide clear disclosure and consent before future remote processing.
- Avoid logging raw URLs where they may contain identifiers, tokens, or sensitive query values.
- Document every permission and its purpose before adding it.
