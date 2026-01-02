/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class SelfSignedCertificateTest {
    @Test
    void fqdnAsteriskDoesNotThrowTest() throws CertificateException {
        new SelfSignedCertificate("*.netty.io", "EC", 256);
        new SelfSignedCertificate("*.netty.io", "RSA", 2048);
    }

    @Test
    void fqdnAsteriskFileNameTest() throws CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate("*.netty.io", "EC", 256);
        assertThat(ssc.certificate().getName()).doesNotContain("*");
        assertThat(ssc.privateKey().getName()).doesNotContain("*");
    }

    @Test
    void subjectAlternativeNamesWithUriTest() throws Exception {
        final List<String> additionalSans = ImmutableList.of(
                "example.com",
                "192.168.1.1",
                "spiffe://centraldogma.dev/sa/centraldogma-alpha",
                "https://api.example.com/service");
        final SelfSignedCertificate ssc = new SelfSignedCertificate(
                "test.armeria.dev",
                ThreadLocalRandom.current(),
                2048,
                SignedCertificate.DEFAULT_NOT_BEFORE,
                SignedCertificate.DEFAULT_NOT_AFTER,
                "RSA",
                additionalSans);

        final X509Certificate cert = ssc.cert();
        final Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        assertThat(sans).isNotNull();

        // Extract DNS names (type 2), IP addresses (type 7), and URIs (type 6) from SANs
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

        assertThat(dnsNames).contains("test.armeria.dev");
        assertThat(dnsNames).contains("example.com");
        assertThat(ipAddresses).contains("192.168.1.1");
        assertThat(uris).contains("spiffe://centraldogma.dev/sa/centraldogma-alpha",
                                  "https://api.example.com/service");
    }
}
