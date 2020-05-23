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
package com.linecorp.armeria.server.saml;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.opensaml.security.credential.Credential;
import org.opensaml.xmlsec.signature.support.SignatureConstants;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.auth.Authorizer;

/**
 * A SAML service provider implementation.
 *
 * @see <a href="https://line.github.io/armeria/docs/advanced-saml">SAML Single Sign-On</a>
 */
public final class SamlServiceProvider {

    /**
     * Returns a new {@link SamlServiceProviderBuilder}.
     */
    public static SamlServiceProviderBuilder builder() {
        return new SamlServiceProviderBuilder();
    }

    private final Authorizer<HttpRequest> authorizer;

    private final String entityId;
    @Nullable
    private final String hostname;

    private final Credential signingCredential;
    private final Credential encryptionCredential;

    private final String signatureAlgorithm;

    private final SamlPortConfigAutoFiller portConfigAutoFiller;

    private final Route metadataRoute;

    private final Map<String, SamlIdentityProviderConfig> idpConfigs;
    @Nullable
    private final SamlIdentityProviderConfig defaultIdpConfig;
    private final SamlIdentityProviderConfigSelector idpConfigSelector;

    private final Collection<SamlAssertionConsumerConfig> acsConfigs;
    private final SamlAssertionConsumerConfig defaultAcsConfig;
    private final Collection<SamlEndpoint> sloEndpoints;

    private final SamlRequestIdManager requestIdManager;

    private final SamlSingleSignOnHandler ssoHandler;
    private final SamlSingleLogoutHandler sloHandler;

    /**
     * A class which helps a {@link Server} have a SAML-based authentication.
     */
    SamlServiceProvider(Authorizer<HttpRequest> authorizer,
                        String entityId,
                        @Nullable String hostname,
                        Credential signingCredential,
                        Credential encryptionCredential,
                        String signatureAlgorithm,
                        SamlPortConfigAutoFiller portConfigAutoFiller,
                        String metadataPath,
                        Map<String, SamlIdentityProviderConfig> idpConfigs,
                        @Nullable SamlIdentityProviderConfig defaultIdpConfig,
                        SamlIdentityProviderConfigSelector idpConfigSelector,
                        Collection<SamlAssertionConsumerConfig> acsConfigs,
                        Collection<SamlEndpoint> sloEndpoints,
                        SamlRequestIdManager requestIdManager,
                        SamlSingleSignOnHandler ssoHandler,
                        SamlSingleLogoutHandler sloHandler) {
        this.authorizer = requireNonNull(authorizer, "authorizer");
        this.entityId = requireNonNull(entityId, "entityId");
        this.hostname = hostname;
        this.signingCredential = requireNonNull(signingCredential, "signingCredential");
        this.encryptionCredential = requireNonNull(encryptionCredential, "encryptionCredential");
        this.signatureAlgorithm = requireNonNull(signatureAlgorithm, "signatureAlgorithm");
        this.portConfigAutoFiller = requireNonNull(portConfigAutoFiller, "portConfigAutoFiller");
        metadataRoute = Route.builder().exact(requireNonNull(metadataPath, "metadataPath")).build();
        this.idpConfigs = ImmutableMap.copyOf(requireNonNull(idpConfigs, "idpConfigs"));
        this.defaultIdpConfig = defaultIdpConfig;
        this.idpConfigSelector = requireNonNull(idpConfigSelector, "idpConfigSelector");
        this.acsConfigs = ImmutableList.copyOf(requireNonNull(acsConfigs, "acsConfigs"));
        this.sloEndpoints = ImmutableList.copyOf(requireNonNull(sloEndpoints, "sloEndpoints"));
        this.requestIdManager = requireNonNull(requestIdManager, "requestIdManager");
        this.ssoHandler = requireNonNull(ssoHandler, "ssoHandler");
        this.sloHandler = requireNonNull(sloHandler, "sloHandler");

        defaultAcsConfig = acsConfigs.stream().filter(SamlAssertionConsumerConfig::isDefault).findFirst()
                                     .orElseThrow(() -> new IllegalArgumentException(
                                             "no default assertion consumer config"));
    }

    /**
     * An {@link Authorizer} which authenticates the received {@link HttpRequest}.
     */
    Authorizer<HttpRequest> authorizer() {
        return authorizer;
    }

    /**
     * An entity ID of the service provider.
     */
    String entityId() {
        return entityId;
    }

    /**
     * A hostname of the service provider which is configured by a user. If a user did not specify a hostname,
     * the virtual hostname of the {@link Server} will be used.
     */
    @Nullable
    String hostname() {
        return hostname;
    }

    /**
     * A {@link Credential} for signing SAML messages.
     */
    Credential signingCredential() {
        return signingCredential;
    }

    /**
     * A {@link Credential} for encrypting SAML messages.
     */
    Credential encryptionCredential() {
        return encryptionCredential;
    }

    /**
     * An algorithm which is used when signing SAML messages. The default value is
     * {@value SignatureConstants#ALGO_ID_SIGNATURE_DSA}.
     */
    String signatureAlgorithm() {
        return signatureAlgorithm;
    }

    /**
     * A {@link SamlPortConfigAutoFiller} which fills the port number and its {@link SessionProtocol} that
     * the {@link Server} is bound to.
     */
    SamlPortConfigAutoFiller portConfigAutoFiller() {
        return portConfigAutoFiller;
    }

    /**
     * A {@link Route} for returning the metadata of the service provider.
     */
    Route metadataRoute() {
        return metadataRoute;
    }

    /**
     * A map of identity provider configurations.
     */
    Map<String, SamlIdentityProviderConfig> idpConfigs() {
        return idpConfigs;
    }

    /**
     * A default identity provider configuration.
     */
    @Nullable
    SamlIdentityProviderConfig defaultIdpConfig() {
        return defaultIdpConfig;
    }

    /**
     * A selector which selects an identity provider configuration.
     */
    SamlIdentityProviderConfigSelector idpConfigSelector() {
        return idpConfigSelector;
    }

    /**
     * The configurations of the assertion consumer services provided by the service provider.
     */
    Collection<SamlAssertionConsumerConfig> acsConfigs() {
        return acsConfigs;
    }

    /**
     * A default assertion consumer service configuration.
     */
    SamlAssertionConsumerConfig defaultAcsConfig() {
        return defaultAcsConfig;
    }

    /**
     * {@link SamlEndpoint}s for single logout service provided by the service provider.
     */
    Collection<SamlEndpoint> sloEndpoints() {
        return sloEndpoints;
    }

    /**
     * A {@link SamlRequestIdManager} which generates and validates a request ID.
     */
    SamlRequestIdManager requestIdManager() {
        return requestIdManager;
    }

    /**
     * An event handler for single sign-on.
     */
    SamlSingleSignOnHandler ssoHandler() {
        return ssoHandler;
    }

    /**
     * An event handler for single logout.
     */
    SamlSingleLogoutHandler sloHandler() {
        return sloHandler;
    }

    /**
     * Creates a decorator which initiates a SAML authentication if a request is not authenticated.
     */
    public Function<? super HttpService, ? extends HttpService> newSamlDecorator() {
        return delegate -> new SamlDecorator(this, delegate);
    }

    /**
     * Creates an {@link HttpService} which handles SAML messages.
     */
    public HttpServiceWithRoutes newSamlService() {
        return new SamlService(this);
    }
}
