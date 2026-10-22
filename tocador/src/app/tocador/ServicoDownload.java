package app.tocador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serviço em primeiro plano dos downloads. Sem ele, quando a tela apaga (tempo ocioso) e nada está tocando,
 * o Android suspende a CPU e a rede, ou até mata o app, e o download para no meio.
 * Enquanto há download, mantém CPU e Wi-Fi acordados e mostra uma notificação com o andamento e o botão
 * Cancelar; no fim, deixa um aviso com o resultado. É independente do serviço de reprodução:
 * parar a música não interrompe o download.
 */
public class ServicoDownload extends Service {

    private static final String CANAL = "downloads";
    private static final int ID_ANDAMENTO = 2, ID_FIM = 3;
    private static final String ACAO_CANCELAR = "app.tocador.CANCELAR_DOWNLOAD";

    /** Downloads em curso: o serviço só para quando todos terminaram. */
    private static final AtomicInteger ativos = new AtomicInteger();
    private static volatile ServicoDownload vivo;
    private static volatile String resumo = "";

    /** Chamado quando um download começa (na tela do app, que é quando o Android deixa subir o serviço). */
    public static void iniciar(Context c) {
        ativos.incrementAndGet();
        resumo = "";
        try {
            Intent i = new Intent(c, ServicoDownload.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { Diagnostico.anotar("Download: o serviço em primeiro plano não subiu: " + e); }
    }

    /** O download terminou, falhou ou foi cancelado; "texto" é o resultado, mostrado na notificação final. */
    public static void encerrar(Context c, String texto) {
        resumo = texto == null ? "" : texto;
        if (ativos.decrementAndGet() > 0) return;
        ativos.set(0);
        final ServicoDownload s = vivo;
        if (s != null) s.h.post(s.ciclo);
    }

    /** Andamento geral de 0 a 100: o arquivo atual dentro da playlist inteira. */
    static int andamento(int atual, int total, double pct) {
        double p = Math.max(0, Math.min(100, pct));
        if (total > 1) return (int) Math.min(100, ((Math.max(1, atual) - 1) * 100.0 + p) / total);
        return (int) p;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    /** Uma vez por segundo: atualiza a notificação e renova o wake lock; sem downloads, encerra. */
    private final Runnable ciclo = new Runnable() {
        public void run() {
            h.removeCallbacks(this);
            if (ativos.get() <= 0) { terminar(); return; }
            segurar();
            nm.notify(ID_ANDAMENTO, montar());
            h.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        vivo = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tocador:download");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            // Alto desempenho: o modo de baixa latência só vale com a tela ligada e o app à frente.
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tocador:download-rede");
            wifi.setReferenceCounted(false);
        }
        segurar();
        try { if (wifi != null && !wifi.isHeld()) wifi.acquire(); } catch (Exception e) { }
        subir();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_CANCELAR.equals(intent.getAction())) {
            Baixador b = Baixador.seExistir();
            if (b != null) b.cancelar();
        } else subir(); // cada startForegroundService exige um startForeground
        ciclo.run();
        return START_NOT_STICKY;
    }

    private void subir() {
        Notification n = montar();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_ANDAMENTO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_ANDAMENTO, n);
    }

    /** Mantém a CPU acordada; o prazo curto é renovado a cada segundo, então um serviço travado não prende o aparelho. */
    private void segurar() {
        try { wake.acquire(10 * 60 * 1000L); } catch (Exception e) { }
    }

    private PendingIntent abrir() {
        Intent i = new Intent(this, Principal.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Builder base() {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
    }

    /** Notificação de andamento, com o texto que o Baixador mostra na tela do app. */
    private Notification montar() {
        Baixador bx = Baixador.seExistir();
        JSONObject e = bx == null ? new JSONObject() : bx.estado();
        int atual = e.optInt("atual"), total = e.optInt("total");
        double pct = e.optDouble("pct", 0);
        String texto = e.optString("estado");
        if (texto.length() == 0) texto = "Preparando o download…";
        Intent cancelar = new Intent(this, ServicoDownload.class).setAction(ACAO_CANCELAR);
        Notification.Builder b = base()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(total > 1 ? "Baixando playlist: " + andamento(atual, total, pct) + "%" : "Baixando")
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setProgress(100, andamento(atual, total, pct), pct <= 0 && atual == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(abrir())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Cancelar",
                        PendingIntent.getService(this, 3, cancelar, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void terminar() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        String r = resumo;
        if (r.length() > 0) {
            // Com a tela apagada, é o aviso que mostra se deu certo.
            nm.notify(ID_FIM, base()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download")
                    .setContentText(r)
                    .setStyle(new Notification.BigTextStyle().bigText(r))
                    .setAutoCancel(true)
                    .setContentIntent(abrir())
                    .build());
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        vivo = null;
        h.removeCallbacks(ciclo);
        try { if (wake.isHeld()) wake.release(); } catch (Exception e) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
