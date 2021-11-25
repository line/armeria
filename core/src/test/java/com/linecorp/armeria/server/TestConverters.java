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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

public final class TestConverters {

    public static class NaiveIntConverterFunction implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result instanceof Integer) {
                return httpResponse(HttpData.ofUtf8("Integer: %d", (Integer) result));
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    public static class NaiveStringConverterFunction implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result instanceof String) {
                return httpResponse(HttpData.ofUtf8("String: %s", result));
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    public static class TypedNumberConverterFunction implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result instanceof Number) {
                return httpResponse(HttpData.ofUtf8("Number[%s]", result));
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    public static class TypedStringConverterFunction implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result instanceof String) {
                return httpResponse(HttpData.ofUtf8("String[%s]", result));
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    public static class ByteArrayConverterFunction implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result instanceof byte[]) {
                return httpResponse(HttpData.wrap((byte[]) result));
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    public static class ByteArrayConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            if (result instanceof byte[]) {
                return httpResponse(HttpData.wrap((byte[]) result));
            }
            throw new IllegalArgumentException("Cannot convert " +
                                               (result != null ? result.getClass().getName() : "null"));
        }
    }

    // Accepts everything.
    public static class UnformattedStringConverterFunction implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            return httpResponse(HttpData.ofUtf8(result != null ? result.toString() : "(null)"));
        }
    }

    private static HttpResponse httpResponse(HttpData data) {
        final HttpResponseWriter res = HttpResponse.streaming();
        final long current = System.currentTimeMillis();
        final ResponseHeadersBuilder headers = ResponseHeaders.builder(HttpStatus.OK);
        headers.setInt(HttpHeaderNames.CONTENT_LENGTH, data.length());
        headers.setTimeMillis(HttpHeaderNames.DATE, current);

        final MediaType contentType = ServiceRequestContext.current().negotiatedResponseMediaType();
        if (contentType != null) {
            headers.contentType(contentType);
        }

        res.write(headers.build());
        res.write(data);
        res.close();
        return res;
    }

    private TestConverters() {}
}
