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

import static com.linecorp.armeria.internal.common.util.SelfSignedCertificate.newSelfSignedCertificate;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates a self-signed certificate using <a href="https://www.bouncycastle.org/">Bouncy Castle</a>.
 */
final class BouncyCastleSelfSignedCertGenerator {

    // Forked from:
    // https://github.com/netty/netty/blob/11e6a77fba9ec7184a558d869373d0ce506d7236/handler/src/main/java/io/netty/handler/ssl/util/BouncyCastleSelfSignedCertGenerator.java

    private static final Provider PROVIDER = new MinifiedBouncyCastleProvider();

    static String[] generate(String fqdn, KeyPair keypair, SecureRandom random, Date notBefore, Date notAfter,
                             String algorithm) throws Exception {
        final PrivateKey key = keypair.getPrivate();

        // Prepare the information required for generating an X.509 certificate.
        final X500Name owner = new X500Name("CN=" + fqdn);
        final X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                owner, new BigInteger(64, random), notBefore, notAfter, owner, keypair.getPublic());

        final ContentSigner signer = new JcaContentSignerBuilder(
                "EC".equalsIgnoreCase(algorithm) ? "SHA256withECDSA" : "SHA256WithRSAEncryption").build(key);
        final X509CertificateHolder certHolder = builder.build(signer);
        final X509Certificate cert =
                new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(certHolder);
        cert.verify(keypair.getPublic());

        return newSelfSignedCertificate(fqdn, key, cert);
    }

    private BouncyCastleSelfSignedCertGenerator() { }
}
