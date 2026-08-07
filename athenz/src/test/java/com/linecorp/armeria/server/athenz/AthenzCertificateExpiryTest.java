/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.server.athenz;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Fails a month before any certificate in the Athenz Docker fixture expires, so that the expiry
 * surfaces as a clear message instead of an opaque {@link AthenzDocker} initialization failure.
 * See {@code src/test/resources/docker/README.md} for how to renew an expiring certificate.
 */
class AthenzCertificateExpiryTest {

    private static final Duration EXPIRY_MARGIN = Duration.ofDays(30);
    private static final String STORE_PASSWORD = "athenz";

    private static final Pattern CERT_PATTERN = Pattern.compile(
            "-----BEGIN CERTIFICATE-----[^-]+-----END CERTIFICATE-----");

    @Test
    void fixtureCertificatesAreNotAboutToExpire() throws Exception {
        final Path dockerDir = Paths.get(
                requireNonNull(AthenzDocker.class.getResource("/docker"), "/docker").toURI());
        final Date deadline = Date.from(Instant.now().plus(EXPIRY_MARGIN));
        final List<String> problems = new ArrayList<>();
        int numCerts = 0;

        try (Stream<Path> files = Files.walk(dockerDir)) {
            for (final Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                final List<X509Certificate> certs;
                try {
                    certs = certificatesIn(file);
                } catch (Exception e) {
                    throw new AssertionError(
                            "Failed to read certificates from " + dockerDir.relativize(file) +
                            " (a keystore must use the password \"" + STORE_PASSWORD + "\"): " + e, e);
                }
                for (final X509Certificate cert : certs) {
                    numCerts++;
                    try {
                        cert.checkValidity(deadline);
                    } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                        problems.add(dockerDir.relativize(file) + " [" + cert.getSubjectX500Principal() +
                                     "] is not valid at " + deadline + " (notBefore: " + cert.getNotBefore() +
                                     ", notAfter: " + cert.getNotAfter() + ')');
                    }
                }
            }
        }

        assertThat(numCerts)
                .withFailMessage("Found only %s certificates under %s; the fixture layout may have " +
                                 "changed and this canary may have gone blind.", numCerts, dockerDir)
                .isGreaterThan(10);
        assertThat(problems)
                .withFailMessage("The following Athenz fixture certificates expire within %s days. " +
                                 "Renew them with the CA keys in this fixture " +
                                 "(see docker/README.md): %s", EXPIRY_MARGIN.toDays(), problems)
                .isEmpty();
    }

    private static List<X509Certificate> certificatesIn(Path file) throws Exception {
        final String name = file.getFileName().toString();
        if (name.endsWith(".pem") || name.endsWith(".crt")) {
            return pemCertificates(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        if (name.endsWith(".pkcs12") || name.endsWith(".p12") || name.endsWith(".jks")) {
            return keyStoreCertificates(file, name.endsWith(".jks") ? "JKS" : "PKCS12");
        }
        return List.of();
    }

    private static List<X509Certificate> pemCertificates(String pem) throws CertificateException {
        final List<X509Certificate> certs = new ArrayList<>();
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        final Matcher matcher = CERT_PATTERN.matcher(pem);
        while (matcher.find()) {
            final InputStream in = new ByteArrayInputStream(
                    matcher.group().getBytes(StandardCharsets.UTF_8));
            certs.add((X509Certificate) factory.generateCertificate(in));
        }
        return certs;
    }

    private static List<X509Certificate> keyStoreCertificates(Path file, String type) throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(file)) {
            keyStore.load(in, STORE_PASSWORD.toCharArray());
        }
        final List<X509Certificate> certs = new ArrayList<>();
        final Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            final Certificate[] chain = keyStore.getCertificateChain(alias);
            if (chain != null) {
                for (final Certificate cert : chain) {
                    if (cert instanceof X509Certificate) {
                        certs.add((X509Certificate) cert);
                    }
                }
            } else {
                final Certificate cert = keyStore.getCertificate(alias);
                if (cert instanceof X509Certificate) {
                    certs.add((X509Certificate) cert);
                }
            }
        }
        return certs;
    }
}
