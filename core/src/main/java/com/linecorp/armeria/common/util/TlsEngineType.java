package com.linecorp.armeria.common.util;

import io.netty.handler.ssl.SslProvider;

/**
 * Tls engine types.
 */
public enum TlsEngineType {
    /**
     * JDK's default implementation.
     */
    JDK(SslProvider.JDK),
    /**
     * OpenSSL-based implementation.
     */
    OPENSSL(SslProvider.OPENSSL);

    private final SslProvider sslProvider;

    TlsEngineType(SslProvider sslProvider) {
        this.sslProvider = sslProvider;
    }
}
