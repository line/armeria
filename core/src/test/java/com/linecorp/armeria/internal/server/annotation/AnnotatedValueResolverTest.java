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
package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedBeanFactoryRegistryTest.noopDependencyInjector;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.toArguments;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.toRequestObjectResolvers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.reflections.ReflectionUtils.getAllConstructors;
import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.AggregatedResult;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.NoAnnotatedParameterException;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.RequestObjectResolver;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.ResolverContext;
import com.linecorp.armeria.server.RoutingResult;
import com.linecorp.armeria.server.RoutingResultBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Attribute;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestObject;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

class AnnotatedValueResolverTest {

    private static final Logger logger = LoggerFactory.getLogger(AnnotatedValueResolverTest.class);

    static final List<RequestObjectResolver> objectResolvers =
            toRequestObjectResolvers(ImmutableList.of(), AnnotatedValueResolverTest.class.getMethods()[0]);

    // A string which is the same as the parameter will be returned.
    static final Set<String> pathParams = ImmutableSet.of("var1");
    static final Set<String> existingHttpParameters = ImmutableSet.of("param1",
                                                                      "enum1",
                                                                      "sensitive");
    // These parameters will be present without the values. e.g., ?emptyParam1=&emptyParam2=&...
    static final Set<String> existingWithoutValueParameters = ImmutableSet.of("emptyParam1",
                                                                              "emptyParam2",
                                                                              "emptyParam3",
                                                                              "emptyParam4");
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
    static Map<String, AttributeKey<?>> successExpectAttrKeys;
    static Map<String, AttributeKey<?>> failExpectAttrKeys;

    static {
        final String path = "/";
        final String query = Stream.concat(existingHttpParameters.stream().map(p -> p + '=' + p),
                                           existingWithoutValueParameters.stream().map(p -> p + '='))
                                   .collect(Collectors.joining("&"));

        final RequestHeadersBuilder headers = RequestHeaders.builder(HttpMethod.GET, path + '?' + query);
        headers.set(HttpHeaderNames.COOKIE, "a=1;b=2;c=3;a=4");
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

        resolverContext = new ResolverContext(context, request, AggregatedResult.EMPTY);
    }

    @AfterAll
    static void ensureUnmodifiedHeaders() {
        assertThat(request.headers()).isEqualTo(originalHeaders);
    }

    @BeforeEach
    void injectAttributeKeys() {
        successExpectAttrKeys = injectAttrKeyToServiceContextForAttributeTest();
        failExpectAttrKeys = injectFailCaseOfAttrKeyToServiceContextForAttributeTest();
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

    static boolean httpParameterExistButNoValuePresent(AnnotatedValueResolver element) {
        return existingWithoutValueParameters.contains(element.httpElementName());
    }

    @Test
    void ofMethods() {
        getAllMethods(Service.class,
                      // Jacoco agent injects `private $jacocoInit(..)` method.
                      method -> !Modifier.isPrivate(method.getModifiers())).forEach(method -> {
            try {
                final List<AnnotatedValueResolver> elements = AnnotatedValueResolver.ofServiceMethod(
                        method, pathParams, objectResolvers, false, noopDependencyInjector, null);
                elements.forEach(AnnotatedValueResolverTest::testResolver);
            } catch (NoAnnotatedParameterException ignored) {
                // Ignore this exception because MixedBean class has not annotated method.
            }
        });
    }

    @Test
    void ofFieldBean() throws NoSuchFieldException {
        final FieldBean bean = new FieldBean();

        getAllFields(FieldBean.class).forEach(field -> {
            final AnnotatedValueResolver resolver = AnnotatedValueResolver.ofBeanField(
                    field, pathParams, objectResolvers, noopDependencyInjector);

            if (resolver != null) {
                testResolver(resolver);
                try {
                    field.setAccessible(true);
                    field.set(bean, resolver.resolve(resolverContext));
                } catch (IllegalAccessException e) {
                    throw new Error("should not reach here", e);
                }
            }
        });

        testBean(bean);
    }

    @Test
    void ofConstructorBean() {
        @SuppressWarnings("rawtypes")
        final Set<Constructor> constructors = getAllConstructors(ConstructorBean.class);
        assertThat(constructors.size()).isOne();
        constructors.forEach(constructor -> {
            final List<AnnotatedValueResolver> elements = AnnotatedValueResolver.ofBeanConstructorOrMethod(
                    constructor, pathParams, objectResolvers, noopDependencyInjector);
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
    void ofSetterBean() throws Exception {
        final SetterBean bean = SetterBean.class.getDeclaredConstructor().newInstance();
        getAllMethods(SetterBean.class).forEach(method -> testMethod(method, bean));
        testBean(bean);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void ofMixedBean() throws Exception {
        final Set<Constructor> constructors = getAllConstructors(MixedBean.class);
        assertThat(constructors.size()).isOne();
        final Constructor constructor = Iterables.getFirst(constructors, null);

        final List<AnnotatedValueResolver> initArgs = AnnotatedValueResolver.ofBeanConstructorOrMethod(
                constructor, pathParams, objectResolvers, noopDependencyInjector);
        initArgs.forEach(AnnotatedValueResolverTest::testResolver);
        final MixedBean bean = (MixedBean) constructor.newInstance(toArguments(initArgs, resolverContext));
        getAllMethods(MixedBean.class).forEach(method -> testMethod(method, bean));
        testBean(bean);
    }

    private static <T> void testMethod(Method method, T bean) {
        try {
            final List<AnnotatedValueResolver> elements = AnnotatedValueResolver.ofBeanConstructorOrMethod(
                    method, pathParams, objectResolvers, noopDependencyInjector);
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

        if (resolver.annotationType() == Attribute.class) {
            if (failExpectAttrKeys.containsKey(resolver.httpElementName())) {
                // When + Then
                assertThatThrownBy(() -> resolver.resolve(resolverContext))
                        .isInstanceOfAny(IllegalArgumentException.class,
                                         IllegalStateException.class);
            } else {
                // When
                final Object value = resolver.resolve(resolverContext);
                logger.debug("Element {}: value {}", resolver, value);

                // Then
                final AttributeKey<?> attrKey = successExpectAttrKeys.get(resolver.httpElementName());
                final Object expectedValue = resolverContext.context().attr(attrKey);

                assertThat(value).isEqualTo(expectedValue);
                assertThat(value).isNotNull();
            }
            return;
        }

        // Given Case.
        final Object value = resolver.resolve(resolverContext);
        logger.debug("Element {}: value {}", resolver, value);
        if (resolver.annotationType() == null) {
            assertThat(value).isInstanceOf(resolver.elementType());

            // Check whether 'Cookie' header is decoded correctly.
            if (resolver.elementType() == Cookies.class) {
                final Cookies cookies = (Cookies) value;
                assertThat(cookies).containsExactly(Cookie.ofSecure("a", "1"),
                                                    Cookie.ofSecure("b", "2"),
                                                    Cookie.ofSecure("c", "3"),
                                                    Cookie.ofSecure("a", "4"));
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
                if (resolver.elementType().isEnum()) {
                    testEnum(value, resolver.httpElementName());
                } else if (resolver.shouldWrapValueAsOptional()) {
                    assertThat(value).isEqualTo(Optional.of(resolver.httpElementName()));
                } else {
                    assertThat(value).isEqualTo(resolver.httpElementName());
                }
            } else if (httpParameterExistButNoValuePresent(resolver)) {
                assertThat(resolver.defaultValue()).isNull();
                if (resolver.hasContainer() && List.class.isAssignableFrom(resolver.containerType())) {
                    assertThat(value).isNotNull();
                    assertThat((List<Object>) value).isEmpty();
                } else {
                    if (String.class.isAssignableFrom(resolver.elementType())) {
                        assertThat(value).isEqualTo("");
                    } else {
                        assertThat(value).isNull();
                    }
                }
            } else {
                assertThat(resolver.defaultValue()).isNotNull();
                if (resolver.hasContainer() && List.class.isAssignableFrom(resolver.containerType())) {
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

    static class AttrPrefix {}

    static class Service {
        void method1(@Param String var1,
                     @Param String param1,
                     @Param @Default("1") int param2,
                     @Param @Default("1") List<Integer> param3,
                     @Param @Default String emptyParam1,
                     @Param @Default Integer emptyParam2,
                     @Param @Default List<String> emptyParam3,
                     @Param @Default List<Integer> emptyParam4,
                     @Header List<String> header1,
                     @Header("header1") Optional<List<ValueEnum>> optionalHeader1,
                     @Header String header2,
                     @Header @Default("defaultValue") List<String> header3,
                     @Header @Default("defaultValue") String header4,
                     @Param CaseInsensitiveEnum enum1,
                     @Param @Default("enum2") CaseInsensitiveEnum enum2,
                     @Param("sensitive") CaseSensitiveEnum enum3,
                     @Param("SENSITIVE") @Default("SENSITIVE") CaseSensitiveEnum enum4,
                     @Param @Default("P1Y2M3W4D") Period period,
                     ServiceRequestContext ctx,
                     HttpRequest request,
                     @RequestObject OuterBean outerBean,
                     Cookies cookies) {}

        void dummy1() {}

        void redundant1(@Param @Default("defaultValue") Optional<String> value) {}

        @Get("/r2/:var1")
        void redundant2(@Param @Default("defaultValue") String var1) {}

        @Get("/r3/:var1")
        void redundant3(@Param Optional<String> var1) {}

        @Get("/attribute-test")
        void attributeTest(

                @Attribute(prefix = AttrPrefix.class, value = "successPrefixOtherValuesOfOtherTypeInt")
                int successPrefixOtherValuesOfOtherTypeInt,
                @Attribute("failPrefixNoneValuesOfOtherTypeInt")
                int failPrefixNoneValuesOfOtherTypeInt,
                @Attribute(prefix = Service.class, value = "successPrefixMineValuesOfMineTypeInt")
                int successPrefixMineValuesOfMineTypeInt,
                @Attribute("successPrefixNoneValuesOfMineTypeInt")
                int successPrefixNoneValuesOfMineTypeInt,
                @Attribute("successPrefixNoneValuesOfNoneTypeInt")
                int successPrefixNoneValuesOfNoneTypeInt,
                @Attribute("failPrefixNoneValuesOfNoneTypeInt")
                int failPrefixNoneValuesOfNoneTypeInt,
                @Attribute("failPrefixOtherValuesOfMineTypeInt")
                int failPrefixOtherValuesOfMineTypeInt,
                @Attribute(prefix = Service.class, value = "failPrefixMineValuesOfNoneTypeInt")
                int failPrefixMineValuesOfNoneTypeInt,
                @Attribute(prefix = Service.class, value = "successPrefixMineValuesOfMineTypeCollection")
                List<String> successPrefixMineValuesOfMineTypeCollection,
                @Attribute("successPrefixNoneValuesOfMineTypeCollection")
                List<String> successPrefixNoneValuesOfMineTypeCollection,
                @Attribute("successPrefixNoneValuesOfNoneTypeCollection")
                List<String> successPrefixNoneValuesOfNoneTypeCollection,
                @Attribute("successImmutableListToList")
                List<String> successImmutableListToList,
                @Attribute("successImmutableMapToMap")
                Map<String, String> successImmutableMapToMap,
                @Attribute("successImmutableSetToSet")
                Set<String> successImmutableSetToSet,
                @Attribute("successQueueToQueue")
                Queue<String> successQueueToQueue,
                @Attribute("failCastListToSet")
                Set<String> failCastListToSet
        ) { }

        void time(@Param @Default("PT20.345S") Duration duration,
                  @Param @Default("2007-12-03T10:15:30.00Z") Instant instant,
                  @Param @Default("2007-12-03") LocalDate localDate,
                  @Param @Default("2007-12-03T10:15:30") LocalDateTime localDateTime,
                  @Param @Default("10:15") LocalTime localTime,
                  @Param @Default("2007-12-03T10:15:30+01:00") OffsetDateTime offsetDateTime,
                  @Param @Default("10:15:30+01:00") OffsetTime offsetTime,
                  @Param @Default("P1Y2M3W4D") Period period,
                  @Param @Default("2007-12-03T10:15:30+01:00[Europe/Paris]") ZonedDateTime zonedDateTime,
                  @Param @Default("America/New_York") ZoneId zoneId,
                  @Param @Default("+01:00:00") ZoneOffset zoneOffset) {}
    }

    private static Map<String, AttributeKey<?>> injectFailCaseOfAttrKeyToServiceContextForAttributeTest() {
        final ServiceRequestContext ctx = resolverContext.context();
        final Map<String, AttributeKey<?>> expectFailAttrs = new HashMap<>();

        final AttributeKey<Integer> failPrefixNoneValuesOfOtherTypeInt =
                AttributeKey.valueOf(AttrPrefix.class, "failPrefixNoneValuesOfOtherTypeInt");
        ctx.setAttr(failPrefixNoneValuesOfOtherTypeInt, 10);
        expectFailAttrs.put(
                "failPrefixNoneValuesOfOtherTypeInt",
                failPrefixNoneValuesOfOtherTypeInt);

        final AttributeKey<Integer> failPrefixNoneValuesOfNoneTypeInt =
                AttributeKey.valueOf(AttrPrefix.class, "failPrefixNoneValuesOfNoneTypeInt");
        expectFailAttrs.put(
                "failPrefixNoneValuesOfNoneTypeInt",
                failPrefixNoneValuesOfNoneTypeInt);

        final AttributeKey<Integer> failPrefixMineValuesOfNoneTypeInt =
                AttributeKey.valueOf("failPrefixMineValuesOfNoneTypeInt");
        ctx.setAttr(failPrefixMineValuesOfNoneTypeInt, 10);
        expectFailAttrs.put(
                "failPrefixMineValuesOfNoneTypeInt",
                failPrefixMineValuesOfNoneTypeInt);

        final AttributeKey<Integer> failPrefixOtherValuesOfMineTypeInt =
                AttributeKey.valueOf(AttrPrefix.class, "failPrefixOtherValuesOfMineTypeInt");
        ctx.setAttr(failPrefixOtherValuesOfMineTypeInt, 10);
        expectFailAttrs.put(
                "failPrefixOtherValuesOfMineTypeInt",
                failPrefixOtherValuesOfMineTypeInt);

        final List<String> list1 = ImmutableList.of("A", "B", "C");
        final AttributeKey<List<String>> failCastListToSet =
                AttributeKey.valueOf("failCastListToSet");
        ctx.setAttr(failCastListToSet, list1);
        expectFailAttrs.put(
                "failCastListToSet",
                failCastListToSet);

        return expectFailAttrs;
    }

    private static Map<String, AttributeKey<?>> injectAttrKeyToServiceContextForAttributeTest() {

        final ServiceRequestContext ctx = resolverContext.context();
        final HashMap<String, AttributeKey<?>> successAttrs = new HashMap<>();

        final AttributeKey<Integer> successPrefixOtherValuesOfOtherTypeInt =
                AttributeKey.valueOf(AttrPrefix.class, "successPrefixOtherValuesOfOtherTypeInt");
        ctx.setAttr(successPrefixOtherValuesOfOtherTypeInt, 10);
        successAttrs.put(
                "successPrefixOtherValuesOfOtherTypeInt",
                successPrefixOtherValuesOfOtherTypeInt);

        final AttributeKey<Integer> successPrefixMineValuesOfMineTypeInt =
                AttributeKey.valueOf(Service.class, "successPrefixMineValuesOfMineTypeInt");
        ctx.setAttr(successPrefixMineValuesOfMineTypeInt, 10);
        successAttrs.put(
                "successPrefixMineValuesOfMineTypeInt",
                successPrefixMineValuesOfMineTypeInt);

        final AttributeKey<Integer> successPrefixNoneValuesOfMineTypeInt =
                AttributeKey.valueOf(Service.class, "successPrefixNoneValuesOfMineTypeInt");
        ctx.setAttr(successPrefixNoneValuesOfMineTypeInt, 10);
        successAttrs.put(
                "successPrefixNoneValuesOfMineTypeInt",
                successPrefixNoneValuesOfMineTypeInt);

        final AttributeKey<Integer> successPrefixNoneValuesOfNoneTypeInt =
                AttributeKey.valueOf("successPrefixNoneValuesOfNoneTypeInt");
        ctx.setAttr(successPrefixNoneValuesOfNoneTypeInt, 10);
        successAttrs.put(
                "successPrefixNoneValuesOfNoneTypeInt",
                successPrefixNoneValuesOfNoneTypeInt);

        final AttributeKey<List<String>> successPrefixMineValuesOfMineTypeCollection =
                AttributeKey.valueOf(Service.class, "successPrefixMineValuesOfMineTypeCollection");
        ctx.setAttr(successPrefixMineValuesOfMineTypeCollection, new ArrayList<>());
        successAttrs.put(
                "successPrefixMineValuesOfMineTypeCollection",
                successPrefixMineValuesOfMineTypeCollection);

        final AttributeKey<List<String>> successPrefixNoneValuesOfMineTypeCollection =
                AttributeKey.valueOf(Service.class, "successPrefixNoneValuesOfMineTypeCollection");
        ctx.setAttr(successPrefixNoneValuesOfMineTypeCollection, new ArrayList<>());
        successAttrs.put(
                "successPrefixNoneValuesOfMineTypeCollection",
                successPrefixNoneValuesOfMineTypeCollection);

        final AttributeKey<List<String>> successPrefixNoneValuesOfNoneTypeCollection =
                AttributeKey.valueOf("successPrefixNoneValuesOfNoneTypeCollection");
        ctx.setAttr(successPrefixNoneValuesOfNoneTypeCollection, new ArrayList<>());
        successAttrs.put(
                "successPrefixNoneValuesOfNoneTypeCollection",
                successPrefixNoneValuesOfNoneTypeCollection);

        final List<String> list1 = ImmutableList.of("A", "B", "C");
        final AttributeKey<List<String>> successImmutableListToList =
                AttributeKey.valueOf("successImmutableListToList");
        ctx.setAttr(successImmutableListToList, list1);
        successAttrs.put(
                "successImmutableListToList",
                successImmutableListToList);

        final Map<String, String> map1 = ImmutableMap.of("Key1", "Value1");
        final AttributeKey<Map<String, String>> successImmutableMapToMap =
                AttributeKey.valueOf("successImmutableMapToMap");
        ctx.setAttr(successImmutableMapToMap, map1);
        successAttrs.put(
                "successImmutableMapToMap",
                successImmutableMapToMap);

        final Set<String> set1 = ImmutableSet.of("Value");
        final AttributeKey<Set<String>> successImmutableSetToSet =
                AttributeKey.valueOf("successImmutableSetToSet");
        ctx.setAttr(successImmutableSetToSet, set1);
        successAttrs.put(
                "successImmutableSetToSet",
                successImmutableSetToSet);

        final Queue<String> queue1 = new ConcurrentLinkedQueue<>();
        final AttributeKey<Queue<String>> successQueueToQueue =
                AttributeKey.valueOf("successQueueToQueue");
        ctx.setAttr(successQueueToQueue, queue1);
        successAttrs.put(
                "successQueueToQueue",
                successQueueToQueue);

        return successAttrs;
    }

    interface Bean {
        String var1();

        String param1();

        int param2();

        List<Integer> param3();

        String emptyParam1();

        Integer emptyParam2();

        List<String> emptyParam3();

        List<Integer> emptyParam4();

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

        Duration duration();

        Instant instant();

        LocalDate localDate();

        LocalDateTime localDateTime();

        LocalTime localTime();

        OffsetDateTime offsetDateTime();

        OffsetTime offsetTime();

        Period period();

        ZonedDateTime zonedDateTime();

        ZoneId zoneId();

        ZoneOffset zoneOffset();
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

        @Param
        @Default
        String emptyParam1;

        @Param
        @Default
        Integer emptyParam2;

        @Param
        @Default
        List<String> emptyParam3;

        @Param
        @Default
        List<Integer> emptyParam4;

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

        @Param
        @Default("PT20.345S")
        Duration duration;

        @Param
        @Default("2007-12-03T10:15:30.00Z")
        Instant instant;

        @Param
        @Default("2007-12-03")
        LocalDate localDate;

        @Param
        @Default("2007-12-03T10:15:30")
        LocalDateTime localDateTime;

        @Param
        @Default("10:15")
        LocalTime localTime;

        @Param
        @Default("2007-12-03T10:15:30+01:00")
        OffsetDateTime offsetDateTime;

        @Param
        @Default("10:15:30+01:00")
        OffsetTime offsetTime;

        @Param
        @Default("P1Y2M3W4D")
        Period period;

        @Param
        @Default("2007-12-03T10:15:30+01:00[Europe/Paris]")
        ZonedDateTime zonedDateTime;

        @Param
        @Default("America/New_York")
        ZoneId zoneId;

        @Param
        @Default("+01:00:00")
        ZoneOffset zoneOffset;

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
        public String emptyParam1() {
            return emptyParam1;
        }

        @Override
        public Integer emptyParam2() {
            return emptyParam2;
        }

        @Override
        public List<String> emptyParam3() {
            return emptyParam3;
        }

        @Override
        public List<Integer> emptyParam4() {
            return emptyParam4;
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

        @Override
        public Duration duration() {
            return duration;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public LocalDate localDate() {
            return localDate;
        }

        @Override
        public LocalDateTime localDateTime() {
            return localDateTime;
        }

        @Override
        public LocalTime localTime() {
            return localTime;
        }

        @Override
        public OffsetDateTime offsetDateTime() {
            return offsetDateTime;
        }

        @Override
        public OffsetTime offsetTime() {
            return offsetTime;
        }

        @Override
        public Period period() {
            return period;
        }

        @Override
        public ZonedDateTime zonedDateTime() {
            return zonedDateTime;
        }

        @Override
        public ZoneId zoneId() {
            return zoneId;
        }

        @Override
        public ZoneOffset zoneOffset() {
            return zoneOffset;
        }
    }

    static class ConstructorBean implements Bean {
        final String var1;
        final String param1;
        final int param2;
        final List<Integer> param3;
        final String emptyParam1;
        final Integer emptyParam2;
        final List<String> emptyParam3;
        final List<Integer> emptyParam4;
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
        final Duration duration;
        final Instant instant;
        final LocalDate localDate;
        final LocalDateTime localDateTime;
        final LocalTime localTime;
        final OffsetDateTime offsetDateTime;
        final OffsetTime offsetTime;
        final Period period;
        final ZonedDateTime zonedDateTime;
        final ZoneId zoneId;
        final ZoneOffset zoneOffset;

        ConstructorBean(@Param String var1,
                        @Param String param1,
                        @Param @Default("1") int param2,
                        @Param @Default("1") List<Integer> param3,
                        @Param @Default String emptyParam1,
                        @Param @Default Integer emptyParam2,
                        @Param @Default List<String> emptyParam3,
                        @Param @Default List<Integer> emptyParam4,
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
                        Cookies cookies,
                        @Param @Default("PT20.345S") Duration duration,
                        @Param @Default("2007-12-03T10:15:30.00Z") Instant instant,
                        @Param @Default("2007-12-03") LocalDate localDate,
                        @Param @Default("2007-12-03T10:15:30") LocalDateTime localDateTime,
                        @Param @Default("10:15") LocalTime localTime,
                        @Param @Default("2007-12-03T10:15:30+01:00") OffsetDateTime offsetDateTime,
                        @Param @Default("10:15:30+01:00") OffsetTime offsetTime,
                        @Param @Default("P1Y2M3W4D") Period period,
                        @Param @Default("2007-12-03T10:15:30+01:00[Europe/Paris]") ZonedDateTime zonedDateTime,
                        @Param @Default("America/New_York") ZoneId zoneId,
                        @Param @Default("+01:00:00") ZoneOffset zoneOffset) {
            this.var1 = var1;
            this.param1 = param1;
            this.param2 = param2;
            this.param3 = param3;
            this.emptyParam1 = emptyParam1;
            this.emptyParam2 = emptyParam2;
            this.emptyParam3 = emptyParam3;
            this.emptyParam4 = emptyParam4;
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
            this.duration = duration;
            this.instant = instant;
            this.localDate = localDate;
            this.localDateTime = localDateTime;
            this.localTime = localTime;
            this.offsetDateTime = offsetDateTime;
            this.offsetTime = offsetTime;
            this.period = period;
            this.zonedDateTime = zonedDateTime;
            this.zoneId = zoneId;
            this.zoneOffset = zoneOffset;
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
        public String emptyParam1() {
            return emptyParam1;
        }

        @Override
        public Integer emptyParam2() {
            return emptyParam2;
        }

        @Override
        public List<String> emptyParam3() {
            return emptyParam3;
        }

        @Override
        public List<Integer> emptyParam4() {
            return emptyParam4;
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

        @Override
        public Duration duration() {
            return duration;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public LocalDate localDate() {
            return localDate;
        }

        @Override
        public LocalDateTime localDateTime() {
            return localDateTime;
        }

        @Override
        public LocalTime localTime() {
            return localTime;
        }

        @Override
        public OffsetDateTime offsetDateTime() {
            return offsetDateTime;
        }

        @Override
        public OffsetTime offsetTime() {
            return offsetTime;
        }

        @Override
        public Period period() {
            return period;
        }

        @Override
        public ZonedDateTime zonedDateTime() {
            return zonedDateTime;
        }

        @Override
        public ZoneId zoneId() {
            return zoneId;
        }

        @Override
        public ZoneOffset zoneOffset() {
            return zoneOffset;
        }
    }

    static class SetterBean implements Bean {
        String var1;
        String param1;
        int param2;
        List<Integer> param3;
        String emptyParam1;
        Integer emptyParam2;
        List<String> emptyParam3;
        List<Integer> emptyParam4;
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
        Duration duration;
        Instant instant;
        LocalDate localDate;
        LocalDateTime localDateTime;
        LocalTime localTime;
        OffsetDateTime offsetDateTime;
        OffsetTime offsetTime;
        Period period;
        ZonedDateTime zonedDateTime;
        ZoneId zoneId;
        ZoneOffset zoneOffset;

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

        @Param
        @Default
        void setEmptyParam1(String emptyParam1) {
            this.emptyParam1 = emptyParam1;
        }

        @Param
        @Default
        void setEmptyParam2(Integer emptyParam2) {
            this.emptyParam2 = emptyParam2;
        }

        @Param
        @Default
        void setEmptyParam3(List<String> emptyParam3) {
            this.emptyParam3 = emptyParam3;
        }

        @Param
        @Default
        void setEmptyParam4(List<Integer> emptyParam4) {
            this.emptyParam4 = emptyParam4;
        }

        void setHeader1(@Header List<String> header1) {
            this.header1 = header1;
        }

        void setOptionalHeader1(@Header("header1") Optional<List<ValueEnum>> optionalHeader1) {
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

        public void setDuration(@Param @Default("PT20.345S") Duration duration) {
            this.duration = duration;
        }

        public void setInstant(@Param @Default("2007-12-03T10:15:30.00Z") Instant instant) {
            this.instant = instant;
        }

        public void setLocalDate(@Param @Default("2007-12-03") LocalDate localDate) {
            this.localDate = localDate;
        }

        public void setLocalDateTime(@Param @Default("2007-12-03T10:15:30") LocalDateTime localDateTime) {
            this.localDateTime = localDateTime;
        }

        public void setLocalTime(@Param @Default("10:15") LocalTime localTime) {
            this.localTime = localTime;
        }

        public void setOffsetDateTime(
                @Param @Default("2007-12-03T10:15:30+01:00") OffsetDateTime offsetDateTime) {
            this.offsetDateTime = offsetDateTime;
        }

        public void setOffsetTime(@Param @Default("10:15:30+01:00") OffsetTime offsetTime) {
            this.offsetTime = offsetTime;
        }

        void setPeriod(@Param @Default("P1Y2M3W4D") Period period) {
            this.period = period;
        }

        public void setZonedDateTime(
                @Param @Default("2007-12-03T10:15:30+01:00[Europe/Paris]") ZonedDateTime zonedDateTime) {
            this.zonedDateTime = zonedDateTime;
        }

        public void setZoneId(@Param @Default("America/New_York") ZoneId zoneId) {
            this.zoneId = zoneId;
        }

        public void setZoneOffset(@Param @Default("+01:00:00") ZoneOffset zoneOffset) {
            this.zoneOffset = zoneOffset;
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
        public String emptyParam1() {
            return emptyParam1;
        }

        @Override
        public Integer emptyParam2() {
            return emptyParam2;
        }

        @Override
        public List<String> emptyParam3() {
            return emptyParam3;
        }

        @Override
        public List<Integer> emptyParam4() {
            return emptyParam4;
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

        @Override
        public Duration duration() {
            return duration;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public LocalDate localDate() {
            return localDate;
        }

        @Override
        public LocalDateTime localDateTime() {
            return localDateTime;
        }

        @Override
        public LocalTime localTime() {
            return localTime;
        }

        @Override
        public OffsetDateTime offsetDateTime() {
            return offsetDateTime;
        }

        @Override
        public OffsetTime offsetTime() {
            return offsetTime;
        }

        @Override
        public Period period() {
            return period;
        }

        @Override
        public ZonedDateTime zonedDateTime() {
            return zonedDateTime;
        }

        @Override
        public ZoneId zoneId() {
            return zoneId;
        }

        @Override
        public ZoneOffset zoneOffset() {
            return zoneOffset;
        }
    }

    static class MixedBean implements Bean {
        final String var1;
        final String param1;
        int param2;
        List<Integer> param3;
        String emptyParam1;
        Integer emptyParam2;
        List<String> emptyParam3;
        List<Integer> emptyParam4;
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
        final Duration duration;
        final Instant instant;
        final LocalDate localDate;
        final LocalDateTime localDateTime;
        final LocalTime localTime;
        final OffsetDateTime offsetDateTime;
        final OffsetTime offsetTime;
        final Period period;
        final ZonedDateTime zonedDateTime;
        final ZoneId zoneId;
        final ZoneOffset zoneOffset;

        MixedBean(@Param String var1,
                  @Param String param1,
                  @Param @Default String emptyParam1,
                  @Header List<String> header1,
                  @Param @Default List<String> emptyParam3,
                  @Header("header1") Optional<List<ValueEnum>> optionalHeader1,
                  @Header @Default("defaultValue") List<String> header3,
                  @Param CaseInsensitiveEnum enum1,
                  @Param("sensitive") CaseSensitiveEnum enum3,
                  ServiceRequestContext ctx,
                  @RequestObject OuterBean outerBean,
                  Cookies cookies,
                  @Param @Default("PT20.345S") Duration duration,
                  @Param @Default("2007-12-03T10:15:30.00Z") Instant instant,
                  @Param @Default("2007-12-03") LocalDate localDate,
                  @Param @Default("2007-12-03T10:15:30") LocalDateTime localDateTime,
                  @Param @Default("10:15") LocalTime localTime,
                  @Param @Default("2007-12-03T10:15:30+01:00") OffsetDateTime offsetDateTime,
                  @Param @Default("10:15:30+01:00") OffsetTime offsetTime,
                  @Param @Default("P1Y2M3W4D") Period period,
                  @Param @Default("2007-12-03T10:15:30+01:00[Europe/Paris]") ZonedDateTime zonedDateTime,
                  @Param @Default("America/New_York") ZoneId zoneId,
                  @Param @Default("+01:00:00") ZoneOffset zoneOffset) {
            this.var1 = var1;
            this.param1 = param1;
            this.emptyParam1 = emptyParam1;
            this.emptyParam3 = emptyParam3;
            this.header1 = header1;
            this.optionalHeader1 = optionalHeader1;
            this.header3 = header3;
            this.enum1 = enum1;
            this.enum3 = enum3;
            this.ctx = ctx;
            this.outerBean = outerBean;
            this.cookies = cookies;
            this.duration = duration;
            this.instant = instant;
            this.localDate = localDate;
            this.localDateTime = localDateTime;
            this.localTime = localTime;
            this.offsetDateTime = offsetDateTime;
            this.offsetTime = offsetTime;
            this.period = period;
            this.zonedDateTime = zonedDateTime;
            this.zoneId = zoneId;
            this.zoneOffset = zoneOffset;
        }

        void setParam2(@Param @Default("1") int param2) {
            this.param2 = param2;
        }

        void setParam3(@Param @Default("1") List<Integer> param3) {
            this.param3 = param3;
        }

        void setEmptyParam2(@Param @Default Integer emptyParam2) {
            this.emptyParam2 = emptyParam2;
        }

        void setEmptyParam4(@Param @Default List<Integer> emptyParam4) {
            this.emptyParam4 = emptyParam4;
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
        public String emptyParam1() {
            return emptyParam1;
        }

        @Override
        public Integer emptyParam2() {
            return emptyParam2;
        }

        @Override
        public List<String> emptyParam3() {
            return emptyParam3;
        }

        @Override
        public List<Integer> emptyParam4() {
            return emptyParam4;
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

        @Override
        public Duration duration() {
            return duration;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public LocalDate localDate() {
            return localDate;
        }

        @Override
        public LocalDateTime localDateTime() {
            return localDateTime;
        }

        @Override
        public LocalTime localTime() {
            return localTime;
        }

        @Override
        public OffsetDateTime offsetDateTime() {
            return offsetDateTime;
        }

        @Override
        public OffsetTime offsetTime() {
            return offsetTime;
        }

        @Override
        public Period period() {
            return period;
        }

        @Override
        public ZonedDateTime zonedDateTime() {
            return zonedDateTime;
        }

        @Override
        public ZoneId zoneId() {
            return zoneId;
        }

        @Override
        public ZoneOffset zoneOffset() {
            return zoneOffset;
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
