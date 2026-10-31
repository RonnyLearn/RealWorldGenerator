# Real World Generator — a Minecraft 26.3 mod (Fabric)

A **Real World** button in the main menu. Behind it:

- **Library** of your generated worlds: cards with an aerial photo of the area, place, date, size,
  disk usage and setting badges. Search, rename, delete, open folder.
- **Area picker map** (OpenStreetMap / terrain / satellite): search for a city or address with a smooth
  flight to it, select an area with the right mouse button (or Shift + left), resize handles, move the
  frame, chunk grid, scale bar, spawn point, quick 500 m / 1 km / 2 km squares. World settings and an
  estimate of how long generation will take are on the side.
- **In-game generation**: the world is built by [Arnis](https://github.com/louis-e/arnis), embedded in
  the mod as a native library. No separate program or process.
- **Generation screen**: an isometric map of the area on which the real city appears as it is built.
  Large areas are built in pieces; a stopped generation can be finished later. Stage list, progress,
  time left and a stop button alongside.
- **Entering the world**: where (spawn, where you left, or any point on the area map) and how (game mode,
  time of day, including "as it is there now" from the local sun, weather, cinematic camera fly-in,
  chunk preloading). During the fly-in the mod takes an aerial photo for the library and the world icon.

## Layout

```
RealWorld/
├─ mod/                      mod sources (Fabric, Java 25)
│  └─ src/main/java/net/ronnylearn/realworld/
│     ├─ RealWorldMod.java    entry point, events
│     ├─ RealWorldConfig.java remembered map position and settings
│     ├─ arnis/               bridge to Arnis (Java FFM) and the generation task
│     ├─ geo/                 coordinates and Web Mercator projection
│     ├─ map/                 tiles, place search, map widget, area snapshot
│     ├─ screen/              screens: library, map, generation, entry, rename
│     ├─ world/               world metadata, library, entry plan, camera fly-in and photo
│     ├─ mixin/               camera (fly-in) and a hidden window for the self-test
│     └─ dev/SelfTest.java    self-test (only runs when rw-selftest.flag exists)
├─ native/arnis/             Arnis 3.3.0 + the embedding wrapper
│  ├─ src/lib.rs              library instead of a program
│  ├─ src/embedded.rs         generation without leaving the process + functions for Java
│  └─ src/embedded_progress.rs progress for the game instead of the Tauri window
└─ tools/
   ├─ build-native.ps1        build arnis_native.dll into the mod's resources
   └─ selftest.ps1            hidden background self-test (muted, low priority)
```

## Building

1. `tools\build-native.ps1` builds `arnis_native.dll` (needs Rust and MSVC Build Tools; the script
   expects Rust in `D:\Tools\rust`). About 10 minutes the first time. The built library is not
   stored in the repository.
2. In `mod\` run `gradlew build`. The mod: `mod\build\libs\real-world-generator-1.0.0.jar`.

Requires Minecraft 26.3, Fabric Loader ≥ 0.19.3, Fabric API and Windows x64 (the Arnis library is
built for Windows).

## Licenses and data

- Real World Generator © RonnyLearn, All Rights Reserved, see `LICENSE`. Copying, modifying or
  redistributing the mod or its code without the author's permission is not allowed.
- Includes Arnis © Louis Erbkamm (Apache License 2.0, with changes); the `native/arnis` directory stays
  under that license. Arnis's `LICENSE` and `NOTICE` ship inside the jar (`META-INF/licenses/arnis`).
  Changes to Arnis code are marked with a "Real World mod" comment.
- Map data © OpenStreetMap contributors (ODbL). Terrain map © OpenTopoMap (CC-BY-SA).
  Satellite imagery © Esri, Maxar, Earthstar Geographics. Place search: Nominatim (at most one
  request per second).
