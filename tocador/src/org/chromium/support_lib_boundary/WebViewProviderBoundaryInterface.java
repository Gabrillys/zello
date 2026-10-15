package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

/** Espelho da interface interna do WebView (ver WebViewProviderFactoryBoundaryInterface). */
public interface WebViewProviderBoundaryInterface {
    InvocationHandler addDocumentStartJavaScript(String script, String[] allowedOriginRules);
}
