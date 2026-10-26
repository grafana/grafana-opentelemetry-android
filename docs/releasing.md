# Releasing the Android package

Release automation is prepared but not enabled. No version has been published from this repository.
Do not enable it until publishing access, signing ownership and the first release gates are agreed.

## Validate without publishing

With the normal JDK 17 / Android SDK setup, Python 3 and GnuPG installed:

```sh
bash .github/scripts/validate-release.sh
```

This runs library tests, lint, API checks and both library builds, signs the local Maven publication
with a temporary test key, verifies its signatures, checksums and metadata, and builds/lints the
Grafana and upstream-only consumers. It never calls a Central task. The test key is deleted on exit;
these signatures are not release signatures. Ordinary local publication remains available without a key.

The same check runs in pull requests. The manual **Validate or stage Android release** workflow
also defaults to this mode and saves the local publication as a seven-day workflow artifact.
The consumer reads `version.txt`, so it checks the version being released. Only the locally built
Grafana artifact is excluded from dependency locks; third-party dependencies stay locked.

## Configure access first

- Give `mobile-o11y-engineers` the agreed repository access.
- Confirm who owns the Central Portal namespace covering `com.grafana.opentelemetry`, and obtain a
  Portal user token authorized to publish `com.grafana.opentelemetry:android`.
- Confirm the release-signing key owner, backup/rotation procedure and public-key distribution.
- Configure a GitHub App through Grafana's token broker for `release-please.yml`, with repository
  contents, pull-request and issue write permissions. Set `RELEASE_GITHUB_APP` to that app's name.
  Limit token issuance to that workflow on `main`.
  A default `GITHUB_TOKEN` is not a substitute: its release PRs would not trigger validation.
- Create the `maven-central` environment with required reviewers, prevent self-review, and allow
  only release tags. An environment name in a workflow does not create an approval policy.
- Provision and authorize the repository-scoped Vault mapping used by `release.yml`:
  `maven-central:username`, `password`, `signing-key` and `signing-password`. These are proposed
  paths, not existing credentials. The username/password must be a Portal token pair; the key is
  ASCII-armored PGP material. Keep credentials out of tickets, source files and build logs.
- Have the credential owner verify that Vault access is restricted to the tagged `release.yml`
  job in the protected `maven-central` environment. Repository-wide access alone is not enough:
  another workflow with OIDC access could bypass this workflow's approval. If the approved secret
  delivery cannot enforce that boundary, keep staging disabled until it can.

Only after that setup has been reviewed, set the repository variable `RELEASE_AUTOMATION_ENABLED=true`
to enable Release Please. Set the repository variable `MAVEN_CENTRAL_ENABLED=true` separately when
staging is approved. These must not be environment-only variables: validation reads them before
the environment approval. Both are disabled
when unset. No workflow in this change automatically publishes to Maven Central.

## Prepare and stage a version

1. Agree the first version, then include a one-off `Release-As: X.Y.Z` footer with that version in
   a merged commit message. Release Please's default bump preserves the `-dev` suffix; it must not
   choose the first stable release for us. The current `0.0.0-dev` is only a bootstrap value.
   Release Please prepares `version.txt`, the changelog and manifest on a release PR. Review all
   three before merging. Subsequent stable releases use normal conventional-commit bumps.
2. Review the release PR, its validation and the exact pinned OTel version. After merge, Release
   Please creates a `vX.Y.Z` tag and GitHub release. This does not publish a Maven package.
3. Run **Validate or stage Android release** on that tag with staging left off. Inspect the result.
4. Once release gates are satisfied, run the same workflow on the same tag with staging enabled.
   It rejects forks, branch refs, development/prerelease versions, mismatched or moved tags, and
   commits not on `main`. Validation runs before the `maven-central` approval. After approval,
   a fresh job rebuilds the same commit with the real signing key and uploads it to Central.
5. Inspect the deployment and validation status in Central Portal. An authorized publisher must
   explicitly publish it there. The workflow stops after upload; success is not proof that the
   package is available. If upload fails, inspect Portal before retrying, and drop any unwanted
   pending deployment rather than creating duplicates. Never overwrite or move a released tag.
6. Install that exact remote coordinate in an independent app and complete the agreed runtime,
   hosted ingest, connected-trace and removal checks. Local publication does not cover these gates.

The Maven publication includes an AAR, sources, POM, Gradle metadata and a documentation JAR.
The latter contains the module guide rather than generated Kotlin API documentation, which is
allowed by [Central's requirements](https://central.sonatype.org/publish/requirements/).
Central upload uses the [Gradle Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)
in manual-publishing mode. No snapshots or automatic releases are configured.
