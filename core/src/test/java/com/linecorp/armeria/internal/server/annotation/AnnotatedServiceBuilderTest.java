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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ByteArrayRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.logging.LoggingService;

class AnnotatedServiceBuilderTest {

    @Test
    void successfulOf() {
        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root() {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Path("/")
            @Get
            public void root() {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Path("/")
            @Get
            @Post
            public void root() {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Param("a") Optional<Byte> a,
                             @Param("b") Optional<Short> b,
                             @Param("c") Optional<Boolean> c,
                             @Param("d") Optional<Integer> d,
                             @Param("e") Optional<Long> e,
                             @Param("f") Optional<Float> f,
                             @Param("g") Optional<Double> g,
                             @Param("h") Optional<String> h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Param("a") byte a,
                             @Param("b") short b,
                             @Param("c") boolean c,
                             @Param("d") int d,
                             @Param("e") long e,
                             @Param("f") float f,
                             @Param("g") double g,
                             @Param("h") String h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Param("a") List<Byte> a,
                             @Param("b") List<Short> b,
                             @Param("c") List<Boolean> c,
                             @Param("d") List<Integer> d,
                             @Param("e") List<Long> e,
                             @Param("f") List<Float> f,
                             @Param("g") List<Double> g,
                             @Param("h") List<String> h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Param("a") Set<Byte> a,
                             @Param("b") Set<Short> b,
                             @Param("c") Set<Boolean> c,
                             @Param("d") Set<Integer> d,
                             @Param("e") Set<Long> e,
                             @Param("f") Set<Float> f,
                             @Param("g") Set<Double> g,
                             @Param("h") Set<String> h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Param("a") Optional<List<Byte>> a,
                             @Param("b") Optional<List<Short>> b,
                             @Param("c") Optional<List<Boolean>> c,
                             @Param("d") Optional<List<Integer>> d,
                             @Param("e") Optional<List<Long>> e,
                             @Param("f") Optional<List<Float>> f,
                             @Param("g") Optional<List<Double>> g,
                             @Param("h") Optional<List<String>> h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
                                              @Get("/")
                                              public void root(@Param("a") byte a) {}
                                          },
                                          new JacksonRequestConverterFunction(),
                                          new ByteArrayRequestConverterFunction(),
                                          new DummyExceptionHandler()).build();

        Server.builder().annotatedService(new Object() {
                                              @Get("/")
                                              public void root(@Param("a") byte a) {}
                                          },
                                          LoggingService.newDecorator()).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Header("a") List<Byte> a,
                             @Header("b") List<Short> b,
                             @Header("c") List<Boolean> c,
                             @Header("d") List<Integer> d,
                             @Header("e") List<Long> e,
                             @Header("f") List<Float> f,
                             @Header("g") List<Double> g,
                             @Header("h") List<String> h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Header("a") Set<Byte> a,
                             @Header("b") Set<Short> b,
                             @Header("c") Set<Boolean> c,
                             @Header("d") Set<Integer> d,
                             @Header("e") Set<Long> e,
                             @Header("f") Set<Float> f,
                             @Header("g") Set<Double> g,
                             @Header("h") Set<String> h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Header("a") Optional<List<Byte>> a,
                             @Header("b") Optional<List<Short>> b,
                             @Header("c") Optional<List<Boolean>> c,
                             @Header("d") Optional<List<Integer>> d,
                             @Header("e") Optional<List<Long>> e,
                             @Header("f") Optional<List<Float>> f,
                             @Header("g") Optional<List<Double>> g,
                             @Header("h") Optional<List<String>> h) {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@Header("a") ArrayList<String> a,
                             @Header("a") LinkedList<String> b) {}
        }).build();

        // Multiple paths are allowed
        Server.builder().annotatedService(new Object() {
            @Get
            @Post
            @Path("/a")
            public void root() {}
        }).build();

        Server.builder().annotatedService(new Object() {
            @Post("/")
            @Get("/")
            public void root() {}
        }).build();

        // Multiple paths with matching params
        Server.builder().annotatedService(new Object() {
            @Get
            @Path("/a/{param}")
            @Path("/b/{param}")
            public void root(@Param("param") String param) {}
        }).build();

        // Multiple paths with non matching but optional path variables
        Server.builder().annotatedService(new Object() {
            @Get
            @Path("/a")
            @Path("/b/{param}")
            public void root(@Param("param") Optional<String> param) {}
        }).build();

        // Multiple paths with non matching non-optional path variables
        Server.builder().annotatedService(new Object() {
            @Get
            @Path("/a/{param1}")
            @Path("/b/{param2}")
            public void root(String param1, String param2) {}
        }).build();

        // Multiple paths with same value
        Server.builder().annotatedService(new Object() {
            @Get
            @Path("/a")
            @Path("/a")
            public void root(BeanB b) {}
        }).build();

        // Optional is redundant, but we just warn.
        Server.builder().annotatedService(new Object() {
            @Get("/{name}")
            public void root(@Param("name") Optional<String> name) {}
        }).build();

        // @Default and Optional were used together, but we just warn.
        Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Param("name") @Default("a") Optional<String> name) {}
        }).build();

        // @Default is redundant, but we just warn.
        Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Default("a") ServiceRequestContext ctx) {}
        }).build();

        // Optional is redundant, but we just warn.
        Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(Optional<ServiceRequestContext> ctx) {}
        }).build();

        // BeanB would be tried to be converted into an object with a request converter.
        Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(BeanB b) {}
        }).build();
    }

    @Test
    void ofBuiltinRequestConverter() {
        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@RequestObject String value) {}
        }).build();
        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@RequestObject byte[] value) {}
        }).build();
        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@RequestObject JsonNode value) {}
        }).build();
        Server.builder().annotatedService(new Object() {
            @Get("/")
            public void root(@RequestObject HttpData value) {}
        }).build();
    }

    @Test
    void failedOf() {
        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get
            public void root() {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("")
            public void root() {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("  ")
            public void root() {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/{name}")
            public void root(@Param("name") Optional<AnnotatedServiceBuilderTest> name) {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/{name}")
            public void root(@Param("name") List<String> name) {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Param("name") Optional<AnnotatedServiceBuilderTest> name) {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Header("name") List<Object> name) {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(@Header("name") NoDefaultConstructorList<String> name) {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/test")
            @Default("a")
            public void root(ServiceRequestContext ctx) {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/test")
            public void root(BeanA a) {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get
            @Post("/")
            public void root() {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Get("/")
            @Path("/")
            public void root() {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Path("")
            public void root() {}
        }).build()).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Server.builder().annotatedService(new Object() {
            @Path("/")
            public void root() {}
        }).build()).isInstanceOf(IllegalArgumentException.class);
    }

    private static class NoDefaultConstructorList<E> extends ArrayList<E> {
        private static final long serialVersionUID = -4221936122807956661L;

        NoDefaultConstructorList(int initialCapacity) {
            super(initialCapacity);
        }
    }

    private static class DummyExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            return null;
        }
    }

    private static class BeanA {
        private final int valA;
        private final int valB;

        BeanA(@Param("a") int valA, int valB) {
            this.valA = valA;
            this.valB = valB;
        }
    }

    private static class BeanB {
        private final ServiceRequestContext ctx;
        private final int val;

        BeanB(ServiceRequestContext ctx, int val) {
            this.ctx = ctx;
            this.val = val;
        }
    }
}
