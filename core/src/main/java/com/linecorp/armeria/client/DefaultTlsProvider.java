package com.linecorp.armeria.client;

import java.util.Map;

final class DefaultTlsProvider implements TlsProvider {

    static final DefaultTlsProvider EMPTY =
            (DefaultTlsProvider) TlsProvider.builder().build();

    private final Map<String, TlsKeyPair> hostnameTlsKeyPairMap;

    DefaultTlsProvider(Map<String, TlsKeyPair> hostnameTlsKeyPairMap) {
        this.hostnameTlsKeyPairMap = hostnameTlsKeyPairMap;
    }

    @Override
    public TlsKeyPair provide(String hostname) {
        return hostnameTlsKeyPairMap.get(hostname);
    }

    @Override
    public boolean containsHostname(String hostname) {
        return hostnameTlsKeyPairMap.containsKey(hostname);
    }

}
