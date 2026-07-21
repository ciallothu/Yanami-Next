# Release integrity

Stable Android releases are signed with the Yanami Next release key and include SHA-256
checksums. The signing certificate SHA-256 fingerprint is:

```text
3D:79:E7:32:3B:04:82:91:58:43:F3:06:E3:F8:B5:F9:87:DE:06:CD:E9:A1:FF:0B:87:36:D8:C1:09:5B:CF:F1
```

Release tags must exactly match the root `VERSION`, point to the current `main` commit,
and pass the Android, iPhone, lint, unit-test, dependency-review, and CodeQL workflows.
`VERSION_CODE` uses `major * 1,000,000 + minor * 10,000 + patch * 100 + revision`.
The final two digits are a `00..99` rebuild revision for replacing artifacts without changing the
user-visible semantic version. The build and release workflows validate that the code remains in
the semantic version's reserved 100-build range. Publishing atomically creates a new draft only
when no release entry exists, verifies the exact six signed/checksummed assets, and makes the draft
public only after every server-side asset check succeeds.

Android update checks bind `docs/update.json` to the current stable release tag through the GitHub
Contents API. A revision is accepted only when its semantic version matches the tag and its build
code remains inside that tag's reserved 100-build range.
