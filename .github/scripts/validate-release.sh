#!/usr/bin/env bash
set -euo pipefail

python3 .github/scripts/test_release_ref.py

# Never use a developer's real key or Maven credentials for this check.
export GNUPGHOME
GNUPGHOME=$(mktemp -d)
trap 'gpgconf --kill gpg-agent; rm -rf "$GNUPGHOME"' EXIT
chmod 700 "$GNUPGHOME"
unset ORG_GRADLE_PROJECT_mavenCentralUsername ORG_GRADLE_PROJECT_mavenCentralPassword
unset ORG_GRADLE_PROJECT_signingInMemoryKeyId
gpg --batch --pinentry-mode loopback --passphrase '' --quick-generate-key \
  'Local release validation <release-validation@example.invalid>' rsa2048 sign 1d
export ORG_GRADLE_PROJECT_signingInMemoryKey ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
ORG_GRADLE_PROJECT_signingInMemoryKey=$(gpg --batch --armor --export-secret-keys)
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=''

./gradlew --no-daemon --no-configuration-cache -PpublishToCentral=false -PsignPublication=true \
  clean :android:check :android:assembleDebug :android:assembleRelease \
  :android:publishReleasePublicationToTestRepository
python3 .github/scripts/verify-publication.py
./gradlew --no-daemon -p consumer \
  assembleGrafanaDebug assembleGrafanaRelease assembleUpstreamDebug assembleUpstreamRelease \
  checkRemovalDependencies lintGrafanaDebug lintUpstreamDebug
