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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi.EC;
import org.bouncycastle.jcajce.provider.config.ConfigurableProvider;
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * A downsized version of {@link BouncyCastleProvider} which provides only RSA/DSA/EC {@link KeyFactorySpi}s.
 */
public final class BouncyCastleKeyFactoryProvider extends Provider implements ConfigurableProvider {

    private static final long serialVersionUID = -834653615603942658L;

    private static final String PROVIDER_NAME = "ABCKFC";

    private static final Map<ASN1ObjectIdentifier, AsymmetricKeyInfoConverter> keyInfoConverters =
            new HashMap<>();

    /**
     * Invokes the specified {@link Runnable} with {@link BouncyCastleKeyFactoryProvider} enabled temporarily.
     */
    public static void call(Runnable task) {
        call(() -> {
            task.run();
            return true;
        });
    }

    /**
     * Invokes the specified {@link Supplier} with {@link BouncyCastleKeyFactoryProvider} enabled temporarily.
     */
    public static synchronized <T> T call(Supplier<T> task) {
        boolean needToAdd = true;
        for (Provider provider : Security.getProviders()) {
            if (provider instanceof BouncyCastleKeyFactoryProvider) {
                needToAdd = false;
                break;
            }
        }

        if (needToAdd) {
            Security.addProvider(new BouncyCastleKeyFactoryProvider());
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
    private BouncyCastleKeyFactoryProvider() {
        super(PROVIDER_NAME, 1.0, "Armeria Bouncy Castle KeyFactory Collection");
        AccessController.doPrivileged((PrivilegedAction<?>) () -> {
            addKeyFactories();
            return true;
        });
    }

    private void addKeyFactories() {
        addKeyFactory(org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyFactorySpi.class, "RSA");
        addKeyFactory(org.bouncycastle.jcajce.provider.asymmetric.dsa.KeyFactorySpi.class, "DSA");
        addKeyFactory(EC.class, "EC");
    }

    private void addKeyFactory(Class<? extends KeyFactorySpi> type, String name) {
        addAlgorithm("KeyFactory." + name, type.getName());
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
