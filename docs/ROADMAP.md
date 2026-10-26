# RAWDOB roadmap

## Stage 0 — engineering baseline
- [x] Kotlin / Compose Android project skeleton
- [x] Local URL heuristics with explicit uncertainty
- [x] Explainable pure policy evaluator
- [x] Hash-chained local audit utility
- [x] Unit tests and GitHub Actions workflow
- [ ] Confirm CI build passes on GitHub-hosted Android SDK image

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

## Stage 3 — VPN research and implementation
- [ ] Select a documented forwarding architecture and threat model
- [ ] Implement a packet engine with protocol and DNS support
- [ ] Test IPv4/IPv6, TCP/UDP, MTU, DNS, captive portals, Doze, and network handover
- [ ] Test revocation, crashes, battery use, and kill-switch semantics on physical devices
- [ ] Do not expose enforcement controls until all gates pass

## Stage 4 — release hardening
- [ ] Dependency and secret scanning
- [ ] Release signing and key management
- [ ] R8 verification and tamper-signal tests
- [ ] Accessibility, localization, and low-end-device performance checks
- [ ] Privacy disclosures and Google Play VPN declaration review
