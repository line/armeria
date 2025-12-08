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

package com.linecorp.armeria.internal.common.util;

import static java.util.Objects.requireNonNull;

import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Random;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

final class CertificateParams {

    private final String fqdn;
    private final X500Name ownerName;
    private final Random random;
    private final int bits;
    private final Date notBefore;
    private final Date notAfter;
    private final String algorithm;
    private final ImmutableList<String> subjectAlternativeNames;

    @Nullable
    private final X509Certificate issuerCert;
    @Nullable
    private final PrivateKey issuerPrivateKey;
    private final X500Name issuerName;

    CertificateParams(String fqdn, Random random, int bits, Date notBefore, Date notAfter,
                      String algorithm, Iterable<String> subjectAlternativeNames) {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        this.random = requireNonNull(random, "random");
        this.bits = bits;
        this.notBefore = requireNonNull(notBefore, "notBefore");
        this.notAfter = requireNonNull(notAfter, "notAfter");
        this.algorithm = requireNonNull(algorithm, "algorithm");
        this.subjectAlternativeNames =
                ImmutableList.copyOf(requireNonNull(subjectAlternativeNames, "subjectAlternativeNames"));
        issuerCert = null;
        issuerPrivateKey = null;

        ownerName = new X500Name("CN=" + fqdn);
        issuerName = ownerName;
    }

    CertificateParams(String fqdn, Random random, int bits, Date notBefore, Date notAfter,
                      SignedCertificate issuer)
            throws CertificateException {
        this(fqdn, random, bits, notBefore, notAfter, issuer, ImmutableList.of());
    }

    CertificateParams(String fqdn, Random random, int bits, Date notBefore, Date notAfter,
                      SignedCertificate issuer, Iterable<String> subjectAlternativeNames)
            throws CertificateException {
        this.fqdn = requireNonNull(fqdn, "fqdn");
        ownerName = new X500Name("CN=" + fqdn);

        this.random = requireNonNull(random, "random");
        this.bits = bits;
        this.notBefore = requireNonNull(notBefore, "notBefore");
        this.notAfter = requireNonNull(notAfter, "notAfter");
        this.subjectAlternativeNames =
                ImmutableList.copyOf(requireNonNull(subjectAlternativeNames, "subjectAlternativeNames"));
        requireNonNull(issuer, "issuer");
        algorithm = issuer.key().getAlgorithm();
        issuerCert = issuer.cert();
        issuerPrivateKey = issuer.key();
        issuerName = new JcaX509CertificateHolder(issuerCert).getSubject();
    }

    String fqdn() {
        return fqdn;
    }

    Random random() {
        return random;
    }

    int bits() {
        return bits;
    }

    Date notBefore() {
        return notBefore;
    }

    Date notAfter() {
        return notAfter;
    }

    String algorithm() {
        return algorithm;
    }

    @Nullable PrivateKey issuerPrivateKey() {
        return issuerPrivateKey;
    }

    @Nullable X509Certificate issuerCert() {
        return issuerCert;
    }

    X500Name issuerName() {
        return issuerName;
    }

    X500Name ownerName() {
        return ownerName;
    }

    ImmutableList<String> subjectAlternativeNames() {
        return subjectAlternativeNames;
    }
}
