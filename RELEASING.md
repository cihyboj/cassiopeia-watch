# Releasing Cassiopeia Watch

1. Write the release notes in `RELEASE_NOTES_NEXT.md` and commit them. From a clean checkout, run `./release.sh <major>.<minor>` (for example, `./release.sh 40.10`). The script fetches main, creates `release/v<version>`, runs `./gradlew test lint`, updates app metadata and the Fastlane changelog (`<versionCode>.txt`), commits, pushes the branch, and opens a PR. `versionCode` is `major*1000+minor` and must increase.
2. Review the release PR, let CI pass, and merge into main.
3. Verify the fetched main tip has the intended `versionName`, then run the exact tag command printed by the script. The tag must be `v<version>` on a commit reachable from main. The script itself never tags or publishes a release.
4. The tag workflow validates the version and ancestry, runs tests and lint, signs the APK, and creates one **draft prerelease**. It refuses to overwrite a published release. The assets are `cassiopeia-watch-<version>.apk`, its `.sha256` checksum, `signer-sha256.txt`, `NOTICE`, `LICENSE`, and `THIRD_PARTY_NOTICES.md` when present.
5. Download the draft APK, verify its checksum and signer, and test it on devices before publishing. Freeze the approved commit, APK checksum, and signer; changes require repeating the affected checks. Publish the draft after approval.

Before tagging, configure repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`, plus repository variable `EXPECTED_SIGNER_SHA256` containing the trusted signing certificate's SHA-256 fingerprint (hex, optionally colon-separated). Keep the key and an encrypted, test-restored backup outside git. Add a protected signing environment after publication if the account plan allows it.
