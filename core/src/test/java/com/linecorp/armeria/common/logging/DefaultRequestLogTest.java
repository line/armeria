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

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.linecorp.armeria.client.ClientRequestContext;
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
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceNaming;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.Channel;

class DefaultRequestLogTest {

    private static final BiFunction<RequestContext, HttpHeaders, String> headersSanitizer =
            (ctx, headers) -> "sanitized_headers";
    private static final BiFunction<RequestContext, Object, String> contentSanitizer =
            (ctx, content) -> "sanitized_content";
    private static final BiFunction<RequestContext, HttpHeaders, String> trailersSanitizer =
            (ctx, trailers) -> "sanitized_trailers";

    @Mock
    private RequestContext ctx;

    @Mock
    private Channel channel;

    private DefaultRequestLog log;

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
    void addChild() {
        when(ctx.method()).thenReturn(HttpMethod.GET);
        final DefaultRequestLog child = new DefaultRequestLog(ctx);
        log.addChild(child);
        child.startRequest();
        child.session(channel, SessionProtocol.H2C, null, null);
        assertThat(log.requestStartTimeMicros()).isEqualTo(child.requestStartTimeMicros());
        assertThat(log.channel()).isSameAs(channel);
        assertThat(log.sessionProtocol()).isSameAs(SessionProtocol.H2C);

        child.serializationFormat(SerializationFormat.NONE);
        assertThat(log.scheme().serializationFormat()).isSameAs(SerializationFormat.NONE);

        child.requestFirstBytesTransferred();
        assertThat(log.requestFirstBytesTransferredTimeNanos())
                .isEqualTo(child.requestFirstBytesTransferredTimeNanos());

        final RequestHeaders foo = RequestHeaders.of(HttpMethod.GET, "/foo");
        child.requestHeaders(foo);
        assertThat(log.requestHeaders()).isSameAs(foo);

        final String requestContent = "baz";
        final String rawRequestContent = "qux";

        child.requestContent(requestContent, rawRequestContent);
        assertThat(log.requestContent()).isSameAs(requestContent);
        assertThat(log.rawRequestContent()).isSameAs(rawRequestContent);

        child.endRequest();
        assertThat(log.requestDurationNanos()).isEqualTo(child.requestDurationNanos());

        // response-side log are propagated when RequestLogBuilder.endResponseWithLastChild() is invoked
        child.startResponse();
        assertThatThrownBy(() -> log.responseStartTimeMicros())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        child.responseFirstBytesTransferred();
        assertThatThrownBy(() -> log.responseFirstBytesTransferredTimeNanos())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        final ResponseHeaders responseHeaders = ResponseHeaders.of(200);
        child.responseHeaders(responseHeaders);
        assertThatThrownBy(() -> log.responseHeaders())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        final HttpHeaders responseTrailers = HttpHeaders.of("status", 0);
        child.responseTrailers(responseTrailers);
        assertThatThrownBy(() -> log.responseTrailers())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        log.endResponseWithLastChild();
        assertThat(log.responseStartTimeMicros()).isEqualTo(child.responseStartTimeMicros());

        assertThat(log.responseFirstBytesTransferredTimeNanos())
                .isEqualTo(child.responseFirstBytesTransferredTimeNanos());
        assertThat(log.responseHeaders()).isSameAs(responseHeaders);
        assertThat(log.responseTrailers()).isSameAs(responseTrailers);

        final String responseContent = "baz1";
        final String rawResponseContent = "qux1";
        child.responseContent(responseContent, rawResponseContent);

        child.endResponse(new AnticipatedException("Oops!"));
        assertThat(log.responseContent()).isSameAs(responseContent);
        assertThat(log.rawResponseContent()).isSameAs(rawResponseContent);
        assertThat(log.responseDurationNanos()).isEqualTo(child.responseDurationNanos());
        assertThat(log.totalDurationNanos()).isEqualTo(child.totalDurationNanos());
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
    void toStringRequestOnlyCache() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        log = new DefaultRequestLog(sctx);

        final String a = log.toStringRequestOnly();
        assertThat(log.toStringRequestOnly()).isSameAs(a); // The second call must be cached.

        // Cache must be invalidated when request state changes.
        log.endRequest();
        final String b = log.toStringRequestOnly();
        assertThat(b).isNotEqualTo(a);
        assertThat(log.toStringRequestOnly()).isSameAs(b); // The second call must be cached.
    }

    @Test
    void toStringRequestOnlyCacheWithSanitizer() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.of(HttpRequest.of(
                        RequestHeaders.of(HttpMethod.GET, "/", "foo", "secret")));

        log = new DefaultRequestLog(sctx);
        log.requestContent("secret", "secret");
        log.requestTrailers(HttpHeaders.of("bar", "secret"));
        log.endRequest();

        // Cache must be invalidated when sanitizers change.
        final String a = log.toStringRequestOnly();
        final String b = log.toStringRequestOnly(headersSanitizer, contentSanitizer, trailersSanitizer);
        assertThat(b).isNotEqualTo(a)
                     .contains("sanitized_headers", "sanitized_content", "sanitized_trailers");

        // Must be cached when sanitizers were not changed.
        final String c = log.toStringRequestOnly(headersSanitizer, contentSanitizer, trailersSanitizer);
        assertThat(c).isSameAs(b);

        // Must not contain the secret.
        assertThat(c).doesNotContain("secret");
    }

    @Test
    void toStringResponseOnlyCache() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        log = new DefaultRequestLog(sctx);

        final String a = log.toStringResponseOnly();
        assertThat(log.toStringResponseOnly()).isSameAs(a); // The second call must be cached.

        // Cache must be invalidated when request state changes.
        log.endResponse();
        final String b = log.toStringResponseOnly();
        assertThat(b).isNotEqualTo(a);
        assertThat(log.toStringResponseOnly()).isSameAs(b); // The second call must be cached.
    }

    @Test
    void toStringResponseOnlyCacheInvalidationWithSanitizer() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        log = new DefaultRequestLog(sctx);
        log.responseHeaders(ResponseHeaders.of(HttpStatus.OK, "foo", "secret"));
        log.responseContent("secret", "secret");
        log.responseTrailers(HttpHeaders.of("bar", "secret"));
        log.endResponse();

        // Cache must be invalidated when sanitizers change.
        final String a = log.toStringResponseOnly();
        final String b = log.toStringResponseOnly(headersSanitizer, contentSanitizer, trailersSanitizer);
        assertThat(b).isNotEqualTo(a)
                     .contains("sanitized_headers", "sanitized_content", "sanitized_trailers");

        // Must be cached when sanitizers were not changed.
        final String c = log.toStringResponseOnly(headersSanitizer, contentSanitizer, trailersSanitizer);
        assertThat(c).isSameAs(b);

        // Must not contain the secret.
        assertThat(c).doesNotContain("secret");
    }

    @Test
    void toStringWithoutChildren() {
        final ServiceRequestContext sctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        log = new DefaultRequestLog(sctx);
        log.endRequest();
        log.endResponse();

        assertThat(log.toString()).matches("^\\{req=\\{.*}, res=\\{.*}}$");
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
        assertThat(lines[0]).matches("^\\{req=\\{.*}, res=\\{.*}}$");
        assertThat(lines[1]).matches("^Children:$");
        assertThat(lines[2]).matches("^\\t\\{req=\\{.*}, res=\\{.*}}$");
        assertThat(lines[3]).matches("^\\t\\{req=\\{.*}, res=\\{.*}}$");
    }
}
