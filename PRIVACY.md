# Privacy and local data in Galaxy Compressor

Updated November 14, 2026.

Galaxy Compressor processes selected videos on your device using Android media APIs. You choose which inputs to open or share to the app and whether to keep copies, export a file or replace an original.

## What is stored

- The app stores settings, device-specific encoder learning and local records of compression runs. Diagnostics can include device and codec details, file fingerprints, hashes, timestamps, sampled quality scores and results. Optional capture settings can retain traces and encoded candidates.
- When replacement is enabled, recovery data can include an intact copy of the original. If a result says an original is at risk, follow the recovery message and keep the app’s private storage intact until you have recovered the file.
- Android’s backup and transfer rules exclude diagnostics, rollback copies, device-test files, and learned encoder profiles and their journal. Other app settings can follow the device’s backup settings. [Backup rules](app/src/main/res/xml/data_extraction_rules.xml)

## What you can share

You can export outputs to the gallery, save a diagnostics ZIP in Downloads, or share an export through an app you choose. A log-only export can still identify a file by its hash. Check an export’s contents before sharing it publicly. Files you export to shared storage are subject to the settings of the gallery or backup apps you use.

The fork and its release documentation are maintained here. [Galaxy Compressor on GitHub](https://github.com/Zfkirke0109/Compressor)
