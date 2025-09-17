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

package com.linecorp.armeria.internal.server.athenz;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClientBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.athenz.AthenzPolicyConfig;
import com.linecorp.armeria.server.athenz.AthenzServiceDecoratorFactory;
import com.linecorp.armeria.server.athenz.AthenzServiceDecoratorFactoryBuilder;

/**
 * A helper class to create an {@link AthenzServiceDecoratorFactory} instance.
 * This class is used internally by the Spring Boot integration module to create the factory with reflection.
 */
public final class AthenzServiceDecoratorFactoryProvider {

    public static AthenzServiceDecoratorFactory create(
            ServerBuilder sb, URI ztsUri, File athenzPrivateKey, File athenzPublicKey,
            @Nullable URI proxyUri, @Nullable File athenzCaCert, @Nullable String oauth2KeysPath,
            List<String> domains,
            boolean jwsPolicySupport,
            Duration policyRefreshInterval) {

        final ZtsBaseClientBuilder clientBuilder =
                ZtsBaseClient.builder(ztsUri)
                             .keyPair(athenzPrivateKey, athenzPublicKey);
        if (proxyUri != null) {
            clientBuilder.proxyUri(proxyUri);
        }
        if (athenzCaCert != null) {
            clientBuilder.trustedCertificate(athenzCaCert);
        }

        final AthenzPolicyConfig athenzPolicyConfig = new AthenzPolicyConfig(domains, ImmutableMap.of(),
                                                                             jwsPolicySupport,
                                                                             policyRefreshInterval);
        final ZtsBaseClient ztsBaseClient = clientBuilder.build();
        sb.serverListener(new ServerListenerAdapter() {
            @Override
            public void serverStopped(Server server) throws Exception {
                ztsBaseClient.close();
            }
        });
        final AthenzServiceDecoratorFactoryBuilder factoryBuilder =
                AthenzServiceDecoratorFactory
                        .builder(ztsBaseClient);
        if (oauth2KeysPath != null) {
            factoryBuilder.oauth2KeysPath(oauth2KeysPath);
        }
        return factoryBuilder
                .policyConfig(athenzPolicyConfig)
                .build();
    }

    private AthenzServiceDecoratorFactoryProvider() {}
}
