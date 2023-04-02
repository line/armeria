/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManagerFactorySpi;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.X509ExtendedKeyManager;

final class CustomAliasKeyManagerFactory extends KeyManagerFactory {
    CustomAliasKeyManagerFactory(KeyManagerFactory delegate, String alias) {
        super(new KeyManagerFactorySpi() {
            @Override
            protected void engineInit(KeyStore ks, char[] password)
                    throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
                delegate.init(ks, password);
            }

            @Override
            protected void engineInit(ManagerFactoryParameters spec) throws InvalidAlgorithmParameterException {
                delegate.init(spec);
            }

            @Override
            protected KeyManager[] engineGetKeyManagers() {
                final KeyManager[] keyManagers = delegate.getKeyManagers().clone();
                for (int i = 0; i < keyManagers.length; i++) {
                    if (keyManagers[i] instanceof X509ExtendedKeyManager) {
                        final X509ExtendedKeyManager keyManager = (X509ExtendedKeyManager) keyManagers[i];
                        keyManagers[i] = new CustomAliasX509ExtendedKeyManager(keyManager, alias);
                    }
                }
                return keyManagers;
            }
        }, delegate.getProvider(), delegate.getAlgorithm());
    }
}
