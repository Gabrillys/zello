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
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Serviço em primeiro plano dos trabalhos longos: download de vídeos e músicas, legenda (Whisper),
 * download do modelo do Whisper e preparo da dublagem. Sem ele, quando a tela apaga (tempo ocioso) e nada
 * está tocando, o Android suspende a CPU e a rede, ou até mata o app, e o trabalho para no meio.
 * Enquanto há trabalho, mantém CPU e Wi-Fi acordados e mostra uma notificação com o andamento e o botão
 * Cancelar; no fim de cada um, deixa um aviso com o resultado. É independente do serviço de reprodução:
 * parar a música não interrompe o trabalho.
 */
public class ServicoTrabalho extends Service {

    /** Um trabalho longo que roda em segundo plano. Quem o executa informa o andamento por aqui. */
    interface Tarefa {
        /** Nome curto: "download", "legenda", "modelo do Whisper", "dublagem". */
        String nome();
        /** Título da notificação enquanto roda ("Baixando", "Legendando"…). */
        String titulo();
        /** Estado atual, em uma frase. */
        String texto();
        /** De 0 a 100, ou -1 se não dá para medir. */
        int andamento();
        void cancelar();
    }

    private static final String CANAL = "downloads";
    private static final int ID_ANDAMENTO = 2, ID_FIM = 10;
    private static final String ACAO_CANCELAR = "app.tocador.CANCELAR_TAREFA";

    /** Trabalhos em curso: o serviço só para quando todos terminaram. */
    private static final List<Tarefa> tarefas = new ArrayList<Tarefa>();
    private static volatile ServicoTrabalho vivo;

    /** Há algum trabalho longo em curso? */
    static boolean ocupado() { synchronized (tarefas) { return !tarefas.isEmpty(); } }

    private static List<Tarefa> copia() { synchronized (tarefas) { return new ArrayList<Tarefa>(tarefas); } }

    /** Chamado quando um trabalho começa (na tela do app, que é quando o Android deixa subir o serviço). */
    static void iniciar(Context c, Tarefa t) {
        synchronized (tarefas) { if (tarefas.contains(t)) return; tarefas.add(t); }
        canal(c);
        try {
            Intent i = new Intent(c, ServicoTrabalho.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { Diagnostico.anotar("Trabalho: o serviço em primeiro plano não subiu: " + e); }
    }

    /** O trabalho terminou, falhou ou foi cancelado; "resumo" (se houver) aparece numa notificação final. */
    static void encerrar(Context c, Tarefa t, String resumo) {
        boolean tinha;
        synchronized (tarefas) { tinha = tarefas.remove(t); }
        if (!tinha) return;
        if (resumo != null && resumo.length() > 0) {
            canal(c);
            String nome = t.nome();
            ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(ID_FIM + (nome.hashCode() & 0xff), base(c)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Tocador: " + nome)
                    .setContentText(resumo)
                    .setStyle(new Notification.BigTextStyle().bigText(resumo))
                    .setAutoCancel(true)
                    .setContentIntent(abrir(c))
                    .build());
        }
        final ServicoTrabalho s = vivo;
        if (s != null) s.h.post(s.ciclo);
    }

    /** Andamento geral de 0 a 100: o arquivo atual dentro da playlist inteira. */
    static int andamento(int atual, int total, double pct) {
        double p = Math.max(0, Math.min(100, pct));
        if (total > 1) return (int) Math.min(100, ((Math.max(1, atual) - 1) * 100.0 + p) / total);
        return (int) p;
    }

    private static void canal(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Downloads e legendas", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private static Notification.Builder base(Context c) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CANAL) : new Notification.Builder(c);
    }

    private static PendingIntent abrir(Context c) {
        Intent i = new Intent(c, Principal.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    /** Uma vez por segundo: atualiza a notificação e renova o wake lock; sem trabalhos, encerra. */
    private final Runnable ciclo = new Runnable() {
        public void run() {
            h.removeCallbacks(this);
            if (!ocupado()) { terminar(); return; }
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
        canal(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tocador:trabalho");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            // Alto desempenho: o modo de baixa latência só vale com a tela ligada e o app à frente.
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tocador:trabalho-rede");
            wifi.setReferenceCounted(false);
        }
        segurar();
        try { if (wifi != null && !wifi.isHeld()) wifi.acquire(); } catch (Exception e) { }
        subir();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_CANCELAR.equals(intent.getAction())) {
            String nome = intent.getData() == null ? "" : intent.getData().getLastPathSegment();
            for (Tarefa t : copia()) if (t.nome().equals(nome)) t.cancelar();
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

    /** Notificação de andamento: uma tarefa mostra o título e a barra dela; várias mostram uma linha cada. */
    private Notification montar() {
        List<Tarefa> ts = copia();
        String titulo, texto;
        int prog = -1;
        if (ts.size() == 1) {
            Tarefa t = ts.get(0);
            titulo = t.titulo();
            texto = t.texto();
            prog = t.andamento();
        } else if (ts.isEmpty()) {
            titulo = "Terminando…";
            texto = "";
        } else {
            titulo = ts.size() + " trabalhos em andamento";
            StringBuilder s = new StringBuilder();
            for (Tarefa t : ts) s.append(s.length() > 0 ? "\n" : "").append(t.titulo()).append(": ").append(t.texto());
            texto = s.toString();
        }
        if (texto == null || texto.length() == 0) texto = "Preparando…";
        Notification.Builder b = base(this)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setProgress(100, Math.max(0, prog), prog < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(abrir(this));
        // Um botão Cancelar por trabalho (o Android mostra até três).
        for (int i = 0; i < ts.size() && i < 3; i++) {
            Intent cancelar = new Intent(this, ServicoTrabalho.class).setAction(ACAO_CANCELAR)
                    .setData(Uri.parse("tocador://cancelar/" + Uri.encode(ts.get(i).nome())));
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    ts.size() == 1 ? "Cancelar" : "Cancelar " + ts.get(i).nome(),
                    PendingIntent.getService(this, 10 + i, cancelar, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void terminar() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
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
