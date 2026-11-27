# Mi_Max MT1 Theme Container

MT1 is a project-authored binary theme container for Mi_Max Launcher. It is not a renamed ZIP archive.

## Version 1 wire format

All integer fields use big-endian byte order.

| Offset | Size | Meaning |
|---:|---:|---|
| 0 | 8 | ASCII magic: `MIMAXMT1` |
| 8 | 2 | Unsigned format version: `1` |
| 10 | 4 | UTF-8 JSON payload length |
| 14 | 32 | SHA-256 digest of the payload bytes |
| 46 | variable | Exact UTF-8 JSON payload |

The payload is limited to 1 MiB. A decoder must reject an unknown magic, unsupported version, invalid length, trailing bytes, invalid JSON, invalid field ranges, or a digest mismatch.

## Version 1 payload

```json
{
  "format": "Mi_Max Theme",
  "name": "Midnight Blue",
  "style": "HIS",
  "accentColor": 4287374335,
  "backgroundColor": 4028110123,
  "cornerRadiusDp": 22,
  "widthDp": 320
}
```

- `style`: `HIS`, `LTIN`, or `COLOROS`.
- Colors are unsigned 32-bit ARGB values represented as JSON integers.
- `cornerRadiusDp`: 8–48.
- `widthDp`: 180–360.
- `name`: 1–64 characters.

The SHA-256 field detects accidental corruption; it is not a digital signature and does not prove who created a theme. MT1 v1 contains declarative visual settings only. It cannot contain executable code, scripts, APKs, or permissions. Future incompatible layouts must use a new version number rather than silently changing this layout.
