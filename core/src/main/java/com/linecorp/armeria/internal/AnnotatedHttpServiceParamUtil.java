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
package com.linecorp.armeria.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.HttpParameters.EMPTY_PARAMETERS;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.CaseFormat;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * Utility class for {@link com.linecorp.armeria.server.AnnotatedHttpService} parameters processing.
 */
public final class AnnotatedHttpServiceParamUtil {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServiceParamUtil.class);

    private AnnotatedHttpServiceParamUtil() { }

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
    public static HttpParameters httpParametersOf(ServiceRequestContext ctx,
                                                  HttpHeaders reqHeaders,
                                                  @Nullable AggregatedHttpMessage message) {
        try {
            Map<String, List<String>> parameters = null;
            final String query = ctx.query();
            if (query != null) {
                parameters = new QueryStringDecoder(query, false).parameters();
            }
            if (aggregationAvailable(reqHeaders)) {
                assert message != null;
                final String body = message.content().toStringAscii();
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
            logger.debug("Failed to decode query string: {}", e);
            return EMPTY_PARAMETERS;
        }
    }

    /**
     * Whether the request is available to be aggregated.
     */
    public static boolean aggregationAvailable(HttpHeaders reqHeaders) {
        final MediaType contentType = reqHeaders.contentType();
        // We aggregate request stream messages for the media type of form data currently.
        return contentType != null && MediaType.FORM_DATA.type().equals(contentType.type());
    }

    /**
     * Converts an HTTP Parameter String to proper data type.
     *
     * @param value HTTP Parameter value
     * @param type expected data type
     * @param enumConverter Enum Converter for Enum type
     * @param optionalWrapped whether this parameter wrapped in {@link Optional}
     *
     * @return parameter value converted to the given data type
     */
    @Nullable
    public static Object convertParameter(@Nullable String value,
                                          Class<?> type,
                                          EnumConverter<?> enumConverter,
                                          boolean optionalWrapped) {
        Object converted = null;
        if (value != null) {
            converted = type.isEnum() ? enumConverter.getEnumElement(value)
                                      : stringToType(value, type);
        }

        return optionalWrapped ? Optional.ofNullable(converted) : converted;
    }

    /**
     * Validates whether the specified {@link Class} is supported. Throws {@link IllegalArgumentException} if
     * it is not supported.
     *
     * @param clazz parameter type to be validated and normalized
     * @return normalized parameter type
     * @throws IllegalArgumentException if {@code clazz} is not a supported data type.
     */
    public static Class<?> validateAndNormalizeSupportedType(Class<?> clazz) {
        if (clazz == Byte.TYPE || clazz == Byte.class) {
            return Byte.TYPE;
        }
        if (clazz == Short.TYPE || clazz == Short.class) {
            return Short.TYPE;
        }
        if (clazz == Boolean.TYPE || clazz == Boolean.class) {
            return Boolean.TYPE;
        }
        if (clazz == Integer.TYPE || clazz == Integer.class) {
            return Integer.TYPE;
        }
        if (clazz == Long.TYPE || clazz == Long.class) {
            return Long.TYPE;
        }
        if (clazz == Float.TYPE || clazz == Float.class) {
            return Float.TYPE;
        }
        if (clazz == Double.TYPE || clazz == Double.class) {
            return Double.TYPE;
        }
        if (clazz.isEnum()) {
            return clazz;
        }
        if (clazz == String.class) {
            return String.class;
        }

        throw new IllegalArgumentException("Parameter type '" + clazz.getName() + "' is not supported.");
    }

    /**
     * Converts the given {@code str} to {@code T} type object. e.g., "42" -> 42.
     *
     * @throws IllegalArgumentException if {@code str} can't be deserialized to {@code T} type object.
     */
    @SuppressWarnings("unchecked")
    private static <T> T stringToType(String str, Class<T> clazz) {
        try {
            if (clazz == Byte.TYPE) {
                return (T) Byte.valueOf(str);
            } else if (clazz == Short.TYPE) {
                return (T) Short.valueOf(str);
            } else if (clazz == Boolean.TYPE) {
                return (T) Boolean.valueOf(str);
            } else if (clazz == Integer.TYPE) {
                return (T) Integer.valueOf(str);
            } else if (clazz == Long.TYPE) {
                return (T) Long.valueOf(str);
            } else if (clazz == Float.TYPE) {
                return (T) Float.valueOf(str);
            } else if (clazz == Double.TYPE) {
                return (T) Double.valueOf(str);
            } else if (clazz == String.class) {
                return (T) str;
            }
        } catch (NumberFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Can't convert '" + str + "' to type '" + clazz.getSimpleName() + "'.", e);
        }

        throw new IllegalArgumentException(
                "Type '" + clazz.getSimpleName() + "' can't be converted.");
    }

    /**
     * Returns the value of the {@link Param} annotation which is specified on the {@code element} if
     * the value is not blank. If the value is blank, it returns the name of the specified
     * {@code element} object which is an instance of {@link Parameter} or {@link Field}.
     */
    @Nullable
    public static String findParamName(AnnotatedElement element) {
        return findParamName(element, element);
    }

    /**
     * Returns the value of the {@link Param} annotation which is specified on the {@code element} if
     * the value is not blank. If the value is blank, it returns the name of the specified
     * {@code nameRetrievalTarget} object which is an instance of {@link Parameter} or {@link Field}.
     */
    @Nullable
    public static String findParamName(AnnotatedElement element, Object nameRetrievalTarget) {
        requireNonNull(element, "element");
        requireNonNull(nameRetrievalTarget, "nameRetrievalTarget");
        final Param param = element.getAnnotation(Param.class);
        if (param == null) {
            return null;
        }

        final String value = param.value();
        if (DefaultValues.isSpecified(value)) {
            checkArgument(!value.isEmpty(), "value is empty");
            return value;
        }
        return getName(nameRetrievalTarget);
    }

    /**
     * Returns the value of the {@link Header} annotation which is specified on the {@code element} if
     * the value is not blank. If the value is blank, it returns the name of the specified
     * {@code element} object which is an instance of {@link Parameter} or {@link Field}.
     *
     * <p>Note that the name of the specified {@code element} will be converted as
     * {@link CaseFormat#LOWER_HYPHEN} that the string elements are separated with one hyphen({@code -})
     * character. The value of the {@link Header} annotation will not be converted because it is clearly
     * specified by a user.
     */
    @Nullable
    public static String findHeaderName(AnnotatedElement element) {
        return findHeaderName(element, element);
    }

    /**
     * Returns the value of the {@link Header} annotation which is specified on the {@code element} if
     * the value is not blank. If the value is blank, it returns the name of the specified
     * {@code nameRetrievalTarget} object which is an instance of {@link Parameter} or {@link Field}.
     *
     * <p>Note that the name of the specified {@code nameRetrievalTarget} will be converted as
     * {@link CaseFormat#LOWER_HYPHEN} that the string elements are separated with one hyphen({@code -})
     * character. The value of the {@link Header} annotation will not be converted because it is clearly
     * specified by a user.
     */
    @Nullable
    public static String findHeaderName(AnnotatedElement element, Object nameRetrievalTarget) {
        requireNonNull(element, "element");
        requireNonNull(nameRetrievalTarget, "nameRetrievalTarget");
        final Header header = element.getAnnotation(Header.class);
        if (header == null) {
            return null;
        }

        final String value = header.value();
        if (DefaultValues.isSpecified(value)) {
            checkArgument(!value.isEmpty(), "value is empty");
            return value;
        }
        return toHeaderName(getName(nameRetrievalTarget));
    }

    /**
     * Returns whether the specified {@code element} is annotated with a {@link Param} or {@link Header}.
     */
    public static boolean hasParamOrHeader(AnnotatedElement element) {
        return element.isAnnotationPresent(Param.class) ||
               element.isAnnotationPresent(Header.class);
    }

    private static String getName(Object element) {
        if (element instanceof Parameter) {
            final Parameter parameter = (Parameter) element;
            if (!parameter.isNamePresent()) {
                throw new IllegalArgumentException(
                        "cannot obtain the name of the parameter or field automatically. " +
                        "Please make sure you compiled your code with '-parameters' option. " +
                        "If not, you need to specify parameter and header names with @" +
                        Param.class.getName() + " and @" + Header.class.getName() + '.');
            }
            return parameter.getName();
        }
        if (element instanceof Field) {
            return ((Field) element).getName();
        }
        throw new IllegalArgumentException("cannot find the name: " + element.getClass().getName());
    }

    @VisibleForTesting
    static String toHeaderName(String name) {
        requireNonNull(name, "name");
        checkArgument(!name.isEmpty(), "name is empty");

        final String upperCased = Ascii.toUpperCase(name);
        if (name.equals(upperCased)) {
            return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, name);
        }
        final String lowerCased = Ascii.toLowerCase(name);
        if (name.equals(lowerCased)) {
            return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, name);
        }
        // Ensure that the name does not contain '_'.
        // If it contains '_', we give up to make it lower hyphen case. Just converting it to lower case.
        if (name.indexOf('_') >= 0) {
            return lowerCased;
        }
        if (Ascii.isUpperCase(name.charAt(0))) {
            return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, name);
        } else {
            return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, name);
        }
    }

    public static class EnumConverter<T extends Enum<T>> {
        private final boolean isCaseSensitiveEnum;

        private final Map<String, T> enumMap;

        /**
         * Creates an instance for the given Enum class.
         *
         * @param enumClass the Enum class this EnumConverter should work for
         */
        public EnumConverter(final Class<T> enumClass) {
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
         * Get the Enum element value correspond with a name.
         *
         * @param str the name string of the Enum element
         *
         * @return the Enum element value correspond with the given name string
         */
        public T getEnumElement(final String str) {
            assert enumMap != null;
            final T result = enumMap.get(isCaseSensitiveEnum ? str : Ascii.toLowerCase(str));

            if (result == null) {
                throw new IllegalArgumentException("unknown enum value: " + str + " (expected: " +
                                                   enumMap.values() + ')');
            }

            return result;
        }
    }
}
