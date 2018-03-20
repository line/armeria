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
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

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
public class BeanRequestConverterFunction implements RequestConverterFunction {
    private static final ConcurrentMap<Class<?>, BeanParamInfo> beanParamInfoCache = new MapMaker().weakKeys()
                                                                                                   .makeMap();

    private static final ConcurrentMap<Class<?>, Boolean> ignoredResultTypeCache = new MapMaker().weakKeys()
                                                                                                 .makeMap();

    /**
     * Builds and registers the parameter information for {@code expectedResultType}.
     *
     * @param expectedResultType a candidate data type, which will be converted by ${@link BeanRequestConverterFunction}
     */
    public static synchronized void register(final Class<?> expectedResultType) {
        if (ignoredResultTypeCache.containsKey(expectedResultType) ||
            beanParamInfoCache.containsKey(expectedResultType)) {
            // already processed this result type
            return;
        }

        final BeanParamInfo beanParamInfo = buildBeanParamInfo(expectedResultType);
        if (beanParamInfo == null) {
            ignoredResultTypeCache.put(expectedResultType, true);
        } else {
            beanParamInfoCache.put(expectedResultType, beanParamInfo);
        }
    }

    private static BeanParamInfo buildBeanParamInfo(final Class<?> expectedResultType) {
        final List<ParamInfo> paramList = new ArrayList<>();

        getAllFields(expectedResultType)
                .forEach(
                        field -> {
                            final Param param = field.getAnnotation(Param.class);
                            if (param != null) {
                                paramList.add(new ParamFieldParamInfo(field, param));
                            }

                            final Header header = field.getAnnotation(Header.class);
                            if (header != null) {
                                paramList.add(new HeaderFieldParamInfo(field, header));
                            }
                        }
                );

        getAllMethods(expectedResultType, withParametersCount(1))
                .forEach(
                        method -> {
                            final Param param = method.getAnnotation(Param.class);
                            if (param != null) {
                                paramList.add(new ParamMethodParamInfo(method, param));
                            }

                            final Header header = method.getAnnotation(Header.class);
                            if (header != null) {
                                paramList.add(new HeaderMethodParamInfo(method, header));
                            }
                        }
                );

        if (paramList.isEmpty()) {
            // there is no fields annotated by @Param or @Header
            // we should not handle this result type
            return null;
        }

        return new BeanParamInfo(expectedResultType, paramList);
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

        final Object requestBean = beanParamInfo.createInstance();
        final MutableValueHolder<HttpParameters> httpParams = MutableValueHolder.ofEmpty();

        beanParamInfo.paramList
                .forEach(
                        paramInfo -> paramInfo.setValue(ctx, request, httpParams, requestBean)
                );

        return requestBean;
    }

    @FunctionalInterface
    private interface ParamValueRetriever {
        static ParamValueRetriever fromParam(final String paramName) {
            return (ctx, request, httpParams) -> {
                String paramValueStr = ctx.pathParam(paramName);
                if (paramValueStr == null) {
                    paramValueStr =
                            httpParams.computeIfAbsent(
                                    () -> httpParametersOf(ctx, request.headers(), request)
                            ).get(paramName);
                }

                return paramValueStr;
            };
        }

        static ParamValueRetriever fromHeader(final String headerName) {
            return (ctx, request, httpParams) -> request.headers().get(AsciiString.of(headerName));
        }

        String getParamValue(ServiceRequestContext ctx,
                             AggregatedHttpMessage request,
                             MutableValueHolder<HttpParameters> httpParams);
    }

    private static final class BeanParamInfo {
        private final Class<?> beanClass;

        private final Constructor<?> constructor;

        private final List<ParamInfo> paramList;

        private BeanParamInfo(final Class<?> beanClass, final List<ParamInfo> paramList) {
            this.beanClass = beanClass;
            this.paramList = paramList;

            try {
                constructor = beanClass.getDeclaredConstructor();
            } catch (final NoSuchMethodException e) {
                throw new IllegalArgumentException(
                        "Cannot find default constructor for '" + beanClass.getCanonicalName() + "'.",
                        e);
            }

            constructor.setAccessible(true);
        }

        private Object createInstance() {
            try {
                return constructor.newInstance();
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "Cannot create instance of '" + beanClass.getCanonicalName() + "'.",
                        e);
            }
        }
    }

    private abstract static class ParamInfo {
        private final Class<?> type;

        private final ParamValueRetriever paramValueRetriever;

        private final EnumConverter<?> enumConverter;

        private ParamInfo(final Class<?> type,
                          final ParamValueRetriever paramValueRetriever) {
            this.type = validateAndNormalizeSupportedType(type);
            this.paramValueRetriever = paramValueRetriever;

            if (type.isEnum()) {
                enumConverter = new EnumConverter<>(this.type.asSubclass(Enum.class));
            } else {
                enumConverter = null;
            }
        }

        protected abstract void setParamValue(Object targetBean,
                                              Object paramValue);

        private void setValue(final ServiceRequestContext ctx,
                              final AggregatedHttpMessage request,
                              final MutableValueHolder<HttpParameters> httpParams,
                              final Object targetBean) {
            final String strValue = paramValueRetriever.getParamValue(ctx, request, httpParams);

            if (strValue == null) {
                return;
            }

            final Object paramValue = convertParameter(strValue, type, enumConverter, false);

            setParamValue(targetBean, paramValue);
        }
    }

    private abstract static class FieldParamInfo extends ParamInfo {
        private final Field field;

        private FieldParamInfo(final Field field,
                               final ParamValueRetriever paramValueRetriever) {
            super(field.getType(), paramValueRetriever);
            this.field = field;
            this.field.setAccessible(true);
        }

        @Override
        protected void setParamValue(final Object targetBean,
                                     final Object paramValue) {
            try {
                field.set(targetBean, paramValue);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "Can't set '" + paramValue + "' " + "to field '" +
                        field.getDeclaringClass().getCanonicalName() + '.' + field.getName() + "'.",
                        e);
            }
        }
    }

    /**
     * a field annotated by {@link Param}.
     */
    private static final class ParamFieldParamInfo extends FieldParamInfo {
        private ParamFieldParamInfo(final Field field, final Param param) {
            super(field, ParamValueRetriever.fromParam(param.value()));
        }
    }

    /**
     * a field annotated by {@link Header}.
     */
    private static final class HeaderFieldParamInfo extends FieldParamInfo {
        private HeaderFieldParamInfo(final Field field, final Header header) {
            super(field, ParamValueRetriever.fromHeader(header.value()));
        }
    }

    private abstract static class MethodParamInfo extends ParamInfo {
        private final Method method;

        private MethodParamInfo(final Method method, final ParamValueRetriever paramValueRetriever) {
            super(method.getParameterTypes()[0], paramValueRetriever);
            this.method = method;
            this.method.setAccessible(true);
        }

        @Override
        protected void setParamValue(final Object targetBean,
                                     final Object paramValue) {
            try {
                method.invoke(targetBean, paramValue);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "Can't use '" + paramValue + "' " + " to invoke method '" +
                        method.getDeclaringClass().getCanonicalName() + '.' + method.getName() + "'.",
                        e);
            }
        }
    }

    /**
     * a setter method annotated by {@link Param}.
     */
    private static final class ParamMethodParamInfo extends MethodParamInfo {
        private ParamMethodParamInfo(final Method method, final Param param) {
            super(method, ParamValueRetriever.fromParam(param.value()));
        }
    }

    /**
     * a setter method annotated by {@link Header}.
     */
    private static final class HeaderMethodParamInfo extends MethodParamInfo {
        private HeaderMethodParamInfo(final Method method, final Header header) {
            super(method, ParamValueRetriever.fromHeader(header.value()));
        }
    }
}
