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
import static com.linecorp.armeria.server.saml.HttpPostBindingUtil.getSsoForm;
import static com.linecorp.armeria.server.saml.HttpPostBindingUtil.toSignedBase64;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.responseWithLocation;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.toRedirectionUrl;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SAML_REQUEST;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.build;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;

import org.joda.time.DateTime;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnContextComparisonTypeEnumeration;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.saml.saml2.core.RequestedAuthnContext;
import org.opensaml.security.credential.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.auth.Authorizer;

/**
 * A decorator which initiates an authentication request to the remote identity provider if the request is
 * not authenticated.
 */
final class SamlDecorator extends SimpleDecoratingHttpService {
    private static final Logger logger = LoggerFactory.getLogger(SamlDecorator.class);

    private final SamlServiceProvider sp;
    private final SamlPortConfigAutoFiller portConfigHolder;

    private final String myEntityId;
    private final Credential signingCredential;
    private final Authorizer<HttpRequest> authorizer;

    private final SamlRequestIdManager requestIdManager;
    private final SamlSingleSignOnHandler ssoHandler;

    @Nullable
    private Server server;

    SamlDecorator(SamlServiceProvider sp, HttpService delegate) {
        super(delegate);
        this.sp = sp;
        portConfigHolder = sp.portConfigAutoFiller();

        myEntityId = sp.entityId();
        signingCredential = sp.signingCredential();
        authorizer = sp.authorizer();
        ssoHandler = sp.ssoHandler();
        requestIdManager = sp.requestIdManager();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);

        if (server != null) {
            if (server != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        server = cfg.server();

        // Auto-detect the primary port number and its session protocol after the server started.
        server.addListener(portConfigHolder);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(authorizer.authorize(ctx, req).handle((result, cause) -> {
            if (cause == null && result) {
                // Already authenticated.
                try {
                    return unwrap().serve(ctx, req);
                } catch (Exception e) {
                    return Exceptions.throwUnsafely(e);
                }
            }

            final CompletionStage<SamlIdentityProviderConfig> f;
            if (portConfigHolder.isDone()) {
                f = sp.idpConfigSelector().select(sp, ctx, req);
            } else {
                f = portConfigHolder.future().thenCompose(
                        unused -> sp.idpConfigSelector().select(sp, ctx, req));
            }
            // Find an identity provider first where the request is to be sent to.
            return HttpResponse.from(f.thenApply(idp -> {
                if (idp == null) {
                    throw new RuntimeException("cannot find a suitable identity provider from configurations");
                }
                final String defaultHostname =
                        firstNonNull(sp.hostname(), ctx.config().virtualHost().defaultHostname());
                final AuthnRequest request = createAuthRequest(idp, defaultHostname);
                final MessageContext<AuthnRequest> messageContext = new MessageContext<>();
                messageContext.setMessage(request);
                return new MessageContextAndIdpConfig(messageContext, idp);
            }).thenCompose(arg -> {
                return ssoHandler.beforeInitiatingSso(ctx, req, arg.messageContext, arg.idpConfig)
                                 .thenApply(unused -> arg);
            }).thenApply(arg -> {
                final SAMLBindingContext bindingContext =
                        arg.messageContext.getSubcontext(SAMLBindingContext.class);
                final String relayState = bindingContext != null ? bindingContext.getRelayState() : null;

                // Support HTTP Redirect and HTTP POST binding protocols when sending
                // an authentication request.
                final SamlEndpoint endpoint = arg.idpConfig.ssoEndpoint();
                try {
                    if (endpoint.bindingProtocol() == SamlBindingProtocol.HTTP_REDIRECT) {
                        return responseWithLocation(toRedirectionUrl(
                                arg.messageContext.getMessage(),
                                endpoint.toUriString(), SAML_REQUEST,
                                signingCredential, sp.signatureAlgorithm(),
                                relayState));
                    } else {
                        final String value = toSignedBase64(
                                arg.messageContext.getMessage(),
                                signingCredential,
                                sp.signatureAlgorithm());
                        final HttpData body = getSsoForm(endpoint.toUriString(),
                                                         SAML_REQUEST, value,
                                                         relayState);
                        return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8,
                                               body);
                    }
                } catch (SamlException e) {
                    return fail(ctx, e);
                }
            }).exceptionally(e -> fail(ctx, e)));
        }));
    }

    /**
     * Returns an {@link HttpResponse} for SAML authentication failure.
     */
    private static HttpResponse fail(ServiceRequestContext ctx, Throwable cause) {
        logger.trace("{} Cannot initiate SAML authentication", ctx, cause);
        return HttpResponse.of(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Returns an {@link AuthnRequest} which is mapped to the specified identity provider.
     */
    private AuthnRequest createAuthRequest(SamlIdentityProviderConfig idp, String defaultHostname) {
        requireNonNull(idp, "idp");

        final AuthnRequest authnRequest = build(AuthnRequest.DEFAULT_ELEMENT_NAME);

        final Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(myEntityId);
        authnRequest.setIssuer(issuer);

        authnRequest.setIssueInstant(DateTime.now());
        authnRequest.setDestination(idp.ssoEndpoint().toUriString());
        authnRequest.setID(requestIdManager.newId());

        // The ProtocolBinding attribute is mutually exclusive with the AssertionConsumerServiceIndex attribute
        // and is typically accompanied by the AssertionConsumerServiceURL attribute.
        final SamlPortConfig portConfig = portConfigHolder.config();
        final SamlEndpoint acsEndpoint = idp.acsEndpoint() != null ? idp.acsEndpoint()
                                                                   : sp.defaultAcsConfig().endpoint();
        authnRequest.setAssertionConsumerServiceURL(acsEndpoint.toUriString(portConfig.scheme().uriText(),
                                                                            defaultHostname,
                                                                            portConfig.port()));
        authnRequest.setProtocolBinding(acsEndpoint.bindingProtocol().urn());

        final SamlNameIdPolicy policy = idp.nameIdPolicy();
        final NameIDPolicy nameIdPolicy = build(NameIDPolicy.DEFAULT_ELEMENT_NAME);
        nameIdPolicy.setFormat(policy.format().urn());
        nameIdPolicy.setAllowCreate(policy.isCreatable());
        authnRequest.setNameIDPolicy(nameIdPolicy);

        final AuthnContextClassRef passwordAuthnCtxRef = build(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
        passwordAuthnCtxRef.setAuthnContextClassRef(AuthnContext.PASSWORD_AUTHN_CTX);

        final RequestedAuthnContext requestedAuthnContext = build(RequestedAuthnContext.DEFAULT_ELEMENT_NAME);
        requestedAuthnContext.setComparison(AuthnContextComparisonTypeEnumeration.EXACT);
        requestedAuthnContext.getAuthnContextClassRefs().add(passwordAuthnCtxRef);

        authnRequest.setRequestedAuthnContext(requestedAuthnContext);

        return authnRequest;
    }

    /**
     * An immutable object holder for {@link MessageContext} and {@link SamlIdentityProviderConfig}.
     */
    private static final class MessageContextAndIdpConfig {
        private final MessageContext<AuthnRequest> messageContext;
        private final SamlIdentityProviderConfig idpConfig;

        private MessageContextAndIdpConfig(MessageContext<AuthnRequest> messageContext,
                                           SamlIdentityProviderConfig idpConfig) {
            this.messageContext = messageContext;
            this.idpConfig = idpConfig;
        }
    }
}
