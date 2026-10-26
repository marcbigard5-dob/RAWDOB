# RAWDOB security model

## Current implementation (0.1.0 baseline)

Implemented in source:
- Local-only URL heuristic checks.
- A pure policy evaluator with exact-host/subdomain boundary matching.
- A local SHA-256 hash-chained audit-log utility and unit tests.
- Android Compose dashboard showing honest feature status.

Not implemented:
- VPN packet forwarding, DNS filtering, or device-wide traffic blocking.
- A remote URL reputation service or malware scanning engine.
- Cross-application private-file inspection.
- A release-grade trusted audit-head anchor, encrypted audit storage, PDF export, or verified anti-tamper pipeline.
- Root/emulator detection or a claim of protection against a privileged attacker.

## Threat assumptions

The app helps users review local signals and policy decisions. A compromised OS, rooted device with a privileged attacker, modified binary, or attacker with write access to app-private storage may defeat local-only checks. R8 raises reverse-engineering costs but is not a security boundary.

## Audit log

The hash chain detects common record modifications, reordering, and internal deletions when checked against an intact chain. An attacker who can rewrite the entire file can recalculate the chain. Stronger truncation detection requires a trusted head hash anchored outside the writable log.

## VPN safety gate

Do not add a UI switch that claims to block traffic until a tested packet engine handles IPv4/IPv6, TCP/UDP, DNS, MTU, network changes, app exclusions, lifecycle revocation, and failure behavior. A TUN descriptor alone is not a working VPN tunnel. Fail-closed behavior must be explicit and tested; Android Always-on VPN / lockdown settings are system-managed.

## Privacy rules

- Keep processing local by default.
- Do not collect credentials, message contents, or browsing history for analytics.
- Provide clear disclosure and consent before future remote processing.
- Avoid logging raw URLs where they may contain identifiers, tokens, or sensitive query values.
- Document every permission and its purpose before adding it.
