#!/usr/bin/env bash
#
# Builds the unsigned APK of the commit checked out here the way F-Droid builds it, in its own
# image: F-Droid publishes our signed APK only if its build comes out with the same bytes. Takes
# a fresh clone, so what is not committed is not in it. See README.md for what to do with it.
#
#   fdroid/build-release.sh [output directory]
#
# A build on any other machine than Linux x86_64 cannot match, see README.md.
set -euo pipefail

IMAGE=registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
output=${1:-$root/fdroid/out}
ndk=$(sed -n 's/^android-ndk = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
mkdir -p "$output"

docker run --rm --interactive --platform linux/amd64 \
    --volume "$root:/source:ro" \
    --volume "$output:/output" \
    --env ndk="$ndk" \
    "$IMAGE" bash -euo pipefail <<'IN_IMAGE'
source /etc/profile.d/bsenv.sh

# What the recipe's sudo and ndk fields ask for.
apt-get update -qq
apt-get install -y -qq cmake gcc libc6-dev make rustup > /dev/null
sdkmanager "ndk;$ndk" > /dev/null
chown -R vagrant "$ANDROID_HOME"

sudo --preserve-env=ANDROID_HOME,LC_ALL,CARGO_HOME --set-home --user vagrant bash -euo pipefail <<'AS_BUILD_USER'
git config --global --add safe.directory '*'
git clone --quiet /source ~/build/app
cd ~/build/app
rm -rf lib-duckcount
git clone --quiet /source/lib-duckcount lib-duckcount
git -C lib-duckcount checkout --quiet "$(git rev-parse HEAD:lib-duckcount)"

# What the recipe's prebuild and gradle fields ask for.
(cd lib-duckcount && rustup show active-toolchain)
cd composeApp
gradle -Pduckcount.fdroid assembleRelease --console=plain
AS_BUILD_USER

cd ~vagrant/build/app/composeApp
version=$(sed -n 's/.*versionName = "\(.*\)"$/\1/p' build.gradle.kts)
cp build/outputs/apk/release/composeApp-release-unsigned.apk "/output/DuckCount-$version-fdroid-unsigned.apk"
sha256sum "/output/DuckCount-$version-fdroid-unsigned.apk"
IN_IMAGE
