# Android localization

`strings_localized.xml` files contain the iOS app's translations, with additional
Android copy in `android.json`. `aliases.json` maps Android wording to equivalent
iOS catalog entries. Run:

    python3 scripts/localization/import_ios.py ../MoneroOne/MoneroOne/Resources/Localizable.xcstrings

The importer generates native Android resources and a compile-time resource map.
`tr` resolves those resources using Android's per-app language, including from
callbacks and notifications. Pass whole English messages with `%s` placeholders;
do not translate wallet names, addresses, seeds, persisted keys, or log messages.
Language selection uses AppCompat locale persistence and Android 13 app languages.
See https://developer.android.com/guide/topics/resources/app-languages.
