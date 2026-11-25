#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: scripts/release-xcframework.sh <version>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/build/release"

cd "$root"
./gradlew :stackaudit-apple:assembleStackAuditKitReleaseXCFramework
rm -rf "$out"
mkdir -p "$out"
(cd stackaudit-apple/build/XCFrameworks/release && zip -qry "$out/StackAuditKit.xcframework.zip" StackAuditKit.xcframework)
checksum="$(swift package compute-checksum "$out/StackAuditKit.xcframework.zip")"

sed -i.bak -E \
  -e "s/^let releaseVersion = \".*\"/let releaseVersion = \"$version\"/" \
  -e "s/^let releaseChecksum = \".*\"/let releaseChecksum = \"$checksum\"/" \
  Package.swift
rm -f Package.swift.bak

echo "version=$version"
echo "checksum=$checksum"
