# The Android SDK this app builds against (compileSdk 34, AGP 8.2.2), for NixOS.
#   nix-build android/android-sdk.nix -o ~/.local/share/android-sdk
# then point android/local.properties at it:
#   sdk.dir=/home/<you>/.local/share/android-sdk/libexec/android-sdk
# Building it accepts the Android SDK licence. AGP's own aapt2 needs programs.nix-ld.
{ pkgs ? import <nixpkgs> { config = { allowUnfree = true; android_sdk.accept_license = true; }; } }:
(pkgs.androidenv.composeAndroidPackages {
  platformVersions = [ "34" ];
  buildToolsVersions = [ "34.0.0" ];
  includeEmulator = false;
  includeSystemImages = false;
  includeNDK = false;
}).androidsdk
