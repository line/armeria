/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

/**
 * Builds a {@link QueryParams}.
 *
 * @see QueryParams#builder()
 * @see QueryParams#toBuilder()
 */
public interface QueryParamsBuilder extends QueryParamGetters {

    /**
     * Returns a newly created {@link QueryParams} with the entries in this builder.
     */
    QueryParams build();

    /**
     * Specifies the hint about the number of parameters which may improve the memory efficiency and
     * performance of the underlying data structure.
     *
     * @return {@code this}
     * @throws IllegalStateException if the hint was specified too late after the underlying data structure
     *                               has been fully initialized.
     */
    QueryParamsBuilder sizeHint(int sizeHint);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @return the first parameter value or {@code null} if there is no such parameter
     */
    @Nullable
    String getAndRemove(String name);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @param defaultValue the default value
     * @return the first parameter value or {@code defaultValue} if there is no such parameter
     */
    String getAndRemove(String name, String defaultValue);

    /**
     * Removes all the parameters with the specified name and returns the removed parameter values.
     *
     * @param name the parameter name
     * @return a {@link List} of parameter values or an empty {@link List} if no values are found.
     */
    List<String> getAllAndRemove(String name);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @return the {@code int} value of the first value in insertion order or {@code null} if there is no
     *         such value or it can't be converted into {@code int}.
     */
    @Nullable
    Integer getIntAndRemove(String name);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @param defaultValue the default value
     * @return the {@code int} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted into {@code int}.
     */
    int getIntAndRemove(String name, int defaultValue);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @return the {@code long} value of the first value in insertion order or {@code null} if there is no such
     *         value or it can't be converted into {@code long}.
     */
    @Nullable
    Long getLongAndRemove(String name);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @param defaultValue the default value
     * @return the {@code long} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted into {@code long}.
     */
    long getLongAndRemove(String name, long defaultValue);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @return the {@code float} value of the first value in insertion order or {@code null} if there is
     *         no such value or it can't be converted into {@code float}.
     */
    @Nullable
    Float getFloatAndRemove(String name);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @param defaultValue the default value
     * @return the {@code float} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such value or it can't be converted into {@code float}.
     */
    float getFloatAndRemove(String name, float defaultValue);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @return the {@code double} value of the first value in insertion order or {@code null} if there is
     *         no such value or it can't be converted into {@code double}.
     */
    @Nullable
    Double getDoubleAndRemove(String name);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @param defaultValue the default value
     * @return the {@code double} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such value or it can't be converted into {@code double}.
     */
    double getDoubleAndRemove(String name, double defaultValue);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @return the milliseconds value of the first value in insertion order or {@code null} if there is no such
     *         value or it can't be converted into milliseconds.
     */
    @Nullable
    Long getTimeMillisAndRemove(String name);

    /**
     * Removes all the parameters with the specified name and returns the parameter value which was added
     * first.
     *
     * @param name the parameter name
     * @param defaultValue the default value
     * @return the milliseconds value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted into milliseconds.
     */
    long getTimeMillisAndRemove(String name, long defaultValue);

    /**
     * Adds a new parameter with the specified {@code name} and {@code value}.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder add(String name, String value);

    /**
     * Adds new parameters with the specified {@code name} and {@code values}. This method is semantically
     * equivalent to
     * <pre>{@code
     * for (String value : values) {
     *     builder.add(name, value);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder add(String name, Iterable<String> values);

    /**
     * Adds new parameters with the specified {@code name} and {@code values}. This method is semantically
     * equivalent to
     * <pre>{@code
     * for (String value : values) {
     *     builder.add(name, value);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder add(String name, String... values);

    /**
     * Adds all parameter names and values of the specified {@code entries}.
     *
     * @return {@code this}
     * @throws IllegalArgumentException if {@code entries == this}.
     */
    QueryParamsBuilder add(Iterable<? extends Entry<? extends String, String>> entries);

    /**
     * Adds all parameter names and values of the specified {@code entries}.
     *
     * @return {@code this}
     */
    default QueryParamsBuilder add(Map<String, String> entries) {
        requireNonNull(entries, "entries");
        return add(entries.entrySet());
    }

    /**
     * Adds a new parameter. The specified parameter value is converted into a {@link String}, as explained
     * in <a href="QueryParams.html#object-values">Specifying a non-String parameter value</a>.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder addObject(String name, Object value);

    /**
     * Adds a new parameter with the specified name and values. The specified parameter values are converted
     * into {@link String}s, as explained in <a href="QueryParams.html#object-values">Specifying a
     * non-String parameter value</a>. This method is equivalent to:
     * <pre>{@code
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder addObject(String name, Iterable<?> values);

    /**
     * Adds a new parameter with the specified name and values. The specified parameter values are converted
     * into {@link String}s, as explained in <a href="QueryParams.html#object-values">Specifying a
     * non-String parameter value</a>. This method is equivalent to:
     * <pre>{@code
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder addObject(String name, Object... values);

    /**
     * Adds all parameter names and values of the specified {@code entries}. The specified parameter values are
     * converted into {@link String}s, as explained in <a href="QueryParams.html#object-values">Specifying
     * a non-String parameter value</a>.
     *
     * @return {@code this}
     * @throws IllegalArgumentException if {@code entries == this}.
     */
    QueryParamsBuilder addObject(Iterable<? extends Entry<? extends String, ?>> entries);

    /**
     * Adds a new parameter.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder addInt(String name, int value);

    /**
     * Adds a new parameter.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder addLong(String name, long value);

    /**
     * Adds a new parameter.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder addFloat(String name, float value);

    /**
     * Adds a new parameter.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder addDouble(String name, double value);

    /**
     * Adds a new parameter.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder addTimeMillis(String name, long value);

    /**
     * Sets a parameter with the specified name and value. Any existing parameters with the same name are
     * overwritten.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder set(String name, String value);

    /**
     * Sets a new parameter with the specified name and values. This method is equivalent to
     * <pre>{@code
     * builder.remove(name);
     * for (String v : values) {
     *     builder.add(name, v);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder set(String name, Iterable<String> values);

    /**
     * Sets a parameter with the specified name and values. Any existing parameters with the specified name are
     * removed. This method is equivalent to:
     * <pre>{@code
     * builder.remove(name);
     * for (String v : values) {
     *     builder.add(name, v);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder set(String name, String... values);

    /**
     * Retains all current parameters but calls {@link #set(String, String)} for each entry in
     * the specified {@code entries}.
     *
     * @param entries the parameters used to set the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder set(Iterable<? extends Entry<? extends String, String>> entries);

    /**
     * Retains all current parameters but calls {@link #set(String, String)} for each entry in
     * the specified {@code entries}.
     *
     * @param entries the parameters used to set the parameter values
     * @return {@code this}
     */
    default QueryParamsBuilder set(Map<String, String> entries) {
        requireNonNull(entries, "entries");
        return set(entries.entrySet());
    }

    /**
     * Copies the entries missing in this parameters from the specified {@code entries}.
     * This method is a shortcut for:
     * <pre>{@code
     * entries.names().forEach(name -> {
     *     if (!builder.contains(name)) {
     *         builder.set(name, entries.getAll(name));
     *     }
     * });
     * }</pre>
     *
     * @return {@code this}
     */
    QueryParamsBuilder setIfAbsent(Iterable<? extends Entry<? extends String, String>> entries);

    /**
     * Sets a new parameter. Any existing parameters with the specified name are removed. The specified
     * parameter value is converted into a {@link String}, as explained in
     * <a href="QueryParams.html#object-values">Specifying a non-String parameter value</a>.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder setObject(String name, Object value);

    /**
     * Sets a parameter with the specified name and values. Any existing parameters with the specified name are
     * removed. The specified parameter values are converted into {@link String}s, as explained in
     * <a href="QueryParams.html#object-values">Specifying a non-String parameter value</a>.
     * This method is equivalent to:
     * <pre>{@code
     * builder.remove(name);
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder setObject(String name, Iterable<?> values);

    /**
     * Sets a parameter with the specified name and values. Any existing parameters with the specified name are
     * removed. The specified parameter values are converted into {@link String}s, as explained in
     * <a href="QueryParams.html#object-values">Specifying a non-String parameter value</a>.
     * This method is equivalent to:
     * <pre>{@code
     * builder.remove(name);
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the parameter name
     * @param values the parameter values
     * @return {@code this}
     */
    QueryParamsBuilder setObject(String name, Object... values);

    /**
     * Retains all current parameters but calls {@link #setObject(String, Object)} for each entry in
     * the specified {@code entries}. The specified parameter values are converted into {@link String}s,
     * as explained in <a href="QueryParams.html#object-values">Specifying a non-String parameter value</a>.
     *
     * @param entries the parameters used to set the values in this instance
     * @return {@code this}
     */
    QueryParamsBuilder setObject(Iterable<? extends Entry<? extends String, ?>> entries);

    /**
     * Sets a parameter with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder setInt(String name, int value);

    /**
     * Sets a parameter with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder setLong(String name, long value);

    /**
     * Sets a parameter with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder setFloat(String name, float value);

    /**
     * Sets a parameter with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder setDouble(String name, double value);

    /**
     * Sets a parameter with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return {@code this}
     */
    QueryParamsBuilder setTimeMillis(String name, long value);

    /**
     * Removes all parameters with the specified {@code name}.
     *
     * @param name the parameter name
     * @return {@code true} if at least one entry has been removed.
     */
    boolean remove(String name);

    /**
     * Removes all parameters with the specified {@code name}. Unlike {@link #remove(String)}
     * this method returns itself so that the caller can chain the invocations.
     *
     * @param name the parameter name
     * @return {@code this}
     */
    QueryParamsBuilder removeAndThen(String name);

    /**
     * Removes all parameters. After a call to this method, {@link #size()} becomes {@code 0}.
     *
     * @return {@code this}
     */
    QueryParamsBuilder clear();
}
