package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * atel (this fork): an appointment's reminder over whatever is on screen (a film, a series), top right, without
 * pausing it: smoked glass like the home screen's at night, the amber dot, "Reminder", the hour and the title, then
 * "in 30 min · the place". It takes no key and no touch (the app in front keeps the remote) and goes away by itself
 * after 15 s. A foreground service in a process of its own (:reminder), as the sleep timer's pill is: everything it
 * shows comes in its Intent.
 */
public class AtelReminderOverlay extends Service {
    private static final long SHOW_MS = 15_000, FADE_MS = 400;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Card card;

    private final Runnable hide = () -> {
        if (card == null) {
            stopSelf();
            return;
        }
        card.animate().alpha(0).setDuration(FADE_MS).withEndAction(this::stopSelf).start();
    };

    @Override public void onCreate() {
        super.onCreate();
        Ui.init(this);   // its own process: the app's font and catalogue aren't loaded here yet
        Ui.applyLanguage();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("atel-reminder", Ui.tr("atel|Reminder"), NotificationManager.IMPORTANCE_MIN));
        startForeground(2, new Notification.Builder(this, "atel-reminder").setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("atel|Reminder")).build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        long start = intent.getLongExtra("start", 0);
        String title = intent.getStringExtra("title"), place = intent.getStringExtra("place");
        WindowManager wm = getSystemService(WindowManager.class);
        if (card != null) wm.removeView(card);
        card = new Card(this, Ui.time(start), title == null ? "" : title, when(start, place == null ? "" : place));
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.RIGHT;
        layout.x = Math.round(card.k * 96);
        layout.y = Math.round(card.k * 96);
        card.setAlpha(0);
        try {
            wm.addView(card, layout);
        } catch (RuntimeException e) {   // drawing over other apps taken back meanwhile
            card = null;
            stopSelf();
            return START_NOT_STICKY;
        }
        card.animate().alpha(1).setDuration(FADE_MS).start();
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, SHOW_MS);
        return START_NOT_STICKY;   // a reminder is for now: not shown again if Android stops it
    }

    /** "in 30 min · Parc de la Tête d'Or", "now" once it has started. */
    private static String when(long start, String place) {
        long left = start - System.currentTimeMillis();
        String w = left <= 0 ? Ui.tr("atel|now") : Ui.trf("atel|in {time}", "time", Atel.duration(left));
        return place.isEmpty() ? w : w + " · " + place;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(hide);
        if (card != null) {
            try {
                getSystemService(WindowManager.class).removeView(card);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** The card, drawn by hand like the home screen; its text is cut to fit once, here. */
    private static final class Card extends View {
        final float k;
        private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                dot = new Paint(Paint.ANTI_ALIAS_FLAG), label, hour, name, sub;
        private final String labelText, hourText, nameText, subText;
        private final RectF box = new RectF();
        private final int w, h;

        Card(Context c, String hourText, String title, String subText) {
            super(c);
            k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
            glass.setColor(0xE61C1C1E);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * k);
            edge.setColor(0x2EFFFFFF);
            dot.setColor(0xFFF2C46D);
            label = Ui.text(Ui.semibold, 24 * k, 0xADFFFFFF);
            hour = Ui.text(Ui.semibold, 40 * k, 0xFFF2C46D);
            name = Ui.text(Ui.medium, 40 * k, 0xFFFFFFFF);
            sub = Ui.text(Ui.regular, 28 * k, 0xADFFFFFF);
            labelText = Ui.tr("atel|Reminder");
            this.hourText = hourText;
            float maxText = 700 * k;
            nameText = TextUtils.ellipsize(title, new android.text.TextPaint(name), maxText, TextUtils.TruncateAt.END).toString();
            this.subText = TextUtils.ellipsize(subText, new android.text.TextPaint(sub), maxText + hour.measureText(hourText) + 20 * k,
                    TextUtils.TruncateAt.END).toString();
            float line = hour.measureText(hourText) + 20 * k + name.measureText(nameText);
            float width = Math.max(line, Math.max(sub.measureText(this.subText), label.measureText(labelText) + 30 * k));
            w = Math.round(width + 2 * 44 * k);
            h = Math.round(196 * k);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(w, h);
        }

        @Override protected void onDraw(Canvas c) {
            float r = 32 * k;
            box.set(k, k, w - k, h - k);
            c.drawRoundRect(box, r, r, glass);
            c.drawRoundRect(box, r, r, edge);
            float x = 44 * k;
            c.drawCircle(x + 7 * k, 58 * k - Ui.halfCap(label), 7 * k, dot);
            c.drawText(labelText, x + 26 * k, 58 * k, label);
            c.drawText(hourText, x, 116 * k, hour);
            c.drawText(nameText, x + hour.measureText(hourText) + 20 * k, 116 * k, name);
            if (!subText.isEmpty()) c.drawText(subText, x, 162 * k, sub);
        }
    }
}
