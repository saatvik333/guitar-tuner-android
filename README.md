# Tuner

Guitar tuner for Android in the same visual language as Level (Dustland "Clean One" grammar):
flat colour, hairlines, single accent, circle motif, centred column, named themes switched from
the header or by swiping sideways.

## Build

Gradle-free. Needs a JDK, `zip`, python3 with PIL, and two Android SDK packages:

    sdkmanager "platforms;android-36" "build-tools;36.0.0"
    KEYSTORE_PASS=... ./build.sh                   # -> build/Tuner.apk (v1+v2+v3, minSdk 23, compile/targetSdk 36)
    KEYSTORE_PASS=... NEW_KEYSTORE=1 ./build.sh    # first build only: creates keystore/tuner.jks

The SDK is found through `ANDROID_SDK_ROOT`, `ANDROID_HOME` or `ANDROID_SDK` (`/usr/lib/android-sdk`
if none is set); `BUILD_TOOLS` and `PLATFORM_JAR` override the newest build-tools and the API 36
jar. Every build that should install over an earlier one must be signed with the same key, so back
the keystore up. The version comes from `VERSION` (code = major x 10000 + minor x 100 + patch);
`VERSION_CODE` / `VERSION_NAME` override it. Google Play needs an .aab, which this script does not
produce yet (needs `bundletool`).

Backup carries the settings but not `device` prefs (whether the microphone was refused), which
describe this phone only.

## Layout

    src/app/tuner/guitar/
      PitchEngine.java   streaming pitch detection (no Android deps)
      Tracker.java       target note/string, smoothing, in-tune state (no Android deps)
      Tuning.java        tunings, note math
      Guide.java         what the guided page says: status and wording (no Android deps)
      Renderer.java      all drawing, android.graphics only
      TunerView.java     animation, input, haptics, accessibility; drains pitch frames on the UI thread
      AudioInput.java    AudioRecord capture thread
      ToneSynth.java     reference tone (holds ducking audio focus while it plays)
      MainActivity.java  permission, lifecycle, window/insets, Material You palette on 12+
      Palette.java       theme tokens: bg, fg, dim, line, accent, onAccent, plus derived accentText
                         (4.5:1 on bg and the guide's row tint), accentStroke (3:1) and onAccentText
    tools/preview/       AWT-backed android.graphics stubs: renders Renderer on the JVM; EngineTest, GuideTest

    javac -d /tmp/pv $(find tools/preview -name '*.java') src/app/tuner/guitar/{Palette,Ui,Renderer,Tuning,PitchEngine,Tracker,StrumAnalyzer,Guide}.java
    java -cp /tmp/pv app.tuner.guitar.EngineTest
    java -cp /tmp/pv app.tuner.guitar.GuideTest
    java -Djava.awt.headless=true -cp /tmp/pv app.tuner.guitar.Preview assets/fonts /tmp/shots

Capture runs from start to stop, so a visible split-screen pane keeps listening without focus.
Rotation, dark mode, font size or process death restore the tab, settings page, pause, tuned
marks, guide step, string lock and tuning.

## Pitch detection

Per hop (1024 samples at 48 kHz, ~47 readings/s) over a 4096-sample window:

1. Streaming 2nd-order Butterworth high-pass 25 Hz and low-pass 3 kHz.
2. McLeod normalized square difference function from one packed complex FFT that also yields a
   Hann-windowed spectrum of the same frame.
3. Period = first key maximum reaching 0.9 x the highest.
4. Octave check: phone microphones roll off below ~150 Hz, so a low E can show a stronger
   half-period peak. If the spectrum carries the odd harmonics of f/2 (or the non-multiples of 3
   for f/3) and the NSDF confirms the longer period, the lower fundamental is taken.
5. Refinement: the NSDF peak at m periods (m up to 8) divided by m.
6. Two strings ringing at once (the last one still sounding while the next is plucked): their
   common period is often the most periodic lag (E2 + A2 repeat every 36 ms; B3 + E4 repeat at
   E2's period). Such a lag has neither its 1st nor its 2nd harmonic in the spectrum. The notes
   are the harmonics of the composite that start series of their own; the one that just grew
   (spectral change since the pluck) is followed, measured after cancelling the other with a comb
   filter y(n) = x(n) - x(n - T_other). Two notes a fifth apart (D3 + A3) repeat every D2
   period and carry D2's 2nd harmonic, like a low D with a weak fundamental; only a real note
   also has its 5th and 7th harmonics. Both readings need one of the notes ringing before the
   pluck: a single string, a slack one included, plucked from silence is one note. A string plucked on the harmonics of a ringing one (high E
   over low E, D3 over D2) is found by the same growth test and measured after a comb sum of d
   copies delayed by T/d, which keeps only the ringing note's harmonics shared with the new one.
7. Plucks: an onset is a 6 dB jump in the newest hop's energy or a spectral flux over 0.22 (new
   partials while another string rings add only ~3 dB).
8. Strums and chords: three notes or more (three series, or a period more than 6 semitones below
   the selected tuning's lowest string) is not one string. Several strings are not periodic but
   their spectrum is all peaks (flatness under 0.14); claps, rustle and the consonants of speech
   are not periodic either but flat (0.2 - 0.6) and never count. When the hops after a pluck are
   mostly unpitched-but-tonal (a fast strum), periodic at first and then not (a slow strum), or
   flicker as chord-like within ~0.5 s of the pluck (notes sharing a period, D A D G A D), no
   pitch is reported and up to 0.75 s of audio from 150 ms after the pluck (0.51 s at 16 kHz)
   goes to `StrumAnalyzer`.
9. Gate: level above an adaptive noise floor plus clarity with hysteresis. Windows containing
   digital silence never update the floor (otherwise capture start or a system mute would leave
   the gate open for minutes); 1.5 s of it (capture muted, or another app holds the microphone)
   is reported on screen.

The capture source is VOICE_RECOGNITION: the Android CDD requires it to run without noise
suppression or AGC and flat from 100 Hz to 4 kHz.

Measured by `EngineTest` (16-bit, dithered; asserted bound in brackets, so a regression fails):

- Pure and harmonic tones E1..C6: worst error 0.006 cents [0.012], 90% of frames voiced or more.
- Plucked strings through a phone-microphone model (fundamental at -11 dB, 30 dB SNR, attack
  glide, noise burst): median error under 0.1 cents [0.17], no octave errors, E1 to G4.
- Missing fundamental (E2, A2): correct octave.
- First note 33-55 ms after the pluck [110 ms], within 1 cent by ~200 ms [350 ms].
- 44.1, 22.05 and 16 kHz capture: worst error 0.023 cents [0.05].
- A string plucked while another rings (all adjacent pairs, low E under high E and back): the new
  string, within 1.1 cents of its reading alone [2].
- Open fifths, fourths and octaves ringing together (Drop D and DADGAD pairs, D2 + A3 included):
  never taken for a chord, the new string within 0.8 cents of its reading alone [1.6].
- Analysis: about 1 ms per hop on a desktop JVM [under the hop's 21.3 ms].
- Known limit: on Bass, a string plucked while another rings reads as several notes (an 85 ms
  window holds too few cycles of 41-98 Hz).

## Strum check (`StrumAnalyzer`)

Strum the open strings and every string is measured at once; strum a chord and the app says so.

- Spectrum of 0.68 s (32768 samples at 48 kHz, 1.5 Hz bins), log-parabolic peak interpolation.
- Each string is searched within its own range (up to +-250 cents, never overlapping a
  neighbour's) by harmonic sum. Open strings share partials (E2's 3rd is B3, its 4th is E4): a
  partial belongs to the string for which it is the lowest harmonic; partials closer than two bins
  merge, so others discount them; estimates are refined four times with collisions recomputed.
- Measured (synthetic strums through the phone-microphone model, strings +-45 cents off):
  per-string median error 0.5-0.7 cents, 90th percentile 1-1.6 cents, worst 3.4 cents at 48 kHz.
  A string within 4 cents (or the in-tune range, if wider) gets a check.
- Another tuning: the same strum is measured against every tuning with as many strings. If one
  fits within 25 cents while the selected one is off by more than 60 (or hears fewer strings),
  "Sounds like Drop D" with a switch that re-reads the same strum. Ties go to the tuning that
  changes the fewest strings. Open G/D/E/C are major chords, so a match to one of them (unless
  selected) is taken as that chord.
- A chord: a strum no tuning fits, with two strings a whole number of semitones off (or one a
  whole tone off) while the rest are in tune, or whose pitch-class profile fits a major, minor,
  power or 7th chord better than the open strings: "Several notes. Pluck one open string at a
  time." Telling someone to detune a fretted note would do harm; asking for one string does none.
- Known limit: D major on the top four strings (D3 A3 D4 F#4) is, to the ear and to the
  detector, a low D with its fundamental missing; it reads as one note.

Inharmonicity: on wound strings the waveform's period sits slightly above the first partial
(~2.5 cents on a low E with B = 1.2e-4). The reading follows the period, which tracks the pitch
the ear hears rather than the first partial alone.

## Screens

Two tabs under one bottom bar (settings, Advanced, reference tone, pause):

- **Easy** (home, where the app always opens): the guided page below.
- **Advanced**: the dial, the tuning selector and the string row. The Advanced button is filled
  while it is open; tapping it again, or Back, returns to Easy. Strings marked tuned in one tab
  stay marked in the other; Easy resumes at the first string not yet tuned.

Settings slides over either tab and returns to it. On Easy the fork plays the current string's
note ("Listen") and follows a tap on another string.

## Guided tuning (Easy)

For someone tuning for the first time.

- One string at a time, top string (nearest the face) first. The strings are drawn as seen when
  holding the guitar, thicker for lower pitch; the current one is highlighted, done ones checked.
- Plain words: "Tighten", "Loosen", "Almost", "Tuned", with a reason underneath ("Much too low.
  Turn the peg slowly as you pluck."). Sharp strings get the standard advice: go below the
  note, then tighten up to it. Far too high warns that the string could break.
- Auto switch (default, toggle at the bottom): after "Tuned" the next untuned string follows by
  itself, and plucking another string (a fresh pluck within 40 cents of that string, 300 from
  the current one, 0.1-0.5 s after its onset) moves the guide to it. Off: the guide stays on its
  string ("Next", or a tap on the meter once tuned, moves on; a tap on a string jumps) and a
  wrong string is pointed out instead. Turning Auto on at a string already tuned moves on as Auto
  would have (after the reference tone, once a pluck confirms it).
- A strum or chord: "One string. Pluck only the top string. If it is floppy, tighten it first."
- Six seconds without a sound: "Not hearing it yet. Hold the phone close and pluck firmly."
- Mistakes beginners make are named: "Other way" when the note moves away while the peg turns
  (the peg direction differs between guitars, so the app watches the result instead of
  prescribing clockwise), and "Wrong string?" when the note belongs to another string.
- The tracker is locked to the string with octave folding off: a slack string never reads as in
  tune an octave down. Down to 6 semitones slack it reads as a note ("Much too low"); slacker, it
  sounds below anything a string should and is taken for several notes, which the guide words to
  cover both ("If it is floppy, tighten it first"). A reading most of an octave high
  is a misreading (a string would have broken): "Pluck again", never "loosen it now". In-tune
  range: the setting, at least 3 cents.
- On reaching pitch: haptic, a check drawn in the target, and after 1.1 s the next untuned
  string; the previous string's ringing is ignored for up to 3 s. At the end: "Check again".
- Battery: as the home screen it draws only while something moves. The demonstration pluck plays
  three times (8.4 s), then rests until the step changes or the next sound has died away. While
  settings covers the tabs, their motion and incoming readings draw nothing.
- Motion: a demonstration pluck loops on the highlighted string while waiting (pick comes down,
  pushes the string, slips off, the string rings, a ripple leaves it); the string vibrates while
  it sounds and sags while it is far too slack, straightening as it is tightened; chevrons march
  from the bead toward the target, faster when further off; "Other way" shakes; titles and words
  slide and crossfade between steps; checks draw themselves.
- Short windows give things up in a fixed order, so nothing is drawn over anything else: the
  strings' diagram becomes a row of circles, then goes (or moves beside the words when the window
  is wide); the sub-line; the meter's labels and second line of advice; the caption; the advice;
  the title; last, below ~260 dp, the Skip / Auto row, whose Next / Skip moves to the meter (a
  "Next string" / "Skip this string" action for TalkBack and the keyboard). While the height
  shrinks, nothing given up comes back, except across the 420 dp switch to the tighter header.

`Preview` renders every guide state (`g*.png`); with a third argument it writes a scripted
9.6 s run as PNG frames for motion review.

## Display

- Needle: median of three, then a one-euro filter (cutoff rises with the rate of change): calm at
  rest, ~2 cents of lag while a peg turns at 40 cents/s. "Fast" in settings raises the cutoff.
- Scale: tanh-compressed, 1.3 degrees per cent at the centre, 50 cents at 52 degrees.
- In tune: inside the range for 250 ms; leaves at range + max(0.7, 0.4 x range). A string that
  reached pitch is filled in the string row and loses the mark if it drifts out.
- Auto string: nearest string, 35-cent hysteresis (none for 150 ms after a pluck), a new target
  must hold for two consecutive frames. The first 100 ms after a pluck never count toward in tune:
  the pitch is still settling from the attack.
- Locked to a string while another sounds (within 50 cents of it, 300 from the locked one): "Sounds
  like string 5" with a switch. A reading that jumps to the octave above the string being followed, mid-note, stays on
  that string but never counts toward in tune. A fresh reading is never moved an octave down: a
  higher string tuned a whole step flat (or the wrong tuning selected) would pass for the string
  below and read "in tune". Tap a string to lock to it; locked, a 12th-fret harmonic counts.
- Changing A4 or the in-tune range clears the tuned marks.
- Reference tone: eight harmonics so a phone speaker conveys a low E; readings are ignored while
  it plays and for 350 ms after. It shows over Paused, ducks other audio while it plays, and ends
  on silence. Volume keys control media volume.
- Keep screen on (default on) applies only while listening or playing the tone on the tuner page.
- Short windows (split screen down to 220 dp): tighter header and bar, dial beside the tuning
  and strings, then the string row and tuning selector drop out before anything overlaps; a
  small dial shows note and cents only, and a microphone message takes the dial's place.
- Accessibility: every control is a virtual node; the dial is a polite live region reading the
  note, string and whole cents at most every 1.5 s; reaching pitch is announced. A microphone
  message is a readable node (with its action, if any); silence starting and a refused
  permission are announced. Contrast: text 4.5:1, indicators and focus ring 3:1 in every theme.
  Text follows the system font scale up to 1.3x; larger settings render at 1.3x so the custom
  layouts keep fitting. Keyboard and D-pad: arrows move focus spatially, Tab in reading order,
  Enter or Space activates, Esc backs out.

Rubik is bundled under the SIL Open Font License (assets/fonts/OFL.txt).
