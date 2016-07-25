/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.metrics;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.metrics.MetricConsumer;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceCodec;
import com.linecorp.armeria.server.ServiceCodec.DecodeResult;
import com.linecorp.armeria.server.ServiceCodec.DecodeResultType;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;

@RunWith(Enclosed.class)
public class MetricCollectingServiceCodecTest {
    @SuppressWarnings("unchecked")
    protected static void setupServer(
            ServerBuilder sb, ServiceInvocationHandler handler, final MetricConsumer consumer) throws Exception {
        ServiceCodec codec = Mockito.mock(ServiceCodec.class);
        DecodeResult decodeResult = Mockito.mock(DecodeResult.class);
        DefaultAttributeMap defaultAttributeMap = new DefaultAttributeMap();
        ServiceInvocationContext invocationContext = Mockito.mock(ServiceInvocationContext.class);

        when(decodeResult.type()).thenReturn(DecodeResultType.SUCCESS);
        when(decodeResult.invocationContext()).thenReturn(invocationContext);
        when(invocationContext.attr(any())).then(x -> defaultAttributeMap.attr(AttributeKey.valueOf("TEST")));
        when(invocationContext.method()).thenReturn("someMethod");

        when(codec.decodeRequest(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(decodeResult);

        when(codec.encodeResponse(any(), any())).thenReturn(Unpooled.EMPTY_BUFFER);
        when(codec.encodeFailureResponse(any(), any())).thenReturn(Unpooled.EMPTY_BUFFER);

        sb.serviceAt("/", Service.of(new MetricCollectingServiceCodec(codec, consumer), handler));
    }

    abstract static class ExecutionCheckingTest extends AbstractServerTest {
        private int started;
        private int completed;

        protected MetricConsumer defaultConsumer = new MetricConsumer() {
            @Override
            public void invocationStarted(Scheme scheme, String hostname, String path, Optional<String> method) {
                started++;
            }

            @Override
            public void invocationComplete(Scheme scheme, int code, long processTimeNanos, int requestSize,
                                           int responseSize, String hostname, String path,
                                           Optional<String> method, boolean started) {
                completed++;
            }
        };

        @Test
        public void test() throws Exception {
            try (CloseableHttpClient hc = HttpClients.createMinimal()) {
                final HttpPost req = new HttpPost(uri("/"));
                req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

                hc.execute(req);

                assertEquals(1, started);
                assertEquals(1, completed);
            }
        }
    }

    public static class NormalResponseTest extends ExecutionCheckingTest {
        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            setupServer(sb, (ctx, blockingTaskExecutor, promise) -> promise.trySuccess("Hello World"),
                        defaultConsumer);
        }
    }

    public static class TypedResponseTest extends ExecutionCheckingTest {
        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            ByteBufHolder response = Mockito.mock(ByteBufHolder.class);
            when(response.content()).thenReturn(Unpooled.EMPTY_BUFFER);
            setupServer(sb, (ctx, blockingTaskExecutor, promise) -> promise.trySuccess(response),
                        defaultConsumer);
        }
    }

    public static class HttpResponseTest extends ExecutionCheckingTest {
        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            setupServer(
                    sb, (ctx, blockingTaskExecutor, promise) -> promise.trySuccess(
                            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)),
                    defaultConsumer);
        }
    }

    public static class ExceptionResponseTest extends ExecutionCheckingTest {
        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            setupServer(sb, (ctx, blockingTaskExecutor, promise) -> promise.tryFailure(new RuntimeException()),
                        defaultConsumer);
        }
    }
}
