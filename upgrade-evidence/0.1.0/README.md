# Android 0.1.0 upgrade evidence

[`assertions.json`](./assertions.json) is the committed, provider-neutral
contract for replacement-install verification. The same fixture sources build
against the published `0.1.0` SDK and the candidate project by setting
`eluUpgradeDependency` to `published` or `candidate`.

CI runs separate clean-device checks for anonymous and identified identity,
replaces each published app with the candidate using the same application ID
and signing key, and executes the continuity assertions on an emulator.

The normalized result and its SHA-256 digest are written under
`build/reports/upgrade-evidence/0.1.0/` and uploaded. Raw `adb` and
instrumentation output stays in the runner's ignored build directory; only
its aggregate digest is included in the normalized manifest.

Each build also emits a normalized dependency-selection report. The published
report verifies the resolved `0.1.0` AAR digest, while the candidate report
requires the `:elu-analytics` project component. Both reports bind their
selection to the fixture APK digest and are carried inside the final manifest.
