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
package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.HttpParameters.EMPTY_PARAMETERS;
import static com.linecorp.armeria.internal.DefaultValues.getSpecifiedValue;
import static com.linecorp.armeria.server.AnnotatedElementNameUtil.findName;
import static com.linecorp.armeria.server.AnnotatedHttpServiceTypeUtil.normalizeContainerType;
import static com.linecorp.armeria.server.AnnotatedHttpServiceTypeUtil.stringToType;
import static com.linecorp.armeria.server.AnnotatedHttpServiceTypeUtil.validateElementType;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.server.AnnotatedBeanFactory.BeanFactoryId;
import com.linecorp.armeria.server.annotation.Cookies;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestBean;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.util.AsciiString;

final class AnnotatedValueResolver {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedValueResolver.class);

    private static final Object[] emptyArguments = new Object[0];

    /**
     * Resolver factories for the automatically injectable types.
     */
    private static final Map<Class<?>,
            BiFunction<AnnotatedElement, Class<?>, AnnotatedValueResolver>> autoInjectableTypes =
            new ImmutableMap.Builder<Class<?>,
                    BiFunction<AnnotatedElement, Class<?>, AnnotatedValueResolver>>()
                    .put(RequestContext.class, AnnotatedValueResolver::ofRequestContext)
                    .put(ServiceRequestContext.class, AnnotatedValueResolver::ofRequestContext)
                    .put(Request.class, AnnotatedValueResolver::ofRequest)
                    .put(HttpRequest.class, AnnotatedValueResolver::ofRequest)
                    .put(AggregatedHttpMessage.class, AnnotatedValueResolver::ofAggregatedHttpMessage)
                    .put(HttpParameters.class, AnnotatedValueResolver::ofHttpParameters)
                    .put(Cookies.class, AnnotatedValueResolver::ofCookies)
                    .build();

    /**
     * Returns an array of arguments which are resolved by each {@link AnnotatedValueResolver} of the
     * specified {@code resolvers}.
     */
    static Object[] toArguments(List<AnnotatedValueResolver> resolvers,
                                ResolverContext resolverContext) {
        requireNonNull(resolvers, "resolvers");
        requireNonNull(resolverContext, "resolverContext");
        if (resolvers.isEmpty()) {
            return emptyArguments;
        }
        return resolvers.stream().map(resolver -> resolver.resolve(resolverContext)).toArray();
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Executable}, {@code pathParams}, {@code converters} and {@code applyConverterForNoAnnotation}.
     * The {@link Executable} can be one of {@link Constructor} or {@link Method}.
     */
    static List<AnnotatedValueResolver> of(Executable constructorOrMethod, Set<String> pathParams,
                                           List<RequestConverterFunction> converters,
                                           boolean applyConverterForNoAnnotation) {
        final Parameter[] parameters = constructorOrMethod.getParameters();
        if (parameters.length == 0) {
            throw new NoParameterException(constructorOrMethod.toGenericString());
        }
        //
        // Try to check whether it is an annotated constructor or method first. e.g.
        //
        // @Param
        // void setter(String name) { ... }
        //
        // In this case, we need to retrieve the value of @Param annotation from 'name' parameter,
        // not the constructor or method. Also 'String' type is used for the parameter.
        //
        final Optional<AnnotatedValueResolver> resolver;
        if (isAnnotationPresent(constructorOrMethod)) {
            //
            // Only allow a single parameter on an annotated method. The followings cause an error:
            //
            // @Param
            // void setter(String name, int id, String address) { ... }
            //
            // @Param
            // void setter() { ... }
            //
            if (parameters.length != 1) {
                throw new IllegalArgumentException("Only one parameter is allowed to an annotated method: " +
                                                   constructorOrMethod.toGenericString());
            }
            //
            // Filter out like the following case:
            //
            // @Param
            // void setter(@Header String name) { ... }
            //
            if (isAnnotationPresent(parameters[0])) {
                throw new IllegalArgumentException("Both a method and parameter are annotated: " +
                                                   constructorOrMethod.toGenericString());
            }

            resolver = of(constructorOrMethod,
                          parameters[0], parameters[0].getType(), pathParams, converters,
                          applyConverterForNoAnnotation);
        } else {
            //
            // There's no annotation. So there should be no @Default annotation, too.
            // e.g.
            // @Default("a")
            // void method1(ServiceRequestContext) { ... }
            //
            if (constructorOrMethod.isAnnotationPresent(Default.class)) {
                throw new IllegalArgumentException(
                        '@' + Default.class.getSimpleName() + " is not supported for: " +
                        constructorOrMethod.toGenericString());
            }

            resolver = Optional.empty();
        }

        //
        // If there is no annotation on the constructor or method, try to check whether it has
        // annotated parameters. e.g.
        //
        // void setter1(@Param String name) { ... }
        // void setter2(@Param String name, @Header List<String> xForwardedFor) { ... }
        //
        final List<AnnotatedValueResolver> list =
                resolver.<List<AnnotatedValueResolver>>map(ImmutableList::of).orElseGet(
                        () -> Arrays.stream(parameters)
                                    .map(p -> of(p, pathParams, converters, applyConverterForNoAnnotation))
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .collect(Collectors.toList()));
        if (list.isEmpty()) {
            throw new NoAnnotatedParameterException(constructorOrMethod.toGenericString());
        }
        if (list.size() != parameters.length) {
            throw new NoAnnotatedParameterException("Unsupported parameter exists: " +
                                                    constructorOrMethod.toGenericString());
        }
        return list;
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Parameter}, {@code pathParams}, {@code converters} and {@code applyConverterForNoAnnotation}.
     */
    static Optional<AnnotatedValueResolver> of(Parameter parameter, Set<String> pathParams,
                                               List<RequestConverterFunction> converters,
                                               boolean applyConverterForNoAnnotation) {
        return of(parameter, parameter, parameter.getType(), pathParams, converters,
                  applyConverterForNoAnnotation);
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Field}, {@code pathParams}.
     */
    static Optional<AnnotatedValueResolver> of(Field field, Set<String> pathParams) {
        // 'Field' is only used for converting a bean. So we need to pass 'applyConverterForNoAnnotation'
        // as false.
        return of(field, field, field.getType(), pathParams, ImmutableList.of(), false);
    }

    /**
     * Creates a new {@link AnnotatedValueResolver} instance if the specified {@code annotatedElement} is
     * a component of {@link AnnotatedHttpService}.
     *
     * @param annotatedElement an element which is annotated with a value specifier such as {@link Param} and
     *                         {@link Header}.
     * @param typeElement an element which is used for retrieving its type and name.
     * @param type a type of the given {@link Parameter} or {@link Field}. It is a type of the specified
     *             {@code typeElement} parameter.
     * @param pathParams a set of path variables.
     * @param converters a list of {@link RequestConverterFunction} to be used for generating an object.
     *                   One of the resolver in the list would create the object to be injected.
     * @param applyConverterForNoAnnotation a flag which indicates how to handle an element which does not have
     *                                      any annotation. If it is {@code true}, a list of
     *                                      {@link RequestConverterFunction} would be applied for creating an
     *                                      object for the element. Otherwise, the element would be ignored.
     */
    private static Optional<AnnotatedValueResolver> of(AnnotatedElement annotatedElement,
                                                       AnnotatedElement typeElement, Class<?> type,
                                                       Set<String> pathParams,
                                                       List<RequestConverterFunction> converters,
                                                       boolean applyConverterForNoAnnotation) {
        requireNonNull(annotatedElement, "annotatedElement");
        requireNonNull(typeElement, "typeElement");
        requireNonNull(type, "type");
        requireNonNull(pathParams, "pathParams");
        requireNonNull(converters, "converters");

        final Param param = annotatedElement.getAnnotation(Param.class);
        if (param != null) {
            final String name = findName(param, typeElement);
            if (pathParams.contains(name)) {
                return Optional.of(ofPathVariable(name, annotatedElement, typeElement, type));
            } else {
                return Optional.of(ofHttpParameter(name, annotatedElement, typeElement, type));
            }
        }

        final Header header = annotatedElement.getAnnotation(Header.class);
        if (header != null) {
            return Optional.of(ofHeader(findName(header, typeElement), annotatedElement, typeElement, type));
        }

        if (type == Optional.class) {
            // Warn if Optional<?> is used for the following auto-injectable types:
            //  - RequestContext and ServiceRequestContext
            //  - Request and HttpRequest
            //  - AggregatedHttpMessage
            //  - HttpParameters
            //  - Cookies
            final Type parameterizedType = parameterizedTypeOf(annotatedElement);
            assert parameterizedType instanceof ParameterizedType : String.valueOf(parameterizedType);
            final Type actual = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            final BiFunction<AnnotatedElement, Class<?>,
                    AnnotatedValueResolver> factory = autoInjectableTypes.get(actual);
            if (factory != null) {
                logger.warn("Unnecessary Optional is used at '{}'", typeElement);
                return Optional.of(factory.apply(typeElement, type));
            }
        } else {
            // There should be no '@Default' annotation on 'annotatedElement' if 'annotatedElement' is
            // different from 'typeElement', because it was checked before calling this method.
            // So, 'typeElement' should be used when finding an injectable type because we need to check
            // syntactic errors like below:
            //
            // void method1(@Default("a") ServiceRequestContext ctx) { ... }
            //
            final BiFunction<AnnotatedElement, Class<?>,
                    AnnotatedValueResolver> factory = autoInjectableTypes.get(type);
            if (factory != null) {
                return Optional.of(factory.apply(typeElement, type));
            }
        }

        // For backward compatibility.
        // TODO(hyangtack) This block will be removed once @RequestObject is removed.
        final RequestObject requestObject = annotatedElement.getAnnotation(RequestObject.class);
        if (requestObject != null) {
            final BeanFactoryId beanFactoryId = AnnotatedBeanFactory.register(type, pathParams);
            if (AnnotatedBeanFactory.find(beanFactoryId).isPresent()) {
                // Can convert into a bean.
                return Optional.of(ofRequestBean(annotatedElement, type, pathParams, beanFactoryId));
            } else {
                // May be converted into an object by the request converters.
                return Optional.of(
                        ofRequestConverter(annotatedElement, type,
                                           addFirstConverterIfAvailable(requestObject.value(), converters)));
            }
        }

        final RequestBean requestBean = annotatedElement.getAnnotation(RequestBean.class);
        if (requestBean != null) {
            // In this case, this element must be registered to AnnotatedBeanFactory.
            final BeanFactoryId beanFactoryId = AnnotatedBeanFactory.register(type, pathParams);
            AnnotatedBeanFactory.find(beanFactoryId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Failed to register to " +
                                        AnnotatedBeanFactory.class.getSimpleName() + ": " + annotatedElement));
            return Optional.of(ofRequestBean(annotatedElement, type, pathParams, beanFactoryId));
        }

        final RequestConverter requestConverter = annotatedElement.getAnnotation(RequestConverter.class);
        if (requestConverter != null) {
            // If we are analyzing a service method, we need to deal with an element, which does not have any
            // annotation, as a target to be converted by one of request converters.
            //
            // A user can optionally specify @RequestConverter in order to specify a converter which
            // would be applied first, as follows:
            //
            // @Get("/")
            // public HttpResponse get(@RequestConverter(MyRequestConverter.class) MyRequest myRequest) { ... }
            //
            return Optional.of(
                    ofRequestConverter(annotatedElement, type,
                                       addFirstConverterIfAvailable(requestConverter.value(), converters)));
        }

        // No annotation but it may be converted into an object by the request converters.
        if (applyConverterForNoAnnotation) {
            return Optional.of(ofRequestConverter(annotatedElement, type, converters));
        } else {
            // If we are analyzing a class to prepare a bean conversion, we don't need to analyze an element
            // which is not annotated with one of the following:
            //  - @Param
            //  - @Header
            //  - @RequestBean
            return Optional.empty();
        }
    }

    private static boolean isAnnotationPresent(AnnotatedElement element) {
        return element.isAnnotationPresent(Param.class) ||
               element.isAnnotationPresent(Header.class) ||
               element.isAnnotationPresent(RequestBean.class) ||
               element.isAnnotationPresent(RequestObject.class);
    }

    private static AnnotatedValueResolver ofPathVariable(String name,
                                                         AnnotatedElement annotatedElement,
                                                         AnnotatedElement typeElement, Class<?> type) {
        return builder(annotatedElement, type)
                .annotationType(Param.class)
                .httpElementName(name)
                .typeElement(typeElement)
                .supportOptional(true)
                .pathVariable(true)
                .resolver((resolver, ctx) -> resolver.convert(ctx.context().pathParam(name)))
                .build();
    }

    private static AnnotatedValueResolver ofHttpParameter(String name,
                                                          AnnotatedElement annotatedElement,
                                                          AnnotatedElement typeElement, Class<?> type) {
        return builder(annotatedElement, type)
                .annotationType(Param.class)
                .httpElementName(name)
                .typeElement(typeElement)
                .supportOptional(true)
                .supportDefault(true)
                .supportContainer(true)
                .aggregation(AggregationStrategy.FOR_FORM_DATA)
                .resolver(resolver(ctx -> ctx.httpParameters().getAll(name),
                                   () -> "Cannot resolve a value from HTTP parameter: " + name))
                .build();
    }

    private static AnnotatedValueResolver ofHeader(String name,
                                                   AnnotatedElement annotatedElement,
                                                   AnnotatedElement typeElement, Class<?> type) {
        return builder(annotatedElement, type)
                .annotationType(Header.class)
                .httpElementName(name)
                .typeElement(typeElement)
                .supportOptional(true)
                .supportDefault(true)
                .supportContainer(true)
                .resolver(resolver(
                        ctx -> ctx.request().headers().getAll(AsciiString.of(name)),
                        () -> "Cannot resolve a value from HTTP header: " + name))
                .build();
    }

    private static AnnotatedValueResolver ofRequestBean(AnnotatedElement annotatedElement,
                                                        Class<?> type, Set<String> pathParams,
                                                        BeanFactoryId beanFactoryId) {
        return builder(annotatedElement, type)
                .annotationType(RequestBean.class)
                .aggregation(AggregationStrategy.ALWAYS)
                .resolver((resolver, ctx) -> {
                    try {
                        return AnnotatedBeanFactory
                                .find(beanFactoryId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Not registered bean factory: " + beanFactoryId))
                                .apply(ctx);
                    } catch (Exception cause) {
                        return Exceptions.throwUnsafely(cause);
                    }
                })
                .build();
    }

    private static AnnotatedValueResolver ofRequestConverter(AnnotatedElement annotatedElement, Class<?> type,
                                                             List<RequestConverterFunction> converters) {
        return builder(annotatedElement, type)
                .annotationType(RequestConverter.class)
                .aggregation(AggregationStrategy.ALWAYS)
                // No 'BeanFactoryId' is supplied because this element would not convert to a bean.
                .resolver((resolver, ctx) -> {
                    Object value = null;
                    final AggregatedHttpMessage message = ctx.message();
                    assert message != null : "message";
                    for (final RequestConverterFunction func : converters) {
                        try {
                            value = func.convertRequest(ctx.context(), message, resolver.elementType());
                            break;
                        } catch (FallthroughException ignore) {
                            // Do nothing.
                        } catch (Exception cause) {
                            Exceptions.throwUnsafely(cause);
                        }
                    }
                    if (value != null) {
                        return value;
                    }
                    throw new IllegalArgumentException("No suitable request converter found for: " +
                                                       resolver.elementType().getSimpleName());
                })
                .build();
    }

    private static AnnotatedValueResolver ofRequestContext(AnnotatedElement annotatedElement, Class<?> type) {
        return builder(annotatedElement, type)
                .supportOptional(true)
                .resolver((unused, ctx) -> ctx.context())
                .build();
    }

    private static AnnotatedValueResolver ofRequest(AnnotatedElement annotatedElement, Class<?> type) {
        return builder(annotatedElement, type)
                .supportOptional(true)
                .resolver((unused, ctx) -> ctx.request())
                .build();
    }

    private static AnnotatedValueResolver ofAggregatedHttpMessage(AnnotatedElement annotatedElement,
                                                                  Class<?> type) {
        return builder(annotatedElement, type)
                .supportOptional(true)
                .resolver((unused, ctx) -> ctx.message())
                .aggregation(AggregationStrategy.ALWAYS)
                .build();
    }

    private static AnnotatedValueResolver ofHttpParameters(AnnotatedElement annotatedElement, Class<?> type) {
        return builder(annotatedElement, type)
                .supportOptional(true)
                .resolver((unused, ctx) -> ctx.httpParameters())
                .aggregation(AggregationStrategy.FOR_FORM_DATA)
                .build();
    }

    private static AnnotatedValueResolver ofCookies(AnnotatedElement annotatedElement, Class<?> type) {
        return builder(annotatedElement, type)
                .supportOptional(true)
                .resolver((unused, ctx) -> {
                    final List<String> values = ctx.request().headers().getAll(HttpHeaderNames.COOKIE);
                    if (values.isEmpty()) {
                        return Cookies.copyOf(ImmutableSet.of());
                    }
                    final ImmutableSet.Builder<Cookie> cookies = ImmutableSet.builder();
                    values.stream()
                          .map(ServerCookieDecoder.STRICT::decode)
                          .forEach(cookies::addAll);
                    return Cookies.copyOf(cookies.build());
                })
                .build();
    }

    private static List<RequestConverterFunction> addFirstConverterIfAvailable(
            Class<? extends RequestConverterFunction> clazz, List<RequestConverterFunction> converters) {
        if (clazz == RequestConverterFunction.class) {
            return converters;
        }

        // There is a converter which is specified by a user.
        final RequestConverterFunction first = AnnotatedHttpServiceFactory.getInstance(clazz);
        return ImmutableList.<RequestConverterFunction>builder()
                .add(first)
                .addAll(converters)
                .build();
    }

    /**
     * Returns a collection value resolver which retrieves a list of string from the specified {@code getter}
     * and adds them to the specified collection data type.
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object>
    resolver(Function<ResolverContext, List<String>> getter, Supplier<String> failureMessageSupplier) {
        return (resolver, ctx) -> {
            final List<String> values = getter.apply(ctx);
            if (!resolver.hasContainer()) {
                if (values != null && !values.isEmpty()) {
                    return resolver.convert(values.get(0));
                }
                return resolver.defaultOrException();
            }

            try {
                assert resolver.containerType() != null;
                @SuppressWarnings("unchecked")
                final Collection<Object> resolvedValues =
                        (Collection<Object>) resolver.containerType().getDeclaredConstructor().newInstance();

                // Do not convert value here because the element type is String.
                if (values != null && !values.isEmpty()) {
                    values.stream().map(resolver::convert).forEach(resolvedValues::add);
                } else {
                    final Object defaultValue = resolver.defaultOrException();
                    if (defaultValue != null) {
                        resolvedValues.add(defaultValue);
                    }
                }
                return resolvedValues;
            } catch (Throwable cause) {
                throw new IllegalArgumentException(failureMessageSupplier.get(), cause);
            }
        };
    }

    static boolean isRequestConverterType(@Nullable Class<? extends Annotation> type) {
        return type == RequestConverter.class || type == RequestBean.class;
    }

    private static Type parameterizedTypeOf(AnnotatedElement element) {
        if (element instanceof Parameter) {
            return ((Parameter) element).getParameterizedType();
        }
        if (element instanceof Field) {
            return ((Field) element).getGenericType();
        }
        throw new IllegalArgumentException("Unsupported annotated element: " +
                                           element.getClass().getSimpleName());
    }

    @Nullable
    private final Class<? extends Annotation> annotationType;

    @Nullable
    private final String httpElementName;

    private final boolean isPathVariable;
    private final boolean shouldExist;
    private final boolean shouldWrapValueAsOptional;

    @Nullable
    private final Class<?> containerType;
    private final Class<?> elementType;

    @Nullable
    private final Object defaultValue;

    private final BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver;

    @Nullable
    private final EnumConverter<?> enumConverter;

    private final AggregationStrategy aggregationStrategy;

    private static final ConcurrentMap<Class<?>, EnumConverter<?>> enumConverters = new MapMaker().makeMap();

    private AnnotatedValueResolver(@Nullable Class<? extends Annotation> annotationType,
                                   @Nullable String httpElementName,
                                   boolean isPathVariable, boolean shouldExist,
                                   boolean shouldWrapValueAsOptional,
                                   @Nullable Class<?> containerType, Class<?> elementType,
                                   @Nullable String defaultValue,
                                   BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver,
                                   AggregationStrategy aggregationStrategy) {
        this.annotationType = annotationType;
        this.httpElementName = httpElementName;
        this.isPathVariable = isPathVariable;
        this.shouldExist = shouldExist;
        this.shouldWrapValueAsOptional = shouldWrapValueAsOptional;
        this.elementType = requireNonNull(elementType, "elementType");
        this.containerType = containerType;
        this.resolver = requireNonNull(resolver, "resolver");
        this.aggregationStrategy = requireNonNull(aggregationStrategy, "aggregationStrategy");
        enumConverter = enumConverter(elementType);

        // Must be called after initializing 'enumConverter'.
        this.defaultValue = defaultValue != null ? convert(defaultValue, elementType, enumConverter)
                                                 : null;
    }

    @Nullable
    private static EnumConverter<?> enumConverter(Class<?> elementType) {
        if (!elementType.isEnum()) {
            return null;
        }
        return enumConverters.computeIfAbsent(elementType, newElementType -> {
            logger.debug("Registered an Enum {}", newElementType);
            return new EnumConverter<>(newElementType.asSubclass(Enum.class));
        });
    }

    @Nullable
    Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    @Nullable
    String httpElementName() {
        // Currently, this is non-null only if the element is one of the HTTP path variable,
        // parameter or header.
        return httpElementName;
    }

    boolean isPathVariable() {
        return isPathVariable;
    }

    @VisibleForTesting
    boolean shouldExist() {
        return shouldExist;
    }

    @VisibleForTesting
    boolean shouldWrapValueAsOptional() {
        return shouldWrapValueAsOptional;
    }

    @VisibleForTesting
    @Nullable
    Class<?> containerType() {
        // 'List' or 'Set'
        return containerType;
    }

    @VisibleForTesting
    Class<?> elementType() {
        return elementType;
    }

    @VisibleForTesting
    @Nullable
    Object defaultValue() {
        return defaultValue;
    }

    AggregationStrategy aggregationStrategy() {
        return aggregationStrategy;
    }

    boolean hasContainer() {
        return containerType != null &&
               (List.class.isAssignableFrom(containerType) || Set.class.isAssignableFrom(containerType));
    }

    Object resolve(ResolverContext ctx) {
        final Object resolved = resolver.apply(this, ctx);
        return shouldWrapValueAsOptional ? Optional.ofNullable(resolved)
                                         : resolved;
    }

    private static Object convert(String value, Class<?> elementType,
                                  @Nullable EnumConverter<?> enumConverter) {
        return enumConverter != null ? enumConverter.toEnum(value)
                                     : stringToType(value, elementType);
    }

    @Nullable
    private Object convert(@Nullable String value) {
        if (value == null) {
            return defaultOrException();
        }
        return convert(value, elementType, enumConverter);
    }

    @Nullable
    private Object defaultOrException() {
        if (!shouldExist) {
            // May return 'null' if no default value is specified.
            return defaultValue;
        }
        throw new IllegalArgumentException("Mandatory parameter is missing: " + httpElementName);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("annotationType",
                               annotationType != null ? annotationType.getSimpleName() : "(none)")
                          .add("httpElementName", httpElementName)
                          .add("pathVariable", isPathVariable)
                          .add("shouldExist", shouldExist)
                          .add("shouldWrapValueAsOptional", shouldWrapValueAsOptional)
                          .add("elementType", elementType.getSimpleName())
                          .add("containerType",
                               containerType != null ? containerType.getSimpleName() : "(none)")
                          .add("defaultValue", defaultValue)
                          .add("defaultValueType",
                               defaultValue != null ? defaultValue.getClass().getSimpleName() : "(none)")
                          .add("resolver", resolver)
                          .add("enumConverter", enumConverter)
                          .toString();
    }

    private static Builder builder(AnnotatedElement annotatedElement, Type type) {
        return new Builder(annotatedElement, type);
    }

    private static final class Builder {
        private final AnnotatedElement annotatedElement;
        private final Type type;
        private AnnotatedElement typeElement;
        @Nullable
        private Class<? extends Annotation> annotationType;
        @Nullable
        private String httpElementName;
        private boolean pathVariable;
        private boolean supportContainer;
        private boolean supportOptional;
        private boolean supportDefault;
        @Nullable
        private BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver;
        private AggregationStrategy aggregation = AggregationStrategy.NONE;

        private Builder(AnnotatedElement annotatedElement, Type type) {
            this.annotatedElement = requireNonNull(annotatedElement, "annotatedElement");
            this.type = requireNonNull(type, "type");
            typeElement = annotatedElement;
        }

        /**
         * Sets the annotation type which is one of the following.
         * <ul>
         *     <li>{@link Param}</li>
         *     <li>{@link Header}</li>
         *     <li>{@link RequestBean}</li>
         *     <li>{@link RequestConverter}</li>
         * </ul>
         */
        private Builder annotationType(Class<? extends Annotation> annotationType) {
            assert annotationType == Param.class ||
                   annotationType == Header.class ||
                   annotationType == RequestBean.class ||
                   annotationType == RequestConverter.class : annotationType.getSimpleName();
            this.annotationType = annotationType;
            return this;
        }

        /**
         * Sets a name of the element.
         */
        private Builder httpElementName(String httpElementName) {
            this.httpElementName = httpElementName;
            return this;
        }

        /**
         * Sets whether this element is a path variable.
         */
        private Builder pathVariable(boolean pathVariable) {
            this.pathVariable = pathVariable;
            return this;
        }

        /**
         * Sets whether the value type can be a {@link List} or {@link Set}.
         */
        private Builder supportContainer(boolean supportContainer) {
            this.supportContainer = supportContainer;
            return this;
        }

        /**
         * Sets whether the value type can be wrapped by {@link Optional}.
         */
        private Builder supportOptional(boolean supportOptional) {
            this.supportOptional = supportOptional;
            return this;
        }

        /**
         * Sets whether the element can be annotated with {@link Default} annotation.
         */
        private Builder supportDefault(boolean supportDefault) {
            this.supportDefault = supportDefault;
            return this;
        }

        /**
         * Sets an {@link AnnotatedElement} which is used to infer its type.
         */
        private Builder typeElement(AnnotatedElement typeElement) {
            this.typeElement = typeElement;
            return this;
        }

        /**
         * Sets a value resolver.
         */
        private Builder resolver(BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * Sets an {@link AggregationStrategy} for the element.
         */
        private Builder aggregation(AggregationStrategy aggregation) {
            this.aggregation = aggregation;
            return this;
        }

        private static Entry<Class<?>, Class<?>> resolveTypes(Type parameterizedType, Type type,
                                                              boolean unwrapOptionalType) {
            if (unwrapOptionalType) {
                // Unwrap once again so that a pattern like 'Optional<List<?>>' can be supported.
                assert parameterizedType instanceof ParameterizedType : String.valueOf(parameterizedType);
                parameterizedType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            }

            final Class<?> elementType;
            final Class<?> containerType;
            if (parameterizedType instanceof ParameterizedType) {
                try {
                    elementType =
                            (Class<?>) ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
                } catch (Throwable cause) {
                    throw new IllegalArgumentException("Invalid parameter type: " + parameterizedType, cause);
                }
                containerType = normalizeContainerType(
                        (Class<?>) ((ParameterizedType) parameterizedType).getRawType());
            } else {
                elementType = unwrapOptionalType ? (Class<?>) parameterizedType : (Class<?>) type;
                containerType = null;
            }
            return new SimpleImmutableEntry<>(containerType, validateElementType(elementType));
        }

        private AnnotatedValueResolver build() {
            checkArgument(resolver != null, "'resolver' should be specified");

            // A request converter may produce 'Optional<?>' value. But it is different from supporting
            // 'Optional' type. So if the value would be generated by a request converter,
            // 'shouldWrapValueAsOptional' is always set as 'false'.
            final boolean shouldWrapValueAsOptional = type == Optional.class &&
                                                      !isRequestConverterType(annotationType);

            if (!supportOptional && shouldWrapValueAsOptional) {
                throw new IllegalArgumentException(
                        Optional.class.getSimpleName() + " is not supported for: " +
                        (annotationType != null ? annotationType.getSimpleName()
                                                : type.getTypeName()));
            }

            final boolean shouldExist;
            final String defaultValue;

            final Default aDefault = annotatedElement.getAnnotation(Default.class);
            if (aDefault != null) {
                if (supportDefault) {
                    // Warn unusual usage. e.g. @Param @Default("a") Optional<String> param
                    if (shouldWrapValueAsOptional) {
                        // 'annotatedElement' can be one of constructor, field, method or parameter.
                        // So, it may be printed verbosely but it's okay because it provides where this message
                        // is caused.
                        logger.warn("@{} was used with '{}'. " +
                                    "Optional is redundant because the value is always present.",
                                    Default.class.getSimpleName(), annotatedElement);
                    }

                    shouldExist = false;
                    defaultValue = getSpecifiedValue(aDefault.value()).get();
                } else {
                    // Warn if @Default exists in an unsupported place.
                    final StringBuilder msg = new StringBuilder();
                    msg.append('@');
                    msg.append(Default.class.getSimpleName());
                    msg.append(" is redundant for ");
                    if (pathVariable) {
                        msg.append("path variable '").append(httpElementName).append('\'');
                    } else if (annotationType != null) {
                        msg.append("annotation @").append(annotationType.getSimpleName());
                    } else {
                        msg.append("type '").append(type.getTypeName()).append('\'');
                    }
                    msg.append(" because the value is always present.");
                    logger.warn(msg.toString());

                    shouldExist = !shouldWrapValueAsOptional;
                    // Set the default value to null just like it was not specified.
                    defaultValue = null;
                }
            } else {
                shouldExist = !shouldWrapValueAsOptional;
                // Set the default value to null if it was not specified.
                defaultValue = null;
            }

            if (pathVariable && !shouldExist) {
                logger.warn("Optional is redundant for path variable '{}' because the value is always present.",
                            httpElementName);
            }

            final Entry<Class<?>, Class<?>> types;
            if (annotationType == Param.class || annotationType == Header.class) {
                assert httpElementName != null;

                // The value annotated with @Param or @Header should be converted to the desired type,
                // so the type should be resolved here.
                final Type parameterizedType = parameterizedTypeOf(typeElement);
                types = resolveTypes(parameterizedType, type, shouldWrapValueAsOptional);

                // Currently a container type such as 'List' and 'Set' is allowed to @Header annotation
                // and HTTP parameters specified by @Param annotation.
                if (!supportContainer && types.getKey() != null) {
                    throw new IllegalArgumentException("Unsupported collection type: " + parameterizedType);
                }
            } else if (shouldWrapValueAsOptional) {
                //
                // Here, 'type' can be one of the following types:
                // - Optional<RequestContext> (or Optional<ServiceRequestContext>)
                // - Optional<Request> (or Optional<HttpRequest>)
                // - Optional<AggregatedHttpMessage>
                // - Optional<HttpParameters>
                // - Optional<Cookies>
                //
                final Type actual =
                        ((ParameterizedType) parameterizedTypeOf(typeElement)).getActualTypeArguments()[0];
                types = new SimpleImmutableEntry<>(null, (Class<?>) actual);
            } else {
                assert type.getClass() == Class.class : String.valueOf(type);
                //
                // Here, 'type' should be one of the following types:
                // - RequestContext (or ServiceRequestContext)
                // - Request (or HttpRequest)
                // - AggregatedHttpMessage
                // - HttpParameters
                // - Cookies
                // - User classes which can be converted by request converter
                //
                // So the container type should be 'null'.
                //
                types = new SimpleImmutableEntry<>(null, (Class<?>) type);
            }

            return new AnnotatedValueResolver(annotationType, httpElementName, pathVariable, shouldExist,
                                              shouldWrapValueAsOptional, types.getKey(), types.getValue(),
                                              defaultValue, resolver, aggregation);
        }
    }

    private static boolean isFormData(@Nullable MediaType contentType) {
        return contentType != null && contentType.belongsTo(MediaType.FORM_DATA);
    }

    enum AggregationStrategy {
        NONE, ALWAYS, FOR_FORM_DATA;

        /**
         * Returns whether the request should be aggregated.
         */
        static boolean aggregationRequired(AggregationStrategy strategy, HttpRequest req) {
            requireNonNull(strategy, "strategy");
            switch (strategy) {
                case ALWAYS:
                    return true;
                case FOR_FORM_DATA:
                    return isFormData(req.headers().contentType());
            }
            return false;
        }

        /**
         * Returns {@link AggregationStrategy} which specifies how to aggregate the request
         * for injecting its parameters.
         */
        static AggregationStrategy from(List<AnnotatedValueResolver> resolvers) {
            AggregationStrategy strategy = NONE;
            for (final AnnotatedValueResolver r : resolvers) {
                switch (r.aggregationStrategy()) {
                    case ALWAYS:
                        return ALWAYS;
                    case FOR_FORM_DATA:
                        strategy = FOR_FORM_DATA;
                        break;
                }
            }
            return strategy;
        }
    }

    /**
     * A context which is used while resolving parameter values.
     */
    static class ResolverContext {
        private final ServiceRequestContext context;
        private final HttpRequest request;

        @Nullable
        private final AggregatedHttpMessage message;

        @Nullable
        private volatile HttpParameters httpParameters;

        ResolverContext(ServiceRequestContext context, HttpRequest request,
                        @Nullable AggregatedHttpMessage message) {
            this.context = requireNonNull(context, "context");
            this.request = requireNonNull(request, "request");
            this.message = message;
        }

        ServiceRequestContext context() {
            return context;
        }

        HttpRequest request() {
            return request;
        }

        @Nullable
        AggregatedHttpMessage message() {
            return message;
        }

        HttpParameters httpParameters() {
            HttpParameters result = httpParameters;
            if (result == null) {
                synchronized (this) {
                    result = httpParameters;
                    if (result == null) {
                        httpParameters = result = httpParametersOf(context.query(),
                                                                   request.headers().contentType(),
                                                                   message);
                    }
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("context", context)
                              .add("request", request)
                              .add("message", message)
                              .add("httpParameters", httpParameters)
                              .toString();
        }

        /**
         * Returns a map of parameters decoded from a request.
         *
         * <p>Usually one of a query string of a URI or URL-encoded form data is specified in the request.
         * If both of them exist though, they would be decoded and merged into a parameter map.</p>
         *
         * <p>Names and values of the parameters would be decoded as UTF-8 character set.</p>
         *
         * @see QueryStringDecoder#QueryStringDecoder(String, boolean)
         * @see HttpConstants#DEFAULT_CHARSET
         */
        private static HttpParameters httpParametersOf(@Nullable String query,
                                                       @Nullable MediaType contentType,
                                                       @Nullable AggregatedHttpMessage message) {
            try {
                Map<String, List<String>> parameters = null;
                if (query != null) {
                    parameters = new QueryStringDecoder(query, false).parameters();
                }

                if (message != null && isFormData(contentType)) {
                    // Respect 'charset' attribute of the 'content-type' header if it exists.
                    final String body = message.content().toString(
                            contentType.charset().orElse(StandardCharsets.US_ASCII));
                    if (!body.isEmpty()) {
                        final Map<String, List<String>> p =
                                new QueryStringDecoder(body, false).parameters();
                        if (parameters == null) {
                            parameters = p;
                        } else if (p != null) {
                            parameters.putAll(p);
                        }
                    }
                }

                if (parameters == null || parameters.isEmpty()) {
                    return EMPTY_PARAMETERS;
                }

                return HttpParameters.copyOf(parameters);
            } catch (Exception e) {
                // If we failed to decode the query string, we ignore the exception raised here.
                // A missing parameter might be checked when invoking the annotated method.
                logger.debug("Failed to decode query string: {}", query, e);
                return EMPTY_PARAMETERS;
            }
        }
    }

    private static final class EnumConverter<T extends Enum<T>> {
        private final boolean isCaseSensitiveEnum;

        private final Map<String, T> enumMap;

        /**
         * Creates an instance for the given {@link Enum} class.
         */
        EnumConverter(Class<T> enumClass) {
            final Set<T> enumInstances = EnumSet.allOf(enumClass);
            final Map<String, T> lowerCaseEnumMap = enumInstances.stream().collect(
                    toImmutableMap(e -> Ascii.toLowerCase(e.name()), Function.identity(), (e1, e2) -> e1));
            if (enumInstances.size() != lowerCaseEnumMap.size()) {
                enumMap = enumInstances.stream().collect(toImmutableMap(Enum::name, Function.identity()));
                isCaseSensitiveEnum = true;
            } else {
                enumMap = lowerCaseEnumMap;
                isCaseSensitiveEnum = false;
            }
        }

        /**
         * Returns the {@link Enum} value corresponding to the specified {@code str}.
         */
        T toEnum(String str) {
            final T result = enumMap.get(isCaseSensitiveEnum ? str : Ascii.toLowerCase(str));
            if (result != null) {
                return result;
            }

            throw new IllegalArgumentException(
                    "unknown enum value: " + str + " (expected: " + enumMap.values() + ')');
        }
    }

    /**
     * A subtype of {@link IllegalArgumentException} which is raised when no annotated parameters exist
     * in a constructor or method.
     */
    static class NoAnnotatedParameterException extends IllegalArgumentException {

        private static final long serialVersionUID = -6003890710456747277L;

        NoAnnotatedParameterException(String name) {
            super("No annotated parameters found from: " + name);
        }
    }

    /**
     * A subtype of {@link NoAnnotatedParameterException} which is raised when no parameters exist in
     * a constructor or method.
     */
    static class NoParameterException extends NoAnnotatedParameterException {

        private static final long serialVersionUID = 3390292442571367102L;

        NoParameterException(String name) {
            super("No parameters found from: " + name);
        }
    }
}
