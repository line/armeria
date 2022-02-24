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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.responseWithLocation;
import static com.linecorp.armeria.server.saml.SamlEndpoint.ofHttpPost;
import static com.linecorp.armeria.server.saml.SamlEndpoint.ofHttpRedirect;
import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.CredentialResolver;
import org.opensaml.xmlsec.algorithm.AlgorithmSupport;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shibboleth.utilities.java.support.resolver.CriteriaSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.Authorizer;

/**
 * A builder which builds a {@link SamlServiceProvider}.
 */
public final class SamlServiceProviderBuilder {
    private static final Logger logger = LoggerFactory.getLogger(SamlServiceProviderBuilder.class);

    private final List<SamlIdentityProviderConfigBuilder> idpConfigBuilders = new ArrayList<>();
    private final List<SamlAssertionConsumerConfigBuilder> acsConfigBuilders = new ArrayList<>();

    private final List<SamlEndpoint> sloEndpoints = new ArrayList<>();

    private final SamlPortConfigBuilder hostConfigBuilder = new SamlPortConfigBuilder();

    @Nullable
    private String entityId;
    @Nullable
    private String hostname;
    @Nullable
    private Authorizer<HttpRequest> authorizer;

    @Nullable
    private CredentialResolverAdapter credentialResolver;
    private String signingKey = "signing";
    private String encryptionKey = "encryption";

    private String signatureAlgorithm = SignatureConstants.ALGO_ID_SIGNATURE_DSA;

    private String metadataPath = "/saml/metadata";

    @Nullable
    private SamlIdentityProviderConfigSelector idpConfigSelector;

    @Nullable
    private SamlRequestIdManager requestIdManager;

    private SamlSingleSignOnHandler ssoHandler = new SamlSingleSignOnHandler() {
        @Override
        public CompletionStage<Void> beforeInitiatingSso(ServiceRequestContext ctx, HttpRequest req,
                                                         MessageContext<AuthnRequest> message,
                                                         SamlIdentityProviderConfig idpConfig) {
            final String requestedPath = req.path();
            if (requestedPath.length() <= 80) {
                // Relay the requested path by default.
                final SAMLBindingContext sub = message.getSubcontext(SAMLBindingContext.class, true);
                assert sub != null : "SAMLBindingContext";
                sub.setRelayState(requestedPath);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public HttpResponse loginSucceeded(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                           MessageContext<Response> message, @Nullable String sessionIndex,
                                           @Nullable String relayState) {
            return responseWithLocation(firstNonNull(relayState, "/"));
        }

        @Override
        public HttpResponse loginFailed(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                        @Nullable MessageContext<Response> message, Throwable cause) {
            logger.warn("{} SAML SSO failed", ctx, cause);
            return responseWithLocation("/error");
        }
    };

    private SamlSingleLogoutHandler sloHandler = new SamlSingleLogoutHandler() {
        @Override
        public CompletionStage<Void> logoutSucceeded(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                                     MessageContext<LogoutRequest> message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> logoutFailed(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                                  Throwable cause) {
            logger.warn("{} SAML SLO failed", ctx, cause);
            return CompletableFuture.completedFuture(null);
        }
    };

    SamlServiceProviderBuilder() {}

    /**
     * Set an {@link Authorizer} which is used for this service provider's authentication.
     */
    public SamlServiceProviderBuilder authorizer(Authorizer<HttpRequest> authorizer) {
        this.authorizer = requireNonNull(authorizer, "authorizer");
        return this;
    }

    /**
     * Sets an entity ID for this service provider.
     */
    public SamlServiceProviderBuilder entityId(String entityId) {
        this.entityId = requireNonNull(entityId, "entityId");
        return this;
    }

    /**
     * Sets a {@link CredentialResolver} for this service provider.
     */
    public SamlServiceProviderBuilder credentialResolver(CredentialResolver credentialResolver) {
        this.credentialResolver =
                new CredentialResolverAdapter(requireNonNull(credentialResolver, "credentialResolver"));
        return this;
    }

    /**
     * Sets a {@code signing} key name for this service provider.
     */
    public SamlServiceProviderBuilder signingKey(String signingKey) {
        this.signingKey = requireNonNull(signingKey, "signingKey");
        return this;
    }

    /**
     * Sets an {@code encryption} key name for this service provider.
     */
    public SamlServiceProviderBuilder encryptionKey(String encryptionKey) {
        this.encryptionKey = requireNonNull(encryptionKey, "encryptionKey");
        return this;
    }

    /**
     * Sets a signature algorithm which is used for signing by this service provider.
     */
    public SamlServiceProviderBuilder signatureAlgorithm(String signatureAlgorithm) {
        this.signatureAlgorithm = requireNonNull(signatureAlgorithm, "signatureAlgorithm");
        return this;
    }

    /**
     * Sets a hostname of this service provider.
     */
    public SamlServiceProviderBuilder hostname(String hostname) {
        this.hostname = requireNonNull(hostname, "hostname");
        return this;
    }

    /**
     * Sets a protocol scheme of this service provider.
     */
    public SamlServiceProviderBuilder scheme(SessionProtocol scheme) {
        hostConfigBuilder.setSchemeIfAbsent(requireNonNull(scheme, "scheme"));
        return this;
    }

    /**
     * Sets a port of this service provider.
     */
    public SamlServiceProviderBuilder port(int port) {
        hostConfigBuilder.setPortIfAbsent(port);
        return this;
    }

    /**
     * Sets a {@link ServerPort} of this service provider.
     */
    public SamlServiceProviderBuilder schemeAndPort(ServerPort serverPort) {
        hostConfigBuilder.setSchemeAndPortIfAbsent(requireNonNull(serverPort, "serverPort"));
        return this;
    }

    /**
     * Sets a URL for retrieving a metadata of this service provider.
     */
    public SamlServiceProviderBuilder metadataPath(String metadataPath) {
        this.metadataPath = requireNonNull(metadataPath, "metadataPath");
        return this;
    }

    /**
     * Sets a {@link SamlIdentityProviderConfigSelector} which determines a suitable idp for a request.
     */
    public SamlServiceProviderBuilder idpConfigSelector(
            SamlIdentityProviderConfigSelector idpConfigSelector) {
        this.idpConfigSelector = requireNonNull(idpConfigSelector, "idpConfigSelector");
        return this;
    }

    /**
     * Adds a new single logout service endpoint of this service provider.
     */
    public SamlServiceProviderBuilder sloEndpoint(SamlEndpoint sloEndpoint) {
        sloEndpoints.add(requireNonNull(sloEndpoint, "sloEndpoint"));
        return this;
    }

    /**
     * Sets a {@link SamlRequestIdManager} which creates and validates a SAML request ID.
     */
    public SamlServiceProviderBuilder requestIdManager(SamlRequestIdManager requestIdManager) {
        this.requestIdManager = requireNonNull(requestIdManager, "requestIdManager");
        return this;
    }

    /**
     * Sets a {@link SamlSingleSignOnHandler} which handles SAML messages for a single sign-on.
     */
    public SamlServiceProviderBuilder ssoHandler(SamlSingleSignOnHandler ssoHandler) {
        this.ssoHandler = requireNonNull(ssoHandler, "ssoHandler");
        return this;
    }

    /**
     * Sets a {@link SamlSingleLogoutHandler} which handles SAML messages for a single sign-on.
     */
    public SamlServiceProviderBuilder sloHandler(SamlSingleLogoutHandler sloHandler) {
        this.sloHandler = requireNonNull(sloHandler, "sloHandler");
        return this;
    }

    /**
     * Returns a {@link SamlIdentityProviderConfigBuilder} to configure a new idp for authentication.
     */
    public SamlIdentityProviderConfigBuilder idp() {
        final SamlIdentityProviderConfigBuilder config = new SamlIdentityProviderConfigBuilder(this);
        idpConfigBuilders.add(config);
        return config;
    }

    /**
     * Returns a {@link SamlAssertionConsumerConfigBuilder} to configure a new assertion consumer service
     * of this service provider.
     *
     * @deprecated Use {@link #acs(SamlEndpoint)}.
     */
    @Deprecated
    public SamlAssertionConsumerConfigBuilder acs() {
        final SamlAssertionConsumerConfigBuilder config = new SamlAssertionConsumerConfigBuilder(this);
        acsConfigBuilders.add(config);
        return config;
    }

    /**
     * Returns a {@link SamlAssertionConsumerConfigBuilder} to configure a new assertion consumer service
     * of this service provider.
     */
    public SamlAssertionConsumerConfigBuilder acs(SamlEndpoint endpoint) {
        final SamlAssertionConsumerConfigBuilder config =
                new SamlAssertionConsumerConfigBuilder(this, requireNonNull(endpoint, "endpoint"));
        acsConfigBuilders.add(config);
        return config;
    }

    /**
     * Builds a {@link SamlServiceProvider} which helps a {@link Server} have a SAML-based
     * authentication.
     */
    public SamlServiceProvider build() {

        // Must ensure that OpenSAML is initialized before building a SAML service provider.
        SamlInitializer.ensureAvailability();

        if (entityId == null) {
            throw new IllegalStateException("entity ID is not specified");
        }
        if (credentialResolver == null) {
            throw new IllegalStateException(CredentialResolver.class.getSimpleName() + " is not specified");
        }
        if (authorizer == null) {
            throw new IllegalStateException(Authorizer.class.getSimpleName() + " is not specified");
        }

        final Credential signingCredential = credentialResolver.apply(signingKey);
        if (signingCredential == null) {
            throw new IllegalStateException("cannot resolve a " + Credential.class.getSimpleName() +
                                            " for signing: " + signingKey);
        }
        final Credential encryptionCredential = credentialResolver.apply(encryptionKey);
        if (encryptionCredential == null) {
            throw new IllegalStateException("cannot resolve a " + Credential.class.getSimpleName() +
                                            " for encryption: " + encryptionKey);
        }
        validateSignatureAlgorithm(signatureAlgorithm, signingCredential);
        validateSignatureAlgorithm(signatureAlgorithm, encryptionCredential);

        // Initialize single logout service configurations.
        final List<SamlEndpoint> sloEndpoints;
        if (this.sloEndpoints.isEmpty()) {
            // Add two endpoints by default if there's no SLO endpoint specified by a user.
            sloEndpoints = ImmutableList.of(ofHttpPost("/saml/slo/post"),
                                            ofHttpRedirect("/saml/slo/redirect"));
        } else {
            sloEndpoints = ImmutableList.copyOf(this.sloEndpoints);
        }

        // Initialize assertion consumer service configurations.
        final List<SamlAssertionConsumerConfig> assertionConsumerConfigs;
        if (acsConfigBuilders.isEmpty()) {
            // Add two endpoints by default if there's no ACS endpoint specified by a user.
            assertionConsumerConfigs =
                    ImmutableList.of(new SamlAssertionConsumerConfigBuilder(this, ofHttpPost("/saml/acs/post"))
                                             .asDefault().build(),
                                     new SamlAssertionConsumerConfigBuilder(
                                             this, ofHttpRedirect("/saml/acs/redirect")).build());
        } else {
            // If there is only one ACS, it will be automatically a default ACS.
            if (acsConfigBuilders.size() == 1) {
                acsConfigBuilders.get(0).asDefault();
            }

            assertionConsumerConfigs = acsConfigBuilders.stream()
                                                        .map(SamlAssertionConsumerConfigBuilder::build)
                                                        .collect(toImmutableList());
        }

        // Collect assertion consumer service endpoints for checking duplication and existence.
        final Set<SamlEndpoint> acsEndpoints =
                assertionConsumerConfigs.stream().map(SamlAssertionConsumerConfig::endpoint)
                                        .collect(toImmutableSet());
        if (acsEndpoints.size() != assertionConsumerConfigs.size()) {
            throw new IllegalStateException("duplicated access consumer services exist");
        }

        // Initialize identity provider configurations.
        if (idpConfigBuilders.isEmpty()) {
            throw new IllegalStateException("no identity provider configuration is specified");
        }
        // If there is only one IdP, it will be automatically a default IdP.
        if (idpConfigBuilders.size() == 1) {
            idpConfigBuilders.get(0).asDefault();
        }

        final ImmutableMap.Builder<String, SamlIdentityProviderConfig> idpConfigs = ImmutableMap.builder();
        SamlIdentityProviderConfig defaultIdpConfig = null;
        for (final SamlIdentityProviderConfigBuilder builder : idpConfigBuilders) {
            if (builder.acsEndpoint() != null && !acsEndpoints.contains(builder.acsEndpoint())) {
                throw new IllegalStateException("unspecified access consumer service at " +
                                                builder.acsEndpoint());
            }

            final SamlIdentityProviderConfig config = builder.build(credentialResolver);

            validateSignatureAlgorithm(signatureAlgorithm, config.signingCredential());
            validateSignatureAlgorithm(signatureAlgorithm, config.encryptionCredential());

            idpConfigs.put(config.entityId(), config);

            if (builder.isDefault()) {
                if (defaultIdpConfig != null) {
                    throw new IllegalStateException("there has to be only one default identity provider");
                }
                defaultIdpConfig = config;
            }
        }

        if (idpConfigSelector == null) {
            if (defaultIdpConfig == null) {
                throw new IllegalStateException("default identity provider does not exist");
            }

            // Configure a default identity provider selector which always returns a default identity provider.
            final SamlIdentityProviderConfig defaultConfig = defaultIdpConfig;
            idpConfigSelector =
                    (unused1, unused2, unused3) -> CompletableFuture.completedFuture(defaultConfig);
        }

        // entityID would be used as a secret by default.
        try {
            requestIdManager = firstNonNull(requestIdManager,
                                            SamlRequestIdManager.ofJwt(entityId, entityId, 60, 5));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("cannot create a " + SamlRequestIdManager.class.getSimpleName(),
                                            e);
        }

        return new SamlServiceProvider(authorizer,
                                       entityId,
                                       hostname,
                                       signingCredential,
                                       encryptionCredential,
                                       signatureAlgorithm,
                                       hostConfigBuilder.toAutoFiller(),
                                       metadataPath,
                                       idpConfigs.build(),
                                       defaultIdpConfig,
                                       idpConfigSelector,
                                       assertionConsumerConfigs,
                                       sloEndpoints,
                                       requestIdManager,
                                       ssoHandler,
                                       sloHandler);
    }

    private static void validateSignatureAlgorithm(String signatureAlgorithm, Credential credential) {
        final String jcaAlgorithmID = AlgorithmSupport.getAlgorithmID(signatureAlgorithm);
        if (jcaAlgorithmID == null) {
            throw new IllegalStateException("unsupported signature algorithm: " + signatureAlgorithm);
        }
        try {
            final Signature signature = Signature.getInstance(jcaAlgorithmID);
            final PrivateKey key = credential.getPrivateKey();
            if (key != null) {
                signature.initSign(key);
            } else {
                signature.initVerify(credential.getPublicKey());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("unsupported signature algorithm: " + signatureAlgorithm, e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("failed to initialize a signature with an algorithm: " +
                                            signatureAlgorithm, e);
        }
    }

    /**
     * An adapter for {@link CredentialResolver} which helps to resolve a {@link Credential} from
     * the specified {@code keyName}.
     */
    static class CredentialResolverAdapter implements Function<String, Credential> {
        private final CredentialResolver resolver;

        CredentialResolverAdapter(CredentialResolver resolver) {
            this.resolver = requireNonNull(resolver, "resolver");
        }

        @Nullable
        @Override
        public Credential apply(String keyName) {
            final CriteriaSet cs = new CriteriaSet();
            cs.add(new EntityIdCriterion(keyName));
            try {
                return resolver.resolveSingle(cs);
            } catch (Throwable cause) {
                return Exceptions.throwUnsafely(cause);
            }
        }
    }
}
