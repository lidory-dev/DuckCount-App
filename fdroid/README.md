# Publishing on F-Droid

F-Droid builds the app itself from the tagged commit, compares the result with the APK we signed, and publishes ours only if the two match byte for byte, signature aside. Users therefore get our signature, the same one as on any other channel, which is what the API's registration attestation checks (`ANDROID_APP_SIGNING_DIGESTS`), and F-Droid holds no key the API trusts.

`metadata/app.lidory.duckcount.yml` is the recipe, a draft until its placeholders are filled in. It goes to [fdroiddata](https://gitlab.com/fdroid/fdroiddata), not in here. F-Droid clones the public repositories, [lidory-dev/DuckCount-App](https://github.com/lidory-dev/DuckCount-App) and, through the submodule's relative URL, [lidory-dev/Lib-DuckCount](https://github.com/lidory-dev/Lib-DuckCount).

## A release

On Linux x86_64, or anywhere Docker can run an `amd64` image at a decent speed (see below):

```bash
git switch --detach v1.0.0
git submodule update --init
fdroid/build-release.sh
```

That leaves `fdroid/out/DuckCount-1.0.0-fdroid-unsigned.apk`. Sign it with `apksigner` from **build-tools 34**, since F-Droid cannot copy a signature made by 35 or later (tried with 36.1.0: it does not verify):

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner sign --ks release.jks --in fdroid/out/DuckCount-1.0.0-fdroid-unsigned.apk --out fdroid/out/DuckCount-1.0.0-fdroid.apk
```

Check it the way F-Droid will, with [apksigcopier](https://github.com/obfusk/apksigcopier), `apksigner` on the `PATH`:

```bash
apksigcopier compare fdroid/out/DuckCount-1.0.0-fdroid.apk --unsigned fdroid/out/DuckCount-1.0.0-fdroid-unsigned.apk
```

Attach the signed APK to the tag's release on GitHub as `DuckCount-1.0.0-fdroid.apk`, the URL in the recipe's `Binaries` field. Raise the API's `MIN_ANDROID_BUILD` only once F-Droid has published that version, or its users get the update screen with no update to install. F-Droid takes a day or two after the tag, and skips a version whose build does not match.

## Why not a build on a Mac

A build on a Mac is reproducible among Macs, and matches F-Droid's in everything but `liblib_duckcount.so`, all four of them. Cargo mixes the build machine's own architecture into the id of every crate that depends on something compiled for the build machine, a proc-macro for instance. 47 of the library's crates end up with other ids, so other symbols, in another order. The NDK's Mac and Linux compilers also sign `.comment` differently. Neither says anything about the code, but F-Droid compares bytes.

So the release has to be built on `x86_64-unknown-linux-gnu`. On Apple Silicon that means Docker under Rosetta, about half an hour a build: under QEMU, seven minutes were not enough to configure the project. Colima may ignore `--vz-rosetta` and leave QEMU in place, as it did on a Mac where macOS's own Rosetta was not installed (`arch -x86_64 /usr/bin/true` fails). `ps` inside a container shows which one runs. Rosetta hung once in five builds, `cargo` idle at "Downloading crates" with every thread in a kernel mutex wait; starting over was enough. A native Linux machine or CI runner has neither problem.

## What the bytes depend on

Moving any of these is fine, as long as a release is then checked with the commands above. The recipe is what F-Droid reads, so it has to say the same.

| What | Where | Also in |
|---|---|---|
| NDK | `android-ndk` in `gradle/libs.versions.toml` | recipe's `ndk` |
| Rust | `lib-duckcount/rust-toolchain.toml` | |
| Crates | `lib-duckcount/Cargo.lock`, taken with `--locked` | |
| JDK | 21, the image's default | |
| Build packages | `apt-get install` in `build-release.sh` | recipe's `sudo` |
| The F-Droid build itself | `-Pduckcount.fdroid` | recipe's `gradleprops` |

`-Pduckcount.fdroid` sends the update screen to F-Droid's page instead of Google Play's, and installs what generates the Kotlin bindings from its lock file. Nothing else differs. The embedded FCM distributor stays: it is free software with no Google code, but it hands pushes to Google's Firebase Cloud Messaging on a phone with Play Services and no distributor, hence `NonFreeNet` in the recipe. F-Droid's reviewers decide which anti-features apply.

To see what differs when two APKs do, [diffoscope](https://diffoscope.org) names the file and the section. F-Droid's own [notes on the usual causes](https://f-droid.org/docs/Reproducible_Builds/) are the next stop.
