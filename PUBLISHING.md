# Publishing the Zelo Spring Boot starter to Maven Central

The starter (`io.github.thgrcarvalho:zelo-spring-boot-starter`) is published to
Maven Central via the **Central Portal** (`central.sonatype.com`) using the
[vanniktech maven-publish plugin](https://github.com/vanniktech/gradle-maven-publish-plugin).
The build is already wired for it (sources + javadoc jars, full POM, signing).
This file is the operator runbook for the parts that need credentials.

## Dependency closure (all on Central)

The published POM declares one runtime dependency:

```
io.github.thgrcarvalho:pix-webhook-validator:0.1.0
```

This artifact **is already on Maven Central**
(`https://repo1.maven.org/maven2/io/github/thgrcarvalho/pix-webhook-validator/0.1.0/`),
so an external consumer adding
`implementation 'io.github.thgrcarvalho:zelo-spring-boot-starter:0.1.0'` resolves
cleanly. There is **no dependency blocker** for the starter release — only the
credentials/GPG setup below.

> The legacy `search.maven.org` solr index can report an artifact as missing even
> when it is present; verify against `repo1.maven.org` (the authoritative CDN).

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
