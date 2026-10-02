# Pets KMP 0.1.0 Maven Central Publication Repair

## Current status

**BLOCKED — SIGNING CONFIGURATION**

The Maven publication has not been retried. GitHub Actions run `36986213799`
shows that the Maven Central credentials, signing key, signing passphrase, and
key ID environment variables were all empty. Repository Actions secret names
could not be queried with the available GitHub connection, and the local GitHub
CLI authentication is invalid. No secret values were inspected.

## Release identity

- Version: `0.1.0` (unchanged)
- Tag: `v0.1.0`
- Tag source SHA: `8290610dee0ad59b453046a5681fab266be365de`
- Existing public GitHub Release: <https://github.com/yet300/Pets/releases/tag/v0.1.0>
- The failed run checked out `refs/tags/v0.1.0` at the same SHA.
- No Maven Central upload occurred in the failed run: Gradle stopped at the
  first signing task, `:pets-compose:signAndroidPublication`.

## Failure and repair

- Failed run: `36986213799`
- Task: `:pets-compose:signAndroidPublication`
- Exception: the task failed while Gradle evaluated
  `signatory.keyId`; the log does not include a concrete nested exception class.
- Exact non-secret error: `The key ID must be in a valid form (eg 00B5050F or
  0x00B5050F), given value:` (empty value).
- Workflow cause: it unconditionally mapped
  `secrets.SIGNING_KEY_ID` to
  `ORG_GRADLE_PROJECT_signingInMemoryKeyId`; Actions supplied an empty value.
- Configuration evidence: the four publishing modules call `signAllPublications()`;
  no project Gradle configuration requires an explicit key ID.
- Workflow repair: removed the optional `ORG_GRADLE_PROJECT_signingInMemoryKeyId`
  mapping. The in-memory key, optional password, and Maven credentials continue
  to use the existing mappings (`GPG_KEY_CONTENTS`, `SIGNING_PASSWORD`,
  `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`). No key ID was fabricated.

## Secret checks

The GitHub Actions job log explicitly showed the mapped variables as empty for
the failed run. This is evidence about that run, not a live inventory of current
repository secret names or values. The available GitHub API integration does
not support the Actions secrets endpoint, and local `gh auth status` reports an
invalid token. Therefore current secret presence, key armor/parsing, passphrase
validity, Central user-token validity, and namespace ownership remain
unverified.

Required secret names under the current workflow mapping:

- `MAVEN_CENTRAL_USERNAME` — empty in failed run; current status unknown
- `MAVEN_CENTRAL_PASSWORD` — empty in failed run; current status unknown
- `GPG_KEY_CONTENTS` — empty in failed run; current status unknown
- `SIGNING_PASSWORD` — empty in failed run; current status unknown; optional if
  the private key has no passphrase
- `SIGNING_KEY_ID` — no longer required or referenced

## Preflight and publication

- Signing preflight: not run; the job log indicates its required credentials
  were unavailable, and no local signing key was supplied.
- `.asc` signatures: not verified/generated.
- Successful publish run ID: none.
- Maven Central deployment ID/state: none; no deployment is known to have been
  created by the failed run.
- Coordinates (`com.yet.pets:pets-core`, `pets-io`, `pets-compose`,
  `pets-host:0.1.0`): public resolution not verified.
- Maven Central only external consumer: not run.
- Existing GitHub Release remains `v0.1.0` at the source SHA above. It was not
  recreated or moved.

## Remaining P2

Restore authenticated GitHub access or inspect repository Actions settings to
confirm/configure the current workflow's secret names. Configure the Maven
Central Portal user token credentials and the complete ASCII-armored private
signing key (plus its passphrase if applicable). Then run signing-only tasks for
all four modules/publications against the exact `v0.1.0` source SHA, verify
`.asc` files, and only then retry publication. Verify the four public
coordinates and a clean `mavenCentral()` only JVM consumer build before marking
the release complete.
