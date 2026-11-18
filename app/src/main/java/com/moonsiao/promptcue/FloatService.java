package com.moonsiao.promptcue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Teleprompter window that floats above every app (e.g. the system camera). */
public class FloatService extends Service implements Choreographer.FrameCallback {
    private static final String CH = "float";
    private WindowManager wm;
    private LinearLayout root;
    private WindowManager.LayoutParams lp;
    private ScrollView scroll;
    private TextView text, playBtn, speedTv, fontTv;
    private boolean playing = false;
    private float speed = 40, font = 24, pos = 0, alpha = 0.6f;
    private long lastFrame = 0;
    private SharedPreferences prefs;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent == null) return START_NOT_STICKY;
        if ("stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs = getSharedPreferences("float", MODE_PRIVATE);
        speed = intent.getIntExtra("speed", 40);
        font = prefs.getFloat("font", intent.getIntExtra("font", 24));
        alpha = prefs.getFloat("alpha", 0.6f);
        if (root == null) build();
        text.setText(intent.getStringExtra("text"));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, font);
        pos = 0;
        scroll.scrollTo(0, 0);
        setPlaying(false);
        updateLabels();
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "悬浮提词", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FloatService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("悬浮提词运行中")
                .setContentText("点「关闭」结束")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "关闭", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView btn(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private void build() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        applyBg();

        // header: drag handle + controls
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView handle = btn("⠿", null);
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bar.addView(handle);
        playBtn = btn("▶", v -> setPlaying(!playing));
        bar.addView(playBtn);
        bar.addView(btn("⏮", v -> { pos = 0; scroll.scrollTo(0, 0); }));
        bar.addView(btn("慢", v -> { speed = Math.max(1, speed - 4); updateLabels(); }));
        speedTv = btn("", null);
        speedTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bar.addView(speedTv);
        bar.addView(btn("快", v -> { speed = Math.min(200, speed + 4); updateLabels(); }));
        bar.addView(btn("A-", v -> setFont(font - 2)));
        fontTv = btn("", null);
        fontTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bar.addView(fontTv);
        bar.addView(btn("A+", v -> setFont(font + 2)));
        bar.addView(btn("◐", v -> { alpha = alpha > 0.75f ? 0.3f : alpha + 0.15f; applyBg(); prefs.edit().putFloat("alpha", alpha).apply(); }));
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(btn("✕", v -> stopSelf()));
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        text = new TextView(this);
        text.setTextColor(0xFFFFFFFF);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setLineSpacing(0, 1.35f);
        text.setShadowLayer(4, 0, 1, 0xFF000000);
        text.setPadding(dp(14), dp(4), dp(14), dm.heightPixels / 2);
        text.setOnClickListener(v -> setPlaying(!playing));
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // bottom resize grip
        TextView grip = btn("═══", null);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        grip.setPadding(0, dp(2), 0, dp(4));
        root.addView(grip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                prefs.getInt("w", (int) (dm.widthPixels * 0.94f)),
                prefs.getInt("h", (int) (dm.heightPixels * 0.32f)),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", (int) (dm.widthPixels * 0.03f));
        lp.y = prefs.getInt("y", dp(60));

        handle.setOnTouchListener(new Drag(false));
        bar.setOnTouchListener(new Drag(false));
        grip.setOnTouchListener(new Drag(true));

        wm.addView(root, lp);
    }

    /** Drags the window (move) or its bottom edge (resize). */
    private class Drag implements View.OnTouchListener {
        final boolean resize;
        float sx, sy;
        int ox, oy, ow, oh;

        Drag(boolean resize) {
            this.resize = resize;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX();
                    sy = e.getRawY();
                    ox = lp.x;
                    oy = lp.y;
                    ow = root.getWidth();
                    oh = root.getHeight();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    if (resize) {
                        lp.height = Math.max(dp(120), (int) (oh + dy));
                        lp.width = ow;
                    } else {
                        lp.x = (int) (ox + dx);
                        lp.y = Math.max(0, (int) (oy + dy));
                    }
                    wm.updateViewLayout(root, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).putInt("h", lp.height).putInt("w", lp.width).apply();
                    return true;
            }
            return false;
        }
    }

    private void applyBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(((int) (alpha * 255) << 24));
        g.setCornerRadius(dp(16));
        root.setBackground(g);
    }

    private void setFont(float f) {
        font = Math.max(12, Math.min(72, f));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, font);
        prefs.edit().putFloat("font", font).apply();
        updateLabels();
    }

    private void updateLabels() {
        if (speedTv == null) return;
        speedTv.setText(String.valueOf((int) speed));
        fontTv.setText(String.valueOf((int) font));
    }

    private void setPlaying(boolean p) {
        playing = p;
        playBtn.setText(p ? "⏸" : "▶");
        lastFrame = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        if (p) {
            pos = scroll.getScrollY();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long nanos) {
        if (!playing || scroll == null) return;
        if (lastFrame != 0) {
            float dt = (nanos - lastFrame) / 1e9f;
            float density = getResources().getDisplayMetrics().density;
            // keep in sync with the user's manual scrolling
            if (Math.abs(scroll.getScrollY() - pos) > 2) pos = scroll.getScrollY();
            pos += dt * speed * 1.2f * density * 0.6f;
            scroll.scrollTo(0, (int) pos);
            int max = text.getHeight() - scroll.getHeight();
            if (pos >= max) {
                setPlaying(false);
                return;
            }
        }
        lastFrame = nanos;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this);
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) { }
        }
        root = null;
        super.onDestroy();
    }
}
