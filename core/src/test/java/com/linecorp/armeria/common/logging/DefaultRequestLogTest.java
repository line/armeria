/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceNaming;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.channel.Channel;

class DefaultRequestLogTest {

    @Mock
    private RequestContext ctx;

    @Mock
    private Channel channel;

    private DefaultRequestLog log;

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @BeforeEach
    void setUp() {
        log = new DefaultRequestLog(ctx);
    }

    @Test
    void endRequestSuccess() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        when(ctx.method()).thenReturn(HttpMethod.GET);
        log.endRequest();
        assertThat(log.requestDurationNanos()).isZero();
        assertThat(log.requestCause()).isNull();
    }

    @Test
    void endRequestWithoutHeaders() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        when(ctx.method()).thenReturn(HttpMethod.GET);
        log.endRequest();
        final RequestHeaders headers = log.requestHeaders();
        assertThat(headers.scheme()).isEqualTo("http");
        assertThat(headers.authority()).isEqualTo("?");
        assertThat(headers.method()).isSameAs(HttpMethod.UNKNOWN);
        assertThat(headers.path()).isEqualTo("?");
    }

    @Test
    void endRequestWithHeadersInContext() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        when(ctx.method()).thenReturn(HttpMethod.GET);
        when(ctx.request()).thenReturn(HttpRequest.of(HttpMethod.GET, "/foo"));
        log.endRequest();
        assertThat(log.requestHeaders()).isSameAs(ctx.request().headers());
    }

    @Test
    void endResponseSuccess() {
        log.endResponse();
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isNull();
    }

    @Test
    void endResponseFailure() {
        final Throwable error = new Throwable("response failed");
        log.endResponse(error);
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    void endResponseWithoutHeaders() {
        log.endResponse();
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.UNKNOWN);
    }

    @Test
    void rpcRequestIsPropagatedToContext() {
        final RpcRequest req = RpcRequest.of(Object.class, "foo");
        when(ctx.rpcRequest()).thenReturn(null);
        log.requestContent(req, null);
        verify(ctx, times(1)).updateRpcRequest(req);
    }

    @Test
    void rpcRequestIsNotPropagatedToContext() {
        final RpcRequest req = RpcRequest.of(Object.class, "foo");
        when(ctx.rpcRequest()).thenReturn(RpcRequest.of(Object.class, "bar"));
        log.requestContent(req, null);
        verify(ctx, never()).updateRpcRequest(any());
    }

    @Test
    void rpcFailure_endResponseWithoutCause() {
        final Throwable error = new Throwable("response failed");
        log.responseContent(RpcResponse.ofFailure(error), null);
        // If user code doesn't call endResponse, the framework automatically does with no cause.
        log.endResponse();
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    void rpcFailure_endResponseDifferentCause() {
        final Throwable error = new Throwable("response failed one way");
        final Throwable error2 = new Throwable("response failed a different way?");
        log.responseContent(RpcResponse.ofFailure(error), null);
        log.endResponse(error2);
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    void rpcFailure_responseContentWithCause() {
        final Throwable error = new Throwable("response failed");
        log.responseContent(RpcResponse.ofFailure(error), null);
        assertThat(log.responseCause()).isSameAs(error);
        assertThat(log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void addChild(boolean isResponseEndingWithFirstChild) {
        when(ctx.method()).thenReturn(HttpMethod.GET);
        final DefaultRequestLog firstChild = new DefaultRequestLog(ctx);
        final DefaultRequestLog lastChild = new DefaultRequestLog(ctx);
        log.addChild(firstChild);
        log.addChild(lastChild);
        firstChild.startRequest();
        assertThat(log.requestStartTimeMicros()).isEqualTo(firstChild.requestStartTimeMicros());
        lastChild.startRequest();
        assertThat(log.requestStartTimeMicros()).isEqualTo(firstChild.requestStartTimeMicros());

        lastChild.session(channel, SessionProtocol.H1C, null, null);
        assertThatThrownBy(() -> log.channel()).isExactlyInstanceOf(RequestLogAvailabilityException.class);
        firstChild.session(channel, SessionProtocol.H2C, null, null);
        assertThat(log.channel()).isSameAs(channel);
        assertThat(log.sessionProtocol()).isSameAs(SessionProtocol.H2C);

        lastChild.serializationFormat(SerializationFormat.UNKNOWN);
        assertThatThrownBy(() -> log.scheme())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);
        firstChild.serializationFormat(SerializationFormat.NONE);
        assertThat(log.scheme().serializationFormat()).isSameAs(SerializationFormat.NONE);

        assertThatThrownBy(() -> log.requestFirstBytesTransferredTimeNanos())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);
        lastChild.requestFirstBytesTransferred();
        assertThatThrownBy(() -> log.requestFirstBytesTransferredTimeNanos())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);
        firstChild.requestFirstBytesTransferred();
        assertThat(log.requestFirstBytesTransferredTimeNanos())
                .isEqualTo(firstChild.requestFirstBytesTransferredTimeNanos());

        final RequestHeaders foo = RequestHeaders.of(HttpMethod.GET, "/foo");
        final RequestHeaders bar = RequestHeaders.of(HttpMethod.GET, "/bar");

        lastChild.requestHeaders(foo);
        assertThatThrownBy(() -> log.requestHeaders())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);
        firstChild.requestHeaders(bar);
        assertThat(log.requestHeaders()).isSameAs(bar);

        final String requestContent = "baz";
        final String rawRequestContent = "qux";

        lastChild.requestContent(rawRequestContent, requestContent); // swapped
        assertThatThrownBy(() -> log.requestContent())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);
        firstChild.requestContent(requestContent, rawRequestContent);
        assertThat(log.requestContent()).isSameAs(requestContent);
        assertThat(log.rawRequestContent()).isSameAs(rawRequestContent);

        lastChild.endRequest();
        assertThatThrownBy(() -> log.requestDurationNanos())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);
        firstChild.endRequest();
        assertThat(log.requestDurationNanos()).isEqualTo(firstChild.requestDurationNanos());

        // response-side log are propagated when RequestLogBuilder.endResponseWith(Last)Child() is invoked
        firstChild.startResponse();
        lastChild.startResponse();
        assertThatThrownBy(() -> log.responseStartTimeMicros())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        lastChild.responseFirstBytesTransferred();
        firstChild.responseFirstBytesTransferred();
        assertThatThrownBy(() -> log.responseFirstBytesTransferredTimeNanos())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        final ResponseHeaders responseHeaders = ResponseHeaders.of(200);
        final ResponseHeaders responseHeaders2 = ResponseHeaders.of(404);
        firstChild.responseHeaders(responseHeaders);
        lastChild.responseHeaders(responseHeaders2);
        assertThatThrownBy(() -> log.responseHeaders())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        final HttpHeaders responseTrailers = HttpHeaders.of("status", 0);
        final HttpHeaders responseTrailers2 = HttpHeaders.of("status", 42);
        firstChild.responseTrailers(responseTrailers);
        lastChild.responseTrailers(responseTrailers2);
        assertThatThrownBy(() -> log.responseTrailers())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        if (isResponseEndingWithFirstChild) {
            log.endResponseWithChild(firstChild);
        } else {
            log.endResponseWithLastChild();
        }

        final DefaultRequestLog winningChild  = isResponseEndingWithFirstChild ? firstChild : lastChild;
        final DefaultRequestLog loosingChild = isResponseEndingWithFirstChild ? lastChild : firstChild;

        assertThat(log.responseStartTimeMicros()).isEqualTo(winningChild.responseStartTimeMicros());
        assertThat(log.responseFirstBytesTransferredTimeNanos())
                .isEqualTo(winningChild.responseFirstBytesTransferredTimeNanos());
        assertThat(log.responseHeaders()).isSameAs(winningChild.responseHeaders());
        assertThat(log.responseTrailers()).isSameAs(winningChild.responseTrailers());

        final String responseContent = "baz1";
        final String rawResponseContent = "qux1";
        winningChild.responseContent(responseContent, rawResponseContent);
        loosingChild.responseContent(rawResponseContent, responseContent); // swapped

        loosingChild.endResponse(new IllegalStateException());
        winningChild.endResponse(new AnticipatedException("Oops!"));

        assertThat(log.responseContent()).isSameAs(winningChild.responseContent());
        assertThat(log.rawResponseContent()).isSameAs(winningChild.rawResponseContent());
        assertThat(log.responseDurationNanos()).isEqualTo(winningChild.responseDurationNanos());
        assertThat(log.requestStartTimeNanos()).isEqualTo(
                Math.min(loosingChild.requestStartTimeNanos(), winningChild.requestStartTimeNanos())
        );
        assertThat(log.requestEndTimeNanos())
                .isEqualTo(Math.max(loosingChild.requestEndTimeNanos(), winningChild.requestEndTimeNanos()));
        assertThat(log.responseEndTimeNanos()).isEqualTo(winningChild.responseEndTimeNanos());
        assertThat(log.totalDurationNanos())
                .isEqualTo(winningChild.responseEndTimeNanos() - log.requestStartTimeNanos());
    }

    @Test
    void setParentIdWhileAddingChild() {
        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(ctx2.log().parent()).isNull();
        ctx1.logBuilder().addChild(ctx2.log());
        assertThat(ctx2.log().parent()).isEqualTo(ctx1.log());
        assertThat(ctx2.log().parent().context().id()).isEqualTo(ctx1.id());
    }

    @Test
    void deferContent_setContentAfterEndResponse() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        when(ctx.method()).thenReturn(HttpMethod.GET);
        when(ctx.eventLoop()).thenReturn(ContextAwareEventLoop.of(ctx, ImmediateEventLoop.INSTANCE));
        final CompletableFuture<RequestLog> completeFuture = log.whenComplete();
        assertThat(completeFuture.isDone()).isFalse();

        log.defer(RequestLogProperty.REQUEST_CONTENT);
        log.defer(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
        log.endRequest();

        log.defer(RequestLogProperty.RESPONSE_CONTENT);
        log.defer(RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
        log.endResponse();

        assertThat(completeFuture.isDone()).isFalse();
        log.requestContent(null, null);
        log.requestContentPreview(null);
        log.responseContent(null, null);
        assertThat(completeFuture.isDone()).isFalse();
        log.responseContentPreview(null);
        assertThat(completeFuture.isDone()).isTrue();
    }

    @Test
    void deferContent_setContentBeforeEndResponse() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        when(ctx.method()).thenReturn(HttpMethod.GET);
        when(ctx.eventLoop()).thenReturn(ContextAwareEventLoop.of(ctx, ImmediateEventLoop.INSTANCE));
        final CompletableFuture<RequestLog> completeFuture = log.whenComplete();
        assertThat(completeFuture.isDone()).isFalse();

        log.defer(RequestLogProperty.REQUEST_CONTENT);
        log.defer(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
        log.requestContent(null, null);
        log.requestContentPreview(null);
        log.endRequest();

        log.defer(RequestLogProperty.RESPONSE_CONTENT);
        log.defer(RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
        log.responseContent(null, null);
        log.responseContentPreview(null);
        assertThat(completeFuture.isDone()).isFalse();

        log.endResponse();
        assertThat(completeFuture.isDone()).isTrue();
    }

    @Test
    void useDefaultLogNameWhenNoNameIsSet() {
        final String logName = "someLogName";
        final ServiceRequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                               .defaultLogName(logName).build();
        ctx.logBuilder().endRequest();
        assertThat(ctx.log().ensureAvailable(RequestLogProperty.NAME).name()).isSameAs(logName);
    }

    @Test
    void logNameWithRequestContent() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();

        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(RpcRequest.of(DefaultRequestLogTest.class, "test"), null);
        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        assertThat(log.serviceName()).isEqualTo(DefaultRequestLogTest.class.getName());
    }

    @Test
    void logNameWithDeferredRequestContent() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();

        log.defer(RequestLogProperty.REQUEST_CONTENT);
        log.endRequest();
        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        assertThat(log.whenRequestComplete()).isNotDone();

        log.requestContent(RpcRequest.of(DefaultRequestLogTest.class, "test"), null);
        assertThat(log.name()).isSameAs("test");
        await().untilAsserted(() -> {
            assertThat(log.whenRequestComplete()).isDone();
        });
    }

    @Test
    void logNameWithDeferredRequestContent_beforeEndRequest() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();

        log.defer(RequestLogProperty.REQUEST_CONTENT);
        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(RpcRequest.of(DefaultRequestLogTest.class, "test"), null);
        assertThat(log.whenRequestComplete()).isNotDone();

        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        await().untilAsserted(() -> {
            assertThat(log.whenRequestComplete()).isDone();
        });
    }

    @Test
    void logServiceNameWithServiceNaming() {
        final HttpService httpService = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .service(httpService)
                                     .defaultServiceNaming(ServiceNaming.simpleTypeName())
                                     .build();
        final RpcRequest rpcRequest = RpcRequest.of(DefaultRequestLogTest.class, "test");
        sctx.updateRpcRequest(rpcRequest);

        log = new DefaultRequestLog(sctx);

        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(rpcRequest, null);
        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        assertThat(log.serviceName()).startsWith(DefaultRequestLogTest.class.getSimpleName());
    }

    @Test
    void logServiceNameWithServiceNaming_of() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .defaultServiceNaming(ServiceNaming.of("hardCodedServiceName"))
                                     .build();
        log = new DefaultRequestLog(sctx);

        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(RpcRequest.of(DefaultRequestLogTest.class, "test"), null);
        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        assertThat(log.serviceName()).isEqualTo("hardCodedServiceName");
    }

    @Test
    void logServiceNameWithServiceNaming_shorten50() {
        final HttpService httpService = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .service(httpService)
                                     .defaultServiceNaming(ServiceNaming.shorten(50))
                                     .build();
        final RpcRequest rpcRequest = RpcRequest.of(DefaultRequestLogTest.class, "test");
        sctx.updateRpcRequest(rpcRequest);

        log = new DefaultRequestLog(sctx);

        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(rpcRequest, null);
        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        assertThat(log.serviceName()).startsWith("c.l.armeria.common.logging.DefaultRequestLogTest");
    }

    @Test
    void logServiceNameWithServiceNaming_shorten() {
        final HttpService httpService = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .service(httpService)
                                     .defaultServiceNaming(ServiceNaming.shorten())
                                     .build();
        final RpcRequest rpcRequest = RpcRequest.of(DefaultRequestLogTest.class, "test");
        sctx.updateRpcRequest(rpcRequest);

        log = new DefaultRequestLog(sctx);

        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(rpcRequest, null);
        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        assertThat(log.serviceName()).startsWith("c.l.a.c.l.DefaultRequestLogTest");
    }

    @Test
    void logServiceNameWithServiceNaming_custom() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .defaultServiceNaming(ctx -> "customServiceName")
                                     .build();
        log = new DefaultRequestLog(sctx);

        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(RpcRequest.of(DefaultRequestLogTest.class, "test"), null);
        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        assertThat(log.serviceName()).isEqualTo("customServiceName");
    }

    @Test
    void logServiceNameWithServiceNaming_null() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .defaultServiceNaming(ctx -> null)
                                     .build();
        log = new DefaultRequestLog(sctx);

        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();
        log.requestContent(RpcRequest.of(DefaultRequestLogTest.class, "test"), null);
        log.endRequest();
        assertThat(log.name()).isSameAs("test");
        assertThat(log.serviceName()).startsWith(DefaultRequestLogTest.class.getName());
    }

    @Test
    void toStringWithoutChildren() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        log = new DefaultRequestLog(sctx);
        log.endRequest();
        log.endResponse();

        assertThat(log.toString()).matches("^\\{Request: \\{.*}, Response: \\{.*}}$");
    }

    @Test
    void toStringWithChildren() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        log = new DefaultRequestLog(sctx);
        final DefaultRequestLog child1 = new DefaultRequestLog(sctx);
        final DefaultRequestLog child2 = new DefaultRequestLog(sctx);
        log.addChild(child1);
        log.addChild(child2);
        child1.endRequest();
        child1.endResponse();
        child2.endRequest();
        child2.endResponse();
        log.endRequest();
        log.endResponse();

        final String logStr = log.toString();
        assertThat(logStr).doesNotEndWith("\n");

        final String[] lines = logStr.split("\\r?\\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).matches("^\\{Request: \\{.*}, Response: \\{.*}}$");
        assertThat(lines[1]).matches("^Children:$");
        assertThat(lines[2]).matches("^\\t\\{Request: \\{.*}, Response: \\{.*}}$");
        assertThat(lines[3]).matches("^\\t\\{Request: \\{.*}, Response: \\{.*}}$");
    }

    @Test
    void testGetIfAvailable() {
        // Given
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogAccess log = ctx.log();

        // When
        assertThat(log.isAvailable(RequestLogProperty.REQUEST_HEADERS)).isTrue();
        assertThat(log.isAvailable(RequestLogProperty.NAME)).isFalse();

        // Then
        assertThat(log.getIfAvailable(RequestLogProperty.REQUEST_HEADERS)).isEqualTo(log);
        assertThat(log.getIfAvailable(RequestLogProperty.NAME)).isNull();
    }

    @Test
    void testPendingLogsAlwaysInEventLoop() {
        // Given
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Thread testThread = Thread.currentThread();

        final BlockingQueue<Thread> queue = new ArrayBlockingQueue<>(32);
        final RequestLogAccess log = ctx.log();
        for (RequestLogProperty property: RequestLogProperty.values()) {
            log.whenAvailable(property).thenRun(() -> queue.add(Thread.currentThread()));
        }

        // schedule log completion from a different thread
        eventLoop.get().execute(() -> {
            ctx.logBuilder().endRequest();
            ctx.logBuilder().endResponse();
        });
        await().untilAsserted(() -> assertThat(queue.size()).isEqualTo(RequestLogProperty.values().length));

        assertThat(queue).allSatisfy(t -> assertThat(t)
                .satisfiesAnyOf(t0 -> assertThat(t0).isEqualTo(testThread),
                                t0 -> assertThat(ctx.eventLoop().inEventLoop(t0)).isTrue()));
    }

    @Test
    void nameIsAlwaysSet() {
        final AtomicInteger atomicInteger = new AtomicInteger();
        final ExecutorService executorService =
                Executors.newFixedThreadPool(2, ThreadFactories.newThreadFactory("test", true));
        // a heurestic number of iterations to reproduce #5981
        final int numIterations = 1000;
        for (int i = 0; i < numIterations; i++) {
            final ServiceRequestContext sctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final DefaultRequestLog log = new DefaultRequestLog(sctx);
            log.defer(RequestLogProperty.REQUEST_CONTENT);
            executorService.execute(log::endRequest);
            executorService.execute(() -> log.requestContent(null, null));
            log.whenRequestComplete().thenRun(atomicInteger::incrementAndGet);
        }
        await().untilAsserted(() -> assertThat(atomicInteger).hasValue(numIterations));
    }
}
