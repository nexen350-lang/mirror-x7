# Security Policy

## Reporting a vulnerability

Do not report exploitable security vulnerabilities, credentials, or sensitive device data in public GitHub issues.

Please report security issues privately through **GitHub Security Advisories** (**Security -> Report a vulnerability** on this repository). Include affected app version, Android versions on both controller and target devices, reproduction steps using synthetic addresses, and impact details.

## Safe use and testing

- Only connect to devices and networks you own or are explicitly authorized to control.
- **Android 11+ Wireless debugging** encrypts transport with TLS and verifies the device's ADB certificate.
- **Legacy `adb tcpip` (port 5555)** is unencrypted plaintext ADB over TCP: use it only on trusted local networks and never expose port 5555 to the internet.
- Revoke debugging authorizations on target devices (**Developer options -> Revoke USB debugging authorizations** or **Wireless debugging**) when access is no longer needed.
- Redact real hostnames, IP addresses, clipboard contents, pairing codes, and ADB identity files before sharing diagnostics.
