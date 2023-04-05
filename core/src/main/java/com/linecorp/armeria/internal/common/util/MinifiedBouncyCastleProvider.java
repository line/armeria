/*
 * Copyright 2018 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;

import java.security.AccessController;
import java.security.KeyFactorySpi;
import java.security.PrivilegedAction;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateFactorySpi;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi.EC;
import org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory;
import org.bouncycastle.jcajce.provider.asymmetric.x509.KeyFactory;
import org.bouncycastle.jcajce.provider.config.ConfigurableProvider;
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * A downsized version of {@link BouncyCastleProvider} which provides only RSA/DSA/EC {@link KeyFactorySpi}s
 * and X.509 {@link CertificateFactorySpi}.
 */
public final class MinifiedBouncyCastleProvider extends Provider implements ConfigurableProvider {

    private static final long serialVersionUID = -834653615603942658L;

    private static final String PROVIDER_NAME = "ArmeriaBC";

    private static final Map<ASN1ObjectIdentifier, AsymmetricKeyInfoConverter> keyInfoConverters =
            new HashMap<>();

    /**
     * Invokes the specified {@link Runnable} with {@link MinifiedBouncyCastleProvider} enabled temporarily.
     */
    public static void call(Runnable task) {
        call(() -> {
            task.run();
            return true;
        });
    }

    /**
     * Invokes the specified {@link Supplier} with {@link MinifiedBouncyCastleProvider} enabled temporarily.
     */
    public static synchronized <T> T call(Supplier<T> task) {
        boolean needToAdd = true;
        for (Provider provider : Security.getProviders()) {
            if (provider instanceof MinifiedBouncyCastleProvider) {
                needToAdd = false;
                break;
            }
        }

        if (needToAdd) {
            Security.addProvider(new MinifiedBouncyCastleProvider());
            try {
                return task.get();
            } finally {
                Security.removeProvider(PROVIDER_NAME);
            }
        } else {
            return task.get();
        }
    }

    @SuppressWarnings("deprecation") // Not deprecated in Java 8.
    public MinifiedBouncyCastleProvider() {
        super(PROVIDER_NAME, 1.0, "Armeria Bouncy Castle Provider");
        AccessController.doPrivileged((PrivilegedAction<?>) () -> {
            addFactories();
            return true;
        });
    }

    private void addFactories() {
        // KeyFactories
        addKeyFactory(org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyFactorySpi.class, "RSA");
        addKeyFactory(org.bouncycastle.jcajce.provider.asymmetric.dsa.KeyFactorySpi.class, "DSA");
        addKeyFactory(EC.class, "EC");
        addKeyFactory(KeyFactory.class, "X.509", "X509");

        // CertificateFactories
        addCertificateFactory(CertificateFactory.class, "X.509", "X509");
    }

    private void addKeyFactory(
            Class<? extends KeyFactorySpi> factoryType, String name, String... aliases) {
        addFactory("KeyFactory.", factoryType, name, aliases);
    }

    private void addCertificateFactory(
            Class<? extends CertificateFactorySpi> factoryType, String name, String... aliases) {
        addFactory("CertificateFactory.", factoryType, name, aliases);
    }

    private void addFactory(String prefix, Class<?> factoryType, String name, String... aliases) {
        addAlgorithm(prefix + name, factoryType.getName());
        for (String alias : aliases) {
            addAlgorithm("Alg.Alias." + prefix + alias, name);
        }
    }

    @Override
    public void setParameter(String parameterName, Object parameter) {}

    @Override
    public boolean hasAlgorithm(String type, String name) {
        return containsKey(type + '.' + name) || containsKey("Alg.Alias." + type + '.' + name);
    }

    @Override
    public void addAlgorithm(String key, String value) {
        checkState(!containsKey(key), "duplicate algorithm: %s", key);
        put(key, value);
    }

    @Override
    public void addAlgorithm(String type, ASN1ObjectIdentifier oid, String className) {
        addAlgorithm(type + '.' + oid, className);
        addAlgorithm(type + ".OID." + oid, className);
    }

    @Override
    public void addKeyInfoConverter(ASN1ObjectIdentifier oid, AsymmetricKeyInfoConverter keyInfoConverter) {
        synchronized (keyInfoConverters) {
            keyInfoConverters.put(oid, keyInfoConverter);
        }
    }

    @Override
    public AsymmetricKeyInfoConverter getKeyInfoConverter(ASN1ObjectIdentifier oid) {
        return keyInfoConverters.get(oid);
    }

    @Override
    public void addAttributes(String key, Map<String, String> attributeMap) {
        for (final Entry<String, String> e : attributeMap.entrySet()) {
            final String attrKey = key + ' ' + e.getKey();
            checkState(!containsKey(attrKey), "duplicate attribute: %s", attrKey);
            put(attrKey, e.getValue());
        }
    }
}
