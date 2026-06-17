# Publishing the Zelo Spring Boot starter to Maven Central

The starter (`io.github.thgrcarvalho:zelo-spring-boot-starter`) is published to
Maven Central via the **Central Portal** (`central.sonatype.com`) using the
[vanniktech maven-publish plugin](https://github.com/vanniktech/gradle-maven-publish-plugin).
The build is already wired for it (sources + javadoc jars, full POM, signing).
This file is the operator runbook for the parts that need credentials.

## ⚠️ Prerequisite: the starter's only runtime dependency must be on Central first

The published POM declares one runtime dependency:

```
io.github.thgrcarvalho:pix-webhook-validator:0.1.0
```

That artifact currently lives **only in `mavenLocal`**, not on Maven Central.
If the starter is published as-is, an external consumer that adds
`implementation 'io.github.thgrcarvalho:zelo-spring-boot-starter:0.1.0'` will fail
to resolve `pix-webhook-validator`. So **before** the first Central release, pick one:

- **A. Publish `pix-webhook-validator` to Central first** (it is a separate repo).
  Preserves the dogfooded-library architecture. Apply this same runbook there,
  then release the starter.
- **B. Vendor the HMAC validation into the starter** — inline the small amount of
  signature-validation code and drop the external dependency, making the starter
  self-contained. One repo, one release.

> Note: this does **not** affect consuming the starter from `mavenLocal` (e.g. the
> Vitalio integration), where both artifacts are already present.

## One-time setup

1. **Central Portal account + namespace.** Sign in at <https://central.sonatype.com>
   and register the `io.github.thgrcarvalho` namespace. For an `io.github.<user>`
   namespace it is **auto-verified via GitHub** (no DNS TXT record) — you log in /
   link the matching GitHub account.
2. **User token.** Portal → *Account* → *Generate User Token* → gives a
   `username` / `password` pair (these are token credentials, not your login).
3. **PGP signing key.** Central requires every artifact signed.
   - Generate: `gpg --gen-key`
   - Publish the public key: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`
   - Export the private key, ASCII-armored, for in-memory signing:
     `gpg --armor --export-secret-keys <KEY_ID>`

## Credentials (never commit these)

Provide them as Gradle properties in `~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=<portal token username>
mavenCentralPassword=<portal token password>
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...armored key...\n-----END PGP PRIVATE KEY BLOCK-----
signingInMemoryKeyPassword=<key passphrase, if any>
```

…or as environment variables (CI), prefixing each with `ORG_GRADLE_PROJECT_`:

```
ORG_GRADLE_PROJECT_mavenCentralUsername
ORG_GRADLE_PROJECT_mavenCentralPassword
ORG_GRADLE_PROJECT_signingInMemoryKey
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

Signing only activates when `signingInMemoryKey` is present, so local
`./gradlew :zelo-spring-boot-starter:publishToMavenLocal` and keyless CI builds
keep working.

## Release

```bash
# Stage to the Portal (then review and release manually in the Portal UI):
./gradlew :zelo-spring-boot-starter:publishToMavenCentral

# …or upload and auto-release in one step:
./gradlew :zelo-spring-boot-starter:publishAndReleaseToMavenCentral
```

Notes:
- Central **rejects re-publishing the same version** — bump `version` in the root
  `build.gradle` for each release. `SNAPSHOT` versions are not accepted on the Portal.
- After a successful release it can take a little while to appear in search /
  be resolvable.
