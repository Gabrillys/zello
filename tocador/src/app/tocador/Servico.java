package app.tocador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Serviço em primeiro plano: é o que deixa o Android manter o som tocando com a tela
 * apagada ou com o app fechado. Mostra a notificação com os botões e atende fones/tela de bloqueio.
 */
public class Servico extends Service {

    private static final String CANAL = "reproducao";
    private static final String ACAO_ALTERNAR = "app.tocador.ALTERNAR";
    private static final String ACAO_PARAR = "app.tocador.PARAR";

    private static volatile Servico vivo;

    public static void iniciar(Context c) {
        Intent i = new Intent(c, Servico.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { }
    }

    public static void atualizar(Context c) {
        final Servico s = vivo;
        if (s != null) s.h.post(new Runnable() { public void run() { s.notificar(); } });
    }

    public static void encerrar(Context c) {
        c.stopService(new Intent(c, Servico.class));
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaSession sessao;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private final BroadcastReceiver barulho = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Fone desconectado: pausa para não sair som pelo alto-falante de repente.
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) Controle.pausar(c);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        vivo = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Reprodução", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        sessao = new MediaSession(this, "Tocador");
        sessao.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Controle.tocar(Servico.this); }
            @Override public void onPause() { Controle.pausar(Servico.this); }
            @Override public void onStop() { Controle.parar(Servico.this); }
        });
        sessao.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tocador:som");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tocador:rede");
            wifi.setReferenceCounted(false);
        }
        registerReceiver(barulho, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        notificar();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACAO_ALTERNAR.equals(a)) Controle.tocarPausar(this);
        else if (ACAO_PARAR.equals(a)) { Controle.parar(this); return START_NOT_STICKY; }
        notificar();
        return START_NOT_STICKY;
    }

    private PendingIntent acao(String a, int cod) {
        Intent i = new Intent(this, Servico.class).setAction(a);
        return PendingIntent.getService(this, cod, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void notificar() {
        boolean tocando = Controle.tocando(this);
        String titulo = Controle.titulo(this);
        if (titulo == null || titulo.length() == 0) titulo = "Tocador";
        String sub = "web".equals(Controle.modo) ? "Modo navegador" : "Player VLC";

        Intent abrir = new Intent(this, Principal.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent piAbrir = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        sessao.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub).build());
        sessao.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(tocando ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        b.setSmallIcon(tocando ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(titulo)
                .setContentText(sub)
                .setContentIntent(piAbrir)
                .setOngoing(tocando)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, tocando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        tocando ? "Pausar" : "Tocar", acao(ACAO_ALTERNAR, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Parar", acao(ACAO_PARAR, 2)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(sessao.getSessionToken()).setShowActionsInCompactView(0, 1));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);

        // Mantém CPU e Wi-Fi acordados só enquanto há algo tocando ou carregando.
        boolean manter = tocando || ("vlc".equals(Controle.modo) && Motor.seExistir() != null && Motor.seExistir().ativo());
        try {
            if (manter) { if (!wake.isHeld()) wake.acquire(); if (wifi != null && !wifi.isHeld()) wifi.acquire(); }
            else { if (wake.isHeld()) wake.release(); if (wifi != null && wifi.isHeld()) wifi.release(); }
        } catch (Exception e) { }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App arrastado para fora da lista de recentes: o navegador morre junto, então paramos.
        // No modo VLC o som continua, como num app de música.
        if (!"vlc".equals(Controle.modo)) { Controle.parar(this); stopSelf(); }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        vivo = null;
        try { unregisterReceiver(barulho); } catch (Exception e) { }
        try { if (wake.isHeld()) wake.release(); } catch (Exception e) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception e) { }
        sessao.setActive(false);
        sessao.release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
