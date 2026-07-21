# Yanami-Next iPhone

Native SwiftUI iPhone app for Yanami Next.

## Scope

- Connects to Komari with password, API Key, or guest mode.
- Supports custom HTTP headers, including Cloudflare Access service token headers.
- Stores server profiles, credentials, custom headers, active instance, and refresh settings in Keychain.
- Tests the connection through `common:getVersion`.
- Loads node information, latest status, node detail, recent load data, load records, and ping records through Komari RPC.
- Auto-refreshes node status on the same cadence as the Android live node list.

## Build

```bash
VERSION=$(tr -d '[:space:]' < ../../VERSION)
BUILD_NUMBER=$(tr -d '[:space:]' < ../../VERSION_CODE)
xcodebuild \
  -project Yanami.xcodeproj \
  -scheme Yanami \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath ../../build/ios \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  DEVELOPMENT_TEAM="" \
  PROVISIONING_PROFILE_SPECIFIER="" \
  MARKETING_VERSION="$VERSION" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
  build
mkdir -p ../../build/ios-ipa/Payload
ditto ../../build/ios/Build/Products/Release-iphoneos/Yanami.app "../../build/ios-ipa/Payload/Yanami Next.app"
(cd ../../build/ios-ipa && ditto -c -k --sequesterRsrc --keepParent Payload "../Yanami-Next-v${VERSION}-unsigned.ipa")
```

The unsigned IPA is generated at `../../build/Yanami-Next-v<version>-unsigned.ipa`.

The IPA must be signed before device installation.

## Terminal dependency

The native iOS terminal uses [SwiftTerm 1.15.0](https://github.com/migueldeicaza/SwiftTerm/tree/1.15.0), pinned to revision `dd2fb8ac5b861e7bf617c872895e338f38165648` in both the Xcode project and `Package.resolved`.

The SwiftTerm MIT license is included in `Yanami/Resources/THIRD_PARTY_NOTICES.md` and copied into the application bundle.
