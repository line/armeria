/*
 * Copyright 2025 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.testing.junit5.server;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

class SignedCertificateExtensionTest {

    @RegisterExtension
    @Order(1)
    static final SelfSignedCertificateExtension parent = new SelfSignedCertificateExtension("ca.example.com");

    @RegisterExtension
    @Order(2)
    static final SignedCertificateExtension childWithSans = new SignedCertificateExtension(
            "service.example.com",
            parent,
            ImmutableList.of(
                    "api.example.com",
                    "*.example.com",
                    "192.168.1.1",
                    "spiffe://example.com/service"
            )
    );

    @Test
    void signedCertificateWithParentAndSans() throws Exception {
        final X509Certificate cert = childWithSans.certificate();
        assertThat(cert).isNotNull();

        // Verify it's signed by the parent
        cert.verify(parent.certificate().getPublicKey());

        // Verify SANs
        final Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        assertThat(sans).isNotNull();

        final List<String> dnsNames = sans.stream()
                                          .filter(san -> (Integer) san.get(0) == 2) // DNS name
                                          .map(san -> (String) san.get(1))
                                          .collect(toImmutableList());
        final List<String> ipAddresses = sans.stream()
                                             .filter(san -> (Integer) san.get(0) == 7) // IP address
                                             .map(san -> (String) san.get(1))
                                             .collect(toImmutableList());
        final List<String> uris = sans.stream()
                                      .filter(san -> (Integer) san.get(0) == 6) // URI
                                      .map(san -> (String) san.get(1))
                                      .collect(toImmutableList());

        assertThat(dnsNames).contains("service.example.com", "api.example.com", "*.example.com");
        assertThat(ipAddresses).contains("192.168.1.1");
        assertThat(uris).contains("spiffe://example.com/service");
    }
}

