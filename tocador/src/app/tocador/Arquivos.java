package app.tocador;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Salva arquivos de texto (legendas) na pasta Download/Tocador. */
public final class Arquivos {

    private Arquivos() { }

    /** O último arquivo salvo, para o botão "Abrir". */
    static volatile Uri ultimo;

    static String nomeSeguro(String n) {
        n = (n == null ? "" : n).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").replaceAll("\\s+", " ").trim();
        if (n.length() > 90) n = n.substring(0, 90).trim();
        return n.length() == 0 ? "legenda" : n;
    }

    /** Grava o texto em UTF-8 e devolve o caminho mostrado para a pessoa (ex.: "Download/Tocador/x.txt"). */
    public static String salvarTexto(Context c, String nome, String texto) throws IOException {
        return salvar(c, nome, ".txt", "text/plain", texto);
    }

    /** Igual, com outra extensão (ex.: ".srt", "application/x-subrip"). */
    public static String salvar(Context c, String nome, String ext, String tipo, String texto) throws IOException {
        nome = nomeSeguro(nome) + ext;
        byte[] dados = texto.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
            v.put(MediaStore.MediaColumns.MIME_TYPE, tipo);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tocador");
            ContentResolver cr = c.getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Não consegui criar o arquivo na pasta Download.");
            OutputStream o = cr.openOutputStream(uri);
            if (o == null) throw new IOException("Não consegui gravar na pasta Download.");
            try { o.write(dados); } finally { o.close(); }
            ultimo = uri;
            return "Download/Tocador/" + nome;
        }
        File pasta = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tocador");
        if (!pasta.exists() && !pasta.mkdirs()) throw new IOException("Sem permissão para gravar na pasta Download.");
        File f = new File(pasta, nome);
        for (int i = 1; f.exists(); i++) f = new File(pasta, nome.substring(0, nome.length() - ext.length()) + " (" + i + ")" + ext);
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(dados); } finally { o.close(); }
        MediaScannerConnection.scanFile(c, new String[] { f.getAbsolutePath() }, new String[] { tipo }, null);
        ultimo = null; // Android 9 ou anterior: abrir um arquivo exige outro mecanismo; mostramos só o caminho
        return "Download/Tocador/" + f.getName();
    }
}
