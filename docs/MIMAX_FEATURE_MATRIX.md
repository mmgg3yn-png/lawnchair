# Mi_Max Launcher — Unified feature requirements

This fork retains Lawnchair as the native Android launcher foundation. The product target is one integrated release, not a sequence of public trial APKs.

## Required product areas

- Me Island: independent HIS / LTIN / ColorOS layouts, live activity cards, multi-event switching, gestures, signed declarative .mt1 themes, idle hiding, and event sources.
- Super Icons: resizable icon tiles, large folders, icon shape/scale controls, previews and drag/drop.
- Home screen: configurable dock, flexible folder grids, nested and stacked folders, page editor, grid sizing, gestures, app hiding and optional PIN gate.
- Motion: app launch/return and icon transitions with presets, speed and reduced-motion mode. System-wide transitions remain limited by public Android APIs and OEM behavior.
- Widgets: real AppWidgetHost widgets, resize/reorder, widget stacks and Smart Stack-like switching.
- Design profiles inspired by HyperOS, iOS, ColorOS, OxygenOS, OriginOS, One UI and Pixel, using original assets and implementation.
- Power tools: backup/restore, theme and icon studios, gesture studio, profiles, app library, search, focus mode, crash recovery and performance-friendly mode.
- Extension framework: signed declarative packages, explicit per-extension capabilities, compatibility/dependency validation, independent logs, automatic disable and last-known-good rollback, extension updates without APK updates, developer tools and isolated recovery UI.

## Platform constraints

- Upstream minimum is API 26; Android 14 is within the supported API range.
- ARM32 and ARM64 must be confirmed from the built APK's native libraries and CI output.
- Me Island requires user-granted notification-listener and display-over-other-apps access.
- Notification access does not grant unrestricted access to all app state, private media sessions, call controls or OEM-only events.
- A third-party launcher cannot replace privileged SystemUI or guarantee OEM app-opening animations.
- Widget hosting uses Android AppWidgetHost APIs and provider-supported resize/configuration behavior.

## Initial implementation

The first source slice adds a real notification-listener-backed Me Island overlay and native permission/style controls. Every feature area still requires connection to Lawnchair's settings, launcher model and persistence before it can be called complete.
