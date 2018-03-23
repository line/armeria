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
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.httpParametersOf;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.validateAndNormalizeSupportedType;
import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;

import java.lang.annotation.Annotation;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.reflections.ReflectionUtils;

import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpParameters;
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

        final BeanParamInfo beanParamInfo = BeanParamInfo.buildFrom(expectedResultType);
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
        return member.getDeclaringClass().getCanonicalName() + '.' + member.getName();
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

        private static BeanParamInfo buildFrom(final Class<?> beanType) {
            final List<AbstractParamSetter> paramSetterList = new ArrayList<>();

            getAllFields(beanType).forEach(
                    field -> addIfNotNull(paramSetterList, FieldParamSetter.buildFrom(field))
            );

            getAllMethods(beanType).forEach(
                    method -> addIfNotNull(paramSetterList, MethodParamSetter.buildFrom(method))
            );

            final ConstructorInfo constructorInfo = ConstructorInfo.buildFrom(beanType);
            if (constructorInfo == null) {
                // there is no annotated constructor or default constructor

                // we should not handle this beanType
                return null;
            }

            if (constructorInfo.getParameterCount() == 0) {
                // there is no arg for constructor

                if (paramSetterList.isEmpty()) {
                    // there is no annotated field or method

                    // we should not handle this beanType
                    return null;
                }
            }

            return new BeanParamInfo(constructorInfo, paramSetterList);
        }

        private Object buildBean(final RequestConvertContext context) {
            context.targetBean = constructorInfo.createInstance(context);

            paramSetterList.forEach(
                    paramSetter -> paramSetter.setParamValue(context)
            );

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

        private static ConstructorInfo buildFrom(final Class<?> beanType) {
            final List<ConstructorInfo> constructorList = new ArrayList<>();

            final MutableValueHolder<ConstructorInfo> defaultConstructor = MutableValueHolder.ofEmpty();

            ReflectionUtils.getConstructors(beanType).forEach(
                    constructor -> {
                        final ConstructorInfo constructorInfo = buildFrom(constructor);
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
                // there is no annotated constructor, use default constructor
                return defaultConstructor.getValue();
            } else if (constructorList.size() == 1) {
                // there is only 1 annotated constructor, use it
                return constructorList.get(0);
            } else {
                // there are more than 1 annotated constructors, we don't know to use which one
                throw new IllegalArgumentException(
                        String.format("There are more than 1 annotated constructors in class '%1$s'.",
                                      beanType.getCanonicalName())
                );
            }
        }

        private static ConstructorInfo buildFrom(final Constructor<?> constructor) {
            final ParamArrayValueRetriever retriever = ParamArrayValueRetriever.buildFrom(constructor);
            if (retriever != null) {
                return new ConstructorInfo(constructor, retriever);
            }

            if (constructor.getParameterCount() == 0) {
                return new ConstructorInfo(constructor, ParamArrayValueRetriever.ofEmpty());
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
                        String.format("Can't invoke constructor '%1$s' with initArgs: %2$s",
                                      getMemberFullName(constructor), Arrays.toString(initArgs)),
                        e);
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

        private static FieldParamSetter buildFrom(final Field field) {
            final ParamValueRetriever retriever = ParamValueRetriever.buildFrom(field);

            if (retriever != null) {
                return new FieldParamSetter(field, retriever);
            }

            return null;
        }

        @Override
        protected void setParamValue(final RequestConvertContext context) {
            final Object paramValue = paramValueRetriever.getParamValue(context);

            try {
                field.set(context.targetBean, paramValue);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        String.format("Can't set field '%1$s' with value: %2$s",
                                      getMemberFullName(field), paramValue),
                        e);
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

        private static MethodParamSetter buildFrom(final Method method) {
            final ParamArrayValueRetriever retriever = ParamArrayValueRetriever.buildFrom(method);

            if (retriever != null) {
                return new MethodParamSetter(method, retriever);
            }

            return null;
        }

        @Override
        protected void setParamValue(final RequestConvertContext context) {
            final Object[] paramValues = paramArrayValueRetriever.getParamValues(context);

            try {
                method.invoke(context.targetBean, paramValues);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        String.format("Can't invoke method '%1$s' with params: %2$s",
                                      getMemberFullName(method), Arrays.toString(paramValues)),
                        e);
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

        private static ParamValueRetriever buildFrom(final Parameter methodParam) {
            return buildFrom(methodParam.getType(), methodParam);
        }

        private static ParamValueRetriever buildFrom(final Field field) {
            return buildFrom(field.getType(), field);
        }

        private static ParamValueRetriever buildFrom(final Class<?> type, final AnnotatedElement element) {
            final Annotation annotation = getParamsAnnotation(element);

            return buildFrom(type, annotation);
        }

        private static ParamValueRetriever buildFrom(final Class<?> type, final Annotation annotation) {
            if (annotation == null) {
                return null;
            }

            if (annotation instanceof Param) {
                return new HttpParamValueRetriever(type, ((Param) annotation).value());
            } else if (annotation instanceof Header) {
                return new HttpHeaderValueRetriever(type, ((Header) annotation).value());
            } else {
                throw new IllegalArgumentException(
                        String.format("Unsupported Annotation: %1$s",
                                      annotation.getClass().getCanonicalName())
                );
            }
        }

        private static Annotation getParamsAnnotation(final AnnotatedElement element) {
            final Param param = element.getAnnotation(Param.class);
            if (param != null) {
                return param;
            }

            final Header header = element.getAnnotation(Header.class);
            if (header != null) {
                return header;
            }

            return null;
        }

        protected abstract String getParamStrValue(RequestConvertContext context);

        private Object getParamValue(final RequestConvertContext context) {
            final String strValue = getParamStrValue(context);

            if (strValue == null) {
                return null;
            }

            return convertParameter(strValue, type, enumConverter, false);
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
                    paramValueStr =
                            context.httpParams.computeIfAbsent(
                                    () -> httpParametersOf(context.ctx,
                                                           context.request.headers(),
                                                           context.request)
                            ).get(paramName);
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
        private final List<ParamValueRetriever> retrieverList;

        private ParamArrayValueRetriever(final List<ParamValueRetriever> retrieverList) {
            this.retrieverList = retrieverList;
        }

        private static ParamArrayValueRetriever of(final ParamValueRetriever... retrievers) {
            return new ParamArrayValueRetriever(Arrays.asList(retrievers));
        }

        private static ParamArrayValueRetriever ofEmpty() {
            return of();
        }

        private static void checkNoParamAnnotations(final Executable element,
                                                    final Parameter[] elementParams) {
            for (final Parameter parameter : elementParams) {
                if (ParamValueRetriever.getParamsAnnotation(parameter) != null) {
                    throw new IllegalArgumentException(
                            String.format("Annotation should not be used on parameter '%1$s' of '%2$s'.",
                                          parameter.getName(), getMemberFullName(element))
                    );
                }
            }
        }

        private static ParamArrayValueRetriever buildFrom(final Executable element) {
            final Annotation annotation = ParamValueRetriever.getParamsAnnotation(element);
            final Parameter[] elementParams = element.getParameters();

            if (annotation != null) {
                // if there is annotation on this method/constructor

                // there should be only one parameter
                if (elementParams.length != 1) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "There should be only 1 parameter for '%1$s'.",
                                    getMemberFullName(element))
                    );
                }

                // parameter should have no annotation
                checkNoParamAnnotations(element, elementParams);

                final Class<?> type = elementParams[0].getType();
                final ParamValueRetriever retriever = ParamValueRetriever.buildFrom(type, annotation);

                return of(retriever);
            }

            // build retrievers by annotations on each parameters
            final List<ParamValueRetriever> retrieverList = Arrays.stream(elementParams)
                                                                  .map(ParamValueRetriever::buildFrom)
                                                                  .filter(Objects::nonNull)
                                                                  .collect(Collectors.toList());
            if (retrieverList.isEmpty()) {
                // there is no annotation on params of this method/constructor
                return null;
            }

            // check if every params has annotation
            if (elementParams.length != retrieverList.size()) {
                throw new IllegalArgumentException(
                        String.format(
                                "There are %1$d parameter(s) for '%2$s', but only %3$d of them are annotated.",
                                elementParams.length, getMemberFullName(element), retrieverList.size())
                );
            }

            return new ParamArrayValueRetriever(retrieverList);
        }

        private Object[] getParamValues(final RequestConvertContext context) {
            return retrieverList.stream()
                                .map(retriever -> retriever.getParamValue(context))
                                .toArray();
        }
    }
}
