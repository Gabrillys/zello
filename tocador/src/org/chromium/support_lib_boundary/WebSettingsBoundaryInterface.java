package org.chromium.support_lib_boundary;

import java.util.Map;

/** Espelho da interface interna do WebView (ver WebViewProviderFactoryBoundaryInterface). */
public interface WebSettingsBoundaryInterface {
    void setUserAgentMetadata(Map<String, Object> uaMetadata);
}
