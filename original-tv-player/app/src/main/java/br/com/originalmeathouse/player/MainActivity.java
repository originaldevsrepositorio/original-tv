package br.com.originalmeathouse.player;

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
import android.widget.FrameLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

/**
 * Player de TV indoor do Original (motor GeckoView embutido).
 *
 * Tela cheia, sem barras, tela sempre ligada, autoplay, dados salvos entre aberturas
 * e reconexão automática quando a internet cai.
 *
 * Controle remoto:
 *  - MENU: recarrega o player
 *  - VOLTAR 5x em 3 segundos: fecha o app (manutenção)
 */
public class MainActivity extends Activity {

    private static final String PLAYER_URL = BuildConfig.PLAYER_URL;
    private static final long RETRY_DELAY_MS = 10_000L;
    private static final long PERIODIC_RELOAD_MS = 6L * 60 * 60 * 1000;
    private static final int EXIT_PRESSES = 5;
    private static final long EXIT_WINDOW_MS = 3_000L;

    private static GeckoRuntime runtime; // um único motor por processo

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
    private GeckoView geckoView;
    private GeckoSession session;
    private TextView offlineView;
    private boolean retryPending = false;

    private int backPresses = 0;
    private long firstBackAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0a09"));

        if (runtime == null) {
            // Preferências do motor: vídeo e áudio tocam sozinhos (sem toque do usuário)
            String configPath = null;
            try {
                java.io.File cfg = new java.io.File(getFilesDir(), "geckoview-config.yaml");
                java.io.FileWriter w = new java.io.FileWriter(cfg, false);
                w.write("prefs:\n  media.autoplay.default: 0\n  media.autoplay.blocking_policy: 0\n");
                w.close();
                configPath = cfg.getAbsolutePath();
            } catch (java.io.IOException ignored) {
            }
            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder();
            if (configPath != null) builder.configFilePath(configPath);
            runtime = GeckoRuntime.create(getApplicationContext(), builder.build());
        }

        geckoView = new GeckoView(this);
        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        openSession();
        loadPlayer();
        handler.postDelayed(periodicReload, PERIODIC_RELOAD_MS);
    }

    private void openSession() {
        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .build();
        session = new GeckoSession(sessionSettings);

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
                showOfflineAndRetry();
                return null;
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                if (success && !retryPending) {
                    offlineView.setVisibility(View.GONE);
                }
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(GeckoSession s) {
                recreateSession();
            }

            @Override
            public void onKill(GeckoSession s) {
                recreateSession();
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
    }

    private void recreateSession() {
        handler.post(() -> {
            try {
                geckoView.releaseSession();
                session.close();
            } catch (Exception ignored) {
            }
            openSession();
            loadPlayer();
        });
    }

    private void loadPlayer() {
        handler.removeCallbacks(retryRunnable);
        retryPending = false;
        session.loadUri(PLAYER_URL);
    }

    private void showOfflineAndRetry() {
        retryPending = true;
        offlineView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Trata VOLTAR e MENU antes do GeckoView, que poderia consumi-los
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_MENU) {
                    loadPlayer();
                } else {
                    long now = SystemClock.elapsedRealtime();
                    if (now - firstBackAt > EXIT_WINDOW_MS) {
                        firstBackAt = now;
                        backPresses = 0;
                    }
                    backPresses++;
                    if (backPresses >= EXIT_PRESSES) finish();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            geckoView.releaseSession();
            session.close();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
