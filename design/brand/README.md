# Boxlet parcel mark

The brand mark is a dimetric parcel: one consistent pair of ground-plane axes,
vertical side edges, two hinged lids, and a single sealing strip that folds over
the top onto the front face. Three opaque blue tones retain depth at small sizes.

- `boxlet-icon.svg` / `.png`: application tile on a pale background.
- `boxlet-mark.svg` / `.png`: transparent sealed parcel.
- `boxlet-open-box.svg` / `.png`: open state used in the selection card.
- `boxlet.ico`: Windows installer/application icon, including 16–256 px variants.
- `boxlet-brand-review.png`: local artwork sheet with open, closed, and small-icon views.

`parcel.json` is the source for the projection, dimensions, opening angle and palette.
Regenerate the vectors, shared Kotlin geometry and platform resources with:

```powershell
python scripts/generate_brand_assets.py
```

Icon surfaces never draw the parcel at its raw unit size. `parcel.json`'s `iconScale`
(0.76) shrinks the mark about its own bounding box and re-centres it on the tile, so
launcher, taskbar, installer and favicon art all keep the same ~27% margin instead of
sitting flush against the edge. The inset reaches the Compose tray/window icon through
`ParcelGeometry.IconScale` / `IconCenter`; notification and lock-screen glyphs bypass it
because their slot is already tight.

Requires Pillow. The script also updates Android adaptive/legacy/themed/notification
icons, Windows assets, and the web favicon. The Android and Compose desktop drawing
helpers consume `ParcelGeometry` so UI animation and the launcher share the same
geometry. Keep these helpers' corner radius and color interpolation in sync with
the exporter when changing rendering details.

Android adaptive foregrounds stay inside the safe zone. Android notification and
Windows lock-screen assets are separate monochrome marks. UI animation remains
finite: lids fold on selection changes and the parcel gives a short bounce.

The native Windows client embeds `parcel.json` directly and draws it with WinUI
vector paths in `windowsApp/ParcelView.cs`. Keep its folding duration (480 ms),
bounce and palette interpolation aligned with the Compose selection card.
Windows' animation preference disables both effects.

Native Windows uses the same Fluent icon paths as Compose. After updating the
Compose Fluent icon dependency, resolve its source JARs and run
`python scripts/export_windows_fluent_icons.py` (set `GRADLE_USER_HOME` if needed).
The exported JSON and upstream MIT license are checked into `windowsApp/Assets`.
