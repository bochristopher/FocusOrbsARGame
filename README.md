# Focus Orbs AR Game

A head-aiming AR mini-game designed for monochrome (green-only) AR glasses like Rokid.

## 🎮 Game Concept

**Focus Orbs** is a simple, addictive game where players use head movements to aim at floating orbs:

1. A **reticle** (crosshair) is fixed at the center of the screen
2. An **orb** appears at a random position
3. The player moves their head to align the orb with the reticle
4. Hold focus on the orb for ~0.6 seconds to "pop" it
5. Score increases, new orb spawns — repeat!

## 📱 Screenshots

*Coming soon*

## 🔧 Technical Details

- **Language:** Kotlin
- **UI:** XML with ViewBinding (not Compose)
- **Min SDK:** 28
- **Target SDK:** 36
- **Orientation:** Portrait (configurable)

### Monochrome Design

The game is designed for AR glasses that only display **green** (monochrome). All visuals use:
- Black background (`#000000`)
- Green elements (`#00FF00`, `#00DD00`, `#00CC00`)
- Varying brightness, size, and stroke thickness for contrast

No color variety — only green shades on black.

## 🏗️ Project Structure

```
app/src/main/
├── java/com/example/focusorbsargame/
│   ├── FocusOrbsActivity.kt    # Main game activity with game loop
│   └── MainActivity.kt          # Original template activity
├── res/
│   ├── drawable/
│   │   ├── orb_circle.xml           # Green filled circle (the orb)
│   │   ├── reticle_circle.xml       # Hollow ring with center dot
│   │   ├── flash_circle.xml         # Pop animation flash effect
│   │   └── focus_progress_drawable.xml  # Progress bar styling
│   ├── layout/
│   │   └── activity_focus_orbs.xml  # Game UI layout
│   └── values/
│       └── themes.xml               # Dark fullscreen theme
└── AndroidManifest.xml
```

## 🎯 Current Features (Step 1)

- [x] Core game loop using `Choreographer` for 60fps updates
- [x] Orb spawns at random positions (away from center)
- [x] Orb drifts toward center (simulated aiming)
- [x] Focus timer with visual progress bar
- [x] Pop animation (scale + flash)
- [x] Score tracking
- [x] Fullscreen immersive mode
- [x] Screen stays on during gameplay

## 🚀 Roadmap

| Phase | Feature |
|-------|---------|
| Step 1 ✅ | Core game loop |
| Step 2 | Real head tracking / sensor input |
| Step 3 | Difficulty progression & levels |
| Step 4 | Sound effects & haptics |
| Step 5 | AI-generated level patterns |
| Step 6 | Daily quests & achievements |

## 🛠️ Building

1. Open in Android Studio
2. Sync Gradle
3. Run on device or emulator

```bash
./gradlew assembleDebug
```

## 📄 License

MIT License — feel free to use and modify.

---

*Built for Rokid AR glasses* 🥽

