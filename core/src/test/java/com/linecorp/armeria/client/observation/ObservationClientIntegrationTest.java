/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client.observation;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.observation.MicrometerObservationRegistryUtils;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.propagation.CurrentTraceContext;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.test.http.ITHttpAsyncClient;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.Protocol;

abstract class ObservationClientIntegrationTest extends ITHttpAsyncClient<WebClient> {

    private final List<Protocol> protocols;
    private final SessionProtocol sessionProtocol;

    private ObservationRegistry observationRegistry;

    private DefaultHttpClientObservationConvention clientObservationConvention;

    ObservationClientIntegrationTest(SessionProtocol sessionProtocol) {
        this.sessionProtocol = sessionProtocol;

        if (sessionProtocol == SessionProtocol.H2C) {
            protocols = ImmutableList.of(Protocol.H2_PRIOR_KNOWLEDGE);
        } else {
            protocols = ImmutableList.of(Protocol.HTTP_1_1);
        }
    }

    @BeforeEach
    @Override
    public void setup() throws IOException {
        clientObservationConvention = DefaultHttpClientObservationConvention.INSTANCE;
        server.setProtocols(protocols);
        super.setup();
    }

    @Override
    protected CurrentTraceContext.Builder currentTraceContextBuilder() {
        return ThreadLocalCurrentTraceContext.newBuilder();
    }

    @Override
    protected WebClient newClient(int port) {
        return WebClient.builder(sessionProtocol.uriText() + "://127.0.0.1:" + port)
                        .decorator(ObservationClient.newDecorator(
                                observationRegistry(), clientObservationConvention))
                        .build();
    }

    private ObservationRegistry observationRegistry() {
        observationRegistry = MicrometerObservationRegistryUtils.observationRegistry(httpTracing);
        return this.observationRegistry;
    }

    @Test
    @Override
    public void callbackContextIsFromInvocationTime_root() {
        try (SafeCloseable ignored = serverContext().push()) {
            super.callbackContextIsFromInvocationTime_root();
        }
    }

    @Test
    @Override
    public void addsStatusCodeWhenNotOk_async() {
        try (SafeCloseable ignored = serverContext().push()) {
            super.addsStatusCodeWhenNotOk_async();
        }
    }

    @Test
    @Override
    public void usesParentFromInvocationTime() {
        try (SafeCloseable ignored = serverContext().push()) {
            super.usesParentFromInvocationTime();
        }
    }

    @Test
    @Override
    @Disabled("TODO: maybe integrate with brave's clock")
    public void clientTimestampAndDurationEnclosedByParent() {
    }

    @Override
    @Test
    public void supportsPortableCustomization() throws IOException {
        clientObservationConvention = new DefaultHttpClientObservationConvention() {

            @Override
            public KeyValues getHighCardinalityKeyValues(ClientObservationContext context) {
                context.setRemoteServiceName("remote-service"); // TODO: As a side effect
                KeyValues values =
                        super.getHighCardinalityKeyValues(
                                context);
                values = values.and(
                        KeyValues.of("http.url",
                                     context.requestContext().uri().toString(),
                                     "request_customizer.is_span", "false"));
                if (context.getResponse() != null) {
                    values = values.and("response_customizer.is_span", "false");
                }
                return values;
            }

            @Override
            public String getContextualName(ClientObservationContext context) {
                return context.httpRequest().method()
                              .toString().toLowerCase() + " " +
                       context.httpRequest().path()
                              .substring(0, context.httpRequest().path().indexOf("?"));
            }
        };
        super.supportsPortableCustomization();
    }

    @Test
    @Override
    @Disabled("TODO: somehow propagate the parent context to the client callback")
    public void callbackContextIsFromInvocationTime() {
        // TODO(trustin): Can't make this pass because span is updated *after* we invoke the callback
        //                ITHttpAsyncClient gave us.
    }

    @Override
    @Disabled("We're not using HttpTracing at all")
    public void customSampler() {
    }

    @Test
    @Override
    public void redirect() {
        Assumptions.abort("Armeria does not support client redirect.");
    }

    @Override
    @Test
    @Disabled
    public void reportsServerAddress() throws IOException {
        super.reportsServerAddress();
    }

    @Override
    protected void closeClient(WebClient client) {
    }

    @Override
    protected void get(WebClient client, String pathIncludingQuery) {
        client.blocking().get(pathIncludingQuery);
    }

    @Override
    protected void get(WebClient client, String path, BiConsumer<Integer, Throwable> callback) {
        final HttpResponse res = client.get(path);
        // Use 'handleAsync' to make sure a callback
        // is invoked without the current trace context
        res.aggregate().handleAsync((response, cause) -> {
            if (cause == null) {
                callback.accept(response.status().code(), null);
            } else {
                callback.accept(null, cause);
            }
            return null;
        });
    }

    @Override
    protected void post(WebClient client, String pathIncludingQuery, String body) {
        client.blocking().post(pathIncludingQuery, body);
    }

    @Override
    protected void options(WebClient client, String path) {
        client.blocking().options(path);
    }

    static ServiceRequestContext serverContext() {
        return ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
