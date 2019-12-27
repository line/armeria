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

import java.util.List;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

import brave.Tracing.Builder;
import brave.propagation.StrictScopeDecorator;
import brave.sampler.Sampler;
import brave.test.http.ITHttpAsyncClient;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import okhttp3.Protocol;

@RunWith(Parameterized.class)
public class BraveClientIntegrationTest extends ITHttpAsyncClient<WebClient> {

    @Parameters
    public static List<SessionProtocol> sessionProtocols() {
        return ImmutableList.of(SessionProtocol.H1C, SessionProtocol.H2C);
    }

    private final List<Protocol> protocols;
    private final SessionProtocol sessionProtocol;

    public BraveClientIntegrationTest(SessionProtocol sessionProtocol) {
        this.sessionProtocol = sessionProtocol;
        if (sessionProtocol == SessionProtocol.H2C) {
            protocols = ImmutableList.of(Protocol.H2_PRIOR_KNOWLEDGE);
        } else {
            protocols = ImmutableList.of(Protocol.HTTP_1_1, Protocol.HTTP_2);
        }
    }

    @Before
    @Override
    public void setup() {
        currentTraceContext =
                RequestContextCurrentTraceContext.builder()
                                                 .addScopeDecorator(StrictScopeDecorator.create())
                                                 .build();
        server.setProtocols(protocols);
        super.setup();
    }

    @Override
    protected Builder tracingBuilder(Sampler sampler) {
        return super.tracingBuilder(sampler).currentTraceContext(currentTraceContext);
    }

    @Override
    protected WebClient newClient(int port) {
        return WebClient.of(sessionProtocol.uriText() + "://127.0.0.1:" + port,
                            ClientOptions.of(ClientOption.DECORATION.newValue(
                                    ClientDecoration.builder()
                                                    .add(BraveClient.newDecorator(httpTracing))
                                                    .build())));
    }

    @Override
    @Test
    public void makesChildOfCurrentSpan() throws Exception {
        new DummyRequestContext().makeContextAware(() -> {
            super.makesChildOfCurrentSpan();
            return null;
        }).call();
    }

    @Override
    @Test
    public void propagatesExtra_newTrace() throws Exception {
        new DummyRequestContext().makeContextAware(() -> {
            super.propagatesExtra_newTrace();
            return null;
        }).call();
    }

    @Override
    @Test
    public void propagatesExtra_unsampledTrace() throws Exception {
        new DummyRequestContext().makeContextAware(() -> {
            super.propagatesExtra_unsampledTrace();
            return null;
        }).call();
    }

    @Override
    @Test
    public void usesParentFromInvocationTime() throws Exception {
        new DummyRequestContext().makeContextAware(() -> {
            super.usesParentFromInvocationTime();
            return null;
        }).call();
    }

    @Override
    @Test
    public void redirect() throws Exception {
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
    protected void getAsync(WebClient client, String pathIncludingQuery) throws Exception {
        client.get(pathIncludingQuery);
    }

    private static class DummyRequestContext extends NonWrappingRequestContext {
        DummyRequestContext() {
            super(NoopMeterRegistry.get(), SessionProtocol.HTTP,
                  RequestId.random(), HttpMethod.GET, "/", null,
                  HttpRequest.streaming(HttpMethod.GET, "/"), null, null);
        }

        @Override
        public RequestContext newDerivedContext(RequestId id,
                                                @Nullable HttpRequest req,
                                                @Nullable RpcRequest rpcReq) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventLoop eventLoop() {
            return ClientFactory.ofDefault().eventLoopGroup().next();
        }

        @Nullable
        @Override
        protected Channel channel() {
            return null;
        }

        @Nullable
        @Override
        public SSLSession sslSession() {
            return null;
        }

        @Override
        public RequestLog log() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestLogBuilder logBuilder() {
            throw new UnsupportedOperationException();
        }
    }
}
