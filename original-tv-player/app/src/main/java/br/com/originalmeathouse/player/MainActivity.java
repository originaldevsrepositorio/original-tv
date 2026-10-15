package br.com.originalmeathouse.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Player de TV indoor do Original.
 *
 * Abre o player em tela cheia, sem barras, sem zoom, com tela sempre ligada, autoplay de vídeo/áudio,
 * dados salvos entre aberturas e reconexão automática quando a internet cai.
 *
 * Atalhos do controle remoto:
 *  - MENU: recarrega o player
 *  - VOLTAR 5x em 3 segundos: fecha o app (manutenção)
 */
public class MainActivity extends Activity {

    private static final String PLAYER_URL = BuildConfig.PLAYER_URL;

    /** Intervalo para tentar de novo quando a página não carrega. */
    private static final long RETRY_DELAY_MS = 10_000L;
    /** Recarga preventiva do player (evita acúmulo de memória em operação 24h). */
    private static final long PERIODIC_RELOAD_MS = 6L * 60 * 60 * 1000;
    private static final int EXIT_PRESSES = 5;
    private static final long EXIT_WINDOW_MS = 3_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryRunnable = this::loadPlayer;
    private final Runnable periodicReload = new Runnable() {
        @Override
        public void run() {
            loadPlayer();
            handler.postDelayed(this, PERIODIC_RELOAD_MS);
        }
    };

    private FrameLayout root;
    private WebView webView;
    private TextView offlineView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private WebChromeClient chromeClient;
    private boolean retryPending = false;

    private int backPresses = 0;
    private long firstBackAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0a09"));

        createWebView();

        offlineView = new TextView(this);
        offlineView.setText(R.string.offline_message);
        offlineView.setTextColor(Color.WHITE);
        offlineView.setTextSize(28);
        offlineView.setGravity(Gravity.CENTER);
        offlineView.setBackgroundColor(Color.parseColor("#0b0a09"));
        offlineView.setVisibility(View.GONE);
        root.addView(offlineView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        hideSystemUi();

        loadPlayer();
        handler.postDelayed(periodicReload, PERIODIC_RELOAD_MS);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0b0a09"));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage: a configuração do player fica salva
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // vídeo/áudio tocam sozinhos
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                    // ignora fonte ampliada do sistema
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        // Identifica o app para o player (ex.: a página pode checar "OriginalTV" no user agent)
        s.setUserAgentString(s.getUserAgentString() + " OriginalTV/" + BuildConfig.VERSION_NAME);

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new PlayerClient());
        chromeClient = new PlayerChromeClient();
        webView.setWebChromeClient(chromeClient);

        root.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void loadPlayer() {
        handler.removeCallbacks(retryRunnable);
        retryPending = false;
        webView.loadUrl(PLAYER_URL);
    }

    private void showOfflineAndRetry() {
        retryPending = true;
        offlineView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private class PlayerClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // mantém o aviso até a página terminar de carregar com sucesso
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!retryPending) {
                offlineView.setVisibility(View.GONE);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showOfflineAndRetry();
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            // Android 5.x e 6.x (API < 23) usam esta assinatura antiga
            if (android.os.Build.VERSION.SDK_INT < 23 && PLAYER_URL.equals(failingUrl)) {
                showOfflineAndRetry();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (request.isForMainFrame() && errorResponse.getStatusCode() >= 500) {
                showOfflineAndRetry();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // O processo do WebView caiu (pouca memória, por ex.): recria e recarrega.
            root.removeView(webView);
            webView.destroy();
            createWebView();
            loadPlayer();
            return true;
        }
    }

    private class PlayerChromeClient extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            webView.setVisibility(View.GONE);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customViewCallback != null) customViewCallback.onCustomViewHidden();
            customViewCallback = null;
            webView.setVisibility(View.VISIBLE);
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                chromeClient.onHideCustomView();
                return true;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - firstBackAt > EXIT_WINDOW_MS) {
                firstBackAt = now;
                backPresses = 0;
            }
            backPresses++;
            if (backPresses >= EXIT_PRESSES) {
                finish();
            }
            return true; // bloqueia o "voltar" normal: o player não fecha sem querer
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            loadPlayer();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        root.removeAllViews();
        webView.destroy();
        super.onDestroy();
    }
}
