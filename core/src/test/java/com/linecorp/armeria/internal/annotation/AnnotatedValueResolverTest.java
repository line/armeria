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
package com.linecorp.armeria.internal.annotation;

import static com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.toArguments;
import static com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.toRequestObjectResolvers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.reflections.ReflectionUtils.getAllConstructors;
import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.NoAnnotatedParameterException;
import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.RequestObjectResolver;
import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.ResolverContext;
import com.linecorp.armeria.server.RoutingResult;
import com.linecorp.armeria.server.RoutingResultBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Cookies;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestObject;

import io.netty.util.AsciiString;

public class AnnotatedValueResolverTest {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedValueResolverTest.class);

    static final List<RequestObjectResolver> objectResolvers = toRequestObjectResolvers(ImmutableList.of());

    // A string which is the same as the parameter will be returned.
    static final Set<String> pathParams = ImmutableSet.of("var1");
    static final Set<String> existingHttpParameters = ImmutableSet.of("param1",
                                                                      "enum1",
                                                                      "sensitive");

    // 'headerValues' will be returned.
    static final Set<AsciiString> existingHttpHeaders = ImmutableSet.of(HttpHeaderNames.of("header1"),
                                                                        HttpHeaderNames.of("header2"));
    static final List<String> headerValues = ImmutableList.of("value1",
                                                              "value3",
                                                              "value2");

    static final ResolverContext resolverContext;
    static final ServiceRequestContext context;
    static final HttpRequest request;
    static final RequestHeaders originalHeaders;

    static {
        final String path = "/";
        final String query = existingHttpParameters.stream().map(p -> p + '=' + p)
                                                   .collect(Collectors.joining("&"));

        final RequestHeadersBuilder headers = RequestHeaders.builder(HttpMethod.GET, path + '?' + query);
        headers.set(HttpHeaderNames.COOKIE, "a=1;b=2", "c=3", "a=4");
        existingHttpHeaders.forEach(name -> headers.set(name, headerValues));

        originalHeaders = headers.build();
        request = HttpRequest.of(originalHeaders);

        final RoutingResultBuilder builder = RoutingResult.builder()
                                                         .path(path)
                                                         .query(query);
        pathParams.forEach(param -> builder.rawParam(param, param));

        context = ServiceRequestContext.builder(request)
                                       .routingResult(builder.build())
                                       .build();

        resolverContext = new ResolverContext(context, request, null);
    }

    @AfterClass
    public static void ensureUnmodifiedHeaders() {
        assertThat(request.headers()).isEqualTo(originalHeaders);
    }

    static boolean shouldHttpHeaderExist(AnnotatedValueResolver element) {
        return element.shouldExist() ||
               existingHttpHeaders.contains(HttpHeaderNames.of(element.httpElementName()));
    }

    static boolean shouldHttpParameterExist(AnnotatedValueResolver element) {
        return existingHttpParameters.contains(element.httpElementName());
    }

    static boolean shouldPathVariableExist(AnnotatedValueResolver element) {
        return pathParams.contains(element.httpElementName());
    }

    @Test
    public void ofMethods() {
        getAllMethods(Service.class).forEach(method -> {
            try {
                final List<AnnotatedValueResolver> elements =
                        AnnotatedValueResolver.ofServiceMethod(method, pathParams, objectResolvers);
                elements.forEach(AnnotatedValueResolverTest::testResolver);
            } catch (NoAnnotatedParameterException ignored) {
                // Ignore this exception because MixedBean class has not annotated method.
            }
        });
    }

    @Test
    public void ofFieldBean() throws NoSuchFieldException {
        final FieldBean bean = new FieldBean();

        getAllFields(FieldBean.class).forEach(field -> {
            final Optional<AnnotatedValueResolver> resolver =
                    AnnotatedValueResolver.ofBeanField(field, pathParams, objectResolvers);

            if (resolver.isPresent()) {
                testResolver(resolver.get());
                try {
                    field.setAccessible(true);
                    field.set(bean, resolver.get().resolve(resolverContext));
                } catch (IllegalAccessException e) {
                    throw new Error("should not reach here", e);
                }
            }
        });

        testBean(bean);
    }

    @Test
    public void ofConstructorBean() {
        @SuppressWarnings("rawtypes")
        final Set<Constructor> constructors = getAllConstructors(ConstructorBean.class);
        assertThat(constructors.size()).isOne();
        constructors.forEach(constructor -> {
            final List<AnnotatedValueResolver> elements =
                    AnnotatedValueResolver.ofBeanConstructorOrMethod(constructor, pathParams, objectResolvers);
            elements.forEach(AnnotatedValueResolverTest::testResolver);

            final ConstructorBean bean;
            try {
                // Use mock instance of ResolverContext.
                constructor.setAccessible(true);
                bean = (ConstructorBean) constructor.newInstance(toArguments(elements, resolverContext));
            } catch (Throwable cause) {
                throw new Error("should not reach here", cause);
            }

            testBean(bean);
        });
    }

    @Test
    public void ofSetterBean() throws Exception {
        final SetterBean bean = SetterBean.class.getDeclaredConstructor().newInstance();
        getAllMethods(SetterBean.class).forEach(method -> testMethod(method, bean));
        testBean(bean);
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void ofMixedBean() throws Exception {
        final Set<Constructor> constructors = getAllConstructors(MixedBean.class);
        assertThat(constructors.size()).isOne();
        final Constructor constructor = Iterables.getFirst(constructors, null);

        final List<AnnotatedValueResolver> initArgs =
                AnnotatedValueResolver.ofBeanConstructorOrMethod(constructor, pathParams, objectResolvers);
        initArgs.forEach(AnnotatedValueResolverTest::testResolver);
        final MixedBean bean = (MixedBean) constructor.newInstance(toArguments(initArgs, resolverContext));
        getAllMethods(MixedBean.class).forEach(method -> testMethod(method, bean));
        testBean(bean);
    }

    private static <T> void testMethod(Method method, T bean) {
        try {
            final List<AnnotatedValueResolver> elements =
                    AnnotatedValueResolver.ofBeanConstructorOrMethod(method, pathParams, objectResolvers);
            elements.forEach(AnnotatedValueResolverTest::testResolver);

            method.setAccessible(true);
            method.invoke(bean, toArguments(elements, resolverContext));
        } catch (NoAnnotatedParameterException ignored) {
            // Ignore this exception because MixedBean class has not annotated method.
        } catch (Throwable cause) {
            throw new Error("should not reach here", cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testResolver(AnnotatedValueResolver resolver) {
        final Object value = resolver.resolve(resolverContext);
        logger.debug("Element {}: value {}", resolver, value);
        if (resolver.annotationType() == null) {
            assertThat(value).isInstanceOf(resolver.elementType());

            // Check whether 'Cookie' header is decoded correctly.
            // 'a=4' will be ignored because 'a=1' is already in the set.
            if (resolver.elementType() == Cookies.class) {
                final Cookies cookies = (Cookies) value;
                assertThat(cookies.size()).isEqualTo(3);
                cookies.forEach(cookie -> {
                    if ("a".equals(cookie.name())) {
                        assertThat(cookie.value()).isEqualTo("1");
                    } else if ("b".equals(cookie.name())) {
                        assertThat(cookie.value()).isEqualTo("2");
                    } else if ("c".equals(cookie.name())) {
                        assertThat(cookie.value()).isEqualTo("3");
                    }
                });
            }
            return;
        }

        if (resolver.annotationType() == Header.class) {
            if (!resolver.hasContainer()) {
                if (shouldHttpHeaderExist(resolver)) {
                    // The first element.
                    assertThat(value).isEqualTo("value1");
                } else {
                    assertThat(resolver.defaultValue()).isNotNull();
                    assertThat(value).isEqualTo(resolver.defaultValue());
                }
                return;
            }

            if (shouldHttpHeaderExist(resolver)) {
                if (value instanceof Optional) {
                    // For Enum conversion test, Optional is always Optional<List<ValueEnum>>.
                    final List<ValueEnum> list = ((Optional<List<ValueEnum>>) value).get();

                    // Should be converted to Enum.
                    assertThat(list).hasSameSizeAs(headerValues)
                                    .containsSequence(ValueEnum.VALUE1,
                                                      ValueEnum.VALUE3,
                                                      ValueEnum.VALUE2);
                } else {
                    assertThat((List<String>) value).hasSameSizeAs(headerValues)
                                                    .containsSequence(headerValues);
                }
            } else {
                assertThat(resolver.defaultValue()).isNotNull();
                assertThat((List<String>) value).hasSize(1)
                                                .containsOnly((String) resolver.defaultValue());
            }
            return;
        }

        if (resolver.annotationType() == Param.class) {
            if (shouldHttpParameterExist(resolver) ||
                shouldPathVariableExist(resolver)) {
                assertThat(resolver.httpElementName()).isNotNull();
                if (resolver.elementType().isEnum()) {
                    testEnum(value, resolver.httpElementName());
                } else if (resolver.shouldWrapValueAsOptional()) {
                    assertThat(value).isEqualTo(Optional.of(resolver.httpElementName()));
                } else {
                    assertThat(value).isEqualTo(resolver.httpElementName());
                }
            } else {
                assertThat(resolver.defaultValue()).isNotNull();
                if (resolver.containerType() != null &&
                    Collection.class.isAssignableFrom(resolver.containerType())) {
                    assertThat((List<Object>) value).hasSize(1)
                                                    .containsOnly(resolver.defaultValue());
                    assertThat(((List<Object>) value).get(0).getClass())
                            .isEqualTo(resolver.elementType());
                } else if (resolver.shouldWrapValueAsOptional()) {
                    assertThat(value).isEqualTo(Optional.of(resolver.defaultValue()));
                } else {
                    assertThat(value).isEqualTo(resolver.defaultValue());
                }
            }
            return;
        }

        assertThat(resolver.annotationType()).isEqualTo(RequestObject.class);
    }

    private static void testEnum(Object value, String name) {
        switch (name) {
            case "enum1":
                assertThat(value).isEqualTo(CaseInsensitiveEnum.ENUM1);
                break;
            case "enum2":
                assertThat(value).isEqualTo(CaseInsensitiveEnum.ENUM2);
                break;
            case "sensitive":
                assertThat(value).isEqualTo(CaseSensitiveEnum.sensitive);
                break;
            case "SENSITIVE":
                assertThat(value).isEqualTo(CaseSensitiveEnum.SENSITIVE);
                break;
        }
    }

    private static void testBean(Bean bean) {
        assertThat(bean).isNotNull();
        assertThat(bean.var1()).isEqualTo("var1");
        assertThat(bean.param1()).isEqualTo("param1");
        assertThat(bean.param2()).isOne();
        assertThat(bean.header1()).containsSequence(headerValues);
        assertThat(bean.header2()).isEqualTo("value1");
        assertThat(bean.header3()).containsOnly("defaultValue");
        assertThat(bean.header4()).isEqualTo("defaultValue");
        assertThat(bean.ctx()).isEqualTo(context);
        assertThat(bean.request()).isEqualTo(request);
        assertThat(bean.outerBean()).isNotNull();
        assertThat(bean.outerBean().var1).isEqualTo("var1");

        testInnerBean(bean.outerBean().innerBean1);
        testInnerBean(bean.outerBean().innerBean2);
        testInnerBean(bean.outerBean().innerBean3);
    }

    private static void testInnerBean(InnerBean bean) {
        assertThat(bean).isNotNull();
        assertThat(bean.var1).isEqualTo("var1");
        assertThat(bean.param1).isEqualTo("param1");
        assertThat(bean.header1).containsSequence(headerValues);
    }

    enum CaseInsensitiveEnum {
        ENUM1, ENUM2
    }

    enum CaseSensitiveEnum {
        sensitive, SENSITIVE
    }

    enum ValueEnum {
        VALUE1, VALUE2, VALUE3
    }

    static class Service {
        public void method1(@Param String var1,
                            @Param String param1,
                            @Param @Default("1") int param2,
                            @Param @Default("1") List<Integer> param3,
                            @Header List<String> header1,
                            @Header("header1") Optional<List<ValueEnum>> optionalHeader1,
                            @Header String header2,
                            @Header @Default("defaultValue") List<String> header3,
                            @Header @Default("defaultValue") String header4,
                            @Param CaseInsensitiveEnum enum1,
                            @Param @Default("enum2") CaseInsensitiveEnum enum2,
                            @Param("sensitive") CaseSensitiveEnum enum3,
                            @Param("SENSITIVE") @Default("SENSITIVE") CaseSensitiveEnum enum4,
                            ServiceRequestContext ctx,
                            HttpRequest request,
                            @RequestObject OuterBean outerBean,
                            Cookies cookies) {}

        public void dummy1() {}

        public void redundant1(@Param @Default("defaultValue") Optional<String> value) {}

        @Get("/r2/:var1")
        public void redundant2(@Param @Default("defaultValue") String var1) {}

        @Get("/r3/:var1")
        public void redundant3(@Param Optional<String> var1) {}
    }

    interface Bean {
        String var1();

        String param1();

        int param2();

        List<Integer> param3();

        List<String> header1();

        Optional<List<ValueEnum>> optionalHeader1();

        String header2();

        List<String> header3();

        String header4();

        CaseInsensitiveEnum enum1();

        CaseInsensitiveEnum enum2();

        CaseSensitiveEnum enum3();

        CaseSensitiveEnum enum4();

        ServiceRequestContext ctx();

        HttpRequest request();

        OuterBean outerBean();

        Cookies cookies();
    }

    static class FieldBean implements Bean {
        @Param
        String var1;

        @Param
        String param1;

        @Param
        @Default("1")
        int param2;

        @Param
        @Default("1")
        List<Integer> param3;

        @Header
        List<String> header1;

        @Header("header1")
        Optional<List<ValueEnum>> optionalHeader1;

        @Header
        String header2;

        @Header
        @Default("defaultValue")
        List<String> header3;

        @Header
        @Default("defaultValue")
        String header4;

        @Param
        CaseInsensitiveEnum enum1;

        @Param
        @Default("enum2")
        CaseInsensitiveEnum enum2;

        @Param("sensitive")
        CaseSensitiveEnum enum3;

        @Param("SENSITIVE")
        @Default("SENSITIVE")
        CaseSensitiveEnum enum4;

        ServiceRequestContext ctx;

        HttpRequest request;

        @RequestObject
        OuterBean outerBean;

        Cookies cookies;

        String notInjected1;

        @Override
        public String var1() {
            return var1;
        }

        @Override
        public String param1() {
            return param1;
        }

        @Override
        public int param2() {
            return param2;
        }

        @Override
        public List<Integer> param3() {
            return param3;
        }

        @Override
        public List<String> header1() {
            return header1;
        }

        @Override
        public Optional<List<ValueEnum>> optionalHeader1() {
            return optionalHeader1;
        }

        @Override
        public String header2() {
            return header2;
        }

        @Override
        public List<String> header3() {
            return header3;
        }

        @Override
        public String header4() {
            return header4;
        }

        @Override
        public CaseInsensitiveEnum enum1() {
            return enum1;
        }

        @Override
        public CaseInsensitiveEnum enum2() {
            return enum2;
        }

        @Override
        public CaseSensitiveEnum enum3() {
            return enum3;
        }

        @Override
        public CaseSensitiveEnum enum4() {
            return enum4;
        }

        @Override
        public ServiceRequestContext ctx() {
            return ctx;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public OuterBean outerBean() {
            return outerBean;
        }

        @Override
        public Cookies cookies() {
            return cookies;
        }
    }

    static class ConstructorBean implements Bean {
        final String var1;
        final String param1;
        final int param2;
        final List<Integer> param3;
        final List<String> header1;
        final Optional<List<ValueEnum>> optionalHeader1;
        final String header2;
        final List<String> header3;
        final String header4;
        final CaseInsensitiveEnum enum1;
        final CaseInsensitiveEnum enum2;
        final CaseSensitiveEnum enum3;
        final CaseSensitiveEnum enum4;
        final ServiceRequestContext ctx;
        final HttpRequest request;
        final OuterBean outerBean;
        final Cookies cookies;

        ConstructorBean(@Param String var1,
                        @Param String param1,
                        @Param @Default("1") int param2,
                        @Param @Default("1") List<Integer> param3,
                        @Header List<String> header1,
                        @Header("header1") Optional<List<ValueEnum>> optionalHeader1,
                        @Header String header2,
                        @Header @Default("defaultValue") List<String> header3,
                        @Header @Default("defaultValue") String header4,
                        @Param CaseInsensitiveEnum enum1,
                        @Param @Default("enum2") CaseInsensitiveEnum enum2,
                        @Param("sensitive") CaseSensitiveEnum enum3,
                        @Param("SENSITIVE") @Default("SENSITIVE") CaseSensitiveEnum enum4,
                        ServiceRequestContext ctx,
                        HttpRequest request,
                        @RequestObject OuterBean outerBean,
                        Cookies cookies) {
            this.var1 = var1;
            this.param1 = param1;
            this.param2 = param2;
            this.param3 = param3;
            this.header1 = header1;
            this.optionalHeader1 = optionalHeader1;
            this.header2 = header2;
            this.header3 = header3;
            this.header4 = header4;
            this.enum1 = enum1;
            this.enum2 = enum2;
            this.enum3 = enum3;
            this.enum4 = enum4;
            this.ctx = ctx;
            this.request = request;
            this.outerBean = outerBean;
            this.cookies = cookies;
        }

        @Override
        public String var1() {
            return var1;
        }

        @Override
        public String param1() {
            return param1;
        }

        @Override
        public int param2() {
            return param2;
        }

        @Override
        public List<Integer> param3() {
            return param3;
        }

        @Override
        public List<String> header1() {
            return header1;
        }

        @Override
        public Optional<List<ValueEnum>> optionalHeader1() {
            return optionalHeader1;
        }

        @Override
        public String header2() {
            return header2;
        }

        @Override
        public List<String> header3() {
            return header3;
        }

        @Override
        public String header4() {
            return header4;
        }

        @Override
        public CaseInsensitiveEnum enum1() {
            return enum1;
        }

        @Override
        public CaseInsensitiveEnum enum2() {
            return enum2;
        }

        @Override
        public CaseSensitiveEnum enum3() {
            return enum3;
        }

        @Override
        public CaseSensitiveEnum enum4() {
            return enum4;
        }

        @Override
        public ServiceRequestContext ctx() {
            return ctx;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public OuterBean outerBean() {
            return outerBean;
        }

        @Override
        public Cookies cookies() {
            return cookies;
        }
    }

    static class SetterBean implements Bean {
        String var1;
        String param1;
        int param2;
        List<Integer> param3;
        List<String> header1;
        Optional<List<ValueEnum>> optionalHeader1;
        String header2;
        List<String> header3;
        String header4;
        CaseInsensitiveEnum enum1;
        CaseInsensitiveEnum enum2;
        CaseSensitiveEnum enum3;
        CaseSensitiveEnum enum4;
        ServiceRequestContext ctx;
        HttpRequest request;
        OuterBean outerBean;
        Cookies cookies;

        @Param
        void setVar1(String var1) {
            this.var1 = var1;
        }

        void setParam1(@Param String param1) {
            this.param1 = param1;
        }

        @Param
        @Default("1")
        void setParam2(int param2) {
            this.param2 = param2;
        }

        @Param
        @Default("1")
        void setParam3(List<Integer> param3) {
            this.param3 = param3;
        }

        void setHeader1(@Header List<String> header1) {
            this.header1 = header1;
        }

        public void setOptionalHeader1(@Header("header1") Optional<List<ValueEnum>> optionalHeader1) {
            this.optionalHeader1 = optionalHeader1;
        }

        void setHeader2(@Header String header2) {
            this.header2 = header2;
        }

        void setHeader3(@Header @Default("defaultValue") List<String> header3) {
            this.header3 = header3;
        }

        void setHeader4(@Header @Default("defaultValue") String header4) {
            this.header4 = header4;
        }

        void setEnum1(@Param CaseInsensitiveEnum enum1) {
            this.enum1 = enum1;
        }

        void setEnum2(@Param @Default("enum2") CaseInsensitiveEnum enum2) {
            this.enum2 = enum2;
        }

        void setEnum3(@Param("sensitive") CaseSensitiveEnum enum3) {
            this.enum3 = enum3;
        }

        void setEnum4(@Param("SENSITIVE") @Default("SENSITIVE") CaseSensitiveEnum enum4) {
            this.enum4 = enum4;
        }

        void setCtx(ServiceRequestContext ctx) {
            this.ctx = ctx;
        }

        void setRequest(HttpRequest request) {
            this.request = request;
        }

        void setOuterBean(@RequestObject OuterBean outerBean) {
            this.outerBean = outerBean;
        }

        void setCookies(Cookies cookies) {
            this.cookies = cookies;
        }

        @Override
        public String var1() {
            return var1;
        }

        @Override
        public String param1() {
            return param1;
        }

        @Override
        public int param2() {
            return param2;
        }

        @Override
        public List<Integer> param3() {
            return param3;
        }

        @Override
        public List<String> header1() {
            return header1;
        }

        @Override
        public Optional<List<ValueEnum>> optionalHeader1() {
            return optionalHeader1;
        }

        @Override
        public String header2() {
            return header2;
        }

        @Override
        public List<String> header3() {
            return header3;
        }

        @Override
        public String header4() {
            return header4;
        }

        @Override
        public CaseInsensitiveEnum enum1() {
            return enum1;
        }

        @Override
        public CaseInsensitiveEnum enum2() {
            return enum2;
        }

        @Override
        public CaseSensitiveEnum enum3() {
            return enum3;
        }

        @Override
        public CaseSensitiveEnum enum4() {
            return enum4;
        }

        @Override
        public ServiceRequestContext ctx() {
            return ctx;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public OuterBean outerBean() {
            return outerBean;
        }

        @Override
        public Cookies cookies() {
            return cookies;
        }
    }

    static class MixedBean implements Bean {
        final String var1;
        final String param1;
        int param2;
        List<Integer> param3;
        final List<String> header1;
        final Optional<List<ValueEnum>> optionalHeader1;
        String header2;
        final List<String> header3;
        String header4;
        final CaseInsensitiveEnum enum1;
        CaseInsensitiveEnum enum2;
        final CaseSensitiveEnum enum3;
        CaseSensitiveEnum enum4;
        final ServiceRequestContext ctx;
        HttpRequest request;
        final OuterBean outerBean;
        final Cookies cookies;

        MixedBean(@Param String var1,
                  @Param String param1,
                  @Header List<String> header1,
                  @Header("header1") Optional<List<ValueEnum>> optionalHeader1,
                  @Header @Default("defaultValue") List<String> header3,
                  @Param CaseInsensitiveEnum enum1,
                  @Param("sensitive") CaseSensitiveEnum enum3,
                  ServiceRequestContext ctx,
                  @RequestObject OuterBean outerBean,
                  Cookies cookies) {
            this.var1 = var1;
            this.param1 = param1;
            this.header1 = header1;
            this.optionalHeader1 = optionalHeader1;
            this.header3 = header3;
            this.enum1 = enum1;
            this.enum3 = enum3;
            this.ctx = ctx;
            this.outerBean = outerBean;
            this.cookies = cookies;
        }

        void setParam2(@Param @Default("1") int param2) {
            this.param2 = param2;
        }

        void setParam3(@Param @Default("1") List<Integer> param3) {
            this.param3 = param3;
        }

        void setHeader2(@Header String header2) {
            this.header2 = header2;
        }

        void setHeader4(@Header @Default("defaultValue") String header4) {
            this.header4 = header4;
        }

        void setEnum2(@Param @Default("enum2") CaseInsensitiveEnum enum2) {
            this.enum2 = enum2;
        }

        void setEnum4(@Param("SENSITIVE") @Default("SENSITIVE") CaseSensitiveEnum enum4) {
            this.enum4 = enum4;
        }

        void setRequest(HttpRequest request) {
            this.request = request;
        }

        @Override
        public String var1() {
            return var1;
        }

        @Override
        public String param1() {
            return param1;
        }

        @Override
        public int param2() {
            return param2;
        }

        @Override
        public List<Integer> param3() {
            return param3;
        }

        @Override
        public List<String> header1() {
            return header1;
        }

        @Override
        public Optional<List<ValueEnum>> optionalHeader1() {
            return optionalHeader1;
        }

        @Override
        public String header2() {
            return header2;
        }

        @Override
        public List<String> header3() {
            return header3;
        }

        @Override
        public String header4() {
            return header4;
        }

        @Override
        public CaseInsensitiveEnum enum1() {
            return enum1;
        }

        @Override
        public CaseInsensitiveEnum enum2() {
            return enum2;
        }

        @Override
        public CaseSensitiveEnum enum3() {
            return enum3;
        }

        @Override
        public CaseSensitiveEnum enum4() {
            return enum4;
        }

        @Override
        public ServiceRequestContext ctx() {
            return ctx;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public OuterBean outerBean() {
            return outerBean;
        }

        @Override
        public Cookies cookies() {
            return cookies;
        }
    }

    static class OuterBean {
        @Param
        String var1;

        // constructor
        final InnerBean innerBean1;

        // field
        @RequestObject
        InnerBean innerBean2;

        // setter
        InnerBean innerBean3;

        OuterBean(@RequestObject InnerBean innerBean1) {
            this.innerBean1 = innerBean1;
        }

        void setInnerBean3(@RequestObject InnerBean innerBean3) {
            this.innerBean3 = innerBean3;
        }
    }

    static class InnerBean {
        // constructor
        final String var1;

        // field
        @Param
        String param1;

        // setter
        List<String> header1;

        InnerBean(@Param String var1) {
            this.var1 = var1;
        }

        void setHeader1(@Header List<String> header1) {
            this.header1 = header1;
        }
    }
}
