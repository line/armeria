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

import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverter;

final class TestConverters {

    public static class NaiveIntConverter implements ResponseConverter {
        @Override
        public HttpResponse convert(Object resObj) throws Exception {
            return httpResponse(HttpData.ofUtf8(String.format("Integer: %d", resObj)));
        }
    }

    public static class NaiveNumberConverter implements ResponseConverter {
        @Override
        public HttpResponse convert(Object resObj) throws Exception {
            return httpResponse(HttpData.ofUtf8(String.format("Number: %d", resObj)));
        }
    }

    public static class NaiveStringConverter implements ResponseConverter {
        @Override
        public HttpResponse convert(Object resObj) throws Exception {
            return httpResponse(HttpData.ofUtf8(String.format("String: %s", resObj)));
        }
    }

    public static class TypedNumberConverter implements ResponseConverter {
        @Override
        public HttpResponse convert(Object resObj) throws Exception {
            return httpResponse(HttpData.ofUtf8(String.format("Number[%d]", resObj)));
        }
    }

    public static class TypedStringConverter implements ResponseConverter {
        @Override
        public HttpResponse convert(Object resObj) throws Exception {
            return httpResponse(HttpData.ofUtf8(String.format("String[%s]", resObj)));
        }
    }

    public static class UnformattedStringConverter implements ResponseConverter {
        @Override
        public HttpResponse convert(Object resObj) throws Exception {
            return httpResponse(HttpData.ofUtf8(resObj.toString()));
        }
    }

    private static HttpResponse httpResponse(HttpData data) {
        assert RequestContext.current() != null;
        final DefaultHttpResponse res = new DefaultHttpResponse();
        final long current = System.currentTimeMillis();
        HttpHeaders headers = HttpHeaders.of(HttpStatus.OK)
                                         .setInt(HttpHeaderNames.CONTENT_LENGTH,
                                                 data.length())
                                         .setTimeMillis(HttpHeaderNames.DATE, current);

        final MediaType contentType =
                ((ServiceRequestContext) RequestContext.current()).negotiatedProduceType();
        if (contentType != null) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
        }

        res.write(headers);
        res.write(data);
        res.close();
        return res;
    }

    private TestConverters() {}
}
