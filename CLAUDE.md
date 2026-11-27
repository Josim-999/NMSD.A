# Working on Video Studio (android/)

- **Never drop a feature.** Read `android/FEATURES.md` before changing anything. Every update keeps all
  earlier features and adds the new request. `FeatureGuardTest` must pass; if a ledger item truly has
  to change, ask the owner first (AskUserQuestion with options), then change the ledger and the test together.
- When a request is unclear, ask with clickable options; otherwise keep the old behaviour and add the new one.
- After each change: add the new feature to `FEATURES.md` and `FeatureGuardTest`, bump `versionName`,
  run `cd android && CENTRAL_MIRROR=1 /opt/gradle/bin/gradle -q :app:testDebugUnitTest :app:assembleRelease`,
  check the signer SHA-256 is `2b18af1117d2e064e5c52e4c4272cb9471658ec4ea3804a4e46432c4f60d6c14`, and send the APK.
- Never add accessibility, SMS or notification-listener permissions.
