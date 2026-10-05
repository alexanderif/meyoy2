package ir.maryafox.irupload;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.json.JSONTokener;

public class MainActivity extends Activity {

    // آدرس پنل شما
    static final String START_URL = "https://maryafox.ir:100/";

    private static final int REQ_FILE = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_CAMERA = 3;
    private static final int REQ_NOTIF = 4;

    private static final String JS_COLORS =
            "(function(){try{var s=getComputedStyle(document.documentElement);"
            + "var c=function(n){var d=document.createElement('i');"
            + "d.style.color='hsl('+s.getPropertyValue(n).trim()+')';"
            + "document.body.appendChild(d);var v=getComputedStyle(d).color;d.remove();return v};"
            + "return JSON.stringify({bg:c('--background'),primary:c('--primary'),"
            + "onPrimary:c('--primary-foreground')})}catch(e){return ''}})()";

    private static final String APP_CSS =
            "html.meyou-native-app,html.meyou-native-app body{background-color:hsl(var(--background))!important}"
            + "html.meyou-native-app .app-shell{background:hsl(var(--background))!important}"
            + "html.meyou-native-app .top-nav-links a[href='/settings']{display:none!important}"
            + "@media(max-width:760px){"
            + "html.meyou-native-app .upload-panel{display:none!important}"
            + "html.meyou-native-app body.meyou-text-collapsed .text-form{display:none!important}"
            + "html.meyou-native-app .login-decoration{display:none!important}"
            + "html.meyou-native-app .login-page{box-sizing:border-box;min-height:100dvh;overflow:visible!important;"
            + "padding-bottom:max(20px,env(safe-area-inset-bottom))!important}"
            + "html.meyou-native-app .login-main{padding-bottom:24px!important}"
            + "}";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private SwipeRefreshLayout swipe;
    private LinearLayout fabDock;
    private ImageButton fab;
    private ImageButton cameraAction;
    private ImageButton fileAction;
    private ImageButton settingsAction;
    private ValueCallback<Uri[]> filePathCallback;
    private String[] pendingDownload;
    private String camPath;
    private boolean firstLoaded = false;
    private boolean errorShown = false;
    private boolean fabMenuOpen = false;
    private boolean textFormOpen = false;
    private String fabMode = "hidden";
    private int barColor = 0xFFFDF2F3;
    private int primaryColor = 0xFFE60F23;
    private int primaryForegroundColor = Color.WHITE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().setStatusBarColor(barColor);
        getWindow().setNavigationBarColor(barColor);
        splash.setKeepOnScreenCondition(() -> !firstLoaded);
        handler.postDelayed(() -> firstLoaded = true, 3000); // سقف زمان splash
        splash.setOnExitAnimationListener(p -> {
            View v = p.getView();
            v.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(380)
                    .withEndAction(p::remove).start();
        });

        if (savedInstanceState != null) camPath = savedInstanceState.getString("camPath");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(barColor);
        web = new WebView(this);
        swipe = new SwipeRefreshLayout(this);
        swipe.setColorSchemeColors(0xFFE60F23);
        swipe.addView(web);
        swipe.setOnRefreshListener(this::refresh);
        swipe.setOnChildScrollUpCallback((p, c) -> web.getScrollY() > 0);
        root.addView(swipe, new FrameLayout.LayoutParams(-1, -1));

        fabDock = new LinearLayout(this);
        fabDock.setOrientation(LinearLayout.HORIZONTAL);
        fabDock.setGravity(Gravity.CENTER_VERTICAL);
        fabDock.setClipChildren(false);
        fabDock.setClipToPadding(false);

        fab = createActionButton(dp(56), R.drawable.ic_menu_add, "باز کردن منوی عملیات");
        fabDock.addView(fab, new LinearLayout.LayoutParams(dp(56), dp(56)));
        cameraAction = createActionButton(dp(44), R.drawable.ic_camera, "آپلود سریع با دوربین");
        fileAction = createActionButton(dp(44), R.drawable.ic_file, "انتخاب فایل از گوشی");
        settingsAction = createActionButton(dp(44), R.drawable.ic_settings, "تنظیمات");
        addMenuAction(cameraAction);
        addMenuAction(fileAction);
        addMenuAction(settingsAction);
        cameraAction.setOnClickListener(v -> {
            collapseFabMenu(false);
            openCamera();
        });
        fileAction.setOnClickListener(v -> {
            collapseFabMenu(false);
            clickWebInput("fileInput");
        });
        settingsAction.setOnClickListener(v -> {
            collapseFabMenu(false);
            web.loadUrl(START_URL + "settings");
        });
        fab.setOnClickListener(v -> onMainFabPressed());

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.BOTTOM | Gravity.LEFT);
        dockLp.leftMargin = dp(18);
        dockLp.bottomMargin = dp(22);
        root.addView(fabDock, dockLp);
        fabDock.setVisibility(View.GONE);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFFFDF2F3);
        web.setAlpha(0f);

        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = Uri.parse(START_URL).getHost();
                if (host != null && host.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                if (!url.startsWith("data:")) {
                    errorShown = false;
                    collapseFabMenu(true);
                    fabDock.setVisibility(View.GONE);
                    fabMode = "hidden";
                    textFormOpen = false;
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                swipe.setRefreshing(false);
                web.animate().alpha(1f).setDuration(350).start();
                firstLoaded = true;
                installMobileEnhancements();
                updateFabForPage();
                syncBarColor();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    errorShown = true;
                    String html = "<html><body style='font-family:sans-serif;text-align:center;"
                            + "padding-top:28vh;direction:rtl;background:#FDF2F3;color:#3b0a10'>"
                            + "<h3>اتصال به سرور برقرار نشد</h3>"
                            + "<p>اینترنت یا آدرس پنل را بررسی کنید.<br>برای تلاش مجدد صفحه را به پایین بکشید.</p>"
                            + "</body></html>";
                    v.loadDataWithBaseURL(START_URL, html, "text/html", "UTF-8", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "انتخاب فایل ممکن نشد", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (Build.VERSION.SDK_INT <= 28
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = new String[]{url, ua, cd, mime};
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
            startDownload(url, ua, cd, mime);
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
        handleShare(getIntent());

        // هماهنگ‌سازی رنگ نوار بالا با تم پنل (تم عوض شود، رنگ هم عوض می‌شود)
        handler.postDelayed(new Runnable() {
            @Override public void run() { syncBarColor(); handler.postDelayed(this, 1500); }
        }, 1500);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private ImageButton createActionButton(int size, int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int pad = size >= dp(56) ? dp(14) : dp(11);
        button.setPadding(pad, pad, pad, pad);
        button.setElevation(dp(8));
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setBackground(makeFabBackground(primaryColor));
        button.setColorFilter(primaryForegroundColor, PorterDuff.Mode.SRC_IN);
        return button;
    }

    private void addMenuAction(ImageButton button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(8);
        fabDock.addView(button, lp);
        button.setVisibility(View.GONE);
    }

    private GradientDrawable makeFabBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private void showFab() {
        if (fabDock == null) return;
        if (fabDock.getVisibility() != View.VISIBLE) {
            fabDock.setVisibility(View.VISIBLE);
            fabDock.setAlpha(0f);
            fabDock.setScaleX(0.82f);
            fabDock.setScaleY(0.82f);
            fabDock.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80)
                    .setDuration(300).setInterpolator(new OvershootInterpolator(1.3f)).start();
        }
    }

    private void installMobileEnhancements() {
        String script = "(function(){try{"
                + "var h=document.documentElement;h.classList.add('meyou-native-app');"
                + "var s=document.getElementById('meyouNativeStyle');"
                + "if(!s){s=document.createElement('style');s.id='meyouNativeStyle';"
                + "s.textContent=" + JSONObject.quote(APP_CSS) + ";"
                + "(document.head||h).appendChild(s)}"
                + "var p=document.getElementById('uploadProgress')||document.getElementById('galProg');"
                + "if(p&&p.parentElement&&p.parentElement.classList.contains('upload-panel'))"
                + "p.parentElement.insertAdjacentElement('afterend',p);"
                + "if(document.getElementById('textContent'))document.body.classList.add('meyou-text-collapsed');"
                + "}catch(e){}})()";
        web.evaluateJavascript(script, null);
    }

    private void updateFabForPage() {
        if (web == null || fabDock == null) return;
        Uri page;
        try {
            page = Uri.parse(web.getUrl());
        } catch (Exception e) {
            setFabMode("hidden");
            return;
        }
        String path = page.getPath() == null ? "/" : page.getPath();
        if ("/settings".equals(path)) {
            setFabMode("back");
        } else if ("/files".equals(path)) {
            setFabMode("menu");
        } else if ("/texts".equals(path)) {
            setFabMode("text");
        } else if ("/gallery".equals(path) || "/gallery/".equals(path)) {
            fabDock.setVisibility(View.GONE);
            web.evaluateJavascript(
                    "(!!document.getElementById('galPick')&&!document.querySelector('.gal-lock-card'))",
                    unlocked -> {
                        if (unlocked != null && unlocked.equals("true")
                                && web.getUrl() != null && web.getUrl().contains("/gallery")) {
                            setFabMode("gallery");
                        } else {
                            setFabMode("hidden");
                        }
                    });
        } else {
            setFabMode("hidden");
        }
    }

    private void setFabMode(String mode) {
        if (mode.equals(fabMode) && fabDock.getVisibility() == View.VISIBLE) return;
        boolean wasHidden = fabMode.equals("hidden") || fabDock.getVisibility() != View.VISIBLE;
        fabMode = mode;
        collapseFabMenu(true);
        textFormOpen = false;
        if ("hidden".equals(mode)) {
            fabDock.animate().cancel();
            fabDock.setVisibility(View.GONE);
            return;
        }

        int icon;
        String description;
        switch (mode) {
            case "back":
                icon = R.drawable.ic_back;
                description = "بازگشت";
                break;
            case "text":
                icon = R.drawable.ic_pencil;
                description = "افزودن متن";
                break;
            case "gallery":
                icon = R.drawable.ic_gallery;
                description = "افزودن عکس یا ویدیو به گالری";
                break;
            default:
                icon = R.drawable.ic_menu_add;
                description = "باز کردن منوی عملیات";
                break;
        }
        animateFabIcon(icon, description);
        if (wasHidden) showFab();
        applyFabTheme();
    }

    private void animateFabIcon(int icon, String description) {
        fab.animate().cancel();
        fab.animate().alpha(0f).rotation(70f).setDuration(110).withEndAction(() -> {
            fab.setImageResource(icon);
            fab.setContentDescription(description);
            fab.setRotation(-70f);
            fab.animate().alpha(1f).rotation(0f).setDuration(230)
                    .setInterpolator(new OvershootInterpolator(1.4f)).start();
        }).start();
    }

    private void applyFabTheme() {
        if (fab == null) return;
        ImageButton[] buttons = {fab, cameraAction, fileAction, settingsAction};
        for (ImageButton button : buttons) {
            if (button == null) continue;
            button.setBackground(makeFabBackground(primaryColor));
            button.setColorFilter(primaryForegroundColor, PorterDuff.Mode.SRC_IN);
        }
    }

    private void onMainFabPressed() {
        switch (fabMode) {
            case "menu":
                if (fabMenuOpen) collapseFabMenu(false);
                else expandFabMenu();
                break;
            case "back":
                if (web.canGoBack()) web.goBack();
                else web.loadUrl(START_URL + "files");
                break;
            case "text":
                toggleTextForm();
                break;
            case "gallery":
                clickWebInput("galPick");
                break;
        }
    }

    private void expandFabMenu() {
        if (!"menu".equals(fabMode)) return;
        fabMenuOpen = true;
        fab.setContentDescription("بستن منوی عملیات");
        fab.animate().rotation(45f).setDuration(180).start();
        ImageButton[] actions = {cameraAction, fileAction, settingsAction};
        for (int i = 0; i < actions.length; i++) {
            ImageButton action = actions[i];
            action.setVisibility(View.VISIBLE);
            action.setAlpha(0f);
            action.setScaleX(0.55f);
            action.setScaleY(0.55f);
            action.setTranslationX(-dp(10));
            action.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f)
                    .setStartDelay(i * 45L).setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.5f)).start();
        }
    }

    private void collapseFabMenu(boolean immediate) {
        fabMenuOpen = false;
        if (fab != null && "menu".equals(fabMode)) {
            fab.setContentDescription("باز کردن منوی عملیات");
            fab.animate().rotation(0f).setDuration(immediate ? 0 : 160).start();
        }
        ImageButton[] actions = {cameraAction, fileAction, settingsAction};
        for (ImageButton action : actions) {
            if (action == null) continue;
            action.animate().cancel();
            if (immediate) {
                action.setVisibility(View.GONE);
                action.setAlpha(1f);
                action.setScaleX(1f);
                action.setScaleY(1f);
                action.setTranslationX(0f);
            } else {
                action.animate().alpha(0f).scaleX(0.65f).scaleY(0.65f)
                        .translationX(-dp(8)).setDuration(130)
                        .withEndAction(() -> {
                            action.setVisibility(View.GONE);
                            action.setAlpha(1f);
                            action.setScaleX(1f);
                            action.setScaleY(1f);
                            action.setTranslationX(0f);
                        }).start();
            }
        }
    }

    private void clickWebInput(String id) {
        if (web == null) return;
        web.evaluateJavascript("(function(){var e=document.getElementById("
                + JSONObject.quote(id) + ");if(e)e.click();return !!e})()", result -> {
            if ("false".equals(result)) {
                Toast.makeText(this, "انتخاب‌گر این بخش در دسترس نیست", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void toggleTextForm() {
        textFormOpen = !textFormOpen;
        String script = textFormOpen
                ? "(function(){document.body.classList.remove('meyou-text-collapsed');"
                    + "var f=document.querySelector('.text-form');if(f)f.scrollIntoView({behavior:'smooth',block:'center'});"
                    + "var t=document.getElementById('textTitle');if(t)t.focus({preventScroll:true});})()"
                : "(function(){document.body.classList.add('meyou-text-collapsed');"
                    + "document.activeElement&&document.activeElement.blur();})()";
        web.evaluateJavascript(script, null);
    }

    private void refresh() {
        if (errorShown) web.loadUrl(START_URL); else web.reload();
        handler.postDelayed(() -> swipe.setRefreshing(false), 6000);
    }

    // ---------- رنگ نوارهای سیستم و دکمه مطابق تم ----------
    private void syncBarColor() {
        if (web == null) return;
        web.evaluateJavascript(JS_COLORS, val -> {
            if (val == null || "null".equals(val)) return;
            try {
                Object decoded = new JSONTokener(val).nextValue();
                if (!(decoded instanceof String)) return;
                JSONObject colors = new JSONObject((String) decoded);
                int bg = parseCssColor(colors.optString("bg"));
                int primary = parseCssColor(colors.optString("primary"));
                int onPrimary = parseCssColor(colors.optString("onPrimary"));
                if (bg != Color.TRANSPARENT) applyBarColor(bg);
                if (primary != Color.TRANSPARENT
                        && (primaryColor != primary || primaryForegroundColor != onPrimary)) {
                    primaryColor = primary;
                    primaryForegroundColor = onPrimary == Color.TRANSPARENT ? Color.WHITE : onPrimary;
                    applyFabTheme();
                }
            } catch (Exception ignored) { }
        });
    }

    private int parseCssColor(String css) {
        Matcher matcher = Pattern.compile("rgba?\\(\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*"
                + "(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)").matcher(css);
        if (!matcher.find()) return Color.TRANSPARENT;
        try {
            return Color.rgb(Math.round(Float.parseFloat(matcher.group(1))),
                    Math.round(Float.parseFloat(matcher.group(2))),
                    Math.round(Float.parseFloat(matcher.group(3))));
        } catch (Exception ignored) {
            return Color.TRANSPARENT;
        }
    }

    private void applyBarColor(int to) {
        if (to == barColor) return;
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), barColor, to);
        a.setDuration(300);
        a.addUpdateListener(v -> {
            int col = (int) v.getAnimatedValue();
            getWindow().setStatusBarColor(col);
            getWindow().setNavigationBarColor(col);
            if (web != null) web.setBackgroundColor(col);
        });
        a.start();
        barColor = to;
        boolean light = Color.luminance(to) > 0.5f;
        var c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(light);
        c.setAppearanceLightNavigationBars(light);
        swipe.setProgressBackgroundColorSchemeColor(to);
    }

    // ---------- دوربین ----------
    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "cam");
            dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "IMG_" + stamp + ".jpg");
            camPath = f.getAbsolutePath();
            Uri out = FileProvider.getUriForFile(this, getPackageName() + ".fp", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.setClipData(ClipData.newRawUri("o", out));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "دوربین در دسترس نیست", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- اشتراک‌گذاری مستقیم ----------
    @SuppressWarnings("deprecation")
    private void handleShare(Intent in) {
        if (in == null) return;
        String act = in.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(act)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (!uris.isEmpty()) {
            startUpload(uris);
            in.setAction(Intent.ACTION_MAIN); // جلوگیری از آپلود دوباره
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    private void startUpload(List<Uri> uris) {
        ClipData clip = ClipData.newRawUri("files", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        Intent s = new Intent(this, UploadService.class);
        s.setClipData(clip);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startForegroundService(s);
        Toast.makeText(this, uris.size() + " فایل در حال آپلود در پس‌زمینه…", Toast.LENGTH_SHORT).show();
    }

    // ---------- دانلود ----------
    private void startDownload(String url, String ua, String cd, String mime) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.addRequestHeader("User-Agent", ua);
            String name = URLUtil.guessFileName(url, cd, mime);
            req.setMimeType(mime);
            req.setTitle(name);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "دانلود شروع شد: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "دانلود ناموفق بود", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_STORAGE && pendingDownload != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload(pendingDownload[0], pendingDownload[1],
                        pendingDownload[2], pendingDownload[3]);
            }
            pendingDownload = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(result, data));
            filePathCallback = null;
        } else if (code == REQ_CAMERA && result == Activity.RESULT_OK && camPath != null) {
            File f = new File(camPath);
            if (f.exists() && f.length() > 0) {
                List<Uri> l = new ArrayList<>();
                l.add(FileProvider.getUriForFile(this, getPackageName() + ".fp", f));
                startUpload(l);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
        out.putString("camPath", camPath);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UploadService.onFinished = () -> { if (web != null) web.reload(); };
    }

    @Override
    public void onBackPressed() {
        if (fabMenuOpen) {
            collapseFabMenu(false);
            return;
        }
        if (textFormOpen) {
            toggleTextForm();
            return;
        }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
