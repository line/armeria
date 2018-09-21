/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.rxjava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.ServiceLoader;

import javax.annotation.Nullable;

import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;

import io.reactivex.Observable;

public class ObservableResponseConverterFunctionProviderTest {

    @Test
    public void shouldBeLoaded() {
        final ServiceLoader<ResponseConverterFunctionProvider> loader =
                ServiceLoader.load(ResponseConverterFunctionProvider.class,
                                   ObservableResponseConverterFunctionProviderTest.class.getClassLoader());

        assertThat(loader).isNotNull();
        final Iterator<ResponseConverterFunctionProvider> it = loader.iterator();
        assertThat(it.hasNext()).isTrue();
        assertThat(it.next()).isInstanceOf(ResponseConverterFunctionProvider.class);
        assertThat(it.hasNext()).isFalse();
    }

    @Test
    public void shouldFailOnObservableOfObservable() throws NoSuchMethodException {
        final ObservableResponseConverterFunctionProvider provider =
                new ObservableResponseConverterFunctionProvider();
        final Type returnType = Sample.class.getMethod("unsupported")
                                            .getGenericReturnType();
        assertThatThrownBy(
                () -> provider.createResponseConverterFunction(returnType,
                                                               new DummyResponseConverter(),
                                                               new DummyExceptionHandler()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot support 'io.reactivex.Observable<java.lang.Object>'");
    }

    public static class Sample {
        @Nullable
        public Observable<Observable<Object>> unsupported() {
            return null;
        }
    }

    private static class DummyResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, @Nullable Object result) {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    private static class DummyExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
