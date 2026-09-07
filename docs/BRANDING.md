# Relay Display branding

## The mark

Two rounded screens with a wedge between them: the phone you hold, relaying content to the
companion display.

```
   ┌──┐   ▶   ┌────┐
   │  │       │    │
   └──┘       │    │
              └────┘
  controller  display
```

The left form is smaller and squarer — a phone in your hand. The right is taller — the spare
screen doing the showing. The teal wedge is content in flight, and it points from the small form
to the large one, which is the whole product in one glyph.

Design constraints it was drawn against, all of which come from how launcher icons actually fail:

- **Three primitives only** — two rounded rectangles and a triangle. Nothing to lose at 48px.
- **No text or letters.** A logo containing type is unreadable at launcher size.
- **No gradients.** An 8-bit gradient bands visibly across 48px, and the launcher scales and
  parallaxes the background layer, which exaggerates it.
- **No thin lines, no strokes.** Filled shapes survive downscaling; 1px strokes do not.
- **Nothing QR-like or intricate**, which turns to noise at small sizes.
- **Real gaps between the three forms**, which is what makes the monochrome silhouette readable
  once every shape becomes one colour.

## Colours

| Role | Hex | Notes |
|---|---|---|
| Plate / adaptive background | `#0A2A4F` | Deep navy. Also `@color/relay_icon_plate` for the splash screen. |
| Screens | `#FFFFFF` | Maximum contrast on the navy in both light and dark launchers. |
| Relay wedge | `#6FF6FE` | `RelayTealLight` from the app's Material palette. |

The wedge colour is taken directly from the app theme (`ui/theme/Color.kt`), so the icon and the
in-app accent are the same teal rather than two similar blues that look like a mistake.

## Source of truth

`branding/generate_launcher_icons.py` holds the geometry. Everything else is generated from it:

| Generated | Purpose |
|---|---|
| `branding/relay-display-logo.svg` | Editable vector master |
| `app/src/main/res/drawable/ic_launcher_background.xml` | Adaptive background (flat navy) |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Adaptive foreground (the mark) |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Android 13+ themed icon |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive descriptor |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | Adaptive descriptor, round |
| `app/src/main/res/drawable/relay_display_mark.xml` | In-app mark, including the About screen |
| `app/src/main/res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher.png` | Legacy icons, API 23 to 25 |
| `app/src/main/res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher_round.png` | Legacy round icons, genuinely circular |

Do not hand-edit the generated files. Change the script and re-run it, or the copies disagree.

## Regenerating

```bash
python3 branding/generate_launcher_icons.py --check   # verify geometry only, write nothing
python3 branding/generate_launcher_icons.py           # rewrite every generated asset
```

**One command regenerates everything in the table above** — the SVG master, the three vector
drawables, the two adaptive descriptors, the in-app mark and the ten density PNGs. Generation is
deterministic: running it twice produces byte-identical files, so a re-run never shows up as a
spurious diff. When the generation was moved into the script it reproduced the previously committed
vectors and SVG exactly, every `pathData`, `fillColor` and `d` attribute unchanged, which is how
the move was checked.

This was previously not true, and the difference matters. The script only ever wrote the density
PNGs; the SVG and the vector drawables were produced by a separate ad-hoc snippet that was never
committed, while this document claimed the script generated them. Anyone who changed the geometry
and re-ran the script would have got PNGs that no longer matched the vectors, with nothing to
warn them. The generation now lives in the script, which is what makes "do not hand-edit" a rule
the tooling can actually keep.

`--check` writes nothing at all, including no `__pycache__`: the script sets
`sys.dont_write_bytecode` before any import, so verifying geometry cannot leave untracked files
behind.

No Pillow, cairosvg, ImageMagick or Inkscape is required. A PNG is a zlib stream plus a header,
and anti-aliasing is 4× supersampling — about eighty lines, against a dependency that would need
installing on every machine that ever touches the icons.

## Safe zone

Android's adaptive icon is a 108×108dp canvas of which only the centre **66dp circle** is
guaranteed visible; launchers mask the rest to a circle, squircle, rounded square or teardrop.

Every extreme point of the mark is inside that circle, and this is checked rather than asserted:

```
$ python3 branding/generate_launcher_icons.py --check
Safe zone OK: furthest point is 30.0 from centre, limit 33.0.
```

The check fails the script if a future geometry edit pushes a corner outside, so a mask cannot
silently clip the mark.

Legacy icons (API 23 to 25) are **not** masked by the launcher, so the script bakes the rounded
navy plate into those PNGs. Its 22dp corner radius sits inside the circle a launcher would apply
if it wants a round icon, so nothing is lost either way.

## Where the mark is used

| Place | How |
|---|---|
| Launcher icon | `android:icon` and `android:roundIcon`, unchanged resource names |
| Themed icon | `<monochrome>` in the adaptive descriptor, Android 13+ |
| Splash screen | Android 12+ builds one from `android:icon` automatically; `values-v31/themes.xml` only sets the plate colour behind it |
| About screen | A 40dp mark beside the app name |

Resource names are unchanged (`ic_launcher`, `ic_launcher_round`), so the manifest needed no edit
and nothing outside the drawables had to move.

## Originality

**This is original artwork made for this project.** It is constructed entirely from geometric
primitives defined in `branding/generate_launcher_icons.py` — no downloaded, purchased, traced,
AI-generated or third-party asset is involved, and no icon font or clip-art library is used.

It was drawn to avoid resembling existing casting marks. Chromecast is a rounded square with
concentric arcs; AirPlay is a triangle beneath an arc; Samsung Smart View and Microsoft Phone Link
both use overlapping-device motifs with quite different geometry. This mark is two separated
rounded rectangles of unequal size with a wedge between them, which is not any of those.

## The round icon is a separate asset

`ic_launcher_round.png` is rendered with a **circular** navy plate, not a copy of the square one.
`android:roundIcon` is displayed as supplied by the launchers that ask for it — nothing masks it
into a circle on the app's behalf — so shipping a copy of the square icon puts a square icon in a
round slot. Lint reported exactly that, as `IconLauncherShape` and `IconDuplicates`, and it was
right: the two files were byte-identical and neither was circular. Both findings are now clear.

This matters most on the Lenovo. `mipmap-anydpi-v26` only takes over from API 26, so on Android 7.0
these PNGs are what actually renders.

## What is not verified

The icons build, and the app installs and runs on both phones with them. Lint's icon checks pass.
**The launcher icon has not been visually inspected on either home screen** in this change. Worth
checking before release:

- the adaptive icon under the launcher's mask on the S22 (Android 16)
- the legacy square and round PNGs on the Lenovo (Android 7.0, API 24), which never uses the
  adaptive icon
- the themed/monochrome icon with a wallpaper-tinted theme on Android 13+

The in-app mark **is** verified on hardware: `FileTransferUiTest.aboutScreenRendersItsMark` renders
the About screen on a real device and passed on both phones. That test exists because the About
screen previously loaded `R.mipmap.ic_launcher`, which on API 26+ resolves to an `<adaptive-icon>`
that `painterResource` cannot inflate — so opening About threw. `relay_display_mark.xml` is a plain
vector with the plate baked in, which is why it renders anywhere.
