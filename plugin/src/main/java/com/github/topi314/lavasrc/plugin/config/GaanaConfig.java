package com.github.topi314.lavasrc.plugin.config;

import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.lavasrc.gaana")
@Component
public class GaanaConfig {

    private int searchLimit = 20;
    @Nullable
    private HttpProxyConfig proxy;

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    @Nullable
    public HttpProxyConfig getProxy() {
        return proxy;
    }

    public void setProxy(@Nullable HttpProxyConfig proxy) {
        this.proxy = proxy;
    }
}
