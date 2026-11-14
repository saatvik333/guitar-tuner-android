package app.tuner.guitar;

import java.util.Locale;

/** Checks of what the guided page says (Guide), run on the JVM. */
public final class GuideTest {
    static int pass, fail;

    public static void main(String[] args) {
        toneWhilePaused();
        resetRepeats();
        System.out.printf(Locale.US, "%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    static Tracker tracker() {
        Tracker tr = new Tracker();
        tr.tuning = Tuning.ALL[Tuning.indexOf("Standard")];
        tr.tolerance = 3.0;
        return tr;
    }

    /** The fork also plays while listening is paused: the words say what sounds, not "Paused". */
    static void toneWhilePaused() {
        System.out.println("guide: reference tone while paused");
        Tracker tr = tracker();
        Guide g = new Guide();
        g.update(tr, 2, false, true, true, false, false, false, false);
        check("tone and pause: \"" + Guide.word(g.status) + "\" " + Guide.detail(g.status, g.arg, tr.tuning, 2, true),
                g.status == Guide.TONE);
    }

    /** The words also depend on the Auto switch, which the status does not show: reset() reports it afresh. */
    static void resetRepeats() {
        System.out.println("guide: status changes are reported once, again after reset");
        Tracker tr = tracker();
        tr.hasNote = tr.live = true;
        tr.cents = -40.0;
        Guide g = new Guide();
        boolean first = g.update(tr, 0, false, false, false, false, false, false, false);
        boolean again = g.update(tr, 0, false, false, false, false, false, false, false);
        g.reset();
        boolean after = g.update(tr, 0, false, false, false, false, false, false, false);
        check(String.format(Locale.US, "40 c flat: first %b, unchanged %b, after reset %b, \"%s\" %s", first, again, after,
                Guide.word(g.status), Guide.detail(g.status, g.arg, tr.tuning, 0, true)),
                first && !again && after && g.status == Guide.TIGHTEN && g.arg == Guide.OFF);
    }
}
