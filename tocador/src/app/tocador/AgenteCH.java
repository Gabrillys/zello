package app.tocador;

import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Além do User-Agent, o WebView envia aos sites os "Client Hints" (Sec-CH-UA-Mobile etc.),
 * que dizem "sou um celular". Sites como o YouTube usam isso para escolher a versão para celular.
 * Este ajuste muda esses sinais pela mesma interface interna que a biblioteca androidx.webkit usa
 * (setUserAgentMetadata), acessada aqui diretamente porque o app não inclui essa biblioteca.
 */
final class AgenteCH {

    private AgenteCH() { }

    /** Espelham as interfaces internas do WebView; as chamadas são repassadas pelo nome do método. */
    interface Fabrica { InvocationHandler getWebkitToCompatConverter(); String[] getSupportedFeatures(); }
    interface Conversor { InvocationHandler convertSettings(WebSettings s); }
    interface Ajustes { void setUserAgentMetadata(Map<String, Object> m); }

    @SuppressWarnings("unchecked")
    private static <T> T como(Class<T> c, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(AgenteCH.class.getClassLoader(), new Class<?>[] { c }, h);
    }

    /** Devolve "ok" ou o motivo de não ter funcionado (vai para o diagnóstico). */
    static String aplicar(WebSettings s, boolean computador, String versaoChrome) {
        if (Build.VERSION.SDK_INT < 28) return "Android 8 ou anterior: não suportado";
        try {
            ClassLoader cl = WebView.getWebViewClassLoader();
            Class<?> util = Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil", false, cl);
            Method criar = util.getDeclaredMethod("createWebViewProviderFactory");
            Fabrica f = como(Fabrica.class, (InvocationHandler) criar.invoke(null));
            boolean suportado = false;
            for (String r : f.getSupportedFeatures()) if ("USER_AGENT_METADATA".equals(r)) suportado = true;
            if (!suportado) return "este WebView não permite mudar os Client Hints (atualize o 'Android System WebView')";
            Ajustes a = como(Ajustes.class, como(Conversor.class, f.getWebkitToCompatConverter()).convertSettings(s));
            String maior = versaoChrome.contains(".") ? versaoChrome.substring(0, versaoChrome.indexOf('.')) : versaoChrome;
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("BRAND_VERSION_LIST", new String[][] {
                    { "Chromium", maior, versaoChrome }, { "Google Chrome", maior, versaoChrome }, { "Not.A/Brand", "99", "99.0.0.0" } });
            m.put("FULL_VERSION", versaoChrome);
            m.put("PLATFORM", computador ? "Linux" : "Android");
            m.put("PLATFORM_VERSION", computador ? "6.5.0" : Build.VERSION.RELEASE);
            m.put("ARCHITECTURE", computador ? "x86" : "");
            m.put("MODEL", computador ? "" : Build.MODEL);
            m.put("MOBILE", !computador);
            m.put("BITNESS", computador ? 64 : 0);
            m.put("WOW64", false);
            a.setUserAgentMetadata(m);
            return "ok";
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }
}
