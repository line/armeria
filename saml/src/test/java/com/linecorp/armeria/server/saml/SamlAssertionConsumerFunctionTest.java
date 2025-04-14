/*
 * Copyright 2024 LINE Corporation
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.saml2.core.Response;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServiceRequestContext;

class SamlAssertionConsumerFunctionTest {

    @Test
    void testServeWithNullMessageContext() {
        // given
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final AggregatedHttpRequest req = mock(AggregatedHttpRequest.class);
        when(req.path()).thenReturn("/");

        final SamlAssertionConsumerConfig consumerConfig = new SamlAssertionConsumerConfigBuilder(
                new SamlServiceProviderBuilder(), SamlEndpoint.ofHttpPost("https://example.com")).build();
        final SamlSingleSignOnHandler ssoHandler = mock(SamlSingleSignOnHandler.class);
        final SamlAssertionConsumerFunction underTest = new SamlAssertionConsumerFunction(
                consumerConfig, "entityId", mock(Map.class), null,
                mock(SamlRequestIdManager.class), ssoHandler, false);

        final SamlPortConfig portConfig = new SamlPortConfigBuilder().toAutoFiller().config();

        // when
        underTest.serve(ctx, req, "example.com", portConfig);

        // then
        verify(ssoHandler).loginFailed(eq(ctx), eq(req), isNull(), any(InvalidSamlRequestException.class));
    }

    @Test
    void testServeWithNonNullMessageContext() {
        // given
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final AggregatedHttpRequest req = mock(AggregatedHttpRequest.class);

        final SamlAssertionConsumerConfig consumerConfig = new SamlAssertionConsumerConfigBuilder(
                new SamlServiceProviderBuilder(), SamlEndpoint.ofHttpPost("https://example.com")).build();
        final SamlSingleSignOnHandler ssoHandler = mock(SamlSingleSignOnHandler.class);
        final SamlRequestIdManager requestIdManager = mock(SamlRequestIdManager.class);
        final Map<String, SamlIdentityProviderConfig> idpConfigs = mock(Map.class);

        final SamlAssertionConsumerFunction underTest = new SamlAssertionConsumerFunction(
                consumerConfig, "entityId", idpConfigs, null, requestIdManager, ssoHandler, false);
        final SamlPortConfig portConfig = new SamlPortConfig(SessionProtocol.HTTPS, 9999);

        final MessageContext<Response> messageContext = mock(MessageContext.class);
        final Response response = mock(Response.class);
        when(messageContext.getMessage()).thenReturn(response);
        when(response.getStatus()).thenThrow(new SamlException("test exception"));

        try (MockedStatic<HttpPostBindingUtil> mockedHttpPostBindingUtil
                     = mockStatic(HttpPostBindingUtil.class)) {
            mockedHttpPostBindingUtil.when(
                    () -> HttpPostBindingUtil.toSamlObject(
                            any(AggregatedHttpRequest.class), eq("SAMLResponse"))).thenReturn(messageContext);

            // when
            underTest.serve(ctx, req, "example.com", portConfig);

            // then
            verify(ssoHandler).loginFailed(eq(ctx), eq(req), eq(messageContext), any(SamlException.class));
        }
    }
}
