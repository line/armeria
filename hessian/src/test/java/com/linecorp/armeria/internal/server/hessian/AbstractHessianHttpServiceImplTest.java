/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.hessian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.caucho.hessian.io.AbstractHessianInput;
import com.caucho.hessian.io.AbstractHessianOutput;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.caucho.hessian.io.HessianServiceException;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.hessian.service.DemoException;
import com.linecorp.armeria.hessian.service.HelloRequest;
import com.linecorp.armeria.hessian.service.HelloResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.hessian.HessianHttpService;

/**
 * test service.
 *
 * @author eisig
 */
abstract class AbstractHessianHttpServiceImplTest {

    HessianHttpService hessianHttpService;

    @BeforeEach
    void setUp() {
        hessianHttpService = setupHessianHttpService();
    }

    protected abstract HessianHttpService setupHessianHttpService();

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = "POST", mode = EnumSource.Mode.EXCLUDE)
    void testOnlySupportPost(HttpMethod httpMethod) throws Exception {
        // Given
        final HttpRequest req = HttpRequest.of(httpMethod, "/services/helloServices.hs", MediaType.JSON_UTF_8,
                                               "{}");
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();

        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void testMediaTypeNotSupported() throws Exception {
        // Given
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloServices.hs",
                                               MediaType.JSON_UTF_8,
                                               "{}");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(ctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();

        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void testNotFound() throws Exception {
        // Given
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService1.hs",
                                               MediaType.create("x-application", "hessian"), "{}");
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();

        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testNotHessianRequestBody() throws Exception {
        // Given
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), "{}");
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();

        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testMethodNotFound() throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "otherMethod", "Tom");
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(ctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThatThrownBy(() -> readReply(String.class, aggregatedRes.content().array()))
                .isInstanceOf(HessianServiceException.class)
                .hasMessageContaining("he service has no method named: otherMethod");
    }

    @Test
    void testMethodWrongArgsCount() throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "sayHelloStr");
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThatThrownBy(() -> readReply(String.class, aggregatedRes.content().array()))
                .isInstanceOf(HessianServiceException.class)
                .hasMessageContaining("The service has no method named: sayHelloStr with length 0");
    }

    @Test
    void testStringRequest() throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "sayHelloStr", "Tom");
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        final String reply = readReply(String.class, aggregatedRes.content().array());
        assertThat(reply).isEqualTo("Hello Tom");
    }

    @Test
    void testObjectRequest() throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "sayHello2", new HelloRequest("Tom"));
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(ctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        final HelloResponse reply = readReply(HelloResponse.class, aggregatedRes.content().array());
        assertThat(reply).isEqualTo(new HelloResponse("Hello Tom"));
    }

    @Test
    void testHessian1Request() throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.CALL_1_REPLY_2, "sayHello2", new HelloRequest("Tom"));
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        final HelloResponse reply = readReply(HelloResponse.class, aggregatedRes.content().array());
        assertThat(reply).isEqualTo(new HelloResponse("Hello Tom"));
    }

    @Test
    void testDefaultInBlockingThread() throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "threadName");
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        final String reply = readReply(String.class, aggregatedRes.content().array());
        assertThat(reply).contains("-blocking");
    }

    /**
     * helloService2 is nonblocking.
     */
    @Test
    void testNonBlockingThread() throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "threadName");
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService2.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        final String reply = readReply(String.class, aggregatedRes.content().array());
        assertThat(reply).satisfiesAnyOf(it -> assertThat(it).contains("common-worker"),
                                         it -> assertThat(it).contains("main"));
    }

    @Test
    void testServiceImplError() throws Exception {
        // Given
        final byte[] data = requestData(HeaderType.CALL_1_REPLY_2, "failedSayHello", new HelloRequest("Tom"));
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> readReply(String.class, aggregatedRes.content().array()))
                .isInstanceOf(DemoException.class).hasMessageContaining("failed");
    }

    @ParameterizedTest
    @ValueSource(strings = { "java.api.class", "java.home.class", "java.object.class" })
    void testAttributeRequest(String attrName) throws Throwable {
        // Given
        final byte[] data = requestData(HeaderType.HESSIAN_2, "_hessian_getAttribute", attrName);
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/services/helloService.hs",
                                               MediaType.create("x-application", "hessian"), data);
        final ServiceRequestContext sctx = ServiceRequestContext.of(req);

        // When
        final HttpResponse res = hessianHttpService.serve(sctx, req);

        // Then
        final AggregatedHttpResponse aggregatedRes = res.aggregate().join();
        assertThat(aggregatedRes.status()).isEqualTo(HttpStatus.OK);
        final String reply = readReply(String.class, aggregatedRes.content().array());
        assertThat(reply).isEqualTo("com.linecorp.armeria.hessian.service.HelloService");
    }

    byte[] requestData(HeaderType headerType, String methodName, Object... args) throws IOException {
        final ByteArrayOutputStream os = new ByteArrayOutputStream(128);
        final AbstractHessianOutput output = hessianOutput(headerType, os);
        output.call(methodName, args);
        output.close();
        return os.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static <T> T readReply(Class<T> tClass, byte[] data) throws Throwable {
        final ByteArrayInputStream is = new ByteArrayInputStream(data);
        final AbstractHessianInput in;
        final int code = is.read();

        if (code == 'H') {
            final int major = is.read();
            final int minor = is.read();

            in = new Hessian2Input(is);

            final Object value = in.readReply(tClass);
            return (T) value;
        } else if (code == 'r') {
            final int major = is.read();
            final int minor = is.read();

            in = new HessianInput(is);

            in.startReplyBody();

            final Object value = in.readObject(tClass);
            in.completeReply();

            return (T) value;
        }
        throw new RuntimeException("Could net be here.");
    }

    static AbstractHessianOutput hessianOutput(HeaderType headerType, ByteArrayOutputStream os) {
        final AbstractHessianOutput out;
        if (headerType.isReply2()) {
            out = new Hessian2Output(os);
        } else {
            final HessianOutput out1 = new HessianOutput(os);
            out = out1;
            if (headerType.isReply2()) {
                out1.setVersion(2);
            }
        }
        return out;
    }
}
