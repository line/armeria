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

import java.util.concurrent.CompletionStage;

import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Response;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Callbacks which are invoked to handle SAML messages exchanging with an identity provider.
 *
 * @see SamlServiceProviderBuilder#ssoHandler(SamlSingleSignOnHandler)
 */
public interface SamlSingleSignOnHandler {
    /**
     * Invoked before the service provider sends an authentication request to an identity provider.
     *
     * @param ctx the {@link ServiceRequestContext} of {@code req}
     * @param req the {@link Request} being handled
     * @param message the {@link MessageContext} of the {@link AuthnRequest} being sent to the identity
     *                provider
     * @param idpConfig the configuration of the identity provider that the request is sending to
     */
    default CompletionStage<Void> beforeInitiatingSso(ServiceRequestContext ctx, HttpRequest req,
                                                      MessageContext<AuthnRequest> message,
                                                      SamlIdentityProviderConfig idpConfig) {
        return UnmodifiableFuture.completedFuture(null);
    }

    /**
     * Invoked when the single sign-on is succeeded. It should return an {@link HttpResponse} which sends
     * to the client in response to the incoming {@code req}.
     *
     * @param ctx the {@link ServiceRequestContext} of {@code req}
     * @param req the {@link AggregatedHttpRequest} being handled
     * @param message the {@link MessageContext} of the {@link Response} received from the identity provider
     * @param sessionIndex the retrieved value from the {@link Response} message. {@code null} if it is omitted.
     * @param relayState the string which is sent with the {@link AuthnRequest} message and is returned
     *                   with the {@link Response} message. {@code null} if it is omitted.
     */
    @CheckReturnValue
    HttpResponse loginSucceeded(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                MessageContext<Response> message,
                                @Nullable String sessionIndex,
                                @Nullable String relayState);

    /**
     * Invoked when the single sign-on is failed. It should return an {@link HttpResponse} which sends
     * to the client in response to the incoming {@code req}. Sending an error HTML page is one of the
     * examples.
     *
     * @param ctx the {@link ServiceRequestContext} of {@code req}
     * @param req the {@link AggregatedHttpRequest} being handled
     * @param message the {@link MessageContext} of the {@link Response} received from the identity provider.
     *                {@code null} if the content of the {@code req} was failed to be parsed as a
     *                {@link Response} message.
     * @param cause the reason of the failure
     */
    @CheckReturnValue
    HttpResponse loginFailed(ServiceRequestContext ctx, AggregatedHttpRequest req,
                             @Nullable MessageContext<Response> message,
                             Throwable cause);
}
