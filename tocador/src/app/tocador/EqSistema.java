package app.tocador;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Equalizador do próprio Android aplicado à saída geral de som (sessão 0).
 * Usado no modo navegador (redes sociais), onde o som vem do player do site.
 * Alguns aparelhos não permitem isso; nesse caso o painel avisa.
 */
public final class EqSistema {

    private static EqSistema unico;

    public static synchronized EqSistema de(Context c) {
        if (unico == null) unico = new EqSistema(c.getApplicationContext());
        return unico;
    }

    private final SharedPreferences prefs;
    private Equalizer eq;
    private String erro = "";

    private EqSistema(Context c) {
        prefs = c.getSharedPreferences("eqsis", Context.MODE_PRIVATE);
    }

    private synchronized Equalizer eq() {
        if (eq != null) return eq;
        try {
            eq = new Equalizer(0, 0);
            short n = eq.getNumberOfBands();
            int p = prefs.getInt("preset", -1);
            if (p >= 0 && p < eq.getNumberOfPresets()) eq.usePreset((short) p);
            else {
                for (short i = 0; i < n; i++) {
                    int v = prefs.getInt("b" + i, Integer.MIN_VALUE);
                    if (v != Integer.MIN_VALUE) eq.setBandLevel(i, (short) v);
                }
            }
            eq.setEnabled(prefs.getBoolean("ligado", true));
            erro = "";
        } catch (Throwable t) {
            eq = null;
            erro = "Este aparelho não permite equalizar o som do modo navegador.";
        }
        return eq;
    }

    public synchronized void soltar() {
        if (eq != null) { try { eq.release(); } catch (Throwable t) { } eq = null; }
    }

    public synchronized JSONObject info() {
        JSONObject o = new JSONObject();
        try {
            Equalizer e = eq();
            if (e == null) { o.put("motor", "nenhum"); o.put("erro", erro); return o; }
            short n = e.getNumberOfBands();
            short[] r = e.getBandLevelRange();
            JSONArray f = new JSONArray(), v = new JSONArray(), p = new JSONArray();
            for (short i = 0; i < n; i++) { f.put(e.getCenterFreq(i) / 1000.0); v.put(e.getBandLevel(i) / 100.0); }
            for (short i = 0; i < e.getNumberOfPresets(); i++) p.put(e.getPresetName(i));
            o.put("motor", "sistema");
            o.put("bandas", f); o.put("niveis", v); o.put("presets", p);
            o.put("preset", prefs.getInt("preset", -1));
            o.put("min", r[0] / 100.0); o.put("max", r[1] / 100.0);
            o.put("ligado", e.getEnabled()); o.put("temPreamp", false);
        } catch (Throwable t) {
            try { o.put("motor", "nenhum"); o.put("erro", erro.length() > 0 ? erro : String.valueOf(t.getMessage())); } catch (Exception x) { }
        }
        return o;
    }

    public synchronized void banda(int i, float db) {
        Equalizer e = eq(); if (e == null) return;
        try {
            short[] r = e.getBandLevelRange();
            int mb = Math.max(r[0], Math.min(r[1], Math.round(db * 100)));
            e.setBandLevel((short) i, (short) mb);
            SharedPreferences.Editor ed = prefs.edit().putInt("preset", -1);
            for (short k = 0; k < e.getNumberOfBands(); k++) ed.putInt("b" + k, e.getBandLevel(k));
            ed.apply();
        } catch (Throwable t) { }
    }

    public synchronized void preset(int p) {
        Equalizer e = eq(); if (e == null) return;
        try {
            e.usePreset((short) p);
            SharedPreferences.Editor ed = prefs.edit().putInt("preset", p);
            for (short k = 0; k < e.getNumberOfBands(); k++) ed.putInt("b" + k, e.getBandLevel(k));
            ed.apply();
        } catch (Throwable t) { }
    }

    public synchronized void ligar(boolean l) {
        Equalizer e = eq(); if (e == null) return;
        try { e.setEnabled(l); prefs.edit().putBoolean("ligado", l).apply(); } catch (Throwable t) { }
    }
}
