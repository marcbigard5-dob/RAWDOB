# RAWDOB

**Android security and privacy project** — Kotlin, Jetpack Compose, Material 3.

> Development status: initial engineering baseline. This repository does not yet claim to be a production-ready firewall, VPN, malware scanner, or anti-tamper solution.

## Goals

- Native Android application using Kotlin and Jetpack Compose.
- Privacy-first, local-by-default design.
- Explicit monitor-only and learning modes before enforcement features.
- Explainable policy decisions and an append-oriented, tamper-evident audit trail.
- Arabic, French, and English localization.
- URL risk checks that explain heuristic findings without claiming certainty.
- Carefully scoped Android VPN integration; no traffic capture tunnel is activated until packet processing and forwarding are implemented and tested.

## Security boundaries

Android's `VpnService` exposes IP packets through a TUN interface. A working VPN solution must process outgoing packets and return corresponding incoming packets through a real tunnel or equivalent forwarding implementation. Merely establishing a TUN interface is not a complete firewall and can disrupt connectivity.

This project will not claim that an ordinary Android app can:
- decrypt arbitrary HTTPS traffic without appropriate trust configuration and cooperation;
- read other apps' private files or observe every access to them;
- detect every data leak or all malicious activity;
- provide absolute root, emulator, or tamper detection.

## Build

Open this repository in a compatible Android Studio version and allow Gradle sync. The project uses a standard Gradle Kotlin DSL layout. Build/test status must be verified in an Android SDK environment before a release is considered.

## Development stages

1. Establish a buildable Android baseline and tests.
2. Implement local policy evaluation and event explanations.
3. Add a privacy-safe audit store and export.
4. Implement URL checks with documented limits.
5. Design VPN packet processing and forwarding, then test connectivity, DNS, IPv4/IPv6, network transitions, and failure behavior before exposing enforcement controls.
6. Measure battery and performance on physical devices.
7. Review signing, R8, dependency updates, and release security.

## Responsible use

Only install and test on devices and networks you own or are authorized to assess. Security findings are signals, not guarantees.
