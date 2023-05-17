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
package com.linecorp.armeria.micrometer.tracing.brave;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.junit.AfterClass;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.micrometer.tracing.client.TracingClient;
import com.linecorp.armeria.micrometer.tracing.common.ArmeriaCurrentTraceContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.http.HttpClientHandler;
import brave.test.http.ITHttpAsyncClient;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveHttpClientHandler;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import okhttp3.Protocol;

@RunWith(Parameterized.class)
public class BraveClientIntegrationTest extends ITHttpAsyncClient<WebClient> {

    @Parameters
    public static Collection<Object[]> params(){
        // Uses brave module's armeria CurrentTraceContext with bridge
        Function<brave.propagation.CurrentTraceContext, CurrentTraceContext> fn1 =
                BraveCurrentTraceContext::new;
        // Uses tracing module's pure Armeria CurrentTraceContext
        Function<brave.propagation.CurrentTraceContext, CurrentTraceContext> fn2 =
                ignored -> ArmeriaCurrentTraceContext.of();
        return Arrays.asList(new Object[][] {
                {SessionProtocol.H1C, fn1},
                {SessionProtocol.H2C, fn1},
                {SessionProtocol.H1C, fn2},
                {SessionProtocol.H2C, fn2},
        });
    }

    /**
     * OkHttp's MockWebServer does not support H2C with HTTP/1 upgrade request.
     */
    private static final ClientFactory clientFactoryWithoutUpgradeRequest =
            ClientFactory.builder().useHttp2Preface(true).build();

    @AfterClass
    public static void closeClientFactory() {
        clientFactoryWithoutUpgradeRequest.closeAsync();
    }

    private final List<Protocol> protocols;
    private final SessionProtocol sessionProtocol;
    private final Function<brave.propagation.CurrentTraceContext, CurrentTraceContext> contextFunction;

    public BraveClientIntegrationTest(
            SessionProtocol sessionProtocol,
            Function<brave.propagation.CurrentTraceContext, CurrentTraceContext> contextFunction) {
        this.sessionProtocol = sessionProtocol;

        if (sessionProtocol == SessionProtocol.H2C) {
            protocols = ImmutableList.of(Protocol.H2_PRIOR_KNOWLEDGE);
        } else {
            protocols = ImmutableList.of(Protocol.HTTP_1_1);
        }
        this.contextFunction = contextFunction;
    }

    @Before
    @Override
    public void setup() throws IOException {
        server.setProtocols(protocols);
        super.setup();
    }

    @Override
    protected brave.propagation.CurrentTraceContext.Builder currentTraceContextBuilder() {
        // we use brave module's context for leak detection support via scope decorators
        return com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext.builder();
    }

    @Override
    protected WebClient newClient(int port) {
        final BraveHttpClientHandler handler =
                new BraveHttpClientHandler(HttpClientHandler.create(httpTracing));
        final Tracer tracer = new BraveTracer(tracing.tracer(), contextFunction.apply(currentTraceContext));
        return WebClient.builder(sessionProtocol.uriText() + "://127.0.0.1:" + port)
                        .factory(clientFactoryWithoutUpgradeRequest)
                        .decorator(TracingClient.newDecorator(tracer, handler))
                        .build();
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
        client.blocking().get(pathIncludingQuery);
    }

    @Override
    protected void get(WebClient client, String path, BiConsumer<Integer, Throwable> callback) {
        final HttpResponse res = client.get(path);
        // Use 'handleAsync' to make sure a callback is invoked without the current trace context
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
