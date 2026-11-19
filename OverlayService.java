package com.aimruler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class OverlayService extends Service {
    static final int SCALE = 4, TX = 40, TY = 24, PW = 96, PH = 54;
    static class Lineup { byte[] th, sm; ArrayList<float[]> tr = new ArrayList<>(); }

    WindowManager wm;
    float d;
    boolean shown = true;
    LinesView lines;
    LinearLayout panel;
    MediaProjection mp;
    VirtualDisplay vd;
    ImageReader reader;
    HandlerThread ht;
    byte[] prev, prevTh, startTh, pendingTh, startSm, pendingSm;
    long lastT, ignoreUntil, lastPtT;
    volatile int thr = 30;
    volatile float matchThr = 12f;
    volatile String msg = "Бросьте гранату";
    int mi = -1, hit = 0, miss = 0, cand = -1;
    volatile int sel = -1, proxBucket = 0;
    Bitmap bmpCache; Lineup bmpFor;
    final ArrayList<float[]> trail = new ArrayList<>(), cur = new ArrayList<>();
    final ArrayList<Lineup> lineups = new ArrayList<>();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (wm != null || in == null || !in.hasExtra("data")) return START_NOT_STICKY;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("aim", "Голограмма раскидок", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "aim") : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Голограмма раскидок включена").setSmallIcon(android.R.drawable.ic_menu_compass).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
        setup(in.getIntExtra("code", 0), (Intent) in.getParcelableExtra("data"));
        return START_NOT_STICKY;
    }

    int type() { return Build.VERSION.SDK_INT >= 26 ? LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE; }
    long now() { return System.currentTimeMillis(); }

    void setup(int code, Intent data) {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        d = m.density;
        loadAll();
        lines = new LinesView();
        LayoutParams lp = new LayoutParams(-1, -1, type(),
            LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCHABLE | LayoutParams.FLAG_LAYOUT_IN_SCREEN | LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(lines, lp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC101A14);
        panel.setPadding(4, 4, 4, 4);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        Button b1 = btn("Чувств+"); b1.setOnClickListener(v -> { thr = Math.max(10, thr - 5); info("Порог движения " + thr); });
        Button b2 = btn("Чувств-"); b2.setOnClickListener(v -> { thr = Math.min(90, thr + 5); info("Порог движения " + thr); });
        Button b3 = btn("Совп+"); b3.setOnClickListener(v -> { matchThr = Math.min(40f, matchThr + 2f); info("Допуск совпадения " + (int) matchThr); });
        Button b4 = btn("Совп-"); b4.setOnClickListener(v -> { matchThr = Math.max(4f, matchThr - 2f); info("Допуск совпадения " + (int) matchThr); });
        r1.addView(b1); r1.addView(b2); r1.addView(b3); r1.addView(b4);
        Button b5 = btn("Запомнить");
        b5.setOnClickListener(v -> {
            synchronized (trail) {
                if (pendingTh == null || trail.size() < 2) { Toast.makeText(this, "Сначала бросьте гранату", Toast.LENGTH_SHORT).show(); return; }
                Lineup l = new Lineup(); l.th = pendingTh; l.sm = pendingSm; l.tr.addAll(trail);
                synchronized (lineups) { lineups.add(l); sel = lineups.size() - 1; mi = -1; }
                trail.clear(); pendingTh = null;
            }
            saveAll(); info("Сохранено раскидок: " + lineups.size());
        });
        Button b6 = btn("Удалить");
        b6.setOnClickListener(v -> {
            synchronized (lineups) { int di = mi >= 0 ? mi : sel; if (di >= 0 && di < lineups.size()) lineups.remove(di); mi = -1; sel = -1; }
            synchronized (trail) { trail.clear(); pendingTh = null; }
            saveAll(); info("Удалено");
        });
        final Button b7 = btn("Скрыть");
        b7.setOnClickListener(v -> {
            shown = !shown;
            lines.setVisibility(shown ? View.VISIBLE : View.GONE);
            b7.setText(shown ? "Скрыть" : "Показать");
            ignoreUntil = now() + 600;
        });
        Button b8 = btn("Выход"); b8.setOnClickListener(v -> stopSelf());
        Button bL = btn("◀"); bL.setOnClickListener(v -> step(-1));
        Button bR = btn("▶"); bR.setOnClickListener(v -> step(1));
        r2.addView(b5); r2.addView(b6); r2.addView(bL); r2.addView(bR); r2.addView(b7); r2.addView(b8);
        panel.addView(r1); panel.addView(r2);
        LayoutParams pp = new LayoutParams(-2, -2, type(), LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        wm.addView(panel, pp);

        ht = new HandlerThread("cap"); ht.start();
        Handler hd = new Handler(ht.getLooper());
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mp = mpm.getMediaProjection(code, data);
        mp.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { stopSelf(); } }, hd);
        int cw = m.widthPixels / SCALE, ch = m.heightPixels / SCALE;
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2);
        vd = mp.createVirtualDisplay("cap", cw, ch, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, hd);
        reader.setOnImageAvailableListener(r -> onFrame(r), hd);
        ignoreUntil = now() + 1000;
    }

    void step(int dir) {
        synchronized (lineups) {
            int n = lineups.size();
            sel = dir < 0 ? (sel - 1 < -1 ? n - 1 : sel - 1) : (sel + 1 >= n ? -1 : sel + 1);
            mi = -1; hit = 0; miss = 0; cand = -1; proxBucket = 0;
        }
        info(sel < 0 ? "Авто: узнаю любую записанную точку" : "Раскидка " + (sel + 1) + ": найдите точку");
    }

    void info(String s) { msg = s; ignoreUntil = now() + 600; if (lines != null) lines.postInvalidate(); }

    Button btn(String t) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(12); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        return b;
    }

    // Хранение раскидок
    void saveAll() {
        SharedPreferences.Editor e = getSharedPreferences("g", 0).edit();
        synchronized (lineups) {
            e.putInt("n", lineups.size());
            for (int i = 0; i < lineups.size(); i++) {
                Lineup l = lineups.get(i);
                StringBuilder sb = new StringBuilder(Base64.encodeToString(l.th, Base64.NO_WRAP)).append("|").append(l.sm == null ? "" : Base64.encodeToString(l.sm, Base64.NO_WRAP)).append("|");
                for (float[] p : l.tr) sb.append(p[0]).append(",").append(p[1]).append(";");
                e.putString("l" + i, sb.toString());
            }
        }
        e.apply();
    }

    void loadAll() {
        SharedPreferences sp = getSharedPreferences("g", 0);
        int n = sp.getInt("n", 0);
        for (int i = 0; i < n; i++) {
            try {
                String[] s = sp.getString("l" + i, "").split("\\|");
                Lineup l = new Lineup();
                l.th = Base64.decode(s[0], Base64.NO_WRAP);
                if (s[1].length() > 0) l.sm = Base64.decode(s[1], Base64.NO_WRAP);
                for (String q : s[2].split(";")) {
                    if (q.isEmpty()) continue;
                    String[] a = q.split(",");
                    l.tr.add(new float[]{Float.parseFloat(a[0]), Float.parseFloat(a[1])});
                }
                lineups.add(l);
            } catch (Exception e) {}
        }
    }

    // Миниатюра центральной части кадра для узнавания места
    byte[] thumb(byte[] g, int w, int h) {
        byte[] o = new byte[TX * TY];
        int x0 = w * 2 / 10, x1 = w * 8 / 10, y0 = h / 10, y1 = h * 7 / 10;
        float cw = (x1 - x0) / (float) TX, chh = (y1 - y0) / (float) TY;
        for (int j = 0; j < TY; j++) for (int i = 0; i < TX; i++) {
            int xs = x0 + (int) (i * cw), xe = Math.max(xs + 1, x0 + (int) ((i + 1) * cw));
            int ys = y0 + (int) (j * chh), ye = Math.max(ys + 1, y0 + (int) ((j + 1) * chh));
            int s = 0, c = 0;
            for (int y = ys; y < ye; y += 2) for (int x = xs; x < xe; x += 2) { s += g[y * w + x] & 255; c++; }
            o[j * TX + i] = (byte) (c > 0 ? s / c : 0);
        }
        return o;
    }

    byte[] small(byte[] g, int w, int h) {
        byte[] o = new byte[PW * PH];
        for (int j = 0; j < PH; j++) for (int i = 0; i < PW; i++) {
            int xs = i * w / PW, xe = Math.max(xs + 1, (i + 1) * w / PW), ys = j * h / PH, ye = Math.max(ys + 1, (j + 1) * h / PH);
            int sum = 0, c = 0;
            for (int y = ys; y < ye; y++) for (int x = xs; x < xe; x++) { sum += g[y * w + x] & 255; c++; }
            o[j * PW + i] = (byte) (c > 0 ? sum / c : 0);
        }
        return o;
    }

    float diff(byte[] a, byte[] b) {
        int n = a.length; float ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i] & 255; mb += b[i] & 255; }
        ma /= n; mb /= n;
        float s = 0;
        for (int i = 0; i < n; i++) s += Math.abs(((a[i] & 255) - ma) - ((b[i] & 255) - mb));
        return s / n;
    }

    void match(byte[] th, long t) {
        int best = -1; float bs = 1e9f;
        synchronized (lineups) {
            boolean one = sel >= 0 && sel < lineups.size();
            int a = one ? sel : 0, b = one ? sel + 1 : lineups.size();
            for (int i = a; i < b; i++) {
                byte[] r = lineups.get(i).th;
                if (r == null || r.length != th.length) continue;
                float sc = diff(r, th);
                if (sc < bs) { bs = sc; best = i; }
            }
        }
        if (sel >= 0) {
            int pb = best < 0 ? 0 : Math.max(0, Math.min(10, Math.round(10 * (1 - bs / (matchThr * 3f)))));
            if (pb != proxBucket) { proxBucket = pb; lines.postInvalidate(); }
        }
        float lim = mi >= 0 ? matchThr * 1.5f : matchThr;
        boolean changed = false;
        if (best >= 0 && bs < lim) {
            miss = 0;
            if (best == mi) { hit = 0; }
            else {
                if (best == cand) hit++; else { cand = best; hit = 1; }
                if (hit >= 3) { mi = best; hit = 0; changed = true; }
            }
        } else {
            hit = 0; cand = -1;
            if (mi >= 0 && ++miss >= 8) { mi = -1; miss = 0; changed = true; }
        }
        if (changed) { ignoreUntil = t + 600; lines.postInvalidate(); }
    }

    void onFrame(ImageReader rd) {
        Image im = null;
        try {
            im = rd.acquireLatestImage();
            if (im == null) return;
            long t = now();
            if (t - lastT < 50) return;
            lastT = t;
            Image.Plane pl = im.getPlanes()[0];
            ByteBuffer bb = pl.getBuffer();
            int rs = pl.getRowStride(), ps = pl.getPixelStride(), w = im.getWidth(), h = im.getHeight();
            byte[] g = new byte[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int o = y * rs + x * ps;
                g[y * w + x] = (byte) (((bb.get(o) & 255) * 3 + (bb.get(o + 1) & 255) * 6 + (bb.get(o + 2) & 255)) / 10);
            }
            byte[] th = thumb(g, w, h);
            if (prev != null && prev.length == g.length && t > ignoreUntil) {
                analyze(g, w, h, t);
                if (cur.isEmpty()) match(th, t);
            }
            prev = g; prevTh = th;
        } catch (Exception e) {
        } finally { if (im != null) im.close(); }
    }

    // Ищем небольшое движущееся пятно (гранату) и копим его путь
    void analyze(byte[] g, int w, int h, long t) {
        int y0 = h / 10, n = 0, th = thr;
        long sx = 0, sy = 0;
        for (int y = y0; y < h; y++) for (int x = 0; x < w * 8 / 10; x++) {
            int i = y * w + x;
            if (Math.abs((g[i] & 255) - (prev[i] & 255)) > th) { n++; sx += x; sy += y; }
        }
        float frac = n / (float) ((h - y0) * (w * 8 / 10));
        if (n >= 3 && frac < 0.02f) {
            float cx = sx / (float) n * SCALE, cy = sy / (float) n * SCALE;
            boolean ok = cur.isEmpty();
            if (!ok) {
                float[] l = cur.get(cur.size() - 1);
                ok = t - lastPtT < 500 && Math.hypot(cx - l[0], cy - l[1]) < w * SCALE * 0.3;
            } else { startTh = prevTh; startSm = small(prev, w, h); }
            if (ok) { cur.add(new float[]{cx, cy}); lastPtT = t; }
        }
        if (!cur.isEmpty() && t - lastPtT > 500) {
            float[] a = cur.get(0), b = cur.get(cur.size() - 1);
            if (cur.size() >= 5 && Math.hypot(b[0] - a[0], b[1] - a[1]) > w * SCALE * 0.08) {
                synchronized (trail) { trail.clear(); trail.addAll(cur); pendingTh = startTh; pendingSm = startSm; }
                msg = "Путь записан, нажмите «Запомнить»";
                ignoreUntil = t + 500;
                lines.postInvalidate();
            }
            cur.clear();
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try { if (vd != null) vd.release(); } catch (Exception e) {}
        try { if (reader != null) reader.close(); } catch (Exception e) {}
        try { if (mp != null) mp.stop(); } catch (Exception e) {}
        if (ht != null) ht.quitSafely();
        if (wm != null) {
            try { wm.removeView(lines); } catch (Exception e) {}
            try { wm.removeView(panel); } catch (Exception e) {}
            wm = null;
        }
    }

    class LinesView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinesView() { super(OverlayService.this); p.setStrokeCap(Paint.Cap.ROUND); }

        void drawPath(Canvas c, ArrayList<float[]> pts, int color, boolean holo) {
            if (pts.size() < 2) return;
            Path q = new Path();
            for (int i = 0; i < pts.size(); i++) {
                float[] a = pts.get(i);
                if (i == 0) q.moveTo(a[0], a[1]); else q.lineTo(a[0], a[1]);
            }
            p.setStyle(Paint.Style.STROKE);
            if (holo) { p.setStrokeWidth(9 * d); p.setColor((color & 0x00FFFFFF) | 0x44000000); c.drawPath(q, p); }
            p.setStrokeWidth(3 * d); p.setColor(color); c.drawPath(q, p);
            p.setStyle(Paint.Style.FILL);
            for (float[] a : pts) c.drawCircle(a[0], a[1], 3.5f * d, p);
            float[] e = pts.get(pts.size() - 1);
            p.setColor(0xFFFF4B3E); c.drawCircle(e[0], e[1], 8 * d, p);
        }

        Bitmap bmp(Lineup l) {
            if (l.sm == null) return null;
            if (bmpFor != l) {
                int[] px = new int[PW * PH];
                for (int i = 0; i < px.length; i++) { int v = l.sm[i] & 255; px[i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
                bmpCache = Bitmap.createBitmap(px, PW, PH, Bitmap.Config.ARGB_8888);
                bmpFor = l;
            }
            return bmpCache;
        }

        @Override protected void onDraw(Canvas c) {
            String text = msg;
            boolean holo = false, arrived = false;
            synchronized (lineups) {
                int idx = sel >= 0 ? sel : mi;
                if (mi >= 0 && mi < lineups.size()) {
                    drawPath(c, lineups.get(mi).tr, 0xFF7DF9FF, true);
                    text = sel >= 0 ? "Раскидка " + (mi + 1) + ": вы на месте" : "Раскидка " + (mi + 1);
                    holo = true; arrived = true;
                } else if (sel >= 0 && sel < lineups.size()) {
                    text = "Раскидка " + (sel + 1) + ": найдите точку";
                    Bitmap bm = bmp(lineups.get(sel));
                    if (bm != null) {
                        float pw = getWidth() * 0.19f, ph = pw * PH / PW, x = getWidth() - pw - 6 * d, y = 48 * d;
                        p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); p.setAlpha(225);
                        c.drawBitmap(bm, null, new RectF(x, y, x + pw, y + ph), p);
                        p.setAlpha(255);
                        tx.setColor(0xFFFFFFFF); tx.setTextAlign(Paint.Align.CENTER); tx.setTextSize(12 * d);
                        tx.setShadowLayer(3 * d, 0, 0, 0xFF000000);
                        c.drawText("Встаньте здесь", x + pw / 2, y - 6 * d, tx);
                        p.setColor(0x88000000); c.drawRect(x, y + ph + 4 * d, x + pw, y + ph + 12 * d, p);
                        p.setColor(proxBucket >= 8 ? 0xFF5FE08A : proxBucket >= 5 ? 0xFFF2C94C : 0xFFFF6B5E);
                        c.drawRect(x, y + ph + 4 * d, x + pw * proxBucket / 10f, y + ph + 12 * d, p);
                    }
                }
            }
            if (!holo && sel < 0) synchronized (trail) { drawPath(c, trail, 0xFFF2C94C, false); }
            tx.setColor(0xFFFFFFFF); tx.setTextAlign(Paint.Align.CENTER); tx.setTextSize(16 * d);
            tx.setShadowLayer(4 * d, 0, 0, 0xFF000000);
            c.drawText(text, getWidth() / 2f, 36 * d, tx);
        }
    }
}
