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

import static com.linecorp.armeria.server.saml.HttpPostBindingUtil.getSsoForm;
import static com.linecorp.armeria.server.saml.HttpPostBindingUtil.toSignedBase64;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.responseWithLocation;
import static com.linecorp.armeria.server.saml.HttpRedirectBindingUtil.toRedirectionUrl;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SAML_REQUEST;
import static com.linecorp.armeria.server.saml.SamlHttpParameterNames.SAML_RESPONSE;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.build;
import static com.linecorp.armeria.server.saml.SamlMessageUtil.validateSignature;

import java.util.Map;

import org.joda.time.DateTime;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.security.credential.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A service which receives a single logout request from an identity provider in order to perform a global
 * logout.
 */
final class SamlSingleLogoutFunction implements SamlServiceFunction {
    private static final Logger logger = LoggerFactory.getLogger(SamlSingleLogoutFunction.class);

    private final SamlEndpoint endpoint;
    private final String entityId;
    private final Credential signingCredential;
    private final String signatureAlgorithm;

    private final Map<String, SamlIdentityProviderConfig> idpConfigs;
    @Nullable
    private final SamlIdentityProviderConfig defaultIdpConfig;

    private final SamlRequestIdManager requestIdManager;
    private final SamlSingleLogoutHandler sloHandler;

    SamlSingleLogoutFunction(SamlEndpoint endpoint, String entityId,
                             Credential signingCredential,
                             String signatureAlgorithm,
                             Map<String, SamlIdentityProviderConfig> idpConfigs,
                             @Nullable SamlIdentityProviderConfig defaultIdpConfig,
                             SamlRequestIdManager requestIdManager,
                             SamlSingleLogoutHandler sloHandler) {
        this.endpoint = endpoint;
        this.entityId = entityId;
        this.signingCredential = signingCredential;
        this.signatureAlgorithm = signatureAlgorithm;
        this.idpConfigs = idpConfigs;
        this.defaultIdpConfig = defaultIdpConfig;
        this.requestIdManager = requestIdManager;
        this.sloHandler = sloHandler;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, AggregatedHttpRequest req,
                              String defaultHostname, SamlPortConfig portConfig) {
        try {
            final MessageContext<LogoutRequest> messageContext;
            if (endpoint.bindingProtocol() == SamlBindingProtocol.HTTP_REDIRECT) {
                messageContext = HttpRedirectBindingUtil.toSamlObject(req, SAML_REQUEST,
                                                                      idpConfigs, defaultIdpConfig);
            } else {
                messageContext = HttpPostBindingUtil.toSamlObject(req, SAML_REQUEST);
            }

            final String endpointUri = endpoint.toUriString(portConfig.scheme().uriText(),
                                                            defaultHostname, portConfig.port());
            final LogoutRequest logoutRequest = messageContext.getMessage();
            final SamlIdentityProviderConfig idp = validateAndGetIdPConfig(logoutRequest, endpointUri);

            if (endpoint.bindingProtocol() == SamlBindingProtocol.HTTP_POST) {
                validateSignature(idp.signingCredential(), logoutRequest);
            }

            final SamlEndpoint sloResEndpoint = idp.sloResEndpoint();
            if (sloResEndpoint == null) {
                // No response URL. Just return 200 OK.
                return HttpResponse.from(sloHandler.logoutSucceeded(ctx, req, messageContext)
                                                   .thenApply(unused -> HttpResponse.of(HttpStatus.OK)));
            }

            final LogoutResponse logoutResponse = createLogoutResponse(logoutRequest, StatusCode.SUCCESS);
            try {
                final HttpResponse response = respond(logoutResponse, sloResEndpoint);
                return HttpResponse.from(sloHandler.logoutSucceeded(ctx, req, messageContext)
                                                   .thenApply(unused -> response));
            } catch (SamlException e) {
                logger.warn("{} Cannot respond a logout response in response to {}",
                            ctx, logoutRequest.getID(), e);
                final HttpResponse response = fail(ctx, logoutRequest, sloResEndpoint);
                return HttpResponse.from(sloHandler.logoutFailed(ctx, req, e)
                                                   .thenApply(unused -> response));
            }
        } catch (SamlException e) {
            return fail(ctx, e);
        }
    }

    private static HttpResponse fail(ServiceRequestContext ctx, Throwable cause) {
        logger.warn("{} Cannot handle a logout request", ctx, cause);
        return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private HttpResponse fail(ServiceRequestContext ctx,
                              LogoutRequest logoutRequest,
                              SamlEndpoint sloResEndpoint) {
        // Try to send a LogoutResponse with the following status code. It's one of the top-level status code
        // which is defined in SAML 2.0 specifications.
        //
        // "urn:oasis:names:tc:SAML:2.0:status:Responder"
        // - The request could not be performed due to an error on the part of the SAML responder
        //   or SAML authority.
        final LogoutResponse failureResponse = createLogoutResponse(logoutRequest, StatusCode.RESPONDER);
        try {
            return respond(failureResponse, sloResEndpoint);
        } catch (SamlException e) {
            return fail(ctx, e);
        }
    }

    private HttpResponse respond(LogoutResponse logoutResponse, SamlEndpoint sloResEndpoint) {
        if (sloResEndpoint.bindingProtocol() == SamlBindingProtocol.HTTP_REDIRECT) {
            return responseWithLocation(toRedirectionUrl(
                    logoutResponse, sloResEndpoint.toUriString(), SAML_RESPONSE,
                    signingCredential, signatureAlgorithm, null));
        } else {
            final String value = toSignedBase64(logoutResponse, signingCredential,
                                                signatureAlgorithm);
            final HttpData body = getSsoForm(sloResEndpoint.toUriString(),
                                             SAML_RESPONSE, value, null);
            return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8, body);
        }
    }

    private SamlIdentityProviderConfig validateAndGetIdPConfig(LogoutRequest logoutRequest,
                                                               String endpointUri) {
        final String issuer = logoutRequest.getIssuer().getValue();
        if (issuer == null) {
            throw new InvalidSamlRequestException("no issuer found from the logout request: " +
                                                  logoutRequest.getID());
        }
        if (!endpointUri.equals(logoutRequest.getDestination())) {
            throw new InvalidSamlRequestException("unexpected destination: " + logoutRequest.getDestination());
        }
        final SamlIdentityProviderConfig config = idpConfigs.get(issuer);
        if (config == null) {
            throw new InvalidSamlRequestException("unexpected identity provider: " + issuer);
        }
        return config;
    }

    private LogoutResponse createLogoutResponse(LogoutRequest logoutRequest,
                                                String statusCode) {
        final StatusCode success = build(StatusCode.DEFAULT_ELEMENT_NAME);
        success.setValue(statusCode);

        final Status status = build(Status.DEFAULT_ELEMENT_NAME);
        status.setStatusCode(success);

        final Issuer me = build(Issuer.DEFAULT_ELEMENT_NAME);
        me.setValue(entityId);

        final LogoutResponse logoutResponse = build(LogoutResponse.DEFAULT_ELEMENT_NAME);
        logoutResponse.setIssuer(me);
        logoutResponse.setID(requestIdManager.newId());
        logoutResponse.setIssueInstant(DateTime.now());
        logoutResponse.setStatus(status);
        logoutResponse.setInResponseTo(logoutRequest.getID());

        return logoutResponse;
    }
}
