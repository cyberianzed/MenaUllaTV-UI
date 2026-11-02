package io.cleanyourtv.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The sound as the focus moves (Home Settings › Navigation sound). The TV's own system sounds are often off, so the
 * launcher brings its own: three short tones synthesised once into the cache (a few KB each) and played through a
 * SoundPool, made for exactly this (low latency, two streams, nothing kept running).
 */
final class Tones {
    private Tones() {}

    /** "soft" (the default), "tick", "pop", "system" (the TV's own focus sound) or "off". */
    static final String[] STYLES = {"soft", "tick", "pop", "system", "off"};

    private static SoundPool pool;
    private static final int[] ids = new int[3];

    static synchronized void init(Context c) {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        String[] names = {"soft", "tick", "pop"};
        for (int i = 0; i < names.length; i++) {
            File f = new File(c.getCacheDir(), "tone-" + names[i] + "-1.wav");
            try {
                if (!f.isFile()) write(f, synth(names[i]));
                ids[i] = pool.load(f.getPath(), 1);
            } catch (Exception e) {
                AppLog.w("tone " + names[i] + ": " + e.getMessage());
            }
        }
    }

    /** As the focus moves; [fx] is the matching system sound, for the "system" style. */
    static void play(Context c, AudioManager audio, int fx) {
        String style = Prefs.navSound();
        if (style.equals("off")) return;
        if (style.equals("system")) {
            if (audio != null) audio.playSoundEffect(fx);
            return;
        }
        if (pool == null) init(c);
        int i = style.equals("tick") ? 1 : style.equals("pop") ? 2 : 0;
        if (ids[i] != 0) pool.play(ids[i], 0.55f, 0.55f, 1, 0, 1f);
    }

    private static final int RATE = 22050;

    /** 16-bit mono PCM: a short tone that rises in a few milliseconds and dies away, so it never clicks. */
    private static short[] synth(String name) {
        double ms, f0, f1, amp;
        switch (name) {
            case "tick": ms = 16; f0 = 2300; f1 = 2300; amp = 0.30; break;   // a dry, light tick
            case "pop": ms = 45; f0 = 520; f1 = 880; amp = 0.34; break;     // a rising bubble
            default: ms = 38; f0 = 1150; f1 = 1100; amp = 0.30; break;      // soft, like a TV's own
        }
        int n = (int) (RATE * ms / 1000);
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            double f = f0 + (f1 - f0) * t;
            phase += 2 * Math.PI * f / RATE;
            double attack = Math.min(1, i / (RATE * 0.003));
            double env = attack * Math.exp(-5.5 * t);
            double v = Math.sin(phase) + 0.25 * Math.sin(2 * phase);   // a little warmth from the second harmonic
            out[i] = (short) (Math.max(-1, Math.min(1, v * env * amp)) * 32767);
        }
        return out;
    }

    private static void write(File f, short[] pcm) throws Exception {
        int data = pcm.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + data).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(data);
        for (short s : pcm) b.putShort(s);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b.array());
        }
    }
}
