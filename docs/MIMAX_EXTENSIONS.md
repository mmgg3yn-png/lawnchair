# Mi_Max Declarative Extensions (MXE)

## Safety model

MXE version 1 is a data-only extension format. It never loads DEX, JAR, scripts, reflection targets, native libraries, or remote code. The first supported extension type is a Me Island theme.

## Manifest

The file is UTF-8 JSON and uses:

- `format`: `MIMAX_EXTENSION`
- `schemaVersion`: `1`
- `id`: stable lowercase identifier matching `[a-z0-9][a-z0-9._-]{2,63}`
- `name`: display name, up to 64 characters
- `versionCode`: positive monotonically increasing integer
- `minLauncherVersionCode`: minimum compatible launcher version code
- `description`: optional short description
- `dependencies`: optional extension IDs; installation and activation require each dependency to be present and enabled
- `permissions`: currently only `theme.apply`
- `theme`: style, ARGB colors, corner radius (8–48 dp), and width (180–360 dp)

## Lifecycle

- Import installs a new ID or updates an existing ID only when the incoming version is greater.
- Extensions are data records; the user must explicitly enable them.
- Theme application requires the user to grant `theme.apply`.
- The manager prevents disabling a dependency while enabled dependents rely on it.
- An application failure records an error, disables the failed extension when possible, and restores the previous theme snapshot.
- A pending theme transaction is rolled back the next time Mi_Max settings opens after an interrupted process.
- The user can export the current theme as an MXE file and import it on another compatible installation.

## Current limits

The built-in catalog is local and ships with three starter theme packages. It is not a remote marketplace. MXE v1 does not support executable feature extensions, widget providers, gesture handlers, icon engines, or network downloads. Those require a separately designed and permission-reviewed API.
