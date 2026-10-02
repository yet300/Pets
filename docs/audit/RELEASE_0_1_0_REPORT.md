# Pets KMP 0.1.0 Maven Central Release Report

## Status

**RELEASED**

The four `io.github.yet300.pets` 0.1.0 coordinates are published and resolve
from Maven Central. A clean Maven Central-only JVM consumer resolved
`pets-core:0.1.0` and compiled successfully.

## Source and GitHub release

- Version: `0.1.0`
- Source tag: `v0.1.0`
- Source SHA used for both publication attempts:
  `8290610dee0ad59b453046a5681fab266be365de`
- Existing GitHub Release: <https://github.com/yet300/Pets/releases/tag/v0.1.0>
- The tag was not moved or recreated; no new GitHub Release was created.
- The final publication used the exact tag checkout. A temporary Gradle init
  script changed publication metadata in memory to the user-selected group
  `io.github.yet300.pets`; source files and the tag were unchanged.

## Signing and local configuration

Only credential presence was inspected; no credential values or Gradle
properties contents were printed.

- Maven Central username/password: available
- In-memory private signing key: available and parsed successfully by Gradle
- Signing passphrase: available
- The local GPG keyring had no secret key; the configured in-memory key was
  sufficient for signing.
- The original Actions failure (`36986213799`) was caused by mapping an empty
  optional `SIGNING_KEY_ID` to `signingInMemoryKeyId`. The workflow fix removed
  that mapping and was pushed to `main` in
  `20f471950733a587df5d0f54c1dde8ac899fc4de`.
- No GitHub Actions publication was used.

## Preflight

- `:pets-compose:signAndroidPublication --no-configuration-cache` succeeded,
  including with the final group override.
- All four modules' `publishToMavenLocal` tasks succeeded from the release tag.
- Signing succeeded for all configured KMP, JVM, Android, iOS Arm64, and iOS
  Simulator Arm64 publications. Maven Local contained 116 non-empty `.asc`
  files after the final preflight.
- The generated POMs used `io.github.yet300.pets` for the four roots and for
  inter-module dependencies.

## Publication attempts

The first attempt used the original group and failed validation:

- Deployment ID: `f5ffc2ce-f5a3-46e0-98b1-88b3a0ffce86`
- State: `FAILED`
- Central validation error: `Namespace 'com.yet.pets' is not allowed`.
- Direct POM URLs for the original group returned 404; no original-group
  coordinates were published.

After the user selected `io.github.yet300.pets`, the four modules were
automatically published from the unchanged tag using:

```sh
./gradlew -I /private/tmp/pets-central-group-override.init.gradle \
  :pets-core:publishAndReleaseToMavenCentral \
  :pets-io:publishAndReleaseToMavenCentral \
  :pets-compose:publishAndReleaseToMavenCentral \
  :pets-host:publishAndReleaseToMavenCentral \
  --no-configuration-cache
```

- Central deployment ID: `13550b03-cef8-47f1-b102-1c58d252cf74`
- Central terminal state: `PUBLISHED`
- Validation errors: none
- Included modules: `pets-core`, `pets-io`, `pets-compose`, `pets-host`
- Example modules and `pets-apple` were not published.

The external init script used for the in-memory metadata override:

```groovy
import org.gradle.api.publish.maven.MavenPublication

def petsCentralGroup = 'io.github.yet300.pets'
def petsCentralModules = [':pets-core', ':pets-io', ':pets-compose', ':pets-host']

gradle.projectsEvaluated {
  petsCentralModules.each { path ->
    def target = gradle.rootProject.findProject(path)
    if (target == null) throw new GradleException("Missing expected project: ${path}")
    target.group = petsCentralGroup
    def publishing = target.extensions.findByName('publishing')
    if (publishing == null) throw new GradleException("Missing publishing extension: ${path}")
    publishing.publications.withType(MavenPublication).all { publication ->
      publication.groupId = petsCentralGroup
    }
  }
}
```

## Public coordinates and consumer check

Direct Maven Central repository POM URLs returned HTTP 200 for all four:

- `io.github.yet300.pets:pets-core:0.1.0`
- `io.github.yet300.pets:pets-io:0.1.0`
- `io.github.yet300.pets:pets-compose:0.1.0`
- `io.github.yet300.pets:pets-host:0.1.0`

A temporary project outside the repository used `mavenCentral()` as its only
dependency repository, resolved `io.github.yet300.pets:pets-core:0.1.0`,
compiled a Kotlin source file importing `com.yet.pets.core.PetAnimations`, and
finished with `BUILD SUCCESSFUL`. The temporary consumer was removed after the
check.
