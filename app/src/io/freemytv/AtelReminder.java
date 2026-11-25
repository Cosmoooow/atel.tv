package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * atel (this fork): a reminder over whatever is on screen, {@link #LEAD_MS} before each appointment with an hour
 * (AtelReminderOverlay). The alarm is set from the last agenda (by the home screen, AtelJob or after a restart) and,
 * when it goes off, the agenda on disk says which appointment it is for, so a moved or cancelled one isn't announced.
 * Each appointment is announced once (its key in `atel-reminded`, read by every process).
 */
final class AtelReminder {
    private AtelReminder() {}

    static final String ACTION = "io.freemytv.ATEL_REMINDER";
    static final long LEAD_MS = 30 * 60_000L;
    private static final String TAG = "FreeMyTV";

    private static PendingIntent alarm(Context c, int flags) {
        Intent i = new Intent(ACTION).setClass(c, HomeReceiver.class);
        return PendingIntent.getBroadcast(c, 4318, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The next appointment to announce, from `now`: its hour not passed yet, not announced yet. */
    private static Atel.Item upcoming(Context c, Atel.Agenda a, long now) {
        String done = reminded(c);
        for (Atel.Item e : a.events) {
            if (e.allDay || e.start <= now + 60_000L) continue;
            if (key(e).equals(done)) continue;
            return e;
        }
        return null;
    }

    /** Sets (or moves, or drops) the alarm for the next appointment. Off the main thread: it reads a file. */
    static void schedule(Context c, Atel.Agenda a) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        Atel.Item next = a == null ? null : upcoming(c, a, now);
        if (next == null) {
            PendingIntent old = alarm(c, PendingIntent.FLAG_NO_CREATE);
            if (old != null) am.cancel(old);
            return;
        }
        // One added less than 30 minutes ahead is announced right away.
        long at = Math.max(now + 1_000L, next.start - LEAD_MS);
        PendingIntent pi = alarm(c, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {   // exact alarms refused: up to a minute late
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
        }
        // Drawing over other apps is allowed through the TV's own ADB, as for the sleep timer (once).
        if (!Settings.canDrawOverlays(c) && Capabilities.overlay(c)) SleepOverlay.allow(c.getApplicationContext());
    }

    /** After a restart: the alarms are gone, the agenda on disk sets the next one. */
    static void restore(Context c) {
        if (Atel.configured()) schedule(c, Atel.cached(c));
        AtelJob.ensure(c);
    }

    /** The alarm went off (HomeReceiver, off the main thread): shows the appointment, then sets the next alarm. */
    static void fire(Context c) {
        Atel.Agenda a = Atel.cached(c);
        if (a == null) return;
        long now = System.currentTimeMillis();
        String done = reminded(c);
        for (Atel.Item e : a.events) {
            if (e.allDay || e.start < now - 60_000L || e.start > now + LEAD_MS + 60_000L) continue;
            if (key(e).equals(done)) continue;
            noteReminded(c, e);
            if (Settings.canDrawOverlays(c)) {
                c.startForegroundService(new Intent(c, AtelReminderOverlay.class).putExtra("title", e.title)
                        .putExtra("place", e.place).putExtra("start", e.start));
                Log.i(TAG, "atel: reminder shown");
            } else {
                Log.i(TAG, "atel: reminder not shown (drawing over other apps isn't allowed)");
            }
            break;
        }
        schedule(c, a);
    }

    private static String key(Atel.Item e) {
        return e.start + ":" + e.title.hashCode();
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "atel-reminded");
    }

    private static String reminded(Context c) {
        try {
            File f = file(c);
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void noteReminded(Context c, Atel.Item e) {
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(key(e).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            Log.w(TAG, "atel: could not note the reminder: " + ex.getMessage());
        }
    }
}
