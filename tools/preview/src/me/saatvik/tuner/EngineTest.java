package me.saatvik.tuner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Accuracy and robustness checks for PitchEngine and Tracker on synthetic signals, run on the JVM.
 * Signals are quantized to 16-bit like AudioRecord output.
 */
public final class EngineTest {
    static int pass, fail;

    public static void main(String[] args) {
        pureTones();
        chromaticSweep();
        plucks();
        weakFundamental();
        inharmonic();
        noiseAndSilence();
        decayIntoNoise();
        latency();
        sampleRates();
        trackerStrings();
        trackerInTune();
        trackerChromatic();
        trackerOctaveFallback();
        trackerFlatStrings();
        trackerDebounce();
        trackerGuided();
        trackerDrift();
        humAfterSilence();
        overlappingStrings();
        ringingFifths();
        quietChordSwell();
        bassRingingBelowGuitar();
        strums();
        timing();
        System.out.printf(Locale.US, "%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ---- signals -------------------------------------------------------------------------------

    static final class Frame {
        double t, f;
        boolean voiced, onset;
        int octFix;
    }

    static ArrayList<Frame> run(int fs, float[] x) {
        return run(fs, quantize(x));
    }

    static ArrayList<Frame> run(int fs, short[] pcm) {
        return run(fs, pcm, PitchEngine.F_MIN);
    }

    /** {@code minHz}: the lowest pitch of the selected tuning, as {@code AudioInput} sets it. */
    static ArrayList<Frame> run(int fs, short[] pcm, double minHz) {
        PitchEngine e = new PitchEngine(fs);
        e.minHz = minHz;
        ArrayList<Frame> out = new ArrayList<>();
        int off = 0;
        while (off < pcm.length) {
            off += e.feed(pcm, off, pcm.length - off);
            if (e.ready) {
                Frame f = new Frame();
                f.t = e.time;
                f.f = e.freq;
                f.voiced = e.voiced;
                f.onset = e.onset;
                f.octFix = e.octaveFix;
                out.add(f);
            }
        }
        return out;
    }

    static short[] quantize(float[] x) {
        short[] s = new short[x.length];
        Random r = new Random(7);
        for (int i = 0; i < x.length; i++) {
            double v = x[i] * 32767.0 + (r.nextDouble() - r.nextDouble());   // TPDF dither
            s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return s;
    }

    /** Harmonic tone with given partial amplitudes, constant level. */
    static float[] tone(int fs, double f, double sec, double peak, double... amps) {
        int n = (int) (fs * sec);
        float[] x = new float[n];
        Random r = new Random(Double.doubleToLongBits(f));
        double[] ph = new double[amps.length];
        for (int k = 0; k < amps.length; k++) ph[k] = r.nextDouble() * 2 * Math.PI;
        double sum = 0;
        for (double a : amps) sum += Math.abs(a);
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs, v = 0;
            for (int k = 0; k < amps.length; k++) {
                if ((k + 1) * f > fs * 0.45) break;
                v += amps[k] * Math.sin(2 * Math.PI * (k + 1) * f * t + ph[k]);
            }
            x[i] = (float) (peak * v / sum);
        }
        return x;
    }

    /**
     * Plucked string as heard by a phone: partials |sin(n pi beta)| / n, per-partial decay,
     * inharmonicity B, a +7 cent attack glide, a short noise burst, a 2nd-order 150 Hz high-pass
     * microphone response, and white noise at the given SNR (dB, against the initial RMS).
     */
    static float[] pluck(int fs, double f0, double sec, double peak, double beta, double B, double snrDb, long seed) {
        int n = (int) (fs * sec);
        double[] y = new double[n];
        Random r = new Random(seed);
        int K = 1;
        while ((K + 1) * f0 * Math.sqrt(1 + B * (K + 1) * (K + 1)) < Math.min(5000, fs * 0.45)) K++;
        double[] amp = new double[K + 1], ph = new double[K + 1], tau = new double[K + 1], s = new double[K + 1];
        for (int k = 1; k <= K; k++) {
            s[k] = Math.sqrt(1 + B * k * k);
            double fk = k * f0 * s[k];
            double mic = 1.0 / Math.sqrt(1.0 + Math.pow(150.0 / fk, 4));
            amp[k] = Math.abs(Math.sin(k * Math.PI * beta)) / k * mic;
            ph[k] = r.nextDouble() * 2 * Math.PI;
            tau[k] = 3.0 / (1.0 + 0.35 * (k - 1));
        }
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            double warp = t + 0.004 * 0.08 * (1 - Math.exp(-t / 0.08));     // integral of the glide
            double v = 0;
            for (int k = 1; k <= K; k++) v += amp[k] * Math.exp(-t / tau[k]) * Math.sin(2 * Math.PI * k * f0 * s[k] * warp + ph[k]);
            if (t < 0.01) v += (r.nextDouble() * 2 - 1) * 0.3 * (1 - t / 0.01);
            y[i] = v;
        }
        double rms = 0;
        int w = Math.min(n, fs / 10);
        for (int i = 0; i < w; i++) rms += y[i] * y[i];
        rms = Math.sqrt(rms / w);
        double nrms = rms * Math.pow(10, -snrDb / 20.0);
        double mx = 0;
        for (int i = 0; i < n; i++) {
            y[i] += r.nextGaussian() * nrms;
            mx = Math.max(mx, Math.abs(y[i]));
        }
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (y[i] / mx * peak);
        return x;
    }

    static float[] concat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] x = new float[n];
        int o = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, x, o, p.length);
            o += p.length;
        }
        return x;
    }

    static float[] noise(int fs, double sec, double rmsDb, long seed) {
        Random r = new Random(seed);
        float[] x = new float[(int) (fs * sec)];
        double a = Math.pow(10, rmsDb / 20.0);
        for (int i = 0; i < x.length; i++) x[i] = (float) (r.nextGaussian() * a);
        return x;
    }

    static double freq(int midi) {
        return Tuning.freq(midi, 440.0);
    }

    /** At least 90% of the frames after t0 are voiced; {@code st} comes from {@link #stats}. */
    static boolean voiced(double[] st) {
        return st[2] >= 0.9 * st[4];
    }

    /** Median and max |cents| of voiced frames after t0; octave errors are frames off by > 50 cents. */
    static double[] stats(ArrayList<Frame> fr, double ref, double t0) {
        ArrayList<Double> d = new ArrayList<>();
        int voiced = 0, oct = 0, total = 0;
        for (Frame f : fr) {
            if (f.t < t0) continue;
            total++;
            if (!f.voiced) continue;
            voiced++;
            double c = Tuning.cents(f.f, ref);
            if (Math.abs(c) > 50) oct++;
            else d.add(Math.abs(c));
        }
        double[] a = new double[d.size()];
        for (int i = 0; i < a.length; i++) a[i] = d.get(i);
        Arrays.sort(a);
        double med = a.length == 0 ? Double.NaN : a[a.length / 2];
        double max = a.length == 0 ? Double.NaN : a[a.length - 1];
        return new double[]{med, max, voiced, oct, total};
    }

    static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    // ---- engine ------------------------------------------------------------------------------

    static void pureTones() {
        System.out.println("pure and harmonic tones, 48 kHz, detuned +3.7 cents");
        int fs = 48000;
        int[] notes = {28, 33, 40, 45, 50, 55, 59, 64, 67, 69, 76, 84};
        for (int m : notes) {
            double f = freq(m) * Math.pow(2, 3.7 / 1200.0);
            double[] st = stats(run(fs, tone(fs, f, 1.0, 0.3, 1.0)), f, 0.15);
            double[] sh = stats(run(fs, tone(fs, f, 1.0, 0.3, 1.0, 0.5, 0.33, 0.25)), f, 0.15);
            check(String.format(Locale.US, "midi %d %.2f Hz  sine max %.4f c  harmonic max %.4f c  voiced %d/%d",
                    m, f, st[1], sh[1], (int) sh[2], (int) sh[4]),
                    st[1] < 0.012 && sh[1] < 0.012 && st[3] == 0 && sh[3] == 0 && voiced(st) && voiced(sh));
        }
    }

    static void chromaticSweep() {
        System.out.println("chromatic sweep E1..C6, random detune +-45 c, 3 partials");
        int fs = 48000, bad = 0, n = 0;
        double worst = 0;
        Random r = new Random(3);
        for (int m = 28; m <= 84; m++) {
            double det = (r.nextDouble() * 2 - 1) * 45;
            double f = freq(m) * Math.pow(2, det / 1200.0);
            double[] st = stats(run(fs, tone(fs, f, 0.6, 0.2, 1.0, 0.6, 0.4)), f, 0.15);
            n++;
            if (st[3] > 0 || !voiced(st)) bad++;
            if (!Double.isNaN(st[1])) worst = Math.max(worst, st[1]);
        }
        check(String.format(Locale.US, "%d notes, %d with octave errors or unvoiced, worst error %.4f c", n, bad, worst),
                bad == 0 && worst < 0.012);
    }

    static void plucks() {
        System.out.println("plucked strings through a phone microphone model, SNR 30 dB, B = 0");
        int fs = 48000;
        int[] std = {40, 45, 50, 55, 59, 64, 38, 35, 28, 33, 67, 60};
        for (int m : std) {
            double f = freq(m);
            int octs = 0, voiced = 0, total = 0;
            double worstMed = 0;
            for (int seed = 0; seed < 6; seed++) {
                double beta = 0.11 + 0.025 * seed;
                ArrayList<Frame> fr = run(fs, pluck(fs, f, 2.5, 0.25, beta, 0, 30, seed * 31 + m));
                double[] st = stats(fr, f, 0.4);
                octs += (int) st[3];
                voiced += (int) st[2];
                total += (int) st[4];
                if (!Double.isNaN(st[0])) worstMed = Math.max(worstMed, st[0]);
            }
            check(String.format(Locale.US, "midi %d  voiced %d/%d  octave errors %d  worst median %.3f c",
                    m, voiced, total, octs, worstMed), octs == 0 && voiced > total * 0.9 && worstMed < 0.17);
        }
    }

    static void weakFundamental() {
        System.out.println("weak fundamentals");
        int fs = 48000;
        double f = freq(40);
        // fundamental at -16 dB, odd partials present: the octave check must recover E2
        ArrayList<Frame> fr = run(fs, tone(fs, f, 1.0, 0.3, 0.15, 1.0, 0.6, 0.4, 0.3, 0.2));
        double[] st = stats(fr, f, 0.15);
        int fixes = 0;
        for (Frame x : fr) fixes += x.octFix;
        check(String.format(Locale.US, "E2, a1 = 0.15: octave errors %d of %d voiced, octave fixes %d", (int) st[3], (int) st[2], fixes),
                st[3] == 0 && voiced(st));
        // fundamental absent entirely
        st = stats(run(fs, tone(fs, f, 1.0, 0.3, 0.0, 1.0, 0.7, 0.5, 0.35, 0.25)), f, 0.15);
        check(String.format(Locale.US, "E2, no fundamental: octave errors %d of %d voiced", (int) st[3], (int) st[2]),
                st[3] == 0 && voiced(st));
        // A2 with no fundamental
        double fa = freq(45);
        st = stats(run(fs, tone(fs, fa, 1.0, 0.3, 0.0, 1.0, 0.5, 0.5, 0.2)), fa, 0.15);
        check(String.format(Locale.US, "A2, no fundamental: octave errors %d of %d voiced", (int) st[3], (int) st[2]),
                st[3] == 0 && voiced(st));
        // true E3 must not be lowered to E2: only even partials of E2 exist
        double f3 = freq(52);
        fr = run(fs, tone(fs, f3, 1.0, 0.3, 1.0, 0.5, 0.3, 0.2));
        st = stats(fr, f3, 0.15);
        check(String.format(Locale.US, "E3 stays E3: octave errors %d of %d voiced", (int) st[3], (int) st[2]),
                st[3] == 0 && voiced(st));
    }

    static void inharmonic() {
        System.out.println("inharmonic strings (reading vs the first partial)");
        int fs = 48000;
        int[] ms = {40, 45, 50, 55, 59, 64};
        double[] bs = {1.2e-4, 1.0e-4, 8e-5, 4e-5, 3e-5, 2e-5};
        for (int i = 0; i < ms.length; i++) {
            double f = freq(ms[i]);
            double f1 = f * Math.sqrt(1 + bs[i]);
            double[] st = stats(run(fs, pluck(fs, f, 2.0, 0.25, 0.14, bs[i], 35, 11 + i)), f1, 0.4);
            check(String.format(Locale.US, "midi %d B=%.0e  median %.2f c  octave errors %d", ms[i], bs[i], st[0], (int) st[3]),
                    st[3] == 0 && st[0] < 3.0);
        }
    }

    static void noiseAndSilence() {
        System.out.println("noise and silence");
        int fs = 48000;
        ArrayList<Frame> fr = run(fs, concat(noise(fs, 0.5, -70, 1), noise(fs, 3.0, -35, 2)));
        int v = 0;
        for (Frame f : fr) if (f.voiced) v++;
        check("white noise at -35 dBFS after a quiet start: voiced frames " + v + " of " + fr.size(), v <= fr.size() / 100);

        PitchEngine e = new PitchEngine(fs);
        short[] z = new short[fs * 2];
        int off = 0;
        while (off < z.length) off += e.feed(z, off, z.length - off);
        check(String.format(Locale.US, "digital silence: voiced %b, silent for %.2f s", e.voiced, e.silentSec),
                !e.voiced && e.silentSec > 1.5);

        // quiet tone under the absolute floor stays unvoiced; at -60 dBFS it is found
        fr = run(fs, tone(fs, 110, 1.0, Math.pow(10, -92 / 20.0), 1.0));
        v = 0;
        for (Frame f : fr) if (f.voiced) v++;
        check("tone at -95 dBFS RMS is below the gate: voiced " + v, v == 0);
        double[] st = stats(run(fs, concat(noise(fs, 0.5, -80, 4), tone(fs, 110, 1.0, Math.pow(10, -57 / 20.0), 1.0, 0.5))), 110, 0.65);
        check(String.format(Locale.US, "tone at -60 dBFS after a quiet room: voiced %d/%d, max %.3f c", (int) st[2], (int) st[4], st[1]),
                st[2] >= st[4] - 1 && st[1] < 0.15);
    }

    static void decayIntoNoise() {
        System.out.println("note decaying into room noise");
        int fs = 48000;
        double f = freq(45);
        float[] x = pluck(fs, f, 8.0, 0.25, 0.15, 0, 50, 99);
        float[] n = noise(fs, 8.0, -62, 5);
        for (int i = 0; i < x.length; i++) x[i] += n[i];
        ArrayList<Frame> fr = run(fs, x);
        double[] st = stats(fr, f, 0.3);
        double lastVoiced = 0;
        for (Frame fm : fr) if (fm.voiced) lastVoiced = fm.t;
        check(String.format(Locale.US, "A2, 8 s: voiced until %.1f s, wrong frames %d, median %.3f c", lastVoiced, (int) st[3], st[0]),
                st[3] == 0 && lastVoiced > 2.5);
    }

    static void latency() {
        System.out.println("latency from the pluck (window 85 ms, hop 21 ms)");
        int fs = 48000;
        int[] ms = {40, 50, 64};
        for (int m : ms) {
            double f = freq(m);
            float[] x = concat(noise(fs, 0.5, -70, 3), pluck(fs, f, 1.5, 0.25, 0.14, 0, 40, m));
            ArrayList<Frame> fr = run(fs, x);
            double first = -1, within1 = -1;
            for (Frame fm : fr) {
                if (!fm.voiced || fm.t < 0.5) continue;
                double c = Math.abs(Tuning.cents(fm.f, f));
                if (first < 0 && c < 50) first = fm.t - 0.5;
                if (within1 < 0 && c < 1.0) within1 = fm.t - 0.5;
            }
            check(String.format(Locale.US, "midi %d: note after %.0f ms, within 1 c after %.0f ms", m, first * 1000, within1 * 1000),
                    first > 0 && first < 0.11 && within1 > 0 && within1 < 0.35);
        }
    }

    static void sampleRates() {
        System.out.println("other sample rates");
        int[] rates = {44100, 16000, 22050};
        for (int fs : rates) {
            int bad = 0;
            double worst = 0;
            for (int m = 28; m <= 76; m += 4) {
                double f = freq(m) * Math.pow(2, -7.3 / 1200.0);
                double[] st = stats(run(fs, tone(fs, f, 0.8, 0.2, 1.0, 0.5, 0.3)), f, 0.25);
                if (st[3] > 0 || !voiced(st)) bad++;
                if (!Double.isNaN(st[1])) worst = Math.max(worst, st[1]);
            }
            PitchEngine e = new PitchEngine(fs);
            check(String.format(Locale.US, "%d Hz (window %d, lowest %.1f Hz): bad notes %d, worst %.4f c", fs, e.W, e.minFreq(), bad, worst),
                    bad == 0 && worst < 0.05);
        }
    }

    // ---- tracker -----------------------------------------------------------------------------

    static Tracker tracker(String tuning) {
        Tracker t = new Tracker();
        t.tuning = Tuning.ALL[Tuning.indexOf(tuning)];
        return t;
    }

    /** Feeds a steady pitch for sec seconds at 47 frames/s starting at t0; returns the end time. */
    static double feed(Tracker tr, double t0, double sec, double f) {
        double t = t0;
        for (; t < t0 + sec; t += 1024.0 / 48000) tr.update(t, f > 0, f);
        return t;
    }

    static void trackerStrings() {
        System.out.println("tracker: strings");
        Tracker tr = tracker("Standard");
        double t = 0;
        boolean ok = true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            double f = freq(tr.tuning.strings[i]) * Math.pow(2, (i % 2 == 0 ? 37 : -41) / 1200.0);
            t = feed(tr, t, 0.6, f);
            sb.append(tr.string).append(' ');
            ok &= tr.string == i && Math.abs(tr.cents - (i % 2 == 0 ? 37 : -41)) < 0.5;
            t = feed(tr, t, 0.5, 0);
        }
        check("each string of Standard, 40 c off: picked " + sb.toString().trim(), ok);

        // a string tuned far flat stays the target while within reach (hysteresis), then hands over
        tr = tracker("Standard");
        t = feed(tr, 0, 0.5, freq(45));
        double c0 = 0;
        for (double c = 0; c > -320; c -= 4) {
            t = feed(tr, t, 0.05, freq(45) * Math.pow(2, c / 1200.0));
            if (tr.string == 1) c0 = c;
        }
        check(String.format(Locale.US, "A string pulled flat keeps the target to %.0f c (nearest flips at -250, kept to -267.5)", c0),
                c0 <= -260 && c0 >= -272);

        tr = tracker("Standard");
        tr.locked = 0;
        t = feed(tr, 0, 0.5, freq(50));
        check(String.format(Locale.US, "locked to string 6 while D3 plays: string %d, %.0f c", tr.string, tr.cents),
                tr.string == 0 && Math.abs(tr.cents - 1000) < 1);
    }

    static void trackerInTune() {
        System.out.println("tracker: in tune");
        Tracker tr = tracker("Standard");
        tr.tolerance = 2.0;
        double t = feed(tr, 0, 0.1, freq(40) * Math.pow(2, 1.5 / 1200.0));
        boolean early = tr.inTune;
        t = feed(tr, t, 0.3, freq(40) * Math.pow(2, 1.5 / 1200.0));
        int ev = tr.takeEvents();
        check("enters after the dwell, not before: early " + early + ", later " + tr.inTune + ", event " + ((ev & Tracker.EV_IN_TUNE) != 0)
                + ", marked " + ((tr.tunedMask & 1) != 0), !early && tr.inTune && (ev & Tracker.EV_IN_TUNE) != 0 && (tr.tunedMask & 1) == 1);
        t = feed(tr, t, 0.5, freq(40) * Math.pow(2, 2.6 / 1200.0));
        boolean held = tr.inTune;
        t = feed(tr, t, 0.5, freq(40) * Math.pow(2, 3.2 / 1200.0));
        check("hysteresis: holds at 2.6 c (" + held + "), leaves at 3.2 c (" + !tr.inTune + ")", held && !tr.inTune);
        t = feed(tr, t, 0.6, 0);
        check("goes stale in silence: live " + tr.live + ", note kept " + tr.hasNote, !tr.live && tr.hasNote);

        // smoothing: jitter of +-1.5 c around +4 c settles near +4 with little ripple
        tr = tracker("Standard");
        Random r = new Random(1);
        double min = 1e9, max = -1e9;
        for (int i = 0; i < 200; i++) {
            t = i * 1024.0 / 48000;
            tr.update(t, true, freq(45) * Math.pow(2, (4 + (r.nextDouble() * 3 - 1.5)) / 1200.0));
            if (i > 60) {
                min = Math.min(min, tr.cents);
                max = Math.max(max, tr.cents);
            }
        }
        check(String.format(Locale.US, "jitter +-1.5 c smoothed to %.2f..%.2f c", min, max), max - min < 1.2 && min > 3 && max < 5);
        // a jump of 20 c is followed quickly
        double t0 = t;
        double reached = -1;
        for (int i = 1; i < 40; i++) {
            t = t0 + i * 1024.0 / 48000;
            tr.update(t, true, freq(45) * Math.pow(2, 24 / 1200.0));
            if (reached < 0 && Math.abs(tr.cents - 24) < 1) reached = t - t0;
        }
        check(String.format(Locale.US, "20 c jump followed within 1 c after %.0f ms", reached * 1000), reached > 0 && reached < 0.2);
        // turning a peg: a 40 c/s ramp is followed with little lag
        tr = tracker("Standard");
        double lag = 0;
        for (int i = 0; i < 120; i++) {
            t = i * 1024.0 / 48000;
            double c = i < 20 ? -30 : -30 + (t - 20 * 1024.0 / 48000) * 40;
            tr.update(t, true, freq(45) * Math.pow(2, c / 1200.0));
            if (i == 110) lag = c - tr.cents;
        }
        check(String.format(Locale.US, "40 c/s peg turn: needle lags %.2f c", lag), lag > 0 && lag < 3);
    }

    static void trackerChromatic() {
        System.out.println("tracker: chromatic");
        Tracker tr = tracker("Chromatic");
        double t = feed(tr, 0, 0.3, freq(57) * Math.pow(2, 48 / 1200.0));
        int a = tr.midi;
        t = feed(tr, t, 0.3, freq(57) * Math.pow(2, 55 / 1200.0));
        int b = tr.midi;
        t = feed(tr, t, 0.3, freq(57) * Math.pow(2, 64 / 1200.0));
        int c = tr.midi;
        check("A3 +48 c -> " + a + ", +55 c holds -> " + b + ", +64 c switches -> " + c, a == 57 && b == 57 && c == 58);
        tr.a4 = 432;
        tr.reset(true);
        feed(tr, 0, 0.3, 432);
        check(String.format(Locale.US, "A4 = 432: 432 Hz reads midi %d, %.3f c", tr.midi, tr.cents), tr.midi == 69 && Math.abs(tr.cents) < 0.01);
    }

    static void trackerOctaveFallback() {
        System.out.println("tracker: octave jumps within a note");
        Tracker tr = tracker("Standard");
        double e2 = freq(40) * Math.pow(2, -6 / 1200.0);
        double t = feed(tr, 0, 0.5, e2);
        t = feed(tr, t, 0.3, 2 * e2);
        check(String.format(Locale.US, "low E, then readings jump to E3 mid-note: string %d, %.1f c, %.2f Hz", tr.string, tr.cents, tr.hz),
                tr.string == 0 && Math.abs(tr.cents + 6) < 0.5 && Math.abs(tr.hz - e2) < 0.05);

        tr = tracker("Standard");
        t = feed(tr, 0, 0.4, freq(40) * Math.pow(2, -30 / 1200.0));
        t = feed(tr, t, 0.6, 2 * freq(40));
        check("readings an octave up never earn in tune: string " + tr.string + ", in tune " + tr.inTune + ", marked " + (tr.tunedMask != 0),
                tr.string == 0 && !tr.inTune && tr.tunedMask == 0);
    }

    /** A higher string tuned a whole step or more flat must not pass for the string an octave below. */
    static void trackerFlatStrings() {
        System.out.println("tracker: strings tuned far flat, or the wrong tuning selected");
        String[][] cases = {
                // selected tuning, sounding midi, expected string index, expected cents
                {"Standard", "57", "3", "200"},            // B string at A3: as far from G3 as from B3
                {"Standard", "62", "5", "-200"},           // high E at D4
                {"Standard", "52", "2", "200"},            // G string at E3: nearest is D3
                {"Full step down", "50", "2", "200"},      // standard guitar: D string
                {"Full step down", "55", "3", "200"},      // standard guitar: G string
        };
        for (String[] k : cases) {
            Tracker tr = tracker(k[0]);
            int m = Integer.parseInt(k[1]), want = Integer.parseInt(k[2]);
            double c = Double.parseDouble(k[3]);
            feed(tr, 0, 1.0, freq(m));
            check(String.format(Locale.US, "%s, midi %d sounding: string %d (want %d), %.0f c, in tune %b, marked %b",
                    k[0], m, tr.string, want, tr.cents, tr.inTune, tr.tunedMask != 0),
                    tr.string == want && Math.abs(tr.cents - c) < 1 && !tr.inTune && tr.tunedMask == 0);
        }
        // through the detector: a plucked A3 in Standard
        int fs = 48000;
        ArrayList<Frame> fr = run(fs, pluck(fs, freq(57), 3.0, 0.25, 0.14, 1e-4, 30, 21));
        Tracker tr = tracker("Standard");
        boolean everInTune = false;
        for (Frame f : fr) {
            tr.update(f.t, f.voiced, f.f);
            everInTune |= tr.inTune;
        }
        check(String.format(Locale.US, "plucked A3 in Standard: string %d, %.0f c, ever in tune %b", tr.string, tr.cents, everInTune),
                (tr.string == 3 || tr.string == 4) && Math.abs(Math.abs(tr.cents) - 200) < 5 && !everInTune && tr.tunedMask == 0);
    }

    static void trackerGuided() {
        System.out.println("tracker: guided mode (locked, no octave folding)");
        Tracker tr = tracker("Standard");
        tr.locked = 0;
        tr.foldOctaves = false;
        tr.tolerance = 3.0;
        feed(tr, 0, 1.0, freq(28));
        check(String.format(Locale.US, "slack low E an octave down: %.0f c, in tune %b", tr.cents, tr.inTune),
                Math.abs(tr.cents + 1200) < 1 && !tr.inTune && tr.tunedMask == 0);
        tr.reset(true);
        double t = feed(tr, 0, 0.6, freq(40) * Math.pow(2, -170 / 1200.0));
        double c0 = tr.cents;
        for (int i = 0; i < 120; i++) {
            double c = -170 + 170 * Math.min(1, i / 100.0);
            tr.update(t, true, freq(40) * Math.pow(2, c / 1200.0));
            t += 1024.0 / 48000;
        }
        t = feed(tr, t, 0.4, freq(40));
        check(String.format(Locale.US, "peg turned from %.0f c up to pitch: %.2f c, in tune %b, marked %b", c0, tr.cents, tr.inTune, (tr.tunedMask & 1) != 0),
                tr.inTune && (tr.tunedMask & 1) == 1 && Math.abs(tr.cents) < 0.5);
    }

    static void trackerDebounce() {
        System.out.println("tracker: debounce needs consecutive frames");
        Tracker tr = tracker("Chromatic");
        double t = feed(tr, 0, 0.4, freq(57));
        double dt = 1024.0 / 48000;
        int changes = 0, last = tr.midi;
        for (int i = 0; i < 40; i++, t += dt) {
            tr.update(t, true, freq(57) * Math.pow(2, (i % 2 == 0 ? 70 : 0) / 1200.0));
            if (tr.midi != last) changes++;
            last = tr.midi;
        }
        check("A3 with every other frame +70 c: note changes " + changes + ", note " + tr.midi, changes == 0 && tr.midi == 57);

        tr = tracker("Standard");
        t = feed(tr, 0, 0.4, freq(45));
        changes = 0;
        last = tr.string;
        for (int i = 0; i < 40; i++, t += dt) {
            tr.update(t, true, freq(i % 2 == 0 ? 50 : 45));
            if (tr.string != last) changes++;
            last = tr.string;
        }
        check("A2 with every other frame D3: string changes " + changes + ", string " + tr.string, changes == 0 && tr.string == 1);
    }

    static void trackerDrift() {
        System.out.println("tracker: drift");
        Tracker tr = tracker("Standard");
        double t = feed(tr, 0, 0.6, freq(45));
        boolean marked = (tr.tunedMask & 2) != 0;
        t = feed(tr, t, 0.5, 0);
        t = feed(tr, t, 0.6, freq(45) * Math.pow(2, -9 / 1200.0));
        check("A marked when tuned (" + marked + "), unmarked after drifting 9 c (" + ((tr.tunedMask & 2) == 0) + ")",
                marked && (tr.tunedMask & 2) == 0);
    }

    /** Leading digital zeros (capture start, system mute) must not open the gate for later hum. */
    static void humAfterSilence() {
        System.out.println("steady hum after digital silence");
        int fs = 48000;
        double peak = Math.pow(10, -66 / 20.0) * Math.sqrt(2);
        for (double lead : new double[]{0.0, 0.09, 2.0}) {
            short[] hum = quantize(add(tone(fs, 120, 30, peak, 1.0, 0.3), noise(fs, 30, -92, 5)));
            short[] x = new short[(int) (fs * lead) + hum.length];          // true zeros, not dithered
            System.arraycopy(hum, 0, x, x.length - hum.length, hum.length);
            int v = 0, n = 0;
            for (Frame f : run(fs, x)) {
                if (f.t < lead + 1.0) continue;
                n++;
                if (f.voiced) v++;
            }
            check(String.format(Locale.US, "120 Hz hum at -66 dBFS after %.2f s of zeros: voiced %d of %d", lead, v, n), v <= n / 50);
        }
    }

    /** A string plucked while the previous one still rings: the new one is read, as if alone. */
    static void overlappingStrings() {
        System.out.println("a string plucked while another rings (read within 2 cents of the string alone)");
        int fs = 48000;
        int[][] pairs = {{40, 45}, {45, 50}, {50, 55}, {55, 59}, {59, 64}, {40, 64}, {64, 59}, {45, 40}};
        for (int[] pr : pairs) {
            double[] solo = new double[1], both = new double[1];
            int[] str = new int[1];
            for (int round = 0; round < 2; round++) {
                float[] first = pluck(fs, freq(pr[0]), 3.0, round == 0 ? 0.0 : 0.3, 0.2, 1.2e-4, 40, pr[0]);
                float[] second = pluck(fs, freq(pr[1]), 2.0, 0.3, 0.2, 1.2e-4, 40, pr[1] + 7);
                float[] x = concat(noise(fs, 0.3, -62, 1), first);
                int o = (int) (fs * 1.1);
                for (int i = 0; i < second.length && o + i < x.length; i++) x[o + i] += second[i];
                Tracker tr = tracker("Standard");
                double sum = 0;
                int n = 0, s = -1;
                for (Frame f : run(fs, x)) {
                    tr.update(f.t, f.voiced, f.f, f.onset);
                    if (f.t > 1.5 && f.t < 2.3 && f.voiced) {
                        sum += Tuning.cents(f.f, freq(pr[1]));
                        n++;
                    }
                    if (s < 0 && f.t > 1.9) s = tr.string;
                }
                (round == 0 ? solo : both)[0] = n >= 10 ? sum / n : Double.NaN;
                if (round == 1) str[0] = s;
            }
            int want = -1;
            for (int i = 0; i < 6; i++) if (Tuning.ALL[Tuning.indexOf("Standard")].strings[i] == pr[1]) want = i;
            check(String.format(Locale.US, "midi %d ringing, %d plucked: string %d (want %d), %+.2f c vs %+.2f c alone",
                    pr[0], pr[1], str[0], want, both[0], solo[0]),
                    str[0] == want && Math.abs(both[0] - solo[0]) < 2.0);
        }
    }

    /** Low D ringing while the A string is plucked (Drop D, DADGAD): two strings, not a chord. */
    static void ringingFifths() {
        System.out.println("open fifths and fourths ringing together (Drop D, DADGAD): read within 1.6 cents of the string alone");
        int fs = 48000;
        int[][] pairs = {{38, 45}, {38, 50}, {45, 50}, {50, 57}, {38, 57}, {43, 50}};
        for (int[] pr : pairs) {
            for (double gap : new double[]{0.3, 0.7}) {
                double[] mean = new double[2];
                int poly = 0;
                for (int round = 0; round < 2; round++) {
                    float[] first = pluck(fs, freq(pr[0]), 3.0, round == 0 ? 0.0 : 0.3, 0.2, 1.2e-4, 40, pr[0]);
                    float[] second = pluck(fs, freq(pr[1]), 2.0, 0.3, 0.2, 1.2e-4, 40, pr[1] + 3);
                    float[] x = concat(noise(fs, 0.3, -62, 1), first);
                    int o = (int) (fs * (0.3 + gap));
                    for (int i = 0; i < second.length && o + i < x.length; i++) x[o + i] += second[i];
                    PitchEngine e = new PitchEngine(fs);
                    e.minHz = freq(38) * Math.pow(2, -600 / 1200.0);            // Drop D's floor
                    short[] pcm = quantize(x);
                    int off = 0, n = 0;
                    double sum = 0;
                    while (off < pcm.length) {
                        off += e.feed(pcm, off, pcm.length - off);
                        if (!e.ready) continue;
                        if (e.poly && round == 1) poly++;
                        if (e.voiced && e.time > 0.3 + gap + 0.4 && e.time < 0.3 + gap + 1.2) {
                            sum += Tuning.cents(e.freq, freq(pr[1]));
                            n++;
                        }
                    }
                    mean[round] = n >= 10 ? sum / n : Double.NaN;
                }
                check(String.format(Locale.US, "midi %d ringing, %d plucked %.1f s later: poly frames %d, %+.2f c vs %+.2f c alone",
                        pr[0], pr[1], gap, poly, mean[1], mean[0]), poly == 0 && Math.abs(mean[1] - mean[0]) < 1.6);
            }
        }
    }

    /**
     * A chord too quiet to count as a strum, then a slow swell with no new pluck: the chord-like
     * hops counted soon after the pluck are stale by then and must not complete a strum.
     */
    static void quietChordSwell() {
        System.out.println("a quiet chord swells slowly without a new pluck (no strum)");
        int fs = 48000;
        int[] midis = Tuning.ALL[Tuning.indexOf("Standard")].strings;
        float[] x = noise(fs, 4.0, -70, 1);
        float[][] tones = new float[midis.length][];
        for (int i = 0; i < tones.length; i++) tones[i] = tone(fs, freq(midis[i]), 4.0, 1.0, 1.0, 0.5, 0.3, 0.2);
        for (int i = 0; i < x.length; i++) {
            double t = i / (double) fs;
            double db = t < 0.5 ? -200 : t < 1.7 ? -52 : t < 2.7 ? -52 + 17 * (t - 1.7) : -35;
            double s = 0;
            for (float[] tn : tones) s += tn[i];
            x[i] += (float) (Math.pow(10, db / 20.0) * s / tones.length);
        }
        int[] poly = new int[1];
        float[] w = strumWindow(fs, x, poly);
        check("six strings from 0.5 s at -67 dBFS, swelling to -50 dBFS from 1.7 s: frames taken for several strings "
                + poly[0] + ", window handed over " + (w != null), poly[0] == 0 && w == null);
    }

    /**
     * Bass: E1 ringing under G2. E1's 2nd harmonic is the strongest stray peak, and only E1's own
     * fundamental (41 Hz, below the 55 Hz a guitar string never reaches) explains the rest.
     */
    static void bassRingingBelowGuitar() {
        System.out.println("Bass: E1 sounding under G2");
        int fs = 48000;
        double g = freq(43);
        float[] low = tone(fs, freq(28), 1.5, 0.2, 0.1, 1.0, 0.8, 0.5, 0.3, 0.2);
        float[] both = add(low, tone(fs, g, 1.5, 0.32, 1.0, 0.5, 0.3, 0.2));
        double floor = freq(28) * Math.pow(2, -600 / 1200.0);                 // the Bass tuning's floor
        double[] st = stats(run(fs, quantize(both), floor), g, 0.5);
        check(String.format(Locale.US, "G2 over a steady E1: voiced %d/%d, median %.2f c, octave errors %d",
                (int) st[2], (int) st[4], st[0], (int) st[3]), voiced(st) && st[0] < 8.0 && st[3] == 0);
    }

    static float[] strum(int fs, int[] midis, double[] cents, double spreadMs, long seed) {
        Random r = new Random(seed);
        double sec = 1.6;
        float[] mix = new float[(int) (fs * sec)];
        for (int i = 0; i < midis.length; i++) {
            if (midis[i] < 0) continue;
            double f = freq(midis[i]) * Math.pow(2, cents[i] / 1200.0);
            float[] p = pluck(fs, f, sec, 0.12 * (0.6 + 0.8 * r.nextDouble()), 0.12 + 0.1 * r.nextDouble(),
                    midis[i] < 50 ? 1.2e-4 : 0.5e-4, 70, seed * 31 + i);
            int off = (int) (fs * spreadMs / 1000.0 * i);
            for (int j = 0; j + off < mix.length && j < p.length; j++) mix[j + off] += p[j];
        }
        float[] nz = noise(fs, sec, -60, seed + 99);
        for (int j = 0; j < mix.length; j++) mix[j] += nz[j];
        return concat(noise(fs, 0.4, -62, seed), mix);
    }

    /** Runs the detector; returns the strum window it hands over, or null. Sets polyFrames[0]. */
    static float[] strumWindow(int fs, float[] x, int[] polyFrames) {
        PitchEngine e = new PitchEngine(fs);
        e.minHz = freq(40) * Math.pow(2, -600 / 1200.0);                 // Standard selected
        short[] pcm = quantize(x);
        int off = 0;
        float[] w = null;
        while (off < pcm.length) {
            off += e.feed(pcm, off, pcm.length - off);
            if (!e.ready) continue;
            if (e.poly) polyFrames[0]++;
            if (e.strum != null) w = e.strum;
        }
        return w;
    }

    static void strums() {
        System.out.println("strums");
        Tuning std = Tuning.ALL[Tuning.indexOf("Standard")];
        StrumAnalyzer an = new StrumAnalyzer();
        for (int fs : new int[]{48000, 44100, 16000}) {
            Random r = new Random(11);
            ArrayList<Double> errs = new ArrayList<>();
            int got = 0, n = 16;
            for (int t = 0; t < n; t++) {
                double[] c = new double[6];
                for (int i = 0; i < 6; i++) c[i] = (r.nextDouble() * 2 - 1) * (t < 8 ? 15 : 45);
                float[] w = strumWindow(fs, strum(fs, std.strings, c, 5 + 45 * r.nextDouble(), 200 + t), new int[1]);
                if (w == null) continue;
                StrumAnalyzer.Result res = an.analyze(w, fs, std, 440);
                if (res.chord || res.heard < 6) continue;
                got++;
                for (int i = 0; i < 6; i++) {
                    errs.add(Math.abs(res.cents[i] - c[i]));
                    if (Math.abs(res.cents[i] - c[i]) > 5) System.out.printf(Locale.US, "       (%d Hz strum %d: string %d %+.1f read %+.1f)%n", fs, t, i, c[i], res.cents[i]);
                }
            }
            Collections.sort(errs);
            double med = errs.isEmpty() ? 99 : errs.get(errs.size() / 2), p90 = errs.isEmpty() ? 99 : errs.get((int) (errs.size() * 0.9));
            double mx = errs.isEmpty() ? 99 : errs.get(errs.size() - 1);
            check(String.format(Locale.US, "%d Hz: open strums detected and read %d of %d; per string median %.2f c, p90 %.2f c, max %.2f c",
                    fs, got, n, med, p90, mx), got == n && med < 1.0 && p90 < 2.5 && mx < 8.0);
        }
        int fs = 48000;
        // D major on the top four strings (D3 A3 D4 F#4) is, to the ear and to the detector, a
        // low D with its fundamental missing: it reads as one note.
        String[] names = {"C major", "G major", "E minor", "A minor", "E major", "F major", "A major", "E minor 7"};
        int[][] ch = {{-1, 48, 52, 55, 60, 64}, {43, 47, 50, 55, 59, 67}, {40, 47, 52, 55, 59, 64}, {-1, 45, 52, 57, 60, 64},
                {40, 47, 52, 56, 59, 64}, {41, 48, 53, 57, 60, 65}, {-1, 45, 52, 57, 61, 64}, {40, 47, 50, 55, 59, 64}};
        StringBuilder wrong = new StringBuilder();
        int chords = 0;
        for (int k = 0; k < ch.length; k++) {
            float[] w = strumWindow(fs, strum(fs, ch[k], new double[6], 12, 300 + k), new int[1]);
            if (w != null && an.analyze(w, fs, std, 440).chord) chords++;
            else wrong.append(' ').append(names[k]);
        }
        check("fretted chords recognised as chords: " + chords + " of " + ch.length + (wrong.length() > 0 ? ", missed:" + wrong : ""), chords == ch.length);
        String[][] other = {{"Drop D", "Drop D"}, {"Half step down", "Half step down"}, {"Full step down", "Full step down"},
                {"DADGAD", "DADGAD"}, {"Open G", "chord"}, {"Open E", "chord"}};
        for (String[] o : other) {
            Tuning t = Tuning.ALL[Tuning.indexOf(o[0])];
            float[] w = strumWindow(fs, strum(fs, t.strings, new double[6], 12, o[0].hashCode()), new int[1]);
            StrumAnalyzer.Result res = w == null ? null : an.analyze(w, fs, std, 440);
            String said = res == null ? "nothing" : res.chord ? "chord" : res.suggest >= 0 ? Tuning.ALL[res.suggest].name : "no suggestion";
            check("guitar in " + o[0] + ", Standard selected: " + said, said.equals(o[1]));
        }
        int falsePoly = 0;
        for (int m : new int[]{40, 45, 50, 55, 59, 64}) {
            int[] pf = new int[1];
            strumWindow(fs, concat(noise(fs, 0.4, -62, m), pluck(fs, freq(m), 2.0, 0.3, 0.2, 1.2e-4, 30, m)), pf);
            if (pf[0] > 0) falsePoly++;
        }
        check("single plucks taken for a strum: " + falsePoly + " of 6", falsePoly == 0);

        // loud sounds without pitch must not read as "several strings"
        String[] what = {"clap -20 dBFS", "clap -30 dBFS", "speech -35 dBFS", "speech -30 dBFS", "rustle -40 dBFS",
                "room noise -50 dBFS from the start", "room noise -45 dBFS from the start"};
        float[][] sounds = {
                mixAt(roomNoise(fs, 3.0, -65, 7), clap(fs, 0.8, -20, 8), fs),
                mixAt(roomNoise(fs, 3.0, -65, 7), clap(fs, 0.8, -30, 8), fs),
                mixAt(roomNoise(fs, 4.0, -65, 9), speech(fs, 2.0, -35, 10), fs),
                mixAt(roomNoise(fs, 5.0, -65, 9), speech(fs, 3.0, -30, 11), fs),
                concat(roomNoise(fs, 2.0, -65, 4), white(fs, 1.5, -40, 5)),
                roomNoise(fs, 4.0, -50, 3), roomNoise(fs, 4.0, -45, 3)};
        for (int k = 0; k < sounds.length; k++) {
            int[] pf = new int[1];
            strumWindow(fs, sounds[k], pf);
            check(what[k] + ": frames taken for several strings " + pf[0], pf[0] == 0);
        }
    }

    static float[] white(int fs, double sec, double dbfs, long seed) {
        float[] x = new float[(int) (fs * sec)];
        Random r = new Random(seed);
        double a = Math.pow(10, dbfs / 20.0);
        for (int i = 0; i < x.length; i++) x[i] = (float) (a * r.nextGaussian());
        return x;
    }

    /** Low-passed noise, a room's rumble and hiss. */
    static float[] roomNoise(int fs, double sec, double dbfs, long seed) {
        float[] w = white(fs, sec, 0, seed);
        double y = 0, k = Math.exp(-2 * Math.PI * 400.0 / fs), ss = 0;
        double[] v = new double[w.length];
        for (int i = 0; i < w.length; i++) {
            y = k * y + (1 - k) * w[i];
            v[i] = y;
            ss += y * y;
        }
        double g = Math.pow(10, dbfs / 20.0) / Math.sqrt(ss / w.length);
        float[] x = new float[w.length];
        for (int i = 0; i < x.length; i++) x[i] = (float) (v[i] * g);
        return x;
    }

    static float[] clap(int fs, double sec, double dbfs, long seed) {
        float[] x = white(fs, sec, dbfs, seed);
        for (int i = 0; i < x.length; i++) x[i] *= (float) Math.exp(-(i / (double) fs) / 0.12);
        return x;
    }

    /** Syllables: a gliding glottal pulse train through three moving formants, with fricatives. */
    static float[] speech(int fs, double sec, double dbfs, long seed) {
        Random r = new Random(seed);
        float[] x = new float[(int) (fs * sec)];
        double ph = 0;
        double[] fF = {600, 1200, 2500};
        double[][] st = new double[3][2];
        for (int i = 0; i < x.length; i++) {
            double t = i / (double) fs, syl = (t % 0.28) / 0.28;
            double env = syl < 0.8 ? Math.sin(Math.PI * syl / 0.8) : 0;
            double f0 = 120 + 40 * Math.sin(2 * Math.PI * 1.3 * t) + 15 * Math.sin(2 * Math.PI * 4.1 * t);
            ph += f0 / fs;
            double src = 0;
            if (ph >= 1) {
                ph -= 1;
                src = 1;
            }
            if (syl > 0.55 && syl < 0.8) src = 0.3 * r.nextGaussian();
            double out = 0;
            for (int k = 0; k < 3; k++) {
                double w = 2 * Math.PI * fF[k] * (1 + 0.15 * Math.sin(2 * Math.PI * 2.2 * t + k)) / fs, rr = 0.97;
                double yk = src + 2 * rr * Math.cos(w) * st[k][0] - rr * rr * st[k][1];
                st[k][1] = st[k][0];
                st[k][0] = yk;
                out += yk / (k + 1);
            }
            x[i] = (float) (out * env);
        }
        double ss = 0;
        for (float v : x) ss += v * v;
        double g = Math.pow(10, dbfs / 20.0) / Math.sqrt(ss / x.length);
        for (int i = 0; i < x.length; i++) x[i] = (float) (x[i] * g);
        return x;
    }

    static float[] mixAt(float[] a, float[] b, int off) {
        float[] x = a.clone();
        for (int i = 0; i < b.length && off + i < x.length; i++) x[off + i] += b[i];
        return x;
    }

    static float[] add(float[] a, float[] b) {
        float[] x = new float[Math.min(a.length, b.length)];
        for (int i = 0; i < x.length; i++) x[i] = a[i] + b[i];
        return x;
    }

    /** Real time on a phone needs a frame analysed in less than the audio it covers. */
    static void timing() {
        System.out.println("timing");
        int fs = 48000;
        PitchEngine e = new PitchEngine(fs);
        short[] pcm = quantize(pluck(fs, freq(40), 10.0, 0.25, 0.14, 0, 30, 5));
        int off = 0, frames = 0;
        long t0 = System.nanoTime();
        while (off < pcm.length) {
            off += e.feed(pcm, off, pcm.length - off);
            if (e.ready) frames++;
        }
        double ms = (System.nanoTime() - t0) / 1e6, hopMs = 1000.0 * e.hop / fs;
        check(String.format(Locale.US, "%d frames in %.0f ms on this JVM: %.2f ms per frame, a frame is %.1f ms of audio",
                frames, ms, ms / frames, hopMs), ms / frames < hopMs);
    }
}
