package app.tocador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fila de downloads, rodando como serviço em primeiro plano (continua com a tela apagada).
 * A parte de rede fica em {@link Transferencia}; aqui ficam as notificações e a pasta Download/Tocador.
 */
public class Baixador extends Service {

    private static final String CANAL = "downloads";
    private static final String ACAO_CANCELAR = "app.tocador.CANCELAR_DOWNLOAD";
    private static final int NOTIF_PROGRESSO = 2;

    private static final List<Transferencia.Tarefa> tarefas = new ArrayList<Transferencia.Tarefa>();
    private static int proximoId = 1;
    private static Thread trabalhador;

    // ------------------------------------------------------------ API usada pela tela

    public static void baixar(Context c, String url, String titulo, String ua, String referer, String cookie) {
        Transferencia.Tarefa t;
        synchronized (tarefas) {
            for (Transferencia.Tarefa x : tarefas) if (x.url.equals(url) && x.ativa()) return; // já está na fila
            t = new Transferencia.Tarefa(proximoId++, url, ua, referer, cookie, nomeArquivo(titulo, url));
            tarefas.add(t);
        }
        Intent i = new Intent(c, Baixador.class);
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); }
        catch (Exception e) { t.estado = "erro"; t.erro = "Não consegui iniciar o download."; }
    }

    public static void cancelar(int id) {
        synchronized (tarefas) {
            for (Transferencia.Tarefa t : tarefas) if (t.id == id) { t.cancelar = true; if (t.estado.equals("na fila")) t.estado = "cancelado"; }
        }
    }

    public static void limparConcluidos() {
        synchronized (tarefas) {
            for (int i = tarefas.size() - 1; i >= 0; i--) if (!tarefas.get(i).ativa()) tarefas.remove(i);
        }
    }

    public static JSONArray lista() {
        JSONArray a = new JSONArray();
        synchronized (tarefas) {
            for (Transferencia.Tarefa t : tarefas) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", t.id); o.put("nome", t.nome); o.put("estado", t.estado); o.put("erro", t.erro);
                    o.put("feitos", t.feitos); o.put("total", t.total); o.put("pedacos", t.porPedacos);
                } catch (Exception e) { }
                a.put(o);
            }
        }
        return a;
    }

    static String nomeArquivo(String titulo, String url) {
        String n = titulo == null ? "" : titulo.trim();
        if (n.length() == 0 || n.startsWith("http")) {
            try { n = Uri.parse(url).getLastPathSegment(); } catch (Exception e) { n = null; }
            if (n == null || n.length() == 0) n = "download";
            int p = n.lastIndexOf('.');
            if (p > 0) n = n.substring(0, p);
        }
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").replaceAll("\\s+", " ").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.length() == 0 ? "download" : n;
    }

    // ------------------------------------------------------------ serviço

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm().createNotificationChannel(ch);
        }
    }

    private NotificationManager nm() { return (NotificationManager) getSystemService(NOTIFICATION_SERVICE); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_CANCELAR.equals(intent.getAction())) {
            synchronized (tarefas) { for (Transferencia.Tarefa t : tarefas) if (t.ativa()) cancelar(t.id); }
        }
        mostrarProgresso(null);
        synchronized (Baixador.class) {
            if (trabalhador == null || !trabalhador.isAlive()) {
                trabalhador = new Thread(new Runnable() { public void run() { trabalhar(); } }, "tocador-download");
                trabalhador.start();
            }
        }
        return START_NOT_STICKY;
    }

    private final Transferencia rede = new Transferencia() {
        @Override protected Saida abrirSaida(String nome, String mime) throws IOException { return new Destino(Baixador.this, nome, mime); }
        @Override protected void progresso(Tarefa t) { Baixador.this.progresso(t); }
    };

    private void trabalhar() {
        while (true) {
            Transferencia.Tarefa t = null;
            synchronized (tarefas) { for (Transferencia.Tarefa x : tarefas) if (x.estado.equals("na fila")) { t = x; break; } }
            if (t == null) break;
            t.estado = "baixando";
            mostrarProgresso(t);
            try {
                rede.executar(t);
                if (t.cancelar) t.estado = "cancelado";
                else { t.estado = "concluido"; avisarFim(t, null); }
            } catch (Throwable e) {
                if (t.cancelar) t.estado = "cancelado";
                else { t.estado = "erro"; t.erro = e.getMessage() == null ? e.toString() : e.getMessage(); avisarFim(t, t.erro); }
            }
        }
        synchronized (Baixador.class) { trabalhador = null; }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private long ultimoAviso;

    private void mostrarProgresso(Transferencia.Tarefa t) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        PendingIntent cancelar = PendingIntent.getService(this, 3, new Intent(this, Baixador.class).setAction(ACAO_CANCELAR),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent abrir = new Intent(this, Principal.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(t == null ? "Preparando download…" : "Baixando: " + t.nome)
                .setContentIntent(PendingIntent.getActivity(this, 5, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Cancelar", cancelar).build());
        if (t != null && t.total > 0) {
            int pct = (int) (t.feitos * 100 / t.total);
            b.setProgress(100, pct, false).setContentText(t.porPedacos ? pct + "% (" + t.feitos + "/" + t.total + " partes)" : pct + "% de " + mb(t.total));
        } else {
            b.setProgress(0, 0, true).setContentText(t != null && t.feitos > 0 ? mb(t.feitos) : "");
        }
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_PROGRESSO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_PROGRESSO, n);
    }

    private void progresso(Transferencia.Tarefa t) {
        long agora = System.currentTimeMillis();
        if (agora - ultimoAviso < 700) return;
        ultimoAviso = agora;
        mostrarProgresso(t);
    }

    private void avisarFim(Transferencia.Tarefa t, String erro) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        Intent abrir = new Intent(this, Principal.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        b.setSmallIcon(erro == null ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(erro == null ? "Download concluído" : "Download não foi possível")
                .setContentText(t.nome)
                .setStyle(new Notification.BigTextStyle().bigText(erro == null ? t.nome + "\nSalvo na pasta Download/Tocador." : t.nome + "\n" + erro))
                .setContentIntent(PendingIntent.getActivity(this, 4, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true);
        nm().notify(100 + t.id, b.build());
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1048576.0); }

    // ------------------------------------------------------------ onde salvar

    /** Arquivo em Download/Tocador. Android 10+: pela MediaStore (sem permissão). Antes: pasta pública. */
    static final class Destino implements Transferencia.Saida {
        private final OutputStream out;
        private final Context ctx;
        private final Uri uri;
        private final File arquivo;

        Destino(Context c, String nome, String mime) throws IOException {
            ctx = c;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tocador");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver cr = c.getContentResolver();
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("Não consegui criar o arquivo na pasta Download.");
                out = cr.openOutputStream(uri);
                if (out == null) throw new IOException("Não consegui gravar na pasta Download.");
                arquivo = null;
            } else {
                File pasta = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tocador");
                if (!pasta.exists() && !pasta.mkdirs()) throw new IOException("Sem permissão para gravar na pasta Download.");
                int p = nome.lastIndexOf('.');
                String base = nome.substring(0, p), ext = nome.substring(p);
                File f = new File(pasta, nome);
                for (int i = 1; f.exists(); i++) f = new File(pasta, base + " (" + i + ")" + ext);
                arquivo = f;
                uri = null;
                out = new FileOutputStream(f);
            }
        }

        @Override public OutputStream fluxo() { return out; }

        @Override
        public void fechar(boolean ok) {
            try { out.close(); } catch (IOException e) { }
            if (uri != null) {
                if (ok) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    ctx.getContentResolver().update(uri, v, null, null);
                } else ctx.getContentResolver().delete(uri, null, null);
            } else if (arquivo != null) {
                if (ok) MediaScannerConnection.scanFile(ctx, new String[] { arquivo.getAbsolutePath() }, null, null);
                else arquivo.delete();
            }
        }
    }
}
