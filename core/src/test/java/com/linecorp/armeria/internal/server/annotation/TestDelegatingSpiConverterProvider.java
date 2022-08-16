/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.internal.server.annotation;

import java.lang.reflect.Type;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.ResponseConverterFunctionUtilTest.TestClassWithDelegatingResponseConverterProvider;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.DelegatingResponseConverterFunctionProvider;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * For use with {@link ResponseConverterFunctionUtilTest}.
 */
public class TestDelegatingSpiConverterProvider implements DelegatingResponseConverterFunctionProvider {

    @Override
    public @Nullable ResponseConverterFunction createResponseConverterFunction(
            Type responseType,
            ResponseConverterFunction responseConverter) {
        final Class<?> responseClass = ClassUtil.typeToClass(responseType);
        if (responseClass != null && TestClassWithDelegatingResponseConverterProvider.class.isAssignableFrom(
                responseClass)) {
            return new TestDelegatingResponseConverterFunction();
        } else {
            return null;
        }
    }

    static class TestDelegatingResponseConverterFunction implements ResponseConverterFunction {

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            if (result instanceof TestClassWithDelegatingResponseConverterProvider) {
                // a real implementation would use the delegate responseConverter
                return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "testDelegatingResponse");
            }
            return ResponseConverterFunction.fallthrough();
        }
    }
}
