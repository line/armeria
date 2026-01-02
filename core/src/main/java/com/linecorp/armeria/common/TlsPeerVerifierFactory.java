/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common;

import java.security.cert.X509Certificate;
import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import com.linecorp.armeria.client.ClientTlsSpecBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A factory for creating {@link TlsPeerVerifier}s.
 * This can be used to verify TLS peers when set at
 * {@link ClientTlsSpecBuilder#verifierFactories(TlsPeerVerifierFactory...)}.
 * <pre>{@code
 * var clientTlsSpec =
 *         ClientTlsSpec.builder()
 *                      // TlsPeerVerifierFactory#create will be called in the specified order 1->2->3
 *                      // TlsPeerVerifier#verify will be called in the reverse order 3->2->1
 *                      .verifierFactories(new MyFactory1(), new MyFactory2(), new MyFactory3())
 *                      .build();
 * var client = WebClient.of();
 * client.prepare()
 *       .clientTlsSpec(clientTlsSpec)
 *       .get("/")
 *       .execute();
 * }</pre>
 * It is recommended to use the pre-defined {@link TlsPeerVerifierFactory}s.
 * When implementing custom factories, it is important that equality and hashcode are implemented correctly
 * to ensure connections are pooled correctly.
 */
@UnstableApi
@FunctionalInterface
public interface TlsPeerVerifierFactory {

    /**
     * Creates a new {@link TlsPeerVerifier} given the delegate {@link TlsPeerVerifier}.
     * Users may use this method to create a {@link TlsPeerVerifier} which decides whether to
     * 1) exit early 2) fail immediately 3) or delegate the decision to the next {@link TlsPeerVerifier}.
     *
     * <p>The last delegate will invoke
     * {@link X509ExtendedTrustManager#checkClientTrusted(X509Certificate[], String, SSLEngine)}
     * or {@link X509ExtendedTrustManager#checkServerTrusted(X509Certificate[], String, SSLEngine)} depending
     * on the context.
     */
    TlsPeerVerifier create(TlsPeerVerifier delegate);

    /**
     * Returns a factory which disables the verification of server's TLS certificate chain
     * for specific hosts.
     *
     * <p><strong>Note:</strong> You should never use this in production but only for a testing purpose.
     */
    static TlsPeerVerifierFactory insecureHosts(String... insecureHosts) {
        return new InsecureHostsPeerVerifierFactory(insecureHosts);
    }

    /**
     * Returns a factory which disables the verification of server's TLS certificate chain
     * for specific hosts.
     *
     * <p><strong>Note:</strong> You should never use this in production but only for a testing purpose.
     */
    static TlsPeerVerifierFactory insecureHosts(Set<String> insecureHosts) {
        return new InsecureHostsPeerVerifierFactory(insecureHosts);
    }

    /**
     * Disables the verification of server's TLS certificate chain.
     *
     * <p><strong>Note:</strong> You should never use this in production but only for a testing purpose.
     */
    static TlsPeerVerifierFactory noVerify() {
        return NoVerifyPeerVerifierFactory.of();
    }
}
