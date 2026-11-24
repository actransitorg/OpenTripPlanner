#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: ${0##*/} [--publish]"
  echo "IMAGE sets the image tag (default: otp-traffic-511:local)."
  echo "To publish, set IMAGE to a repository and tag, e.g. registry.example.org/transit/otp:traffic-511."
}

if (( $# > 1 )); then
  usage >&2
  exit 2
fi

image=${IMAGE:-otp-traffic-511:local}
case "${1:-}" in
  "") ;;
  -h|--help) usage; exit 0 ;;
  --publish)
    if [[ $image != */* || $image == *@* || ${image##*/} != ?*:?* ]]; then
      echo "Set IMAGE to a repository and tag before publishing." >&2
      exit 2
    fi
    ;;
  *) usage >&2; exit 2 ;;
esac

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_dir=$(cd -- "$script_dir/../.." && pwd -P)

# Use the caller's UID and GID for generated files in the checkout.
docker run --rm --user "$(id -u):$(id -g)" \
  --env HOME=/tmp --env MAVEN_CONFIG=/tmp/.m2 --env MAVEN_OPTS=-Duser.home=/tmp \
  --mount "type=bind,source=$repo_dir,target=/workspace" --workdir /workspace \
  maven:3-eclipse-temurin-25 bash -euc '
    mvn -f client/pom.xml frontend:install-node-and-npm@install-node-and-npm
    export PATH="$PWD/client/target/frontend/node:$PATH"
    mvn -pl otp-shaded -am package -DskipTests
  '

docker build --file "$script_dir/Containerfile" --tag "$image" "$repo_dir/otp-shaded/target"
if [[ ${1:-} == --publish ]]; then
  docker push "$image"
fi
