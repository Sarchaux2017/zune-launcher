# Zune Launcher 

A minimalist, typography-driven Android launcher inspired by the Windows Phone / Zune HD interface, tailored for the Google Pixel Series 's OLED display.

## Architecture & Layout Binding

- **Root Container (`activity_main.xml`)**: Employs an edge-to-edge `FrameLayout` hosting the custom canvas layer, `ViewPager2`, and `PivotHeaderView`.
- **Dynamic Theming**: As lists are scrolled, rows intersect a "focus line" located 36% down the viewport. The nearest app's icon palette is extracted and dispatched to `ZuneOledWallpaperView.setGlowColor()`, fluidly re-tinting ambient glowing ribbons and typographic glyphs.
- **OLED Power Optimization**:
  - Unlit pixels remain pure `#000000`, drawing zero current on OLED panels.
  - Custom canvas loops are throttled at 30 fps via `Choreographer` callbacks (15 fps during OS Power Saver mode).
  - Allocation-free `onDraw()` avoids Android garbage collection pauses.
- **Android 14 (API 34+) Compliance**:
  - Includes `android.permission.QUERY_ALL_PACKAGES` to index installed apps.
  - Explicitly registers system broadcast receivers using `ContextCompat.RECEIVER_EXPORTED` for runtime package update events (`ACTION_PACKAGE_ADDED`, `ACTION_PACKAGE_REMOVED`, `ACTION_PACKAGE_CHANGED`).

## Project Setup

1. Open the project root in **Android Studio Ladybug (2024.2+)** or newer.
2. Ensure your `build.gradle.kts` specifies `compileSdk = 34` (or 35) and enables View Binding:
   ```kotlin
   android {
       buildFeatures {
           viewBinding = true
       }
   }
