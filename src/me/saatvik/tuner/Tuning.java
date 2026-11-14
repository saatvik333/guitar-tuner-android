package me.saatvik.tuner;

/** Tunings and equal-temperament note math. No Android dependencies. */
final class Tuning {
    static final int SPELL_SETTING = 0, SPELL_SHARPS = 1, SPELL_FLATS = 2;

    final String name;
    /**
     * Open strings as MIDI note numbers in physical order, from the string nearest the player's
     * face: lowest-sounding first, except the re-entrant G of the ukulele. Null = chromatic.
     */
    final int[] strings;
    /** How the open strings are spelled: the user's setting, or fixed by the tuning's key. */
    final int spell;
    /** The open strings sound a major chord (Open G, D, E, C). */
    final boolean openChord;

    Tuning(String name, int spell, int... strings) {
        this(name, spell, false, strings);
    }

    private Tuning(String name, int spell, boolean openChord, int[] strings) {
        this.name = name;
        this.spell = spell;
        this.openChord = openChord;
        this.strings = strings.length == 0 ? null : strings;
    }

    private static Tuning open(String name, int... strings) {
        return new Tuning(name, SPELL_SHARPS, true, strings);
    }

    boolean chromatic() {
        return strings == null;
    }

    int count() {
        return strings == null ? 0 : strings.length;
    }

    /** Conventional string number: the string farthest from the player's face is 1. */
    int stringNumber(int idx) {
        return strings.length - idx;
    }

    boolean flats(boolean settingFlats) {
        return spell == SPELL_SETTING ? settingFlats : spell == SPELL_FLATS;
    }

    // MIDI: E2 = 40, A2 = 45, D3 = 50, G3 = 55, B3 = 59, E4 = 64, A4 = 69
    static final Tuning[] ALL = {
        new Tuning("Standard",       SPELL_SHARPS, 40, 45, 50, 55, 59, 64),
        new Tuning("Chromatic",      SPELL_SETTING),
        new Tuning("Drop D",         SPELL_SHARPS, 38, 45, 50, 55, 59, 64),
        new Tuning("Half step down", SPELL_FLATS,  39, 44, 49, 54, 58, 63),
        new Tuning("Full step down", SPELL_SHARPS, 38, 43, 48, 53, 57, 62),
        new Tuning("Drop C",         SPELL_SHARPS, 36, 43, 48, 53, 57, 62),
        new Tuning("DADGAD",         SPELL_SHARPS, 38, 45, 50, 55, 57, 62),
        open("Open G",                             38, 43, 50, 55, 59, 62),
        open("Open D",                             38, 45, 50, 54, 57, 62),
        open("Open E",                             40, 47, 52, 56, 59, 64),
        open("Open C",                             36, 43, 48, 55, 60, 64),
        new Tuning("7-string",       SPELL_SHARPS, 35, 40, 45, 50, 55, 59, 64),
        new Tuning("Bass",           SPELL_SHARPS, 28, 33, 38, 43),
        new Tuning("Ukulele",        SPELL_SHARPS, 67, 60, 64, 69),
    };

    static int indexOf(String name) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].name.equals(name)) return i;
        return 0;
    }

    // ---- note math ---------------------------------------------------------------------------

    static double freq(int midi, double a4) {
        return a4 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    static double midiOf(double hz, double a4) {
        return 69.0 + 12.0 * log2(hz / a4);
    }

    static double cents(double hz, double refHz) {
        return 1200.0 * log2(hz / refHz);
    }

    static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }

    private static final char[] LETTER_SHARP = {'C', 'C', 'D', 'D', 'E', 'F', 'F', 'G', 'G', 'A', 'A', 'B'};
    private static final char[] LETTER_FLAT  = {'C', 'D', 'D', 'E', 'E', 'F', 'G', 'G', 'A', 'A', 'B', 'B'};
    private static final boolean[] BLACK = {false, true, false, true, false, false, true, false, true, false, true, false};

    static int pitchClass(int midi) {
        return ((midi % 12) + 12) % 12;
    }

    static char letter(int midi, boolean flats) {
        int pc = pitchClass(midi);
        return flats ? LETTER_FLAT[pc] : LETTER_SHARP[pc];
    }

    /** 0 natural, 1 sharp, 2 flat. */
    static int accidental(int midi, boolean flats) {
        return BLACK[pitchClass(midi)] ? (flats ? 2 : 1) : 0;
    }

    static int octave(int midi) {
        return (midi >= 0 ? midi / 12 : (midi - 11) / 12) - 1;
    }

    /** Name for speech, e.g. "E flat 2". */
    static String spoken(int midi, boolean flats) {
        int acc = accidental(midi, flats);
        return letter(midi, flats) + (acc == 1 ? " sharp " : acc == 2 ? " flat " : " ") + octave(midi);
    }
}
