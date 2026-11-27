# Mi_Max Backup Format

## Scope

Mi_Max backups contain application-owned preferences only:

- Me Island style and visual settings, Focus Mode, timer and UI preferences.
- Mi_Max stacked-folder relationships.
- Saved profile slots 1–3.
- Installed declarative MXE extension manifests and permission grants.
- The theme recovery snapshot used by the extension manager.

The backup does **not** contain Lawnchair's workspace/database, app shortcuts, installed APKs, live AppWidget IDs or provider data, system permissions, or Android account data. The app does not have a supported way to restore those items using this format.

## Version 2

A UTF-8 JSON document with:

- `format`: `MIMAX_BACKUP`
- `version`: `2`
- `package`: exact Android package name
- `createdAt`: Unix epoch milliseconds
- `payload`: serialized JSON object containing typed SharedPreferences entries
- `sha256`: lowercase SHA-256 hex digest of the exact UTF-8 bytes in `payload`

The restore path verifies the digest, package name, format, schema version, input size, preference keys, and primitive value types before changing any preference file. If a preference write fails, the manager attempts to restore the captured pre-import values. After a successful restore, the daily alarm is synchronized with the restored auto-backup preference. Transient last-success and last-error status fields are not exported.

SHA-256 detects accidental corruption; it is not a digital signature and does not prove who created a backup.

## Legacy version 1

The importer continues to accept the earlier `preferences` object format. Legacy backups did not contain the saved profile slots or extension registry; missing legacy files are left unchanged.

## Automatic copies

When enabled, Android schedules an inexact daily alarm. The receiver writes a copy atomically into the app-private `files/mimax-automatic-backups` directory and keeps the newest seven JSON backups. The schedule is restored after `BOOT_COMPLETED`. Timing may be delayed by Android power-management policies.
