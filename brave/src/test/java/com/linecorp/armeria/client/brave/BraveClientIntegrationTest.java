/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.brave;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.NotNull;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.http.HttpTracing;
import brave.propagation.StrictScopeDecorator;
import brave.sampler.Sampler;
import brave.test.http.ITHttpAsyncClient;
import okhttp3.Protocol;

@RunWith(Parameterized.class)
public class BraveClientIntegrationTest extends ITHttpAsyncClient<WebClient> {

    @Parameters
    public static List<SessionProtocol> sessionProtocols() {
        return ImmutableList.of(SessionProtocol.H1C, SessionProtocol.H2C);
    }

    private final List<Protocol> protocols;
    private final SessionProtocol sessionProtocol;
    private final StrictScopeDecorator strictScopeDecorator = StrictScopeDecorator.create();

    public BraveClientIntegrationTest(SessionProtocol sessionProtocol) {
        this.currentTraceContext = RequestContextCurrentTraceContext.builder()
            .addScopeDecorator(strictScopeDecorator)
            .build();
        this.tracing = tracingBuilder(Sampler.ALWAYS_SAMPLE).build();
        this.httpTracing = HttpTracing.create(tracing);

        this.sessionProtocol = sessionProtocol;
        if (sessionProtocol == SessionProtocol.H2C) {
            protocols = ImmutableList.of(Protocol.H2_PRIOR_KNOWLEDGE);
        } else {
            protocols = ImmutableList.of(Protocol.HTTP_1_1, Protocol.HTTP_2);
        }
    }

    @Override
    protected void checkForLeakedScopes() {
        strictScopeDecorator.close();
    }

    @Before
    @Override
    public void setup() throws IOException {
        server.setProtocols(protocols);
        super.setup();
    }

    @Override
    protected WebClient newClient(int port) {
        return WebClient.builder(sessionProtocol.uriText() + "://127.0.0.1:" + port)
                        .decorator(BraveClient.newDecorator(httpTracing))
                        .build();
    }

    @Test
    @Override
    public void callbackContextIsFromInvocationTime_root() {
        try (SafeCloseable context = pushServerContext()) {
            super.callbackContextIsFromInvocationTime_root();
        }
    }

    @Test
    @Override
    public void addsStatusCodeWhenNotOk_async() {
        try (SafeCloseable context = pushServerContext()) {
            super.addsStatusCodeWhenNotOk_async();
        }
    }

    @Test
    @Override
    public void usesParentFromInvocationTime() {
        try (SafeCloseable context = pushServerContext()) {
            super.usesParentFromInvocationTime();
        }
    }

    @Test
    @Override
    @Ignore("TODO: maybe integrate with brave's clock")
    public void clientTimestampAndDurationEnclosedByParent() {

    }

    @Test
    @Override
    @Ignore("TODO: somehow propagate the parent context to the client callback")
    public void callbackContextIsFromInvocationTime() {
        // TODO(trustin): Can't make this pass because span is updated *after* we invoke the callback
        //                ITHttpAsyncClient gave us.
    }

    @Test
    @Override
    public void redirect() {
        throw new AssumptionViolatedException("Armeria does not support client redirect.");
    }

    @Override
    protected void closeClient(WebClient client) {
    }

    @Override
    protected void get(WebClient client, String pathIncludingQuery) {
        client.get(pathIncludingQuery).aggregate().join();
    }

    @Override
    protected void post(WebClient client, String pathIncludingQuery, String body) {
        client.post(pathIncludingQuery, body).aggregate().join();
    }

    @Override
    protected void get(WebClient client, String path, BiConsumer<Integer, Throwable> callback) {
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final HttpResponse res = client.get(path);
            final ClientRequestContext ctx = ctxCaptor.get();
            res.aggregate().handle((response, cause) -> {
                try (SafeCloseable ignored = ctx.push()) {
                    if (cause == null) {
                        callback.accept(response.status().code(), null);
                    } else {
                        callback.accept(null, cause);
                    }
                }
                return null;
            });
        }
    }

    /**
     * Try/resources instead of using a lambda, as lambda failures hide the actual failure.
     *
     * <p>Note: this could probably be rewritten as a test rule..
     */
    @NotNull static SafeCloseable pushServerContext() {
        return ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/")).push();
    }
}
