#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: ${0##*/} [--publish]"
  echo "useJarShaded=1 reuses the shaded JAR already built in this checkout."
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

case "${useJarShaded:-0}" in
  0)
    # Use the caller's UID and GID for generated files in the checkout.
    docker run --rm --user "$(id -u):$(id -g)" \
      --env HOME=/tmp --env MAVEN_CONFIG=/tmp/.m2 --env MAVEN_OPTS=-Duser.home=/tmp \
      --mount "type=bind,source=$repo_dir,target=/workspace" --workdir /workspace \
      maven:3-eclipse-temurin-25 bash -euc '
        mvn -f client/pom.xml frontend:install-node-and-npm@install-node-and-npm
        export PATH="$PWD/client/target/frontend/node:$PATH"
        mvn -pl otp-shaded -am package -DskipTests
      '
    ;;
  1)
    if [[ ! -s "$repo_dir/otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar" ]]; then
      echo "Missing otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar. Build the checkout first." >&2
      exit 1
    fi
    ;;
  *)
    echo "useJarShaded must be 0 (build) or 1 (reuse)." >&2
    exit 2
    ;;
esac

docker build --file "$script_dir/Containerfile" --build-arg useJarShaded=1 --tag "$image" "$repo_dir"
if [[ ${1:-} == --publish ]]; then
  docker push "$image"
fi
