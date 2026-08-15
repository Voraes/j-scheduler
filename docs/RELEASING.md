# Release preparation

The build prepares `io.github.voraes:j-scheduler` and
`io.github.voraes:j-scheduler-spring-boot-starter` as Maven publications. It does not publish to an
external repository or create credentials automatically.

## Local verification

```bash
./gradlew clean check stressTest
./gradlew publish
```

`publish` writes both publications to `build/repository`. Inspect the generated POMs and verify that
each module includes its primary JAR, sources JAR, and Javadoc JAR.

## Maven Central prerequisites

Before an authorized release:

1. Verify the `io.github.voraes` namespace in the Central Publisher Portal.
2. Select a non-snapshot version and update `CHANGELOG.md`.
3. Generate a protected PGP key and publish its public key.
4. Supply the private key through `SIGNING_KEY` and its passphrase through `SIGNING_PASSWORD`, or use
   the equivalent Gradle properties. Never commit either value.
5. Build the signed publications and upload them through an approved Central Publisher Portal Gradle
   integration or bundle workflow.
6. Validate coordinates, POM metadata, signatures, checksums, sources, and Javadocs in the portal.
7. Publish only after explicit maintainer authorization.

The repository intentionally stops short of configuring an external upload endpoint because no
credentials or publication action have been authorized.
