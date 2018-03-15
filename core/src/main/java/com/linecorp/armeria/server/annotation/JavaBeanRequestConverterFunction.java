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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
public class JavaBeanRequestConverterFunction implements RequestConverterFunction {
    private static final ConcurrentMap<Class<?>, BeanParamInfo> beanParamInfoCache = new MapMaker().weakKeys()
                                                                                                   .makeMap();

    private static final ConcurrentMap<Class<?>, Boolean> ignoredResultTypeCache = new MapMaker().weakKeys()
                                                                                                 .makeMap();

    /**
     * Initialization process for a expectedResultType.
     *
     * @param expectedResultType a candidate data type, which will converted by JavaBeanRequestConverterFunction
     */
    public static synchronized void initFor(final Class<?> expectedResultType) {
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
        final List<FieldParamInfo> fieldParamList = new ArrayList<>();
        getAllFields(expectedResultType).forEach(
                field -> {
                    final Param param = field.getAnnotation(Param.class);
                    if (param != null) {
                        fieldParamList.add(new ParamFieldParamInfo(field, param));
                    }

                    final Header header = field.getAnnotation(Header.class);
                    if (header != null) {
                        fieldParamList.add(new HeaderFieldParamInfo(field, header));
                    }
                }
        );

        if (fieldParamList.isEmpty()) {
            // there is no fields annotated by @Param or @Header
            // we should not handle this result type
            return null;
        }

        return new BeanParamInfo(expectedResultType, fieldParamList);
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

        beanParamInfo.fieldParamList
                .forEach(
                        fieldParamInfo -> fieldParamInfo.setValue(ctx, request, httpParams, requestBean)
                );

        return requestBean;
    }

    private static final class BeanParamInfo {
        private final Class<?> beanClass;

        private final Constructor<?> constructor;

        private final List<FieldParamInfo> fieldParamList;

        private BeanParamInfo(final Class<?> beanClass, final List<FieldParamInfo> fieldParamList) {
            this.beanClass = beanClass;
            this.fieldParamList = fieldParamList;

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

    private abstract static class FieldParamInfo {
        private final Field field;

        private final Class<?> type;

        private final EnumConverter<?> enumConverter;

        private FieldParamInfo(final Field field) {
            this.field = field;
            this.field.setAccessible(true);

            type = validateAndNormalizeSupportedType(field.getType());

            if (type.isEnum()) {
                enumConverter = new EnumConverter<>(type.asSubclass(Enum.class));
            } else {
                enumConverter = null;
            }
        }

        protected abstract String getParamValue(ServiceRequestContext ctx,
                                                AggregatedHttpMessage request,
                                                MutableValueHolder<HttpParameters> httpParams);

        private void setValue(final ServiceRequestContext ctx,
                              final AggregatedHttpMessage request,
                              final MutableValueHolder<HttpParameters> httpParams,
                              final Object targetBean) {
            final String strValue = getParamValue(ctx, request, httpParams);

            if (strValue == null) {
                return;
            }

            final Object fieldValue = convertParameter(strValue, type, enumConverter, false);

            try {
                field.set(targetBean, fieldValue);
            } catch (final Exception e) {
                throw new IllegalArgumentException(
                        "Can't set '" + strValue + "' " + "to field '" +
                        field.getDeclaringClass().getCanonicalName() + '.' + field.getName() + "'.",
                        e);
            }
        }
    }

    private static final class ParamFieldParamInfo extends FieldParamInfo {
        private final String paramName;

        private ParamFieldParamInfo(final Field field, final Param param) {
            super(field);

            paramName = param.value();
        }

        @Override
        protected String getParamValue(final ServiceRequestContext ctx,
                                       final AggregatedHttpMessage request,
                                       final MutableValueHolder<HttpParameters> httpParams) {
            String paramValueStr = ctx.pathParam(paramName);
            if (paramValueStr == null) {
                paramValueStr =
                        httpParams.computeIfAbsent(
                                () -> httpParametersOf(ctx, request.headers(), request)
                        ).get(paramName);
            }

            return paramValueStr;
        }
    }

    private static final class HeaderFieldParamInfo extends FieldParamInfo {
        private final String headerName;

        private HeaderFieldParamInfo(final Field field, final Header header) {
            super(field);

            headerName = header.value();
        }

        @Override
        protected String getParamValue(final ServiceRequestContext ctx,
                                       final AggregatedHttpMessage request,
                                       final MutableValueHolder<HttpParameters> httpParams) {
            return request.headers().get(AsciiString.of(headerName));
        }
    }
}
