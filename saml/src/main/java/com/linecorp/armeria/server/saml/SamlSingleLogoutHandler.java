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
import org.opensaml.saml.saml2.core.LogoutRequest;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A callback which is invoked when a SAML single logout request is received.
 *
 * @see SamlServiceProviderBuilder#sloHandler(SamlSingleLogoutHandler)
 */
public interface SamlSingleLogoutHandler {
    /**
     * Invoked when the single logout request is succeeded. It can do the local logout using session indexes
     * containing in the {@link LogoutRequest}.
     *
     * @param ctx the {@link ServiceRequestContext} of {@code req}
     * @param req the {@link AggregatedHttpMessage} being handled
     * @param message the {@link MessageContext} of the {@link LogoutRequest} received from the identity
     *                provider.
     */
    CompletionStage<Void> logoutSucceeded(ServiceRequestContext ctx, AggregatedHttpMessage req,
                                          MessageContext<LogoutRequest> message);

    /**
     * Invoked when the single logout request is failed.
     *
     * @param ctx the {@link ServiceRequestContext} of {@code req}
     * @param req the {@link AggregatedHttpMessage} being handled
     * @param cause the reason of the failure
     */
    CompletionStage<Void> logoutFailed(ServiceRequestContext ctx, AggregatedHttpMessage req,
                                       Throwable cause);
}
