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
package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

public class CertificateUtilTest {

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(CertificateUtil.class);
    @Nullable
    private Level oldLevel;

    @BeforeEach
    void attachAppender() {
        logAppender.start();
        oldLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logAppender);
        logger.setLevel(oldLevel);
        logAppender.list.clear();
    }

    @Test
    void subjectAltNameTakesPrecedenceOverCommonName() throws Exception {
        final X500NameBuilder subject = new X500NameBuilder(BCStyle.INSTANCE);
        subject.addRDN(BCStyle.C, "KR");
        subject.addRDN(BCStyle.O, "Armeria");
        subject.addRDN(BCStyle.CN, "cn.armeria.dev");
        final X509Certificate cert = newCertificate(subject.build(), "san.armeria.dev");

        assertThat(CertificateUtil.getHostname(cert)).isEqualTo("san.armeria.dev");
    }

    @Test
    void commonNameTakesPrecedenceOverSubjectDn() throws Exception {
        final X500NameBuilder subject = new X500NameBuilder(BCStyle.INSTANCE);
        subject.addRDN(BCStyle.C, "KR");
        subject.addRDN(BCStyle.O, "Armeria");
        subject.addRDN(BCStyle.CN, "cn.armeria.dev");
        final X509Certificate cert = newCertificate(subject.build(), null);

        assertThat(CertificateUtil.getHostname(cert)).isEqualTo("cn.armeria.dev");
    }

    @Test
    void fallsBackToSubjectDnWhenCnAndSanAreAbsent() throws Exception {
        final X500NameBuilder subject = new X500NameBuilder(BCStyle.INSTANCE);
        subject.addRDN(BCStyle.C, "TW");
        subject.addRDN(BCStyle.O, "Chunghwa Telecom Co., Ltd.");
        subject.addRDN(BCStyle.OU, "ePKI Root Certification Authority");
        final X509Certificate cert = newCertificate(subject.build(), null);

        assertThat(CertificateUtil.getHostname(cert))
                .isEqualTo("OU=ePKI Root Certification Authority,O=Chunghwa Telecom Co.\\, Ltd.,C=TW");
        // The fallback must not log anything.
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void fallsBackToSubjectDnWhenCommonNameIsEmpty() throws Exception {
        final X500NameBuilder subject = new X500NameBuilder(BCStyle.INSTANCE);
        subject.addRDN(BCStyle.C, "KR");
        subject.addRDN(BCStyle.O, "Armeria");
        subject.addRDN(BCStyle.CN, "");
        final X509Certificate cert = newCertificate(subject.build(), null);

        assertThat(CertificateUtil.getHostname(cert)).isEqualTo("CN=,O=Armeria,C=KR");
    }

    @Test
    void cachesNegativeResultOnExtractionFailureAndWarnsOnlyOnce() throws Exception {
        final X509Certificate cert = mock(X509Certificate.class);
        when(cert.getEncoded()).thenThrow(new CertificateEncodingException("failure"));

        assertThat(CertificateUtil.getHostname(cert)).isNull();
        assertThat(CertificateUtil.getHostname(cert)).isNull();

        // The negative result must be cached, so the warning must be emitted only once.
        assertThat(logAppender.list)
                .filteredOn(event -> event.getFormattedMessage().contains(
                        "Failed to get the hostname from a certificate"))
                .hasSize(1);
    }

    @Test
    void cachesNegativeResultAndLogsOnlyOnce() throws Exception {
        // The JDK requires a non-empty issuer, and an empty subject is allowed only with a critical
        // subject alternative name extension. An rfc822Name is neither a DNS name nor an IP address,
        // so no hostname can be extracted from this certificate.
        final X509Certificate cert = newCertificate(
                new X500Name(new RDN[0]), new X500Name("CN=issuer.armeria.dev"),
                new GeneralNames(new GeneralName(GeneralName.rfc822Name, "cert@armeria.dev")), true);

        assertThat(CertificateUtil.getHostname(cert)).isNull();
        assertThat(CertificateUtil.getHostname(cert)).isNull();

        // The negative result must be cached, so the log must be emitted only once.
        assertThat(logAppender.list)
                .filteredOn(event -> event.getFormattedMessage().contains(
                        "No subject alternative name, common name or subject distinguished name"))
                .hasSize(1);
    }

    @Test
    void returnsNullForNullOrNonX509Certificate() {
        assertThat(CertificateUtil.getHostname((Certificate) null)).isNull();
        assertThat(CertificateUtil.getHostname(mock(Certificate.class))).isNull();
    }

    /**
     * Generates a self-signed certificate with the specified subject and an optional DNS subject
     * alternative name. Unlike {@link SelfSignedCertificate}, the subject does not need to contain
     * a common name and the subject alternative name extension can be omitted entirely.
     */
    public static X509Certificate newCertificate(X500Name subject, @Nullable String san) throws Exception {
        final GeneralNames sanNames =
                san != null ? new GeneralNames(new GeneralName(GeneralName.dNSName, san)) : null;
        return newCertificate(subject, subject, sanNames, false);
    }

    private static X509Certificate newCertificate(X500Name subject, X500Name issuer,
                                                  @Nullable GeneralNames san, boolean sanCritical)
            throws Exception {
        final KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        final KeyPair keyPair = keyGen.generateKeyPair();
        final Instant now = Instant.now();
        final X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, new BigInteger(64, ThreadLocalRandom.current()),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(1, ChronoUnit.DAYS)),
                subject, keyPair.getPublic());
        if (san != null) {
            builder.addExtension(Extension.subjectAlternativeName, sanCritical, san);
        }
        final ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
