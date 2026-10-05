package ir.maryafox.irupload;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** آپلود در پس‌زمینه با نوتیف پیشرفته (پیشرفت + دکمه لغو). */
public class UploadService extends Service {

    static final String ACTION_CANCEL = "ir.maryafox.irupload.CANCEL";
    private static final String CH_PROGRESS = "up_progress";
    private static final String CH_DONE = "up_done";
    private static final int ID_PROGRESS = 11;
    private static final int ID_DONE = 12;

    /** MainActivity این را ست می‌کند تا بعد از آپلود لیست فایل‌ها رفرش شود. */
    static volatile Runnable onFinished;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> queue = new ArrayList<>();
    private volatile boolean cancelled = false;
    private boolean running = false;
    private int done = 0, failed = 0, total = 0;
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel p = new NotificationChannel(CH_PROGRESS, "پیشرفت آپلود",
                    NotificationManager.IMPORTANCE_LOW);
            p.setShowBadge(false);
            NotificationChannel d = new NotificationChannel(CH_DONE, "نتیجه آپلود",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(p);
            nm.createNotificationChannel(d);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getClipData() != null) {
            ClipData c = intent.getClipData();
            synchronized (queue) {
                for (int i = 0; i < c.getItemCount(); i++) {
                    Uri u = c.getItemAt(i).getUri();
                    if (u != null) { queue.add(u); total++; }
                }
            }
        }
        startFg(buildProgress("آماده‌سازی…", 0, true));
        if (!running) {
            running = true;
            cancelled = false;
            new Thread(this::work, "meyou-upload").start();
        }
        return START_NOT_STICKY;
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_PROGRESS, n);
        }
    }

    private Notification buildProgress(String text, int pct, boolean indeterminate) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, UploadService.class).setAction(ACTION_CANCEL);
        PendingIntent cpi = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFE60F23)
                .setContentTitle("MeYou · در حال آپلود (" + Math.min(done + failed + 1, Math.max(total, 1)) + "/" + total + ")")
                .setContentText(text)
                .setProgress(100, pct, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(0, "لغو", cpi)
                .build();
    }

    private void work() {
        while (!cancelled) {
            Uri u;
            synchronized (queue) {
                if (queue.isEmpty()) break;
                u = queue.remove(0);
            }
            if (upload(u)) done++; else failed++;
        }
        boolean wasCancelled = cancelled;
        stopForeground(true);
        String msg;
        if (wasCancelled) msg = "آپلود لغو شد (" + done + " فایل ارسال شده بود)";
        else if (failed == 0) msg = done + " فایل با موفقیت آپلود شد ✓";
        else msg = done + " فایل آپلود شد، " + failed + " مورد ناموفق (ورود به پنل را بررسی کنید)";
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        nm.notify(ID_DONE, new NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFE60F23)
                .setContentTitle("MeYou")
                .setContentText(msg)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build());
        main.post(() -> { Runnable r = onFinished; if (r != null) r.run(); });
        running = false;
        stopSelf();
    }

    private boolean upload(Uri uri) {
        String name = "file";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) { }
        HttpURLConnection con = null;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            con = (HttpURLConnection) new URL(MainActivity.START_URL + "upload").openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(20000);
            con.setReadTimeout(0);
            String cookie = CookieManager.getInstance().getCookie(MainActivity.START_URL);
            if (cookie != null) con.setRequestProperty("Cookie", cookie);
            con.setRequestProperty("X-File-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
            con.setRequestProperty("Content-Type", "application/octet-stream");
            if (size >= 0) con.setFixedLengthStreamingMode(size);
            else con.setChunkedStreamingMode(64 * 1024);
            long sent = 0, lastUi = 0;
            byte[] buf = new byte[64 * 1024];
            try (OutputStream out = con.getOutputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) { con.disconnect(); return false; }
                    out.write(buf, 0, n);
                    sent += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 500) {
                        lastUi = now;
                        int pct = size > 0 ? (int) (sent * 100 / size) : 0;
                        nm.notify(ID_PROGRESS, buildProgress(name + " — " + pct + "%", pct, size <= 0));
                    }
                }
            }
            return con.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
