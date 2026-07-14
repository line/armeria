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

package com.yahoo.athenz.auth.token.jwts;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.util.ResourceRetriever;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link JwtsSigningKeyResolver} that uses an Armeria {@link WebClient} to fetch JWKS keys
 * instead of the default JDK HTTP client. This avoids the need to extract an {@link SSLContext}
 * from the Armeria {@link com.linecorp.armeria.client.ClientFactory}.
 *
 * <p>Use the static factory method {@link #create(WebClient, String)} to create an instance.
 */
@UnstableApi
public final class ArmeriaJwtsSigningKeyResolver extends JwtsSigningKeyResolver {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaJwtsSigningKeyResolver.class);

    private static final ThreadLocal<ArmeriaResourceRetriever> INIT_RETRIEVER = new ThreadLocal<>();

    /**
     * Creates a new {@link ArmeriaJwtsSigningKeyResolver}.
     *
     * @param webClient the {@link WebClient} pre-configured to connect to the ZTS server
     * @param oauth2KeysPath the path to the OAuth2 keys endpoint (e.g., "/oauth2/keys?rfc=true")
     */
    public static ArmeriaJwtsSigningKeyResolver create(WebClient webClient, String oauth2KeysPath) {
        INIT_RETRIEVER.set(new ArmeriaResourceRetriever(webClient, oauth2KeysPath));
        try {
            final String jwksUri = webClient.uri() + oauth2KeysPath;
            return new ArmeriaJwtsSigningKeyResolver(jwksUri);
        } finally {
            INIT_RETRIEVER.remove();
        }
    }

    private ArmeriaJwtsSigningKeyResolver(String jwksUri) {
        super(jwksUri, null, true);
    }

    @Override
    ResourceRetriever getResourceRetriever(@Nullable String proxyUrl, @Nullable SSLContext sslContext) {
        final ArmeriaResourceRetriever retriever = INIT_RETRIEVER.get();
        if (retriever != null) {
            return retriever;
        }
        logger.warn("ArmeriaResourceRetriever is not available. " +
                     "Falling back to the default resource retriever.");
        return super.getResourceRetriever(proxyUrl, sslContext);
    }
}
