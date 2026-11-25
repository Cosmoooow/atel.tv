package io.freemytv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * atel (this fork): today's agenda and reminders from the atel home server's read-only device API
 * (`GET <atel_server>/device/v1/agenda?days=7`, atel/API-APPAREILS.md), for the "Aujourd'hui" card next to the
 * weather. The TV's token comes from Secrets (tools/send_key.py), never from the APK. Without a server in
 * config.properties the card shows sample data; with one, the last good answer is kept on disk so the card has
 * something after a restart while the server (a Mac that sleeps, during development) is away. Read only: the TV
 * never writes to atel.
 */
final class Atel {
    private Atel() {}

    private static final String TAG = "FreeMyTV";
    /** How often the agenda is fetched again while the home screen shows (and at most this often on resume). */
    private static final long EVERY_MS = 5 * 60_000L;
    private static final long RETRY_MS = 60_000L;
    private static final long STALE_MS = 15 * 60_000L;   // three missed refreshes
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    /** An appointment (start set) or a reminder (due set, start 0). Times in ms since the epoch. */
    static final class Item {
        final String title, place;
        final long start, end, due;
        final boolean allDay;

        Item(String title, String place, long start, long end, boolean allDay, long due) {
            this.title = title;
            this.place = place;
            this.start = start;
            this.end = end;
            this.allDay = allDay;
            this.due = due;
        }

        boolean reminder() { return start == 0; }
    }

    /** One answer from the server (or the sample). */
    static final class Agenda {
        final List<Item> events, reminders;
        final long at;
        final boolean sample;

        Agenda(List<Item> events, List<Item> reminders, long at, boolean sample) {
            this.events = events;
            this.reminders = reminders;
            this.at = at;
            this.sample = sample;
        }
    }

    /** A line of the card, built once a minute (never while drawing). */
    static final class Row {
        /** sub: the place, or for a reminder when it's due. badge: when, for the first line ("in 1 h 50"). */
        final String time, title, sub, badge;
        final boolean check, strong, tomorrow;
        /** Not today: the day after tomorrow or later in the week (its date then stands in the time column). */
        final String day;

        Row(String time, String title, String sub, String badge, boolean check, boolean strong, boolean tomorrow) {
            this(time, title, sub, badge, check, strong, tomorrow, "");
        }

        Row(String time, String title, String sub, String badge, boolean check, boolean strong, boolean tomorrow, String day) {
            this.day = day;
            this.time = time;
            this.title = title;
            this.sub = sub;
            this.badge = badge;
            this.check = check;
            this.strong = strong;
            this.tomorrow = tomorrow;
        }
    }

    /** How far ahead the TV looks: a week, so the next appointment shows even when the next two days are empty. */
    static final int DAYS = 7;

    private static volatile Agenda agenda;
    private static volatile long fetchedAt, triedAt;
    private static volatile boolean loading;

    static boolean configured() {
        return !Config.ATEL_SERVER.isEmpty();
    }

    /**
     * Whether the server answered lately: the home screen shows the agenda only then, and the clock otherwise (no
     * server set, not reached since the app started, or no answer for STALE_MS). The disk cache alone doesn't count:
     * an old agenda could show what was moved or cancelled since.
     */
    static boolean online(long now) {
        long at = fetchedAt;
        return configured() && at > 0 && now - at < STALE_MS;
    }

    /** What the card shows now: the server's last answer, or the sample. Never null. */
    static Agenda now() {
        Agenda a = agenda;
        if (a != null) return a;
        return configured() ? new Agenda(new ArrayList<>(), new ArrayList<>(), 0, false) : sample(System.currentTimeMillis());
    }

    /**
     * Fetches again if the last answer is old (or `force`), off the main thread; `changed` runs on that thread when
     * something new came, so the caller posts its redraw.
     */
    static void refresh(Context context, boolean force, Runnable changed) {
        if (!configured()) return;
        long now = System.currentTimeMillis();
        if (loading || !force && (now - fetchedAt < EVERY_MS || now - triedAt < RETRY_MS)) return;
        loading = true;
        triedAt = now;
        final Context c = context.getApplicationContext();
        LOADER.execute(() -> {
            try {
                boolean fresh = false;
                if (agenda == null) {
                    String cached = readCache(c);
                    if (cached != null) {
                        agenda = parse(cached);
                        // A copy saved minutes ago (the screen came back after a restart) counts as an answer.
                        long saved = cache(c).lastModified();
                        if (saved <= now && now - saved < STALE_MS) fetchedAt = saved;
                        fresh = true;
                    }
                }
                String token = Secrets.get(c, Secrets.ATEL_TOKEN);
                try {
                    String body = request(token, "/device/v1/agenda?days=" + DAYS);
                    agenda = parse(body);
                    fetchedAt = System.currentTimeMillis();
                    writeCache(c, body);
                    fresh = true;
                } catch (Exception e) {
                    Log.i(TAG, "atel: agenda not fetched (" + e.getMessage() + ")");   // never the token
                    fresh = true;   // the screen checks online() again
                }
                if (fresh && changed != null) changed.run();
            } catch (Throwable t) {
                // :home's watchdog counts a crash here as a home screen crash: nothing may escape.
                Log.w(TAG, "atel: " + t);
            } finally {
                loading = false;
            }
        });
    }

    // MARK: - The card's lines

    /**
     * At most `max` lines: today's appointments not over yet, then tomorrow's, then the reminders due by tomorrow (or
     * late), then the next reminders. The badge says when: "in 1 h 50", "now", "tomorrow", "15 Dec".
     */
    static List<Row> rows(Agenda a, long now, int max) {
        List<Row> out = new ArrayList<>();
        long today = startOfDay(now, 0), tomorrow = startOfDay(now, 1), after = startOfDay(now, 2), week = startOfDay(now, DAYS);
        for (Item e : a.events) {
            if (out.size() >= max) break;
            if (e.end > 0 ? e.end <= now : e.start < today) continue;
            if (e.start >= week) continue;
            if (e.start >= after) {
                // Later in the week: the date in the time column, the weekday as the badge, the hour before the place.
                Date d = new Date(e.start);
                String hour = e.allDay ? "" : Ui.time(e.start);
                String sub = hour.isEmpty() ? e.place : e.place.isEmpty() ? hour : hour + " · " + e.place;
                out.add(new Row(new SimpleDateFormat("d MMM", Ui.locale()).format(d), e.title, sub,
                        new SimpleDateFormat("EEEE", Ui.locale()).format(d), false, false, true,
                        new SimpleDateFormat("EEEE d MMMM", Ui.locale()).format(d)));
                continue;
            }
            boolean isTomorrow = e.start >= tomorrow;
            String time = e.allDay ? Ui.tr("atel|All day") : Ui.time(e.start);
            String badge;
            boolean strong = !isTomorrow;
            if (isTomorrow) badge = Ui.tr("tomorrow");
            else if (e.allDay) badge = Ui.tr("today");
            else if (e.start <= now) badge = Ui.tr("atel|now");
            else badge = Ui.trf("atel|in {time}", "time", duration(e.start - now));
            out.add(new Row(time, e.title, e.place, badge, false, strong, isTomorrow));
        }
        for (Item r : a.reminders) {
            if (out.size() >= max) break;
            boolean soon = r.due < after;
            String due = dueText(r.due, today, tomorrow);
            out.add(new Row("", r.title, due, due, true, soon, r.due >= tomorrow));
        }
        return out;
    }

    private static String dueText(long due, long today, long tomorrow) {
        if (due < today) return Ui.tr("atel|late");
        if (due < tomorrow) return Ui.tr("today");
        if (due < tomorrow + 86_400_000L + 3_600_000L && due >= tomorrow) return Ui.tr("tomorrow");
        return new SimpleDateFormat("d MMM", Ui.locale()).format(new Date(due));
    }

    /** "1 h 50", "25 min". */
    static String duration(long ms) {
        long min = Math.max(1, (ms + 59_999) / 60_000);
        if (min < 60) return Ui.trf("atel|{n} min", "n", min);
        long h = min / 60, m = min % 60;
        return m == 0 ? Ui.trf("atel|{h} h", "h", h) : Ui.trf("atel|{h} h {m}", "h", h, "m", String.format(Ui.locale(), "%02d", m));
    }

    static long startOfDay(long at, int plusDays) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, plusDays);
        return c.getTimeInMillis();
    }

    // MARK: - The server

    /** Parses the agenda answer (atel/API-APPAREILS.md); unknown fields are ignored, broken items skipped. */
    static Agenda parse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        List<Item> events = new ArrayList<>(), reminders = new ArrayList<>();
        JSONArray ev = o.optJSONArray("events");
        for (int i = 0; ev != null && i < ev.length(); i++) {
            JSONObject e = ev.optJSONObject(i);
            if (e == null) continue;
            try {
                boolean allDay = e.optBoolean("all_day", false);
                long start = instant(e.getString("start"));
                String endText = e.optString("end", "");
                long end = endText.isEmpty() ? 0 : instant(endText);
                events.add(new Item(e.optString("title", "").trim(), e.optString("location", "").trim(), start, end, allDay, 0));
            } catch (Exception skip) {
                // one broken event doesn't hide the others
            }
        }
        JSONArray rem = o.optJSONArray("reminders");
        for (int i = 0; rem != null && i < rem.length(); i++) {
            JSONObject r = rem.optJSONObject(i);
            if (r == null || r.optBoolean("done", false)) continue;
            try {
                reminders.add(new Item(r.optString("title", "").trim(), "", 0, 0, true, instant(r.getString("due"))));
            } catch (Exception skip) {
                // same
            }
        }
        Collections.sort(events, (x, y) -> Long.compare(x.start, y.start));
        Collections.sort(reminders, (x, y) -> Long.compare(x.due, y.due));
        return new Agenda(events, reminders, System.currentTimeMillis(), false);
    }

    /** "2026-11-26T09:00:00+01:00" (or with Z), or a plain date "2026-11-26" (local midnight). */
    static long instant(String s) {
        if (s.length() == 10) return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return OffsetDateTime.parse(s).toInstant().toEpochMilli();
    }

    private static String request(String token, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Config.ATEL_SERVER.replaceAll("/+$", "") + path).openConnection();
        // A redirect would carry the token to wherever it points, and past lan_server's check: none is followed.
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "ateltv");
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("the token was refused (HTTP " + code + ")");
            if (code >= 300 && code < 400) throw new IOException("HTTP " + code + ": redirects aren't followed");
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 512 * 1024) throw new IOException("answer too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    private static File cache(Context c) {
        return new File(c.getFilesDir(), "atel-agenda.json");
    }

    private static String readCache(Context c) {
        try {
            File f = cache(c);
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context c, String body) {
        File f = cache(c), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    // MARK: - Sample data (no server set)

    /** Made around `now`, so it always reads as today: an outing in a little while, tomorrow's appointment, reminders. */
    static Agenda sample(long now) {
        long today = startOfDay(now, 0), tomorrow = startOfDay(now, 1);
        long soon = (now / 1_800_000L + 4) * 1_800_000L;   // 1 h 30 to 2 h from now, on the half hour
        List<Item> events = new ArrayList<>(), reminders = new ArrayList<>();
        if (soon < tomorrow) events.add(new Item("Course à pied", "Parc de la Tête d'Or", soon, soon + 3_600_000L, false, 0));
        events.add(new Item("Dentiste", "Lyon 3e", tomorrow + 9 * 3_600_000L, tomorrow + 10 * 3_600_000L, false, 0));
        events.add(new Item("Appel notaire", "visio", tomorrow + 14 * 3_600_000L, tomorrow + 15 * 3_600_000L, false, 0));
        reminders.add(new Item("Attestation CAF", "", 0, 0, true, tomorrow));
        reminders.add(new Item("Taxe foncière", "", 0, 0, true, today + 20 * 86_400_000L));
        return new Agenda(events, reminders, now, true);
    }
}
