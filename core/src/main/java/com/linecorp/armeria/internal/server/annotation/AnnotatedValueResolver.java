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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedElementNameUtil.findName;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedElementNameUtil.getName;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedElementNameUtil.getNameOrDefault;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedElementNameUtil.toHeaderName;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.findDescription;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceTypeUtil.stringToType;
import static com.linecorp.armeria.internal.server.annotation.DefaultValues.getSpecifiedValue;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Primitives;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.server.FileAggregatedMultipart;
import com.linecorp.armeria.internal.server.annotation.AnnotatedBeanFactoryRegistry.BeanFactoryId;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.Attribute;
import com.linecorp.armeria.server.annotation.ByteArrayRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Delimiter;
import com.linecorp.armeria.server.annotation.FallthroughException;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunctionProvider;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.StringRequestConverterFunction;
import com.linecorp.armeria.server.docs.DescriptionInfo;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.util.AttributeKey;
import scala.concurrent.ExecutionContext;

final class AnnotatedValueResolver {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedValueResolver.class);

    private static final List<RequestConverterFunction> defaultRequestConverterFunctions = ImmutableList.of(
            new JacksonRequestConverterFunction(),
            new StringRequestConverterFunction(),
            new ByteArrayRequestConverterFunction());

    private static final ClassValue<EnumConverter<?>> enumConverters = new ClassValue<EnumConverter<?>>() {
        @Override
        protected EnumConverter<?> computeValue(Class<?> type) {
            logger.debug("Registered an Enum {}", type);
            return new EnumConverter<>(type.asSubclass(Enum.class));
        }
    };

    private static final Object[] emptyArguments = new Object[0];

    private static final List<RequestObjectResolver> defaultRequestObjectResolvers;

    static {
        final ImmutableList.Builder<RequestObjectResolver> builder = ImmutableList.builderWithExpectedSize(4);
        builder.add((resolverContext, expectedResultType, expectedParameterizedResultType, beanFactoryId) -> {
            final AnnotatedBeanFactory<?> factory = AnnotatedBeanFactoryRegistry.find(beanFactoryId);
            if (factory == null) {
                return RequestConverterFunction.fallthrough();
            } else {
                return factory.create(resolverContext);
            }
        });
        for (RequestConverterFunction function : defaultRequestConverterFunctions) {
            builder.add(RequestObjectResolver.of(function));
        }

        defaultRequestObjectResolvers = builder.build();
    }

    static final List<RequestConverterFunctionProvider> requestConverterFunctionProviders =
            ImmutableList.copyOf(ServiceLoader.load(RequestConverterFunctionProvider.class,
                                                    AnnotatedService.class.getClassLoader()));

    static {
        if (!requestConverterFunctionProviders.isEmpty()) {
            logger.debug("Available {}s: {}", RequestConverterFunctionProvider.class.getSimpleName(),
                         requestConverterFunctionProviders);
        }
    }

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
     * Returns a list of {@link RequestObjectResolver} that default request converters are added.
     */
    static List<RequestObjectResolver> toRequestObjectResolvers(
            List<RequestConverterFunction> converters, Method method) {
        final ImmutableList.Builder<RequestObjectResolver> builder = ImmutableList.builder();
        // Wrap every converters received from a user with a default object resolver.
        converters.stream().map(RequestObjectResolver::of).forEach(builder::add);
        if (!requestConverterFunctionProviders.isEmpty()) {
            final ImmutableList<RequestConverterFunction> merged =
                    ImmutableList.<RequestConverterFunction>builder()
                                 .addAll(converters)
                                 .addAll(defaultRequestConverterFunctions)
                                 .build();
            final CompositeRequestConverterFunction composed = new CompositeRequestConverterFunction(merged);
            for (Type type : method.getGenericParameterTypes()) {
                for (RequestConverterFunctionProvider provider : requestConverterFunctionProviders) {
                    final RequestConverterFunction func =
                            provider.createRequestConverterFunction(type, composed);
                    if (func != null) {
                        builder.add(RequestObjectResolver.of(func));
                    }
                }
            }
        }
        builder.addAll(defaultRequestObjectResolvers);
        return builder.build();
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Method}, {@code pathParams} and {@code objectResolvers}.
     */
    static List<AnnotatedValueResolver> ofServiceMethod(Method method, Set<String> pathParams,
                                                        List<RequestObjectResolver> objectResolvers,
                                                        boolean useBlockingExecutor,
                                                        DependencyInjector dependencyInjector,
                                                        @Nullable String queryDelimiter) {
        return of(method, pathParams, objectResolvers, true, true,
                  useBlockingExecutor, dependencyInjector, queryDelimiter);
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@code constructorOrMethod}, {@code pathParams} and {@code objectResolvers}.
     */
    static List<AnnotatedValueResolver> ofBeanConstructorOrMethod(
            Executable constructorOrMethod,
            Set<String> pathParams,
            List<RequestObjectResolver> objectResolvers,
            DependencyInjector dependencyInjector) {
        return of(constructorOrMethod, pathParams, objectResolvers,
                  false, false, false, dependencyInjector, null);
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Field}, {@code pathParams} and {@code objectResolvers}.
     */
    @Nullable
    static AnnotatedValueResolver ofBeanField(Field field, Set<String> pathParams,
                                              List<RequestObjectResolver> objectResolvers,
                                              DependencyInjector dependencyInjector) {
        // 'Field' is only used for converting a bean.
        // So we always need to pass 'implicitRequestObjectAnnotation' as false.
        return of(field, field, field.getType(), pathParams,
                  objectResolvers, false, false, dependencyInjector, null);
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Executable}, {@code pathParams}, {@code objectResolvers} and
     * {@code implicitRequestObjectAnnotation}.
     * The {@link Executable} can be either {@link Constructor} or {@link Method}.
     *
     * @param isServiceMethod {@code true} if the {@code constructorOrMethod} is a service method.
     */
    private static List<AnnotatedValueResolver> of(Executable constructorOrMethod, Set<String> pathParams,
                                                   List<RequestObjectResolver> objectResolvers,
                                                   boolean implicitRequestObjectAnnotation,
                                                   boolean isServiceMethod,
                                                   boolean useBlockingExecutor,
                                                   DependencyInjector dependencyInjector,
                                                   @Nullable String queryDelimiter) {
        final ImmutableList<Parameter> parameters =
                Arrays.stream(constructorOrMethod.getParameters())
                      .filter(it -> !KotlinUtil.isContinuation(it.getType()))
                      .collect(toImmutableList());
        final int parametersSize = parameters.size();
        if (parametersSize == 0) {
            throw new NoParameterException(constructorOrMethod.toGenericString());
        }

        final Parameter headParameter = parameters.get(0);
        //
        // Try to check whether it is an annotated constructor or method first. e.g.
        //
        // @Param
        // void setter(String name) { ... }
        //
        // In this case, we need to retrieve the value of @Param annotation from 'name' parameter,
        // not the constructor or method. Also 'String' type is used for the parameter.
        //
        final AnnotatedValueResolver resolver;
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
            if (parametersSize != 1) {
                throw new IllegalArgumentException("Only one parameter is allowed to an annotated method: " +
                                                   constructorOrMethod.toGenericString());
            }
            //
            // Filter out the cases like the following:
            //
            // @Param
            // void setter(@Header String name) { ... }
            //
            if (isAnnotationPresent(headParameter)) {
                throw new IllegalArgumentException("Both a method and parameter are annotated: " +
                                                   constructorOrMethod.toGenericString());
            }

            resolver = of(constructorOrMethod,
                          headParameter, headParameter.getType(), pathParams, objectResolvers,
                          implicitRequestObjectAnnotation, useBlockingExecutor,
                          dependencyInjector, queryDelimiter);
        } else if (!isServiceMethod && parametersSize == 1 &&
                   !AnnotationUtil.findDeclared(constructorOrMethod, RequestConverter.class).isEmpty()) {
            //
            // Filter out the cases like the following:
            //
            // @RequestConverter(BeanConverter.class)
            // void setter(@Header String name) { ... }
            //
            if (isAnnotationPresent(headParameter)) {
                throw new IllegalArgumentException("Both a method and parameter are annotated: " +
                                                   constructorOrMethod.toGenericString());
            }
            //
            // Implicitly apply @RequestObject for the following case:
            //
            // @RequestConverter(BeanConverter.class)
            // void setter(Bean bean) { ... }
            //
            resolver = of(headParameter, pathParams, objectResolvers, true,
                          useBlockingExecutor, dependencyInjector, queryDelimiter);
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

            resolver = null;
        }
        //
        // If there is no annotation on the constructor or method, try to check whether it has
        // annotated parameters. e.g.
        //
        // void setter1(@Param String name) { ... }
        // void setter2(@Param String name, @Header List<String> xForwardedFor) { ... }
        //
        final List<AnnotatedValueResolver> list;
        if (resolver != null) {
            list = ImmutableList.of(resolver);
        } else {
            list = parameters.stream()
                             .map(p -> of(p, pathParams, objectResolvers, implicitRequestObjectAnnotation,
                                          useBlockingExecutor, dependencyInjector, queryDelimiter))
                             .filter(Objects::nonNull)
                             .collect(toImmutableList());
        }

        if (list.isEmpty()) {
            throw new NoAnnotatedParameterException(constructorOrMethod.toGenericString());
        }

        if (list.size() != parametersSize) {
            // There are parameters which cannot be resolved, so we cannot accept this constructor or method
            // as an annotated bean or method. We handle this case in two ways as follows.
            if (list.stream().anyMatch(r -> r.annotationType() != null)) {
                // If a user specify one of @Param, @Header or @RequestObject on the parameter list,
                // it clearly means that the user wants to convert the parameter into a bean. e.g.
                //
                // class BeanA {
                //     ...
                //     BeanA(@Param("a") int a, int b) { ... }
                // }
                throw new IllegalArgumentException("Unsupported parameter exists: " +
                                                   constructorOrMethod.toGenericString());
            } else {
                // But for the automatically injected types such as RequestContext and HttpRequest, etc.
                // it is not easy to understand what a user intends for. So we ignore that.
                //
                // class BeanB {
                //     ...
                //     BeanB(ServiceRequestContext ctx, int b) { ... }
                // }
                throw new NoAnnotatedParameterException("Unsupported parameter exists: " +
                                                        constructorOrMethod.toGenericString());
            }
        }
        //
        // If there are annotations used more than once on the constructor or method, warn it.
        //
        // class RequestBean {
        //     RequestBean(@Param("serialNo") Long serialNo, @Param("serialNo") Long serialNo2) { ... }
        // }
        //
        // or
        //
        // void setter(@Param("serialNo") Long serialNo, @Param("serialNo") Long serialNo2) { ... }
        //
        warnOnDuplicateResolver(constructorOrMethod, list);
        return list;
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Parameter}, {@code pathParams}, {@code objectResolvers} and
     * {@code implicitRequestObjectAnnotation}.
     */
    @Nullable
    static AnnotatedValueResolver of(Parameter parameter, Set<String> pathParams,
                                     List<RequestObjectResolver> objectResolvers,
                                     boolean implicitRequestObjectAnnotation,
                                     boolean useBlockingExecutor,
                                     DependencyInjector dependencyInjector,
                                     @Nullable String queryDelimiter) {
        return of(parameter, parameter, parameter.getType(), pathParams, objectResolvers,
                  implicitRequestObjectAnnotation, useBlockingExecutor, dependencyInjector, queryDelimiter);
    }

    /**
     * Creates a new {@link AnnotatedValueResolver} instance if the specified {@code annotatedElement} is
     * a component of {@link AnnotatedService}.
     *
     * @param annotatedElement an element which is annotated with a value specifier such as {@link Param} and
     *                         {@link Header}.
     * @param typeElement      an element which is used for retrieving its type and name.
     * @param type             a type of the given {@link Parameter} or {@link Field}. It is a type of
     *                         the specified {@code typeElement} parameter.
     * @param pathParams       a set of path variables.
     * @param objectResolvers  a list of {@link RequestObjectResolver} to be evaluated for the objects which
     *                         are annotated with {@link RequestObject} annotation.
     * @param implicitRequestObjectAnnotation {@code true} if an element is always treated like it is annotated
     *                                        with {@link RequestObject} so that conversion is always done.
     *                                        {@code false} if an element has to be annotated with
     *                                        {@link RequestObject} explicitly to get converted.
     * @param useBlockingExecutor whether to use blocking task executor
     */
    @Nullable
    private static AnnotatedValueResolver of(AnnotatedElement annotatedElement,
                                             AnnotatedElement typeElement, Class<?> type,
                                             Set<String> pathParams,
                                             List<RequestObjectResolver> objectResolvers,
                                             boolean implicitRequestObjectAnnotation,
                                             boolean useBlockingExecutor,
                                             DependencyInjector dependencyInjector,
                                             @Nullable String queryDelimiter) {
        requireNonNull(annotatedElement, "annotatedElement");
        requireNonNull(typeElement, "typeElement");
        requireNonNull(type, "type");
        requireNonNull(pathParams, "pathParams");
        requireNonNull(objectResolvers, "objectResolvers");
        requireNonNull(dependencyInjector, "dependencyInjector");

        final DescriptionInfo description = findDescription(annotatedElement);

        final Attribute attr = annotatedElement.getAnnotation(Attribute.class);
        if (attr != null) {
            final String name = findName(typeElement, attr.value());
            return ofAttribute(name, attr, annotatedElement, typeElement, type, description);
        }

        final Param param = annotatedElement.getAnnotation(Param.class);
        if (param != null) {
            final String name = findName(typeElement, param.value());
            if (type == File.class || type == Path.class || type == MultipartFile.class) {
                return ofFileParam(name, annotatedElement, typeElement, type, description);
            }
            if (pathParams.contains(name)) {
                return ofPathVariable(name, annotatedElement, typeElement, type, description);
            } else {
                return ofQueryParam(name, annotatedElement, typeElement, type, description, queryDelimiter);
            }
        }

        final Header header = annotatedElement.getAnnotation(Header.class);
        if (header != null) {
            final String name = toHeaderName(findName(typeElement, header.value()));
            return ofHeader(name, annotatedElement, typeElement, type, description);
        }

        final String name = getNameOrDefault(typeElement, type.getName());
        final RequestObject requestObject = annotatedElement.getAnnotation(RequestObject.class);
        if (requestObject != null) {
            // Find more request converters from a field or parameter.
            final List<RequestConverter> converters =
                    AnnotationUtil.findDeclared(typeElement, RequestConverter.class);
            return ofRequestObject(name, annotatedElement, type, pathParams,
                                   addToFirstIfExists(objectResolvers, converters, dependencyInjector),
                                   dependencyInjector, description);
        }

        // There should be no '@Default' annotation on 'annotatedElement' if 'annotatedElement' is
        // different from 'typeElement', because it was checked before calling this method.
        // So, 'typeElement' should be used when finding an injectable type because we need to check
        // syntactic errors like below:
        //
        // void method1(@Default("a") ServiceRequestContext ctx) { ... }
        //
        final AnnotatedValueResolver resolver = ofInjectableTypes(name, typeElement, type, useBlockingExecutor);
        if (resolver != null) {
            return resolver;
        }

        final List<RequestConverter> converters =
                AnnotationUtil.findDeclared(typeElement, RequestConverter.class);
        if (!converters.isEmpty()) {
            // Apply @RequestObject implicitly when a @RequestConverter is specified.
            return ofRequestObject(name, annotatedElement, type, pathParams,
                                   addToFirstIfExists(objectResolvers, converters, dependencyInjector),
                                   dependencyInjector, description);
        }

        if (implicitRequestObjectAnnotation) {
            return ofRequestObject(name, annotatedElement, type, pathParams, objectResolvers,
                                   dependencyInjector, description);
        }

        return null;
    }

    static List<RequestObjectResolver> addToFirstIfExists(List<RequestObjectResolver> resolvers,
                                                          List<RequestConverter> converters,
                                                          DependencyInjector dependencyInjector) {
        if (converters.isEmpty()) {
            return resolvers;
        }

        final ImmutableList.Builder<RequestObjectResolver> builder = new ImmutableList.Builder<>();
        converters.forEach(c -> builder.add(RequestObjectResolver.of(
                AnnotatedObjectFactory.getInstance(c, RequestConverterFunction.class,
                                                   dependencyInjector))));
        builder.addAll(resolvers);
        return builder.build();
    }

    private static boolean isAnnotationPresent(AnnotatedElement element) {
        return element.isAnnotationPresent(Param.class) ||
               element.isAnnotationPresent(Attribute.class) ||
               element.isAnnotationPresent(Header.class) ||
               element.isAnnotationPresent(RequestObject.class);
    }

    private static void warnOnDuplicateResolver(Executable constructorOrMethod,
                                                List<AnnotatedValueResolver> list) {
        final Set<AnnotatedValueResolver> uniques = AnnotatedBeanFactoryRegistry.uniqueResolverSet();
        list.forEach(element -> {
            if (!uniques.add(element)) {
                AnnotatedBeanFactoryRegistry.warnDuplicateResolver(
                        element, constructorOrMethod.toGenericString());
            }
        });
    }

    private static AnnotatedValueResolver ofPathVariable(String name,
                                                         AnnotatedElement annotatedElement,
                                                         AnnotatedElement typeElement, Class<?> type,
                                                         DescriptionInfo description) {
        return new Builder(annotatedElement, type, name)
                .annotationType(Param.class)
                .typeElement(typeElement)
                .pathVariable(true)
                .description(description)
                .resolver(resolver(ctx -> ctx.context().pathParam(name)))
                .build();
    }

    private static AnnotatedValueResolver ofQueryParam(String name,
                                                       AnnotatedElement annotatedElement,
                                                       AnnotatedElement typeElement, Class<?> type,
                                                       DescriptionInfo description,
                                                       @Nullable String serviceQueryDelimiter) {
        String queryDelimiter = serviceQueryDelimiter;
        final Delimiter delimiter = annotatedElement.getAnnotation(Delimiter.class);
        if (delimiter != null) {
            if (DefaultValues.isSpecified(delimiter.value())) {
                queryDelimiter = delimiter.value();
            }
        }
        return new Builder(annotatedElement, type, name)
                .annotationType(Param.class)
                .typeElement(typeElement)
                .supportDefault(true)
                .supportContainer(true)
                .description(description)
                .aggregation(AggregationStrategy.FOR_FORM_DATA)
                .resolver(resolver(ctx -> ctx.queryParams().getAll(name),
                                   () -> "Cannot resolve a value from a query parameter: " + name,
                                   queryDelimiter))
                .build();
    }

    private static AnnotatedValueResolver ofFileParam(String name,
                                                      AnnotatedElement annotatedElement,
                                                      AnnotatedElement typeElement, Class<?> type,
                                                      DescriptionInfo description) {
        return new Builder(annotatedElement, type, name)
                .annotationType(Param.class)
                .typeElement(typeElement)
                .supportContainer(true)
                .description(description)
                .aggregation(AggregationStrategy.ALWAYS)
                .resolver(fileResolver())
                .build();
    }

    private static AnnotatedValueResolver ofHeader(String name,
                                                   AnnotatedElement annotatedElement,
                                                   AnnotatedElement typeElement, Class<?> type,
                                                   DescriptionInfo description) {
        return new Builder(annotatedElement, type, name)
                .annotationType(Header.class)
                .typeElement(typeElement)
                .supportDefault(true)
                .supportContainer(true)
                .description(description)
                .resolver(resolver(ctx -> ctx.request().headers().getAll(HttpHeaderNames.of(name)),
                                   () -> "Cannot resolve a value from HTTP header: " + name,
                                   null))
                .build();
    }

    private static AnnotatedValueResolver ofRequestObject(String name, AnnotatedElement annotatedElement,
                                                          Class<?> type, Set<String> pathParams,
                                                          List<RequestObjectResolver> objectResolvers,
                                                          DependencyInjector dependencyInjector,
                                                          DescriptionInfo description) {
        // To do recursive resolution like a bean inside another bean, the original object resolvers should
        // be passed into the AnnotatedBeanFactoryRegistry#register.
        final BeanFactoryId beanFactoryId = AnnotatedBeanFactoryRegistry.register(
                type, pathParams, objectResolvers, dependencyInjector);
        return new Builder(annotatedElement, type, name)
                .annotationType(RequestObject.class)
                .description(description)
                .aggregation(AggregationStrategy.ALWAYS)
                .resolver(resolver(objectResolvers, beanFactoryId))
                .beanFactoryId(beanFactoryId)
                .build();
    }

    private static AnnotatedValueResolver ofAttribute(String name,
                                                      Attribute attr,
                                                      AnnotatedElement annotatedElement,
                                                      AnnotatedElement typeElement, Class<?> type,
                                                      DescriptionInfo description) {

        final ImmutableList.Builder<AttributeKey<?>> builder = ImmutableList.builder();

        if (attr.prefix() != Attribute.class) {
            builder.add(AttributeKey.valueOf(attr.prefix(), name));
        } else {
            final Class<?> serviceClass = ((Parameter) annotatedElement).getDeclaringExecutable()
                                                                        .getDeclaringClass();
            builder.add(AttributeKey.valueOf(serviceClass, name));
            builder.add(AttributeKey.valueOf(name));
        }

        final ImmutableList<AttributeKey<?>> attrKeys = builder.build();
        return new Builder(annotatedElement, type, name)
                .annotationType(Attribute.class)
                .typeElement(typeElement)
                .supportDefault(true)
                .supportContainer(true)
                .description(description)
                .resolver(attributeResolver(attrKeys))
                .build();
    }

    @Nullable
    private static AnnotatedValueResolver ofInjectableTypes(String name, AnnotatedElement annotatedElement,
                                                            Class<?> type, boolean useBlockingExecutor) {
        // Unwrap Optional type to support a parameter like 'Optional<RequestContext> ctx'
        // which is always non-empty.
        if (type != Optional.class) {
            return ofInjectableTypes0(name, annotatedElement, type, type, useBlockingExecutor);
        }

        final Type actual =
                ((ParameterizedType) parameterizedTypeOf(annotatedElement)).getActualTypeArguments()[0];
        final AnnotatedValueResolver resolver =
                ofInjectableTypes0(name, annotatedElement, type, actual, useBlockingExecutor);
        if (resolver != null) {
            logger.warn("Unnecessary Optional is used at '{}'", annotatedElement);
        }
        return resolver;
    }

    @Nullable
    private static AnnotatedValueResolver ofInjectableTypes0(String name, AnnotatedElement annotatedElement,
                                                             Class<?> type, Type actual,
                                                             boolean useBlockingExecutor) {
        if (actual == RequestContext.class || actual == ServiceRequestContext.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> ctx.context())
                    .build();
        }

        if (actual == Request.class || actual == HttpRequest.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> ctx.request())
                    .build();
        }

        if (actual == HttpHeaders.class || actual == RequestHeaders.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> ctx.request().headers())
                    .build();
        }

        if (actual == AggregatedHttpRequest.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> ctx.aggregatedRequest())
                    .aggregation(AggregationStrategy.ALWAYS)
                    .build();
        }

        if (actual == QueryParams.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> ctx.queryParams())
                    .aggregation(AggregationStrategy.FOR_FORM_DATA)
                    .build();
        }

        if (actual == Multipart.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> Multipart.from(ctx.request()))
                    .build();
        }

        if (actual == MultipartFile.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> {
                        final String filename = getName(annotatedElement);
                        return Iterables.getFirst(ctx.aggregatedMultipart().files().get(filename), null);
                    })
                    .aggregation(AggregationStrategy.ALWAYS)
                    .build();
        }

        if (actual == Cookies.class) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> {
                        final String value = ctx.request().headers().get(HttpHeaderNames.COOKIE);
                        if (value == null) {
                            return Cookies.of();
                        }
                        return Cookie.fromCookieHeader(value);
                    })
                    .build();
        }

        if (actual instanceof Class && ScalaUtil.isExecutionContext((Class<?>) actual)) {
            return new Builder(annotatedElement, type, name)
                    .resolver((unused, ctx) -> {
                        if (useBlockingExecutor) {
                            return ExecutionContext.fromExecutorService(ctx.context().blockingTaskExecutor());
                        } else {
                            return ExecutionContext.fromExecutorService(ctx.context().eventLoop());
                        }
                    })
                    .build();
        }

        // Unsupported type.
        return null;
    }

    /**
     * Returns a single value resolver which retrieves a value from the specified {@code getter}
     * and converts it.
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object>
    resolver(Function<ResolverContext, String> getter) {
        return (resolver, ctx) -> resolver.convert(getter.apply(ctx));
    }

    /**
     * Returns a collection value resolver which retrieves a list of string from the specified {@code getter}
     * and adds them to the specified collection data type.
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object>
    resolver(Function<ResolverContext, List<String>> getter, Supplier<String> failureMessageSupplier,
             @Nullable String queryDelimiter) {
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
                    if (values.size() == 1 && values.get(0).isEmpty()) {
                        return resolvedValues;
                    }
                    if (queryDelimiter != null && values.size() == 1) {
                        final String first = values.get(0);
                        Splitter.on(queryDelimiter)
                                .splitToStream(first)
                                .map(resolver::convert)
                                .forEach(resolvedValues::add);
                    } else {
                        values.stream().map(resolver::convert).forEach(resolvedValues::add);
                    }
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

    /**
     * Returns a bean resolver which retrieves a value using request converters. If the target element
     * is an annotated bean, a bean factory of the specified {@link BeanFactoryId} will be used for creating an
     * instance.
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object>
    resolver(List<RequestObjectResolver> objectResolvers, BeanFactoryId beanFactoryId) {
        return (resolver, ctx) -> {
            boolean found = false;
            Object value = null;
            for (final RequestObjectResolver objectResolver : objectResolvers) {
                try {
                    value = objectResolver.convert(ctx, resolver.elementType(),
                                                   resolver.parameterizedElementType(), beanFactoryId);
                    found = true;
                    break;
                } catch (FallthroughException ignore) {
                    // Do nothing.
                } catch (Throwable cause) {
                    Exceptions.throwUnsafely(cause);
                }
            }

            if (!found) {
                throw new IllegalArgumentException(
                        "No suitable request converter found for a @" +
                        RequestObject.class.getSimpleName() + " '" +
                        resolver.elementType().getSimpleName() + '\'');
            }

            if (value == null && resolver.shouldExist()) {
                throw new IllegalArgumentException(
                        "A request converter converted the request into null, but the injection target " +
                        "is neither an Optional nor annotated with @Nullable");
            }

            return value;
        };
    }

    /**
     * Returns an attribute resolver which retrieves a value specified by {@code attrKeys}
     * from the {@link RequestContext}.
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object>
    attributeResolver(Iterable<AttributeKey<?>> attrKeys) {
        return (resolver, ctx) -> {
            Class<?> targetType = resolver.rawType();
            if (targetType.isPrimitive()) {
                targetType = Primitives.wrap(targetType);
            }

            for (AttributeKey<?> attrKey : attrKeys) {
                final Object value = ctx.context.attr(attrKey);
                if (value != null) {
                    if (!targetType.isInstance(value)) {
                        throw new IllegalStateException(
                                String.format("Cannot inject the attribute '%s' due to " +
                                              "mismatching type (expected: %s, actual: %s)",
                                              attrKey.name(),
                                              targetType.getName(),
                                              value.getClass().getName()));
                    }
                    return value;
                }
            }
            return resolver.defaultOrException();
        };
    }

    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object> fileResolver() {
        return (resolver, ctx) -> {
            final FileAggregatedMultipart fileAggregatedMultipart = ctx.aggregatedMultipart();
            if (fileAggregatedMultipart == null) {
                return resolver.defaultOrException();
            }
            final Function<? super MultipartFile, Object> mapper;
            final Class<?> elementType = resolver.elementType();
            if (elementType == File.class) {
                mapper = MultipartFile::file;
            } else if (elementType == MultipartFile.class) {
                mapper = Function.identity();
            } else {
                assert elementType == Path.class;
                mapper = multipartFile -> multipartFile.file().toPath();
            }
            final String name = resolver.httpElementName();
            final List<MultipartFile> values = fileAggregatedMultipart.files().get(name);
            if (!resolver.hasContainer()) {
                if (values != null && !values.isEmpty()) {
                    return mapper.apply(values.get(0));
                }
                return resolver.defaultOrException();
            }

            try {
                assert resolver.containerType() != null;
                @SuppressWarnings("unchecked")
                final Collection<Object> resolvedValues =
                        (Collection<Object>) resolver.containerType().getDeclaredConstructor().newInstance();

                if (values != null && !values.isEmpty()) {
                    values.stream().map(mapper::apply).forEach(resolvedValues::add);
                } else {
                    final Object defaultValue = resolver.defaultOrException();
                    if (defaultValue != null) {
                        resolvedValues.add(defaultValue);
                    }
                }
                return resolvedValues;
            } catch (Throwable cause) {
                throw new IllegalArgumentException("Cannot resolve a file param: " + name, cause);
            }
        };
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

    static boolean isAnnotatedNullable(AnnotatedElement annotatedElement) {
        for (Annotation a : annotatedElement.getAnnotations()) {
            final String annotationTypeName = a.annotationType().getName();
            if (annotationTypeName.endsWith(".Nullable")) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private final Class<? extends Annotation> annotationType;

    private final String httpElementName;

    private final boolean isPathVariable;
    private final boolean shouldExist;
    private final boolean shouldWrapValueAsOptional;

    @Nullable
    private final Class<?> containerType;
    private final Class<?> rawType;
    private final Class<?> elementType;
    @Nullable
    private final ParameterizedType parameterizedElementType;

    @Nullable
    private final Object defaultValue;

    private final DescriptionInfo description;

    private final BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver;

    @Nullable
    private final EnumConverter<?> enumConverter;

    @Nullable
    private final BeanFactoryId beanFactoryId;

    private final AggregationStrategy aggregationStrategy;

    private AnnotatedValueResolver(@Nullable Class<? extends Annotation> annotationType,
                                   String httpElementName,
                                   boolean isPathVariable, boolean shouldExist,
                                   boolean shouldWrapValueAsOptional,
                                   @Nullable Class<?> containerType, Class<?> elementType,
                                   Class<?> rawType,
                                   @Nullable ParameterizedType parameterizedElementType,
                                   @Nullable String defaultValue,
                                   DescriptionInfo description,
                                   BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver,
                                   @Nullable BeanFactoryId beanFactoryId,
                                   AggregationStrategy aggregationStrategy) {
        this.annotationType = annotationType;
        this.httpElementName = httpElementName;
        this.isPathVariable = isPathVariable;
        this.shouldExist = shouldExist;
        this.shouldWrapValueAsOptional = shouldWrapValueAsOptional;
        this.elementType = requireNonNull(elementType, "elementType");
        this.parameterizedElementType = parameterizedElementType;
        this.description = requireNonNull(description, "description");
        this.containerType = containerType;
        this.rawType = rawType;
        this.resolver = requireNonNull(resolver, "resolver");
        this.beanFactoryId = beanFactoryId;
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
        return enumConverters.get(elementType);
    }

    @Nullable
    Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    String httpElementName() {
        return httpElementName;
    }

    boolean isPathVariable() {
        return isPathVariable;
    }

    boolean shouldExist() {
        return shouldExist;
    }

    boolean shouldWrapValueAsOptional() {
        return shouldWrapValueAsOptional;
    }

    @Nullable
    Class<?> containerType() {
        // 'List' or 'Set'
        return containerType;
    }

    Class<?> rawType() {
        return rawType;
    }

    Class<?> elementType() {
        return elementType;
    }

    @Nullable
    ParameterizedType parameterizedElementType() {
        return parameterizedElementType;
    }

    @Nullable
    Object defaultValue() {
        return defaultValue;
    }

    DescriptionInfo description() {
        return description;
    }

    @Nullable
    BeanFactoryId beanFactoryId() {
        return beanFactoryId;
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
        if (value.isEmpty()) {
            if (String.class.isAssignableFrom(elementType)) {
                return value;
            }
            if (elementType.isPrimitive()) {
                throw new IllegalArgumentException("Cannot set null to primitive type parameter: " +
                                                   httpElementName);
            }
            return null;
        }
        return convert(value, elementType, enumConverter);
    }

    @Nullable
    private Object defaultOrException() {
        if (!shouldExist) {
            // May return 'null' if no default value is specified.
            if (elementType.isPrimitive() && defaultValue == null) {
                throw new IllegalArgumentException("Cannot set null to primitive type parameter: " +
                                                   httpElementName);
            }
            return defaultValue;
        }
        throw new IllegalArgumentException("Mandatory parameter/header/attribute is missing: " +
                                           httpElementName);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("annotation",
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
                          .add("description", description)
                          .add("resolver", resolver)
                          .add("enumConverter", enumConverter)
                          .toString();
    }

    private static final class Builder {
        private final AnnotatedElement annotatedElement;
        private final Type type;
        private final String httpElementName;
        private AnnotatedElement typeElement;
        @Nullable
        private Class<? extends Annotation> annotationType;
        private boolean pathVariable;
        private boolean supportContainer;
        private boolean supportDefault;
        private DescriptionInfo description = DescriptionInfo.empty();
        @Nullable
        private BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver;
        @Nullable
        private BeanFactoryId beanFactoryId;
        private AggregationStrategy aggregation = AggregationStrategy.NONE;
        private boolean warnedRedundantUse;

        private Builder(AnnotatedElement annotatedElement, Type type, String name) {
            this.annotatedElement = requireNonNull(annotatedElement, "annotatedElement");
            this.type = requireNonNull(type, "type");
            httpElementName = requireNonNull(name, "name");
            typeElement = annotatedElement;
        }

        /**
         * Sets the annotation which is one of {@link Param}, {@link Header} or {@link RequestObject}.
         */
        private Builder annotationType(Class<? extends Annotation> annotationType) {
            assert annotationType == Param.class ||
                   annotationType == Attribute.class ||
                   annotationType == Header.class ||
                   annotationType == RequestObject.class : annotationType.getSimpleName();
            this.annotationType = annotationType;
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
         * Sets the description of the {@link AnnotatedElement}.
         */
        private Builder description(DescriptionInfo description) {
            this.description = description;
            return this;
        }

        /**
         * Sets a value resolver.
         */
        private Builder resolver(BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver) {
            this.resolver = resolver;
            return this;
        }

        private Builder beanFactoryId(BeanFactoryId beanFactoryId) {
            this.beanFactoryId = beanFactoryId;
            return this;
        }

        /**
         * Sets an {@link AggregationStrategy} for the element.
         */
        private Builder aggregation(AggregationStrategy aggregation) {
            this.aggregation = aggregation;
            return this;
        }

        private AnnotatedValueResolver build() {
            checkArgument(resolver != null, "'resolver' should be specified");

            final boolean isOptional = type == Optional.class;
            final boolean isAnnotatedNullable = isAnnotatedNullable(typeElement);
            final boolean isMarkedNullable = KotlinUtil.isMarkedNullable(typeElement);
            final boolean isNullable = isAnnotatedNullable || isMarkedNullable;
            final Type originalParameterizedType = parameterizedTypeOf(typeElement);
            final Type unwrappedParameterizedType = isOptional ? unwrapOptional(originalParameterizedType)
                                                               : originalParameterizedType;

            // Used only for warning message.
            final String nullableRepresentation =
                    isMarkedNullable && !isAnnotatedNullable ? "?(Kotlin nullable type)"
                                                             : "@Nullable";

            if (isAnnotatedNullable && isMarkedNullable) {
                warnRedundantUse("@Nullable", "?(Kotlin nullable type)");
            }
            if (isOptional && isNullable) {
                warnRedundantUse("Optional", nullableRepresentation);
            }

            final boolean shouldExist;
            final String defaultValue;

            final Default aDefault = annotatedElement.getAnnotation(Default.class);
            if (aDefault != null) {
                if (supportDefault) {
                    // Warn unusual usage. e.g. @Param @Default("a") Optional<String> param
                    if (isOptional) {
                        warnRedundantUse("@Default", "Optional");
                    } else if (isNullable) {
                        warnRedundantUse("@Default", nullableRepresentation);
                    }

                    shouldExist = false;
                    defaultValue = getSpecifiedValue(aDefault.value());
                } else {
                    // Warn if @Default exists in an unsupported place.
                    warnRedundantUse("@Default", null);

                    shouldExist = !isOptional;
                    // Set the default value to null just like it was not specified.
                    defaultValue = null;
                }
            } else {
                // Set the default value to null if it was not specified.
                defaultValue = null;

                if (isOptional) {
                    shouldExist = false;
                } else {
                    // Allow `null` if annotated with `@Nullable`.
                    shouldExist = !isNullable;
                }
            }

            if (pathVariable && !shouldExist) {
                if (isOptional) {
                    warnRedundantUse("a path variable", "Optional");
                } else {
                    warnRedundantUse("a path variable", nullableRepresentation);
                }
            }

            final Class<?> containerType = getContainerType(unwrappedParameterizedType);
            final Class<?> rawType;
            final Class<?> mayRawType = toRawType(unwrappedParameterizedType);
            if (mayRawType.isPrimitive()) {
                rawType = Primitives.wrap(mayRawType);
            } else {
                rawType = mayRawType;
            }
            final Class<?> elementType;
            final ParameterizedType parameterizedElementType;

            if (containerType != null) {
                elementType = getElementType(unwrappedParameterizedType, isOptional);
                parameterizedElementType = getParameterizedElementType(unwrappedParameterizedType);
            } else {
                //
                // Here, 'type' should be one of the following types:
                // - RequestContext (or ServiceRequestContext)
                // - Request (or HttpRequest)
                // - AggregatedHttpRequest
                // - QueryParams (or HttpParameters)
                // - User classes which can be converted by request converter
                //
                if (unwrappedParameterizedType instanceof Class) {
                    elementType = (Class<?>) unwrappedParameterizedType;
                    parameterizedElementType = null;
                } else if (unwrappedParameterizedType instanceof ParameterizedType) {
                    parameterizedElementType = (ParameterizedType) unwrappedParameterizedType;
                    elementType = (Class<?>) parameterizedElementType.getRawType();
                } else {
                    throw new IllegalArgumentException("Unsupported parameter type: " +
                                                       unwrappedParameterizedType.getTypeName());
                }
            }

            return new AnnotatedValueResolver(annotationType, httpElementName, pathVariable, shouldExist,
                                              isOptional, containerType, elementType, rawType,
                                              parameterizedElementType, defaultValue, description, resolver,
                                              beanFactoryId, aggregation);
        }

        private void warnRedundantUse(String whatWasUsed1, @Nullable String whatWasUsed2) {
            if (warnedRedundantUse) {
                return;
            }

            warnedRedundantUse = true;
            final StringBuilder buf = new StringBuilder();
            buf.append("Found a redundant ");
            if (whatWasUsed2 != null) {
                buf.append("combined ");
            }
            buf.append("use of ")
               .append(whatWasUsed1);
            if (whatWasUsed2 != null) {
                buf.append(" and ").append(whatWasUsed2);
            }
            buf.append(" in '")
               .append(typeElement)
               .append('\'');

            if (typeElement != annotatedElement) {
                buf.append(" of '")
                   .append(annotatedElement)
                   .append('\'');
            }

            if (annotationType != null) {
                buf.append(" (type: ")
                   .append(annotationType.getSimpleName())
                   .append(')');
            }

            logger.warn(buf.toString());
        }

        @Nullable
        private Class<?> getContainerType(Type parameterizedType) {
            final Class<?> rawType = toRawType(parameterizedType);
            if (pathVariable) {
                if (Iterable.class.isAssignableFrom(rawType)) {
                    throw new IllegalArgumentException(
                            "Container type is not supported for a path variable: " + httpElementName +
                            " (" + parameterizedType + ')');
                }
            }

            if (!supportContainer) {
                return null;
            }

            if (rawType == Iterable.class ||
                rawType == List.class ||
                rawType == Collection.class) {
                return ArrayList.class;
            }

            if (rawType == Set.class) {
                return LinkedHashSet.class;
            }

            if (List.class.isAssignableFrom(rawType) ||
                Set.class.isAssignableFrom(rawType)) {
                try {
                    // Only if there is a default constructor.
                    // Note: `requireNonNull()` is redundant here, but it stops Error Prone from complaining
                    //       about an ignored return value.
                    requireNonNull(rawType.getConstructor());
                    return rawType;
                } catch (Throwable cause) {
                    throw new IllegalArgumentException("Unsupported container type: " + rawType.getName(),
                                                       cause);
                }
            }

            return null;
        }

        private Class<?> getElementType(Type parameterizedType, boolean unwrapOptional) {
            if (!(parameterizedType instanceof ParameterizedType)) {
                return toRawType(unwrapOptional ? parameterizedType : type);
            }

            final Type elementType;
            try {
                elementType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            } catch (Throwable cause) {
                throw new IllegalArgumentException(
                        "Unsupported or invalid parameter type: " + parameterizedType, cause);
            }
            return toRawType(elementType);
        }

        @Nullable
        private static ParameterizedType getParameterizedElementType(Type parameterizedType) {
            if (!(parameterizedType instanceof ParameterizedType)) {
                return null;
            }

            try {
                final Type elementType =
                        ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
                if (elementType instanceof ParameterizedType) {
                    return (ParameterizedType) elementType;
                }
            } catch (Throwable cause) {
                throw new IllegalArgumentException(
                        "Unsupported parameter type: " + parameterizedType, cause);
            }

            return null;
        }

        private static Type unwrapOptional(Type parameterizedType) {
            // Unwrap once again so that a pattern like 'Optional<List<?>>' can be supported.
            assert parameterizedType instanceof ParameterizedType : String.valueOf(parameterizedType);
            parameterizedType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            return parameterizedType;
        }

        private static Class<?> toRawType(Type type) {
            if (type instanceof Class) {
                return (Class<?>) type;
            }
            if (type instanceof ParameterizedType) {
                return (Class<?>) ((ParameterizedType) type).getRawType();
            }
            if (type instanceof WildcardType) {
                final Type[] upperBounds = ((WildcardType) type).getUpperBounds();
                if (upperBounds.length > 0) {
                    return (Class<?>) upperBounds[0];
                }
            }
            if (type instanceof TypeVariable) {
                final Type[] bounds = ((TypeVariable<?>) type).getBounds();
                if (bounds.length > 0) {
                    return (Class<?>) bounds[0];
                }
            }

            throw new IllegalArgumentException("Unsupported or invalid parameter type: " + type);
        }
    }

    private static boolean isFormData(@Nullable MediaType contentType) {
        return contentType != null && contentType.belongsTo(MediaType.FORM_DATA);
    }

    private static boolean isMultipartFormData(@Nullable MediaType contentType) {
        return contentType != null && contentType.belongsTo(MediaType.MULTIPART_FORM_DATA);
    }

    static AggregationType aggregationType(AggregationStrategy strategy, RequestHeaders headers) {
        requireNonNull(strategy, "strategy");
        final MediaType mediaType = headers.contentType();
        if (isMultipartFormData(mediaType)) {
            if (strategy == AggregationStrategy.NONE) {
                return AggregationType.NONE;
            }
            return AggregationType.MULTIPART;
        }

        switch (strategy) {
            case ALWAYS:
                return AggregationType.ALL;
            case FOR_FORM_DATA:
                return isFormData(mediaType) ? AggregationType.ALL : AggregationType.NONE;
        }
        return AggregationType.NONE;
    }

    /**
     * Decide aggregation in memory, file or not.
     */
    enum AggregationType {
        ALL,
        MULTIPART,
        NONE,
    }

    /**
     * Each argument use this enum to decide its strategy.
     */
    enum AggregationStrategy {
        NONE, ALWAYS, FOR_FORM_DATA;

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

    static class AggregatedResult {
        static final AggregatedResult EMPTY = new AggregatedResult(null, null);

        @Nullable
        private final FileAggregatedMultipart aggregatedMultipart;
        @Nullable
        private final AggregatedHttpRequest aggregatedHttpRequest;

        private AggregatedResult(@Nullable FileAggregatedMultipart aggregatedMultipart,
                                 @Nullable AggregatedHttpRequest aggregatedHttpRequest) {
            this.aggregatedMultipart = aggregatedMultipart;
            this.aggregatedHttpRequest = aggregatedHttpRequest;
        }

        AggregatedResult(FileAggregatedMultipart aggregatedMultipart) {
            this(aggregatedMultipart, null);
        }

        AggregatedResult(AggregatedHttpRequest aggregatedHttpRequest) {
            this(null, aggregatedHttpRequest);
        }
    }

    /**
     * A context which is used while resolving parameter values.
     */
    static class ResolverContext {
        private final ServiceRequestContext context;
        private final HttpRequest request;

        @Nullable
        private final AggregatedResult aggregatedResult;

        private volatile QueryParams queryParams;

        ResolverContext(ServiceRequestContext context, HttpRequest request,
                        AggregatedResult aggregatedResult) {
            this.context = requireNonNull(context, "context");
            this.request = requireNonNull(request, "request");
            this.aggregatedResult = requireNonNull(aggregatedResult, "aggregatedResult");
        }

        ServiceRequestContext context() {
            return context;
        }

        HttpRequest request() {
            return request;
        }

        @Nullable
        AggregatedHttpRequest aggregatedRequest() {
            return aggregatedResult.aggregatedHttpRequest;
        }

        @Nullable
        FileAggregatedMultipart aggregatedMultipart() {
            return aggregatedResult.aggregatedMultipart;
        }

        QueryParams queryParams() {
            QueryParams result = queryParams;
            if (result == null) {
                result = queryParams;
                if (result == null) {
                    queryParams = result = queryParamsOf(context.query(),
                                                         request.contentType(),
                                                         aggregatedResult);
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("context", context)
                              .add("request", request)
                              .add("aggregatedResult", aggregatedResult)
                              .add("queryParams", queryParams)
                              .toString();
        }

        /**
         * Returns a {@link QueryParams} decoded from a request.
         *
         * <p>Usually one of a query string of a URI or URL-encoded form data is specified in the request.
         * If both of them exist though, they would be decoded and merged into a parameter map.</p>
         *
         * <p>Names and values of the parameters would be decoded as UTF-8 character set.</p>
         *
         * @see QueryParams#fromQueryString(String)
         * @see HttpConstants#DEFAULT_CHARSET
         */
        private static QueryParams queryParamsOf(@Nullable String query,
                                                 @Nullable MediaType contentType,
                                                 AggregatedResult result) {
            try {
                final QueryParams params1 = query != null ? QueryParams.fromQueryString(query) : null;
                QueryParams params2 = null;
                if (isFormData(contentType)) {
                    final AggregatedHttpRequest message = result.aggregatedHttpRequest;
                    if (message != null) {
                        // Respect 'charset' attribute of the 'content-type' header if it exists.
                        final String body = message.content(contentType.charset(StandardCharsets.US_ASCII));
                        if (!body.isEmpty()) {
                            params2 = QueryParams.fromQueryString(body);
                        }
                    }
                } else if (isMultipartFormData(contentType)) {
                    final FileAggregatedMultipart multipart = result.aggregatedMultipart;
                    if (multipart != null) {
                        params2 = QueryParams.builder().add(multipart.params().entries()).build();
                    }
                }

                if (params1 == null || params1.isEmpty()) {
                    return firstNonNull(params2, QueryParams.of());
                } else if (params2 == null || params2.isEmpty()) {
                    return params1;
                } else {
                    return QueryParams.builder()
                                      .sizeHint(params1.size() + params2.size())
                                      .add(params1)
                                      .add(params2)
                                      .build();
                }
            } catch (Exception e) {
                // If we failed to decode the query string, we ignore the exception raised here.
                // A missing parameter might be checked when invoking the annotated method.
                logger.debug("Failed to decode query string: {}", query, e);
                return QueryParams.of();
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
     * An interface to make a {@link RequestConverterFunction} be adapted for
     * {@link AnnotatedValueResolver} internal implementation.
     */
    @FunctionalInterface
    interface RequestObjectResolver {
        static RequestObjectResolver of(RequestConverterFunction function) {
            return (resolverContext, expectedResultType, expectedParameterizedResultType, beanFactoryId) -> {
                final AggregatedHttpRequest request = resolverContext.aggregatedRequest();
                if (request == null) {
                    throw new IllegalArgumentException(
                            "Cannot convert this request to an object because it is not aggregated.");
                }
                return function.convertRequest(resolverContext.context(), request,
                                               expectedResultType, expectedParameterizedResultType);
            };
        }

        @Nullable
        Object convert(ResolverContext resolverContext, Class<?> expectedResultType,
                       @Nullable ParameterizedType expectedParameterizedResultType,
                       @Nullable BeanFactoryId beanFactoryId) throws Throwable;
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
