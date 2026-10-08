# Releasing Cassiopeia Watch

1. Write the release notes in `RELEASE_NOTES_NEXT.md` and ensure they are committed on `origin/main`. From a clean checkout, run `./release.sh <major>.<minor>` (for example, `./release.sh 40.10`). The script fetches main, creates `release/v<version>`, runs `./gradlew test lint`, updates app metadata and the Fastlane changelog (`<versionCode>.txt`), commits, pushes the branch, and opens a PR. `versionCode` is `major*1000+minor` and must increase.
2. Review the release PR, let CI pass, and merge into main.
3. Verify the fetched main tip has the intended `versionName`, then run the exact tag command printed by the script. The tag must be `v<version>` on a commit reachable from main. The script itself never tags or publishes a release.
4. The tag workflow validates the version and ancestry, runs tests and lint, signs the APK, and creates one **draft prerelease**. It fails if a release already exists for the tag, including a draft, so reruns cannot create duplicate candidates. The assets are `cassiopeia-watch-<version>.apk`, its `.sha256` checksum, `signer-sha256.txt`, `NOTICE`, `LICENSE`, and `THIRD_PARTY_NOTICES.md` when present.
5. Download the draft APK, verify its checksum and signer, and test it on devices before publishing. Freeze the approved commit, APK checksum, and signer; changes require repeating the affected checks. Publish the draft after approval.

Before tagging, configure repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`, plus repository variable `EXPECTED_SIGNER_SHA256` containing the trusted signing certificate's SHA-256 fingerprint (hex, optionally colon-separated). Keep the key and an encrypted, test-restored backup outside git. If the account plan allows it after publication, create a protected environment named `signing`, move the four signing secrets from repository scope into it, and add `environment: signing` to the `release` job. Creating the environment alone changes nothing.

## Recovering from a failed run

- **`release.sh` stopped partway.** It prints the commands to undo the local branch. Nothing is published by the script, so starting again is always safe.
- **The tag workflow failed before the draft was created.** Fix the cause and re-run the same workflow run. Do not move the tag.
- **The tag workflow failed after the draft was created** (for example an asset upload error). Re-runs refuse to touch an existing release. Inspect the draft; if it is the incomplete, unapproved candidate, delete only that draft (keep the tag) and re-run the same workflow run. Never delete a draft that has been tested and approved, and never delete a published release.
- **The tag points at the wrong commit.** Do not move it. Delete the draft if one exists, delete the tag, prepare the next version, and tag again. The workflow refuses to release a tag that changed while it was running.
