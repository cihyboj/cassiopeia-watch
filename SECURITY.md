# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub private vulnerability reporting on this repository: open the repository's Security tab and choose "Report a vulnerability". If that option is not available, open a public issue that only asks for a private way to report, with no details of the problem. Do not put vulnerability details in a public issue.

Include the app version, your Android version and phone model, a description of the problem, and steps to reproduce it. Do not include real Bluetooth addresses or personal data unless they are needed to reproduce the problem.

There is no email address for security reports.

## Scope

In scope:

- The Cassiopeia Watch Android app (`com.beamburst.casswatch`) and the code in this repository.
- Problems in how the app handles data from the watch, from other apps (broadcast intents), or from import files.
- Flaws in the build or release configuration in this repository that could affect users.

Out of scope:

- Vulnerabilities in the Casio watch firmware or the Bluetooth protocol itself.
- Vulnerabilities in third-party libraries that are not reachable from this app, and in Android.
- Problems that require a rooted or already compromised phone.
- The upstream projects (CasioGShockSmartSync and GShockAPI). Report those to their own repositories.

## Response

This project is maintained by one person in their spare time. Reports are handled on a best-effort basis, without guaranteed response times. Fixes are published as new prereleases. Please allow reasonable time for a fix before disclosing details publicly.

## Supported versions

Only the latest release is supported. The app is an experimental prerelease.
