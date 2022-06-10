/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

class CompositeResponseConverterFunctionTest {

    @Test
    void composeResponseStreaming() {
        final ResponseConverterFunction convertA = new ResponseConverterFunction() {

            @Override
            public Boolean isResponseStreaming(Type returnType, @Nullable MediaType produceType) {
                return null;
            }

            @Override
            public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                                @Nullable Object result, HttpHeaders trailers)
                    throws Exception {
                return null;
            }
        };

        final ResponseConverterFunction convertB = new ResponseConverterFunction() {

            @Override
            public Boolean isResponseStreaming(Type returnType, @Nullable MediaType produceType) {
                return true;
            }

            @Override
            public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                                @Nullable Object result, HttpHeaders trailers)
                    throws Exception {
                return null;
            }
        };

        final ResponseConverterFunction convertC = new ResponseConverterFunction() {

            @Override
            public Boolean isResponseStreaming(Type returnType, @Nullable MediaType produceType) {
                return false;
            }

            @Override
            public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                                @Nullable Object result, HttpHeaders trailers)
                    throws Exception {
                return null;
            }
        };

        CompositeResponseConverterFunction converter =
                new CompositeResponseConverterFunction(ImmutableList.of(convertA, convertB, convertC));
        Boolean responseStreaming = converter.isResponseStreaming(null, null);
        assertThat(responseStreaming).isTrue();

        converter = new CompositeResponseConverterFunction(ImmutableList.of(convertC, convertB, convertA));
        responseStreaming = converter.isResponseStreaming(null, null);
        assertThat(responseStreaming).isFalse();
    }

    @Test
    void composeResponseStreaming_nonMatching() {
        final ResponseConverterFunction nullConverter = new ResponseConverterFunction() {

            @Override
            public Boolean isResponseStreaming(Type returnType, @Nullable MediaType produceType) {
                return null;
            }

            @Override
            public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                                @Nullable Object result, HttpHeaders trailers)
                    throws Exception {
                return null;
            }
        };

        final CompositeResponseConverterFunction converter = new CompositeResponseConverterFunction(
                ImmutableList.of(nullConverter, nullConverter, nullConverter));
        final Boolean responseStreaming = converter.isResponseStreaming(null, null);
        assertThat(responseStreaming).isNull();
    }
}
