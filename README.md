# Pocket Toolkit

Android handheld utility toolkit for ROM duplicate detection, storage analysis, device diagnostics, and offline ROM patching.

## Initial scope

- **Duplicate Finder** — scans user-selected storage trees for exact duplicate ROMs, ISOs, CHDs, disc images, archives, and related game files using streaming SHA-256 hashes. Nothing is deleted automatically.
- **Storage Analyzer** — summarizes selected storage by platform/file type and highlights the largest files and reclaimable duplicate space.
- **Handheld Diagnostics** — reports Android/device, CPU ABI, memory, display, battery, storage, OpenGL ES, and Vulkan capability information exposed by Android.
- **ROM Patcher** — applies IPS, BPS, and UPS patches entirely offline while preserving the source ROM by default.

Pocket Toolkit is designed for Android gaming handhelds such as the Retroid Pocket series. It uses Android's Storage Access Framework rather than requiring root or broad unrestricted storage access.

## Status

Early development.
