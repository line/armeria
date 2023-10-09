package com.linecorp.armeria.client;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

public class TlsKeyPair {
    private final PrivateKey privateKey;
    private final List<X509Certificate> keyCertChain;

    public TlsKeyPair(PrivateKey privateKey, List<X509Certificate> keyCertChain) {
        this.privateKey = privateKey;
        this.keyCertChain = keyCertChain;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    List<X509Certificate> keyCertChain() {
        return keyCertChain;
    }
}
