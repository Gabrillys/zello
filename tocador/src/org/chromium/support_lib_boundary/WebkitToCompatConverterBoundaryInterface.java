package org.chromium.support_lib_boundary;

import android.webkit.WebSettings;

import java.lang.reflect.InvocationHandler;

/** Espelho da interface interna do WebView (ver WebViewProviderFactoryBoundaryInterface). */
public interface WebkitToCompatConverterBoundaryInterface {
    InvocationHandler convertSettings(WebSettings settings);
}
