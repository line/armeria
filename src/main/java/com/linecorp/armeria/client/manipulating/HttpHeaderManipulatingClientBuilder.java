/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.manipulating;

import static java.util.Objects.requireNonNull;

import java.util.LinkedList;

import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;

import io.netty.util.AsciiString;

/**
 * A builder class to build an instance of {@link HttpHeaderManipulatingClient} class, providing
 * various methods to create the client, with a chain of manipulating operations. Each operation
 * calls the method on {@link HttpHeaders} with same name.
 *
 * @see HttpHeaders
 */
public class HttpHeaderManipulatingClientBuilder {

    private LinkedList<Consumer<HttpRequest>> requestManipulatingOperations;

    public HttpHeaderManipulatingClientBuilder() {
        this.requestManipulatingOperations = new LinkedList<>();
    }

    /**
     * Creates a decorator with {@link HttpHeaderManipulatingClient}.
     *
     * @return this::{@link #buildClient(Client)}
     */
    public Function<Client<? super HttpRequest, ? extends HttpResponse>, HttpHeaderManipulatingClient>
    newDecorator() {
        return this::buildClient;
    }

    /**
     * Creates a new {@link HttpHeaderManipulatingClient} instance with chained operations.
     *
     * @return new instance of {@link HttpHeaderManipulatingClient}
     */
    public HttpHeaderManipulatingClient buildClient(
            Client<? super HttpRequest, ? extends HttpResponse> delegate) {
        Manipulator<HttpRequest> manipulator = new Manipulator(requestManipulatingOperations);
        return new HttpHeaderManipulatingClient(delegate, manipulator);
    }

    private HttpHeaderManipulatingClientBuilder appendOperator(Consumer<HttpRequest> operator) {
        this.requestManipulatingOperations.add(operator);
        return this;
    }

    /**
     * Appends an add operation that adds a (header, value) entry to every request.
     *
     * @param header header to be added
     * @param value  the value to be added
     *
     * @return this instance
     *
     * @throws NullPointerException when header or value is null
     * @throws IllegalArgumentException when header or value is empty
     */

    public HttpHeaderManipulatingClientBuilder add(AsciiString header, String value) {
        requireNotEmpty(header, "header");
        requireNotEmpty(value, "value");
        return appendOperator(request -> request.headers().add(header, value));
    }

    /**
     * Appends a set operation that sets (header, value) entry to every request.
     *
     * <p>Existing header entries will be removed and a new, single (header, value) will replace them.
     *
     * @param header header to be replaced
     * @param value new value to replace with
     *
     * @return this instance
     *
     * @throws NullPointerException when header or value is null.
     * @throws IllegalArgumentException when header or value is empty, or header is restricted.
     */
    public HttpHeaderManipulatingClientBuilder set(AsciiString header, String value) {
        requireNotEmpty(header, "header");
        requireNotEmpty(value, "value");
        return appendOperator(request -> request.headers().set(header, value));
    }

    /**
     * Appends a set operation that sets (header, value) entry to every request, with a value returned by
     * generator function.
     *
     * <p>Existing header entries will be removed and a new, single (header, value) will replace them.
     *
     * @param header header to replace value
     * @param generator a function that receives current value and returns the new value to be set.
     *                  When request has no header entry of given name, generator gets null input.
     *                  When generator returns null or empty value, the returned value will be ignored.
     *
     * @return this instance
     *
     * @throws NullPointerException when header or generator function is null
     * @throws IllegalArgumentException when header is empty
     */
    public HttpHeaderManipulatingClientBuilder set(AsciiString header,
                                                   Function<String, String> generator) {
        requireNotEmpty(header, "header");
        requireNonNull(generator, "generator should not be null");
        return appendOperator(request -> {
            String oldValue = request.headers().get(header);
            String newValue = generator.apply(oldValue);
            if (newValue != null && !newValue.isEmpty()) {
                request.headers().set(header, newValue);
            }
        });
    }

    /**
     * Appends an remove operation that removes given header from request.
     * @param header header to remove
     *
     * @return this instance
     *
     * @throws NullPointerException when header is null
     * @throws IllegalArgumentException when header is empty
     */

    public HttpHeaderManipulatingClientBuilder remove(AsciiString header) {
        requireNotEmpty(header, "header");
        return appendOperator(request -> request.headers().remove(header));
    }

    /**
     * Appends an conditional remove operation that removes all (header, value) entries passes the test
     * of given predicate.
     *
     * @param predicate function that returns true when input (header, value) is to be removed.
     * @return this instance
     *
     * @throws NullPointerException when predicate is null
     */
    public HttpHeaderManipulatingClientBuilder remove(BiPredicate<AsciiString, String> predicate) {
        requireNonNull(predicate, "predicate should not be null");
        return appendOperator(request -> {
            HttpHeaders headers = request.headers();
            headers.forEach(entry -> {
                AsciiString header = entry.getKey();
                if (predicate.test(header, entry.getValue())) {
                    headers.remove(header);
                }
            });
        });
    }

    /**
     * Appends an update operation to manipulator to do some complex operation that cannot be done with
     * other simple operations.
     *
     * @param updater function that updates given request headers.
     * @return this instance
     * @throws NullPointerException when updater is null
     */
    public HttpHeaderManipulatingClientBuilder update(Consumer<HttpHeaders> updater) {
        requireNonNull(updater, "updater should not be null");
        return appendOperator(request -> updater.accept(request.headers()));
    }

    private static void requireNotEmpty(CharSequence str, String target) {
        requireNonNull(str, target + " should not be null");
        if (str.length() < 1) {
            throw new IllegalArgumentException(target + " should not be empty");
        }
    }
}
