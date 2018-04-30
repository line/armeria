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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.annotation.ByteArrayRequestConverterFunction;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.logging.LoggingService;

public class AnnotatedHttpServiceBuilderTest {

    @Test
    public void successfulOf() {
        new ServerBuilder().annotatedService(new Object() {
            @Get("/")
            public void root() {}
        });

        new ServerBuilder().annotatedService(new Object() {
            @Path("/")
            @Get
            public void root() {}
        });

        new ServerBuilder().annotatedService(new Object() {
            @Path("/")
            @Get
            @Post
            public void root() {}
        });

        new ServerBuilder().annotatedService(new Object() {
            @Options
            @Get
            @Post("/")
            public void root() {}
        });

        new ServerBuilder().annotatedService(new Object() {
            @Get("/")
            public void root(@Param("a") Optional<Byte> a,
                             @Param("b") Optional<Short> b,
                             @Param("c") Optional<Boolean> c,
                             @Param("d") Optional<Integer> d,
                             @Param("e") Optional<Long> e,
                             @Param("f") Optional<Float> f,
                             @Param("g") Optional<Double> g,
                             @Param("h") Optional<String> h) {}
        });

        new ServerBuilder().annotatedService(new Object() {
            @Get("/")
            public void root(@Param("a") byte a,
                             @Param("b") short b,
                             @Param("c") boolean c,
                             @Param("d") int d,
                             @Param("e") long e,
                             @Param("f") float f,
                             @Param("g") double g,
                             @Param("h") String h) {}
        });

        new ServerBuilder().annotatedService(new Object() {
                                                 @Get("/")
                                                 public void root(@Param("a") byte a) {}
                                             },
                                             new JacksonRequestConverterFunction(),
                                             new ByteArrayRequestConverterFunction(),
                                             new DummyExceptionHandler());

        new ServerBuilder().annotatedService(new Object() {
                                                 @Get("/")
                                                 public void root(@Param("a") byte a) {}
                                             },
                                             LoggingService.newDecorator());

        new ServerBuilder().annotatedService(new Object() {
            @Get("/")
            public void root(@Header("a") List<String> a) {}
        });

        new ServerBuilder().annotatedService(new Object() {
            @Get("/")
            public void root(@Header("a") ArrayList<String> a,
                             @Header("a") LinkedList<String> b) {}
        });
    }

    @Test
    public void failedOf() {
        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Path("/")
            @Get("/")
            public void root() {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Post("/")
            @Get("/")
            public void root() {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get
            public void root() {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("")
            public void root() {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("  ")
            public void root() {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("/{name}")
            public void root(@Param("name") Optional<String> name) {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("/{name}")
            public void root(@Param("name") Optional<AnnotatedHttpServiceBuilderTest> name) {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("/{name}")
            public void root(@Param("name") List<String> name) {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Param("name") Optional<AnnotatedHttpServiceBuilderTest> name) {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Param("name") List<String> name) {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Header("name") List<Object> name) {}
        })).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ServerBuilder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Header("name") NoDefaultConstructorList<String> name) {}
        })).isInstanceOf(IllegalArgumentException.class);
    }

    private static class NoDefaultConstructorList<E> extends ArrayList<E> {
        private static final long serialVersionUID = -4221936122807956661L;

        NoDefaultConstructorList(int initialCapacity) {
            super(initialCapacity);
        }
    }

    private static class DummyExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            return null;
        }
    }
}
