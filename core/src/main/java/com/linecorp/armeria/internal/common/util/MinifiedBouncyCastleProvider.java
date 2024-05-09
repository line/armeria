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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.security.AccessController;
import java.security.KeyFactorySpi;
import java.security.PrivilegedAction;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateFactorySpi;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.jcajce.provider.asymmetric.DSA;
import org.bouncycastle.jcajce.provider.asymmetric.EC;
import org.bouncycastle.jcajce.provider.asymmetric.RSA;
import org.bouncycastle.jcajce.provider.asymmetric.X509;
import org.bouncycastle.jcajce.provider.config.ConfigurableProvider;
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.common.annotations.VisibleForTesting;

/**
 * A downsized version of {@link BouncyCastleProvider} which provides only RSA/DSA/EC {@link KeyFactorySpi}s
 * and X.509 {@link CertificateFactorySpi}.
 */
public final class MinifiedBouncyCastleProvider extends Provider implements ConfigurableProvider {

    private static final long serialVersionUID = -834653615603942658L;

    /**
     * The relocated Bouncy Castle package prefix. If relocated, it will be:
     * {@code com.linecorp.armeria.internal.shaded.bouncycastle}.
     */
    @VisibleForTesting
    @SuppressWarnings("DynamicRegexReplaceableByCompiledPattern")
    static final String BC_PACKAGE_PREFIX =
            BouncyCastleProvider.class.getName().replaceFirst("\\.bouncycastle\\..*$", ".bouncycastle.");

    private static final String PROVIDER_NAME = "ArmeriaBC";

    private static final ReentrantLock lock = new ReentrantShortLock();

    private static final Map<ASN1ObjectIdentifier, AsymmetricKeyInfoConverter> keyInfoConverters =
            new ConcurrentHashMap<>();

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
    public static <T> T call(Supplier<T> task) {
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
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
        new RSA.Mappings().configure(this);
        new DSA.Mappings().configure(this);
        new EC.Mappings().configure(this);
        new X509.Mappings().configure(this);
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
        if (value.contains(".bouncycastle.")) {
            // Very likely to be a class name.
            checkClassName(value);
        }
        put(key, value);
    }

    @Override
    public void addAlgorithm(String type, ASN1ObjectIdentifier oid, String className) {
        checkClassName(className);
        addAlgorithm(type + '.' + oid, className);
        addAlgorithm(type + ".OID." + oid, className);
    }

    private static void checkClassName(String className) {
        // Bouncy Castle sometimes uses a hard-coded class name when configuring itself.
        // Such hard-coded names may or may not be updated during the class relocation process
        // depending on how and what relocation tool we use, so this `assert` will help us
        // notice when the relocation did not work as expected.
        checkArgument(className.startsWith(BC_PACKAGE_PREFIX),
                      "Unexpected Bouncy Castle class name: %s", className);
    }

    @Override
    public void addKeyInfoConverter(ASN1ObjectIdentifier oid, AsymmetricKeyInfoConverter keyInfoConverter) {
        keyInfoConverters.put(oid, keyInfoConverter);
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
