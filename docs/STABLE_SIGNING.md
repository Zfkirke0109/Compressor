# Stable Debug Signing

Phase 4 lets GitHub Actions PR APKs use the same debug signing key every time.

That means future APKs can install as updates instead of forcing uninstall/reinstall.

## GitHub Secrets needed

Add these under:

Settings -> Secrets and variables -> Actions -> New repository secret

Required secrets:

- COMPRESSOR_DEBUG_KEYSTORE_BASE64
- COMPRESSOR_DEBUG_KEYSTORE_PASSWORD
- COMPRESSOR_DEBUG_KEY_ALIAS
- COMPRESSOR_DEBUG_KEY_PASSWORD

## Generate a stable debug keystore later

Do this later in a safe local terminal. Do not commit the keystore.

    keytool -genkeypair -v -storetype JKS -keystore ci-debug.keystore -alias compressor-debug -keyalg RSA -keysize 2048 -validity 10000 -storepass "choose-a-private-password" -keypass "choose-a-private-password" -dname "CN=Compressor Debug,O=Zfkirke0109,C=US"

Encode it for GitHub Secrets:

    base64 -w 0 ci-debug.keystore

Put that output into:

    COMPRESSOR_DEBUG_KEYSTORE_BASE64

Use the same password for:

    COMPRESSOR_DEBUG_KEYSTORE_PASSWORD
    COMPRESSOR_DEBUG_KEY_PASSWORD

Use this alias unless changed:

    compressor-debug

## Safety

Never commit:

- ci-debug.keystore
- raw passwords
- decoded secret files

The repo .gitignore already excludes ci-debug.keystore.
