#!/usr/bin/env bash
set -euo pipefail

[[ "${GITHUB_REPOSITORY:-}" == "grafana/grafana-opentelemetry-android" ]] || { echo 'Unexpected repository'; exit 1; }
[[ "${MAVEN_CENTRAL_ENABLED:-}" == true ]] || { echo 'Maven Central upload has not been enabled'; exit 1; }
version=$(tr -d '\r\n' < version.txt)
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || { echo 'Not a stable release version'; exit 1; }
[[ "${GITHUB_REF:-}" == "refs/tags/v${version}" ]] || { echo 'Select the tag matching version.txt'; exit 1; }
[[ "$(git rev-parse HEAD)" == "${GITHUB_SHA:-}" ]] || { echo 'Checkout does not match workflow commit'; exit 1; }
[[ "$(git rev-parse "${GITHUB_REF}^{commit}")" == "$GITHUB_SHA" ]] || { echo 'Release tag changed'; exit 1; }
git merge-base --is-ancestor HEAD origin/main || { echo 'Release commit is not on main'; exit 1; }
