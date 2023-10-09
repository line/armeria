package com.linecorp.armeria.client;

import java.util.HashMap;
import java.util.Map;

public class TlsProviderBuilder {
    private final Map<String, TlsKeyPair> hostnameTlsKeyPair = new HashMap<>();

    TlsProviderBuilder() {}

    public TlsProviderBuilder addHostnameTlsKeyPair(String hostname, TlsKeyPair tlsKeyPair) {
        hostnameTlsKeyPair.put(hostname, tlsKeyPair);
        return this;
    }

    public TlsProvider build() {
        return new DefaultTlsProvider(hostnameTlsKeyPair);
    }
}
