package com.linecorp.armeria.client;

public interface TlsProvider {
    TlsKeyPair provide(String hostname);
    boolean containsHostname(String hostname);

    static TlsProvider empty() {
        return DefaultTlsProvider.EMPTY;
    }

    static TlsProviderBuilder builder() { return new TlsProviderBuilder(); }
}
