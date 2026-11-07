package app.tuner.guitar;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/**
 * Reference tone. A phone speaker hardly reproduces a low E (82 Hz), so the tone carries eight
 * harmonics: the ear hears the fundamental from them. Phase-continuous, with 20 ms fades and a
 * short glide when the pitch changes, so starting, stopping and switching strings never click.
 */
final class ToneSynth implements Runnable {

    interface Listener {
        /** Tone thread: the output could not be opened or stopped accepting samples. */
        void onToneFailed();
    }

    private static final double[] HARM = {1.0, 0.7, 0.55, 0.42, 0.3, 0.22, 0.15, 0.1};
    private static final double LEVEL = 0.45;

    private final Listener listener;
    private volatile double freq = 440.0;
    private boolean on, running;          // guarded by this

    ToneSynth(Listener listener) {
        this.listener = listener;
    }

    /** True while a tone is wanted; false once stopped or after a failure. */
    synchronized boolean playing() {
        return on;
    }

    synchronized void play(double hz) {
        freq = hz;
        on = true;
        if (!running) {
            running = true;
            new Thread(this, "tuner-tone").start();
        }
    }

    synchronized void stop() {
        on = false;
    }

    private synchronized boolean wanted() {
        return on;
    }

    /** Called by the tone thread once it has faded out; false if play() came in meanwhile. */
    private synchronized boolean finish() {
        if (on) return false;
        running = false;
        return true;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        int sr = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        if (sr <= 0) sr = 48000;
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, sr / 20 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (RuntimeException e) {
            if (track != null) track.release();
            fail();
            return;
        }

        double norm = 0;
        for (double h : HARM) norm += h;
        short[] buf = new short[256];
        double phase = 0.0, gain = 0.0, f = freq;
        double kGain = 1.0 - Math.exp(-1.0 / (0.02 * sr / 5.0));     // ~20 ms to settle
        double kGlide = 1.0 - Math.exp(-1.0 / (0.03 * sr));
        boolean failed = false;
        while (true) {
            boolean want = wanted();
            double target = want ? 1.0 : 0.0, ft = freq;
            for (int i = 0; i < buf.length; i++) {
                gain += (target - gain) * kGain;
                f *= Math.pow(ft / f, kGlide);
                phase += f / sr;
                if (phase >= 1.0) phase -= Math.floor(phase);
                double v = 0.0, w = 2.0 * Math.PI * phase;
                for (int k = 0; k < HARM.length; k++) {
                    if ((k + 1) * f > sr * 0.45) break;
                    v += HARM[k] * Math.sin((k + 1) * w);
                }
                buf[i] = (short) Math.round(v / norm * gain * LEVEL * 32767.0);
            }
            if (track.write(buf, 0, buf.length) < 0) {
                failed = true;                                  // e.g. the audio server restarted
                break;
            }
            if (!want && gain < 1e-4 && finish()) break;
        }
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
        }
        track.release();
        if (failed) fail();
    }

    private void fail() {
        synchronized (this) {
            running = false;
            on = false;
        }
        listener.onToneFailed();
    }
}
