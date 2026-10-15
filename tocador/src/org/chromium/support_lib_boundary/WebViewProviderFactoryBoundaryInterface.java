package org.chromium.support_lib_boundary;

import android.webkit.WebView;

import java.lang.reflect.InvocationHandler;

/**
 * Espelho (só com os métodos usados) da interface interna do WebView que a biblioteca androidx.webkit usa.
 * O nome do pacote e da interface precisa ser exatamente este: o WebView procura o mesmo nome do lado dele.
 */
public interface WebViewProviderFactoryBoundaryInterface {
    InvocationHandler createWebView(WebView webview);
    InvocationHandler getWebkitToCompatConverter();
    String[] getSupportedFeatures();
}
