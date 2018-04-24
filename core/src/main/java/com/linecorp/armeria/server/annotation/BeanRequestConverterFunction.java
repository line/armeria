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
package com.linecorp.armeria.server.annotation;

import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.convertParameter;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.findHeaderName;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.findParamName;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.hasParamOrHeader;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.httpParametersOf;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.validateAndNormalizeSupportedType;
import static java.util.Collections.singletonList;
import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.reflections.ReflectionUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil;
import com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.EnumConverter;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

/**
 * A default {@link RequestConverterFunction} which converts a request into a Java bean.
 *
 * <p>The following parameters are eligible for automatic injection into bean fields or properties:
 * <ul>
 *   <li>Path parameters to fields annotated by {@link Param}</li>
 *   <li>HTTP parameters to fields annotated by {@link Param}</li>
 *   <li>Headers to fields annotated by {@link Header}</li>
 * </ul>
 */
public final class BeanRequestConverterFunction implements RequestConverterFunction {
    // In BeanParamInfo, there are strong references to Field/Method/Constructor, which end up with strong
    // references to their parent Class. So it don't make sense if we use map with weak reference key here
    private static final ConcurrentMap<Class<?>, BeanParamInfo> beanParamInfoCache = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Class<?>, Boolean> ignoredResultTypeCache = new MapMaker().weakKeys()
                                                                                                 .makeMap();

    /**
     * Builds and registers the parameter information for {@code expectedResultType}.
     *
     * @param expectedResultType a candidate data type, which will be converted
     *                           by {@link BeanRequestConverterFunction}
     */
    public static synchronized void register(final Class<?> expectedResultType) {
        if (ignoredResultTypeCache.containsKey(expectedResultType) ||
            beanParamInfoCache.containsKey(expectedResultType)) {
            // already processed this result type
            return;
        }

        final BeanParamInfo beanParamInfo = BeanParamInfo.from(expectedResultType);
        if (beanParamInfo == null) {
            ignoredResultTypeCache.put(expectedResultType, true);
        } else {
            beanParamInfoCache.put(expectedResultType, beanParamInfo);
        }
    }

    private static <T> void addIfNotNull(final List<T> list, final T item) {
        if (item != null) {
            list.add(item);
        }
    }

    private static String getMemberFullName(final Member member) {
        return member.getDeclaringClass().getSimpleName() + '.' + member.getName();
    }

    private static String toString(Object[] objects) {
        final StringJoiner joiner = new StringJoiner(",", "(", ")");
        Arrays.stream(objects).forEach(o -> joiner.add(o.toString()));
        return joiner.toString();
    }

    @Override
    public Object convertRequest(final ServiceRequestContext ctx,
                                 final AggregatedHttpMessage request,
                                 final Class<?> expectedResultType) throws Exception {
        final BeanParamInfo beanParamInfo = beanParamInfoCache.get(expectedResultType);
        if (beanParamInfo == null) {
            // no BeanParamInfo for this result type, we should not handle it
            return RequestConverterFunction.fallthrough();
        }

        final MutableValueHolder<HttpParameters> httpParams = MutableValueHolder.ofEmpty();
        final RequestConvertContext context = new RequestConvertContext(ctx, request, httpParams);
        return beanParamInfo.buildBean(context);
    }

    private static final class RequestConvertContext {
        private final ServiceRequestContext ctx;

        private final AggregatedHttpMessage request;

        private final MutableValueHolder<HttpParameters> httpParams;

        @SuppressWarnings("NullableProblems")
        private Object targetBean;

        private RequestConvertContext(final ServiceRequestContext ctx,
                                      final AggregatedHttpMessage request,
                                      final MutableValueHolder<HttpParameters> httpParams) {
            this.ctx = ctx;
            this.request = request;
            this.httpParams = httpParams;
        }
    }

    private static final class BeanParamInfo {
        private final ConstructorInfo constructorInfo;

        private final List<AbstractParamSetter> paramSetterList;

        private BeanParamInfo(final ConstructorInfo constructorInfo,
                              final List<AbstractParamSetter> paramSetterList) {
            this.constructorInfo = constructorInfo;
            this.paramSetterList = paramSetterList;
        }

        @Nullable
        private static BeanParamInfo from(final Class<?> beanType) {
            final List<AbstractParamSetter> paramSetterList = new ArrayList<>();

            getAllFields(beanType).forEach(
                    field -> addIfNotNull(paramSetterList, FieldParamSetter.from(field)));
            getAllMethods(beanType).forEach(
                    method -> addIfNotNull(paramSetterList, MethodParamSetter.from(method)));

            final ConstructorInfo constructorInfo = ConstructorInfo.from(beanType);
            if (constructorInfo == null) {
                // No annotated constructor or default constructor. Skip this.
                return null;
            }
            if (constructorInfo.getParameterCount() == 0 && paramSetterList.isEmpty()) {
                // No arg constructor exists but there is no annotated field or method. Skip this.
                return null;
            }
            return new BeanParamInfo(constructorInfo, paramSetterList);
        }

        private Object buildBean(final RequestConvertContext context) {
            context.targetBean = constructorInfo.createInstance(context);
            paramSetterList.forEach(paramSetter -> paramSetter.setParamValue(context));
            return context.targetBean;
        }
    }

    private static final class ConstructorInfo {
        private final Constructor<?> constructor;

        private final ParamArrayValueRetriever paramArrayValueRetriever;

        private ConstructorInfo(final Constructor<?> constructor,
                                final ParamArrayValueRetriever paramArrayValueRetriever) {
            this.constructor = constructor;
            this.paramArrayValueRetriever = paramArrayValueRetriever;

            this.constructor.setAccessible(true);
        }

        private static ConstructorInfo from(final Class<?> beanType) {
            final List<ConstructorInfo> constructorList = new ArrayList<>();

            final MutableValueHolder<ConstructorInfo> defaultConstructor = MutableValueHolder.ofEmpty();

            ReflectionUtils.getConstructors(beanType).forEach(
                    constructor -> {
                        final ConstructorInfo constructorInfo = from(constructor);
                        if (constructorInfo != null) {
                            if (constructorInfo.getParameterCount() == 0) {
                                // default constructor, with no args
                                defaultConstructor.setValue(constructorInfo);
                            } else {
                                // constructor with args
                                constructorList.add(constructorInfo);
                            }
                        }
                    }
            );

            if (constructorList.isEmpty()) {
                // No annotated constructor. Use default constructor.
                return defaultConstructor.getValue();
            }
            if (constructorList.size() == 1) {
                // Only 1 annotated constructor.
                return constructorList.get(0);
            }
            // More than 1 annotated constructors. We don't know which one to use.
            throw new IllegalArgumentException(
                    "too many annotated constructors in " + beanType.getSimpleName() +
                    ": " + constructorList.size() + " (expected: 0 or 1)");
        }

        @Nullable
        private static ConstructorInfo from(final Constructor<?> constructor) {
            final ParamArrayValueRetriever retriever = ParamArrayValueRetriever.from(constructor);
            if (retriever != null) {
                return new ConstructorInfo(constructor, retriever);
            }
            if (constructor.getParameterCount() == 0) {
                return new ConstructorInfo(constructor, ParamArrayValueRetriever.NO_PARAMETERS);
            }
            return null;
        }

        private int getParameterCount() {
            return paramArrayValueRetriever.retrieverList.size();
        }

        private Object createInstance(final RequestConvertContext context) {
            final Object[] initArgs = paramArrayValueRetriever.getParamValues(context);
            try {
                return constructor.newInstance(initArgs);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "cannot invoke constructor: " + getMemberFullName(constructor) +
                        BeanRequestConverterFunction.toString(initArgs), e);
            }
        }
    }

    private abstract static class AbstractParamSetter {
        protected abstract void setParamValue(RequestConvertContext context);
    }

    private static final class FieldParamSetter extends AbstractParamSetter {
        private final Field field;
        private final ParamValueRetriever paramValueRetriever;

        private FieldParamSetter(final Field field,
                                 final ParamValueRetriever paramValueRetriever) {
            this.field = field;
            this.paramValueRetriever = paramValueRetriever;
            this.field.setAccessible(true);
        }

        @Nullable
        private static FieldParamSetter from(final Field field) {
            final ParamValueRetriever retriever = ParamValueRetriever.of(field.getType(), field);
            return retriever != null ? new FieldParamSetter(field, retriever)
                                     : null;
        }

        @Override
        protected void setParamValue(final RequestConvertContext context) {
            final Object paramValue = paramValueRetriever.getParamValue(context);
            try {
                field.set(context.targetBean, paramValue);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "cannot set '" + paramValue + "' to: " + getMemberFullName(field), e);
            }
        }
    }

    private static final class MethodParamSetter extends AbstractParamSetter {
        private final Method method;
        private final ParamArrayValueRetriever paramArrayValueRetriever;

        private MethodParamSetter(final Method method,
                                  final ParamArrayValueRetriever paramArrayValueRetriever) {
            this.method = method;
            this.paramArrayValueRetriever = paramArrayValueRetriever;

            this.method.setAccessible(true);
        }

        @Nullable
        private static MethodParamSetter from(final Method method) {
            final ParamArrayValueRetriever retriever = ParamArrayValueRetriever.from(method);
            return retriever != null ? new MethodParamSetter(method, retriever)
                                     : null;
        }

        @Override
        protected void setParamValue(final RequestConvertContext context) {
            final Object[] paramValues = paramArrayValueRetriever.getParamValues(context);
            try {
                method.invoke(context.targetBean, paramValues);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "cannot invoke method: " + getMemberFullName(method) +
                        BeanRequestConverterFunction.toString(paramValues), e);
            }
        }
    }

    private abstract static class ParamValueRetriever {
        private final Class<?> type;

        private final EnumConverter<?> enumConverter;

        private ParamValueRetriever(final Class<?> type) {
            this.type = validateAndNormalizeSupportedType(type);

            if (type.isEnum()) {
                enumConverter = new EnumConverter<>(this.type.asSubclass(Enum.class));
            } else {
                enumConverter = null;
            }
        }

        @Nullable
        private static ParamValueRetriever of(Class<?> type, AnnotatedElement element) {
            return of(type, element, element);
        }

        @Nullable
        private static ParamValueRetriever of(Class<?> type, AnnotatedElement element,
                                              Object nameRetrievalTarget) {
            final String param = findParamName(element, nameRetrievalTarget);
            if (param != null) {
                return new HttpParamValueRetriever(type, param);
            }
            final String header = findHeaderName(element, nameRetrievalTarget);
            if (header != null) {
                return new HttpHeaderValueRetriever(type, header);
            }
            return null;
        }

        protected abstract String getParamStrValue(RequestConvertContext context);

        @Nullable
        private Object getParamValue(final RequestConvertContext context) {
            final String strValue = getParamStrValue(context);
            return strValue != null ? convertParameter(strValue, type, enumConverter, false)
                                    : null;
        }

        private static final class HttpParamValueRetriever extends ParamValueRetriever {
            private final String paramName;

            private HttpParamValueRetriever(final Class<?> type, final String paramName) {
                super(type);
                this.paramName = paramName;
            }

            @Override
            protected String getParamStrValue(final RequestConvertContext context) {
                String paramValueStr = context.ctx.pathParam(paramName);
                if (paramValueStr == null) {
                    paramValueStr = context.httpParams.computeIfAbsent(
                            () -> httpParametersOf(context.ctx,
                                                   context.request.headers(),
                                                   context.request)).get(paramName);
                }
                return paramValueStr;
            }
        }

        private static final class HttpHeaderValueRetriever extends ParamValueRetriever {
            private final String headerName;

            private HttpHeaderValueRetriever(final Class<?> type, final String headerName) {
                super(type);
                this.headerName = headerName;
            }

            @Override
            protected String getParamStrValue(final RequestConvertContext context) {
                return context.request.headers().get(AsciiString.of(headerName));
            }
        }
    }

    private static final class ParamArrayValueRetriever {
        static final ParamArrayValueRetriever NO_PARAMETERS = new ParamArrayValueRetriever(ImmutableList.of());

        private final List<ParamValueRetriever> retrieverList;

        private ParamArrayValueRetriever(final List<ParamValueRetriever> retrieverList) {
            this.retrieverList = retrieverList;
        }

        @Nullable
        private static ParamArrayValueRetriever from(final Executable element) {
            if (hasParamOrHeader(element)) {
                return fromAnnotatedMethodOrConstructor(element);
            } else {
                return fromAnnotatedParameters(element);
            }
        }

        private static ParamArrayValueRetriever fromAnnotatedMethodOrConstructor(Executable element) {
            final Parameter[] elementParams = element.getParameters();

            if (elementParams.length != 1) {
                throw new IllegalArgumentException("the number of parameters of " + getMemberFullName(element) +
                                                   ": " + elementParams.length + " (expected: 1)");
            }
            if (Arrays.stream(elementParams).anyMatch(AnnotatedHttpServiceParamUtil::hasParamOrHeader)) {
                throw new IllegalArgumentException(
                        "no @" + Param.class.getSimpleName() + " and @" + Header.class.getSimpleName() +
                        " annotations are allowed in the parameter of " + getMemberFullName(element));
            }
            return new ParamArrayValueRetriever(singletonList(
                    ParamValueRetriever.of(elementParams[0].getType(), element, elementParams[0])));
        }

        @Nullable
        private static ParamArrayValueRetriever fromAnnotatedParameters(Executable element) {
            final Parameter[] elementParams = element.getParameters();
            final long annotatedParameterCount = Arrays.stream(elementParams)
                                                       .filter(AnnotatedHttpServiceParamUtil::hasParamOrHeader)
                                                       .count();
            if (annotatedParameterCount == 0) {
                return null;
            }

            if (annotatedParameterCount != elementParams.length) {
                throw new IllegalArgumentException(
                        "every parameter of " + getMemberFullName(element) +
                        " should be annotated with one of @" + Param.class.getSimpleName() +
                        " or @" + Header.class.getSimpleName());
            }

            final List<ParamValueRetriever> retrievers = Arrays.stream(elementParams)
                                                               .map(p -> ParamValueRetriever.of(p.getType(), p))
                                                               .collect(Collectors.toList());
            return new ParamArrayValueRetriever(retrievers);
        }

        private Object[] getParamValues(final RequestConvertContext context) {
            return retrieverList.stream()
                                .map(retriever -> retriever.getParamValue(context))
                                .toArray();
        }
    }
}
