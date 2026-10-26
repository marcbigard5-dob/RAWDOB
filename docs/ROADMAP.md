# RAWDOB roadmap

## Stage 0 — engineering baseline
- [x] Kotlin / Compose Android project skeleton
- [x] Local URL heuristics with explicit uncertainty
- [x] Explainable pure policy evaluator
- [x] Hash-chained local audit utility
- [x] Unit tests and GitHub Actions workflow
- [x] Confirm earlier baseline CI build passes on GitHub-hosted Android SDK image
- [ ] Verify current DNS-filtering implementation passes CI and device-level tests

## Stage 1 — reliable local core
- [ ] Add localized Arabic/French/English resources and language selection
- [ ] Persist user rules with schema versioning and safe migration
- [ ] Add audit viewer, redaction, retention, and export
- [ ] Add policy conflict analysis and deterministic rule priority
- [ ] Add tests for malformed hosts, IDN, IPv6, and audit truncation

## Stage 2 — URL intelligence
- [ ] Add optional reputation provider only after privacy review and explicit consent
- [ ] Handle redirects and timeouts safely if remote lookup is introduced
- [ ] Keep offline mode functional and label remote results distinctly

## Stage 3 — local DNS protection (limited first implementation)
- [x] Add Android VpnService consent flow and foreground notification
- [x] Route virtual IPv4 UDP DNS queries through a local TUN resolver
- [x] Forward allowed DNS queries to Cloudflare DNS using a protected socket
- [x] Return NXDOMAIN for built-in tracker domains and user-defined blocked domains
- [x] Record the latest DNS decision and counters locally for the live radar
- [ ] Validate DNS packet checksums, EDNS, malformed packets, timeout behavior, and network transitions on physical devices

## Stage 4 — full traffic firewall and release hardening
- [ ] Select and implement a complete forwarding architecture for TCP/UDP/IPv4/IPv6
- [ ] Test IPv4/IPv6, TCP/UDP, MTU, DNS, captive portals, Doze, and network handover
- [ ] Test revocation, crashes, battery use, and kill-switch semantics on physical devices
- [ ] Do not expose enforcement controls until all gates pass

## Stage 5 — release hardening
- [ ] Dependency and secret scanning
- [ ] Release signing and key management
- [ ] R8 verification and tamper-signal tests
- [ ] Accessibility, localization, and low-end-device performance checks
- [ ] Privacy disclosures and Google Play VPN declaration review
