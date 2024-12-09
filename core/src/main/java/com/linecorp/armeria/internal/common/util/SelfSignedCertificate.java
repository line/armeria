/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

import static org.bouncycastle.asn1.x509.Extension.subjectAlternativeName;
import static org.bouncycastle.asn1.x509.GeneralName.dNSName;
import static org.bouncycastle.asn1.x509.GeneralName.iPAddress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;

/**
 * Generates a temporary self-signed certificate for testing purposes.
 * <p>
 * <strong>NOTE:</strong>
 * Never use the certificate and private key generated by this class in production.
 * It is purely for testing purposes, and thus it is very insecure.
 * It even uses an insecure pseudo-random generator for faster generation by default.
 * </p><p>
 * An X.509 certificate file and a EC/RSA private key file are generated in a system's temporary directory using
 * {@link File#createTempFile(String, String)}, and they are deleted when the JVM exits using
 * {@link File#deleteOnExit()}.
 * </p>
 */
public final class SelfSignedCertificate {

    // Forked from:
    // https://github.com/netty/netty/blob/11e6a77fba9ec7184a558d869373d0ce506d7236/handler/src/main/java/io/netty/handler/ssl/util/SelfSignedCertificate.java
    // https://github.com/netty/netty/blob/11e6a77fba9ec7184a558d869373d0ce506d7236/handler/src/main/java/io/netty/handler/ssl/util/BouncyCastleSelfSignedCertGenerator.java
    //
    // Changes:
    // - Always use shaded Bouncy Castle instead of JDK, so it works on Java 16+.
    //   See https://github.com/line/armeria/issues/3673 for more information.
    // - Accept `Random` instead of `SecureRandom`.
    // - Inline `BouncyCastleSelfSignedCertGenerator`.

    private static final Logger logger = LoggerFactory.getLogger(SelfSignedCertificate.class);

    private static final Provider bouncyCastleProvider = new MinifiedBouncyCastleProvider();

    /** Current time minus 1 year, just in case software clock goes back due to time synchronization. */
    private static final Date DEFAULT_NOT_BEFORE = new Date(SystemPropertyUtil.getLong(
            "io.netty.selfSignedCertificate.defaultNotBefore", System.currentTimeMillis() - 86400000L * 365));
    /** The maximum possible value in X.509 specification: 9999-12-31 23:59:59 */
    private static final Date DEFAULT_NOT_AFTER = new Date(SystemPropertyUtil.getLong(
            "io.netty.selfSignedCertificate.defaultNotAfter", 253402300799000L));

    /**
     * FIPS 140-2 encryption requires the RSA key length to be 2048 bits or greater.
     * Let's use that as a sane default but allow the default to be set dynamically
     * for those that need more stringent security requirements.
     */
    private static final int DEFAULT_KEY_LENGTH_BITS =
            SystemPropertyUtil.getInt("io.netty.handler.ssl.util.selfSignedKeyStrength", 2048);

    private final File certificate;
    private final File privateKey;
    private final X509Certificate cert;
    private final PrivateKey key;

    /**
     * Creates a new instance.
     * <p> Algorithm: RSA </p>
     */
    public SelfSignedCertificate() throws CertificateException {
        this(DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER, "RSA", DEFAULT_KEY_LENGTH_BITS);
    }

    /**
     * Creates a new instance.
     * <p> Algorithm: RSA </p>
     *
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     */
    public SelfSignedCertificate(Date notBefore, Date notAfter)
            throws CertificateException {
        this("localhost", notBefore, notAfter, "RSA", DEFAULT_KEY_LENGTH_BITS);
    }

    /**
     * Creates a new instance.
     *
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     * @param algorithm Key pair algorithm
     * @param bits      the number of bits of the generated private key
     */
    public SelfSignedCertificate(Date notBefore, Date notAfter, String algorithm, int bits)
            throws CertificateException {
        this("localhost", notBefore, notAfter, algorithm, bits);
    }

    /**
     * Creates a new instance.
     * <p> Algorithm: RSA </p>
     *
     * @param fqdn a fully qualified domain name
     */
    public SelfSignedCertificate(String fqdn) throws CertificateException {
        this(fqdn, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER, "RSA", DEFAULT_KEY_LENGTH_BITS);
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn      a fully qualified domain name
     * @param algorithm Key pair algorithm
     * @param bits      the number of bits of the generated private key
     */
    public SelfSignedCertificate(String fqdn, String algorithm, int bits) throws CertificateException {
        this(fqdn, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER, algorithm, bits);
    }

    /**
     * Creates a new instance.
     * <p> Algorithm: RSA </p>
     *
     * @param fqdn      a fully qualified domain name
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     */
    public SelfSignedCertificate(String fqdn, Date notBefore, Date notAfter) throws CertificateException {
        // Bypass entropy collection by using insecure random generator.
        // We just want to generate it without any delay because it's for testing purposes only.
        this(fqdn, ThreadLocalRandom.current(), DEFAULT_KEY_LENGTH_BITS, notBefore, notAfter, "RSA");
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn      a fully qualified domain name
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     * @param algorithm Key pair algorithm
     * @param bits      the number of bits of the generated private key
     */
    public SelfSignedCertificate(String fqdn, Date notBefore, Date notAfter, String algorithm, int bits)
            throws CertificateException {
        // Bypass entropy collection by using insecure random generator.
        // We just want to generate it without any delay because it's for testing purposes only.
        this(fqdn, ThreadLocalRandom.current(), bits, notBefore, notAfter, algorithm);
    }

    /**
     * Creates a new instance.
     * <p> Algorithm: RSA </p>
     *
     * @param fqdn      a fully qualified domain name
     * @param random    the {@link Random} to use
     * @param bits      the number of bits of the generated private key
     */
    public SelfSignedCertificate(String fqdn, Random random, int bits)
            throws CertificateException {
        this(fqdn, random, bits, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER, "RSA");
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn      a fully qualified domain name
     * @param random    the {@link Random} to use
     * @param algorithm Key pair algorithm
     * @param bits      the number of bits of the generated private key
     */
    public SelfSignedCertificate(String fqdn, Random random, String algorithm, int bits)
            throws CertificateException {
        this(fqdn, random, bits, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER, algorithm);
    }

    /**
     * Creates a new instance.
     * <p> Algorithm: RSA </p>
     *
     * @param fqdn      a fully qualified domain name
     * @param random    the {@link Random} to use
     * @param bits      the number of bits of the generated private key
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     */
    public SelfSignedCertificate(String fqdn, Random random, int bits, Date notBefore, Date notAfter)
            throws CertificateException {
        this(fqdn, random, bits, notBefore, notAfter, "RSA");
    }

    /**
     * Creates a new instance.
     *
     * @param fqdn      a fully qualified domain name
     * @param random    the {@link Random} to use
     * @param bits      the number of bits of the generated private key
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     * @param algorithm Key pair algorithm
     */
    public SelfSignedCertificate(String fqdn, Random random, int bits, Date notBefore, Date notAfter,
                                 String algorithm) throws CertificateException {

        if (!"EC".equalsIgnoreCase(algorithm) && !"RSA".equalsIgnoreCase(algorithm)) {
            throw new IllegalArgumentException("Algorithm not valid: " + algorithm);
        }

        final KeyPair keypair;
        try {
            final KeyPairGenerator keyGen = KeyPairGenerator.getInstance(algorithm);
            keyGen.initialize(bits, toSecureRandom(random));
            keypair = keyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            // Should not reach here because every Java implementation must have RSA and EC key pair generator.
            throw new Error(e);
        }

        final String[] paths = MinifiedBouncyCastleProvider.call(() -> {
            try {
                return generate(
                        fqdn, keypair, random, notBefore, notAfter, algorithm);
            } catch (Throwable cause) {
                return Exceptions.throwUnsafely(new CertificateException(
                        "Failed to generate a self-signed X.509 certificate: " + cause,
                        cause));
            }
        });

        certificate = new File(paths[0]);
        privateKey = new File(paths[1]);
        key = keypair.getPrivate();
        FileInputStream certificateInput = null;
        try {
            certificateInput = new FileInputStream(certificate);
            cert = (X509Certificate) CertificateFactory.getInstance("X509")
                                                       .generateCertificate(certificateInput);
        } catch (Exception e) {
            throw new CertificateEncodingException(e);
        } finally {
            if (certificateInput != null) {
                try {
                    certificateInput.close();
                } catch (IOException e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Failed to close a file: {}", certificate, e);
                    }
                }
            }
        }
    }

    /**
     * Returns the generated X.509 certificate file in PEM format.
     */
    public File certificate() {
        return certificate;
    }

    /**
     * Returns the generated RSA private key file in PEM format.
     */
    public File privateKey() {
        return privateKey;
    }

    /**
     *  Returns the generated X.509 certificate.
     */
    public X509Certificate cert() {
        return cert;
    }

    /**
     * Returns the generated RSA private key.
     */
    public PrivateKey key() {
        return key;
    }

    /**
     * Deletes the generated X.509 certificate file and RSA private key file.
     */
    public void delete() {
        safeDelete(certificate);
        safeDelete(privateKey);
    }

    private static String[] generate(String fqdn, KeyPair keypair, Random random,
                                     Date notBefore, Date notAfter, String algorithm) throws Exception {

        final PrivateKey key = keypair.getPrivate();

        // Prepare the information required for generating an X.509 certificate.
        final X500Name owner = new X500Name("CN=" + fqdn);
        final X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                owner, new BigInteger(64, random), notBefore, notAfter, owner, keypair.getPublic());

        final List<GeneralName> names = new ArrayList<>();
        // Add fqdn as Subject Alternative Name too for clients that don't use CN when validating such as
        // OkHttp.
        if (NetUtil.isValidIpV4Address(fqdn) || NetUtil.isValidIpV6Address(fqdn)) {
            names.add(new GeneralName(iPAddress, fqdn));
        } else {
            names.add(new GeneralName(dNSName, fqdn));
        }

        // Support request with IP or domain for local connections.
        if ("localhost".equals(fqdn)) {
            names.add(new GeneralName(iPAddress, "127.0.0.1"));
            names.add(new GeneralName(iPAddress, "::1"));
        } else if ("127.0.0.1".equals(fqdn)) {
            names.add(new GeneralName(dNSName, "localhost"));
            names.add(new GeneralName(iPAddress, "::1"));
        }

        builder.addExtension(subjectAlternativeName,
                             false,
                             new GeneralNames(names.toArray(new GeneralName[0])));

        final ContentSigner signer = new JcaContentSignerBuilder(
                "EC".equalsIgnoreCase(algorithm) ? "SHA256withECDSA" : "SHA256WithRSAEncryption").build(key);
        final X509CertificateHolder certHolder = builder.build(signer);
        final X509Certificate cert =
                new JcaX509CertificateConverter().setProvider(bouncyCastleProvider).getCertificate(certHolder);
        cert.verify(keypair.getPublic());

        // Encode the private key into a file.
        ByteBuf wrappedBuf = Unpooled.wrappedBuffer(key.getEncoded());
        ByteBuf encodedBuf;
        final String keyText;
        try {
            encodedBuf = Base64.encode(wrappedBuf, true);
            try {
                keyText = "-----BEGIN PRIVATE KEY-----\n" +
                          encodedBuf.toString(CharsetUtil.US_ASCII) +
                          "\n-----END PRIVATE KEY-----\n";
            } finally {
                encodedBuf.release();
            }
        } finally {
            wrappedBuf.release();
        }

        // Change all asterisk to 'x' for file name safety.
        fqdn = fqdn.replaceAll("[^\\w.-]", "x");

        final File keyFile = PlatformDependent.createTempFile("keyutil_" + fqdn + '_', ".key", null);
        keyFile.deleteOnExit();

        OutputStream keyOut = new FileOutputStream(keyFile);
        try {
            keyOut.write(keyText.getBytes(CharsetUtil.US_ASCII));
            keyOut.close();
            keyOut = null;
        } finally {
            if (keyOut != null) {
                safeClose(keyFile, keyOut);
                safeDelete(keyFile);
            }
        }

        wrappedBuf = Unpooled.wrappedBuffer(cert.getEncoded());
        final String certText;
        try {
            encodedBuf = Base64.encode(wrappedBuf, true);
            try {
                // Encode the certificate into a CRT file.
                certText = "-----BEGIN CERTIFICATE-----\n" +
                           encodedBuf.toString(CharsetUtil.US_ASCII) +
                           "\n-----END CERTIFICATE-----\n";
            } finally {
                encodedBuf.release();
            }
        } finally {
            wrappedBuf.release();
        }

        final File certFile = PlatformDependent.createTempFile("keyutil_" + fqdn + '_', ".crt", null);
        certFile.deleteOnExit();

        OutputStream certOut = new FileOutputStream(certFile);
        try {
            certOut.write(certText.getBytes(CharsetUtil.US_ASCII));
            certOut.close();
            certOut = null;
        } finally {
            if (certOut != null) {
                safeClose(certFile, certOut);
                safeDelete(certFile);
                safeDelete(keyFile);
            }
        }

        return new String[] { certFile.getPath(), keyFile.getPath() };
    }

    private static void safeDelete(File certFile) {
        if (!certFile.delete()) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to delete a file: " + certFile);
            }
        }
    }

    private static void safeClose(File keyFile, OutputStream keyOut) {
        try {
            keyOut.close();
        } catch (IOException e) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close a file: " + keyFile, e);
            }
        }
    }

    private static SecureRandom toSecureRandom(Random random) {
        if (random instanceof SecureRandom) {
            return (SecureRandom) random;
        }

        return new SecureRandom() {

            private static final long serialVersionUID = -4101939112170161136L;

            @Override
            public String getAlgorithm() {
                return "unknown";
            }

            @Override
            public void setSeed(byte[] seed) {}

            @Override
            public void setSeed(long seed) {}

            @Override
            public void nextBytes(byte[] bytes) {
                random.nextBytes(bytes);
            }

            @Override
            public byte[] generateSeed(int numBytes) {
                final byte[] seed = new byte[numBytes];
                random.nextBytes(seed);
                return seed;
            }

            @Override
            public int nextInt() {
                return random.nextInt();
            }

            @Override
            public int nextInt(int n) {
                return random.nextInt(n);
            }

            @Override
            public boolean nextBoolean() {
                return random.nextBoolean();
            }

            @Override
            public long nextLong() {
                return random.nextLong();
            }

            @Override
            public float nextFloat() {
                return random.nextFloat();
            }

            @Override
            public double nextDouble() {
                return random.nextDouble();
            }

            @Override
            public double nextGaussian() {
                return random.nextGaussian();
            }
        };
    }
}