/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import java.io.IOException;
import java.util.function.Predicate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;

final class AggregatedResponseAs {
    private static final HttpStatusClassPredicates SUCCESS_PREDICATE =
            HttpStatusClassPredicates.of(HttpStatusClass.SUCCESS);

    static ResponseAs<AggregatedHttpResponse, ResponseEntity<byte[]>> bytes() {
        return response -> ResponseEntity.of(response.headers(), response.content().array(),
                                             response.trailers());
    }

    static ResponseAs<AggregatedHttpResponse, ResponseEntity<String>> string() {
        return response -> ResponseEntity.of(response.headers(), response.contentUtf8(), response.trailers());
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(Class<? extends T> clazz) {
        return json(clazz, SUCCESS_PREDICATE);
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(
            Class<? extends T> clazz, Predicate<? super HttpStatus> predicate) {
        return response -> newJsonResponseEntity(response, bytes -> JacksonUtil.readValue(bytes, clazz),
                                                 predicate);
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(Class<? extends T> clazz,
                                                                          ObjectMapper mapper) {
        return json(clazz, mapper, SUCCESS_PREDICATE);
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(
            Class<? extends T> clazz, ObjectMapper mapper, Predicate<? super HttpStatus> predicate) {
        return response -> newJsonResponseEntity(response, bytes -> mapper.readValue(bytes, clazz),
                                                 predicate);
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(TypeReference<? extends T> typeRef) {
        return json(typeRef, SUCCESS_PREDICATE);
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(
            TypeReference<? extends T> typeRef, Predicate<? super HttpStatus> predicate) {
        return response -> newJsonResponseEntity(response, bytes -> JacksonUtil.readValue(bytes, typeRef),
                                                 predicate);
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(TypeReference<? extends T> typeRef,
                                                                          ObjectMapper mapper) {
        return json(typeRef, mapper, SUCCESS_PREDICATE);
    }

    static <T> ResponseAs<AggregatedHttpResponse, ResponseEntity<T>> json(
            TypeReference<? extends T> typeRef, ObjectMapper mapper, Predicate<? super HttpStatus> predicate) {
        return response -> newJsonResponseEntity(response, bytes -> mapper.readValue(bytes, typeRef),
                                                 predicate);
    }

    private static <T> ResponseEntity<T> newJsonResponseEntity(AggregatedHttpResponse response,
                                                               JsonDecoder<T> decoder,
                                                               Predicate<? super HttpStatus> predicate) {
        if (!predicate.test(response.status())) {
            if (predicate instanceof HttpStatusPredicate) {
                throw newInvalidHttpStatusResponseException(
                        response, ((HttpStatusPredicate) predicate).status());
            } else if (predicate instanceof HttpStatusClassPredicates) {
                throw newInvalidHttpStatusClassResponseException(
                        response, ((HttpStatusClassPredicates) predicate).statusClass());
            } else {
                throw newInvalidPredicateResponseException(response, predicate);
            }
        }

        try {
            return ResponseEntity.of(response.headers(), decoder.decode(response.content().array()),
                                     response.trailers());
        } catch (IOException e) {
            return Exceptions.throwUnsafely(new InvalidHttpResponseException(response, e));
        }
    }

    private static InvalidHttpResponseException newInvalidHttpStatusResponseException(
            AggregatedHttpResponse response, HttpStatus status) {
        return new InvalidHttpResponseException(
                response, "status: " + response.status() +
                          " (expect: the " + status.reasonPhrase() + " class (" + status.codeAsText() +
                          "). response: " + response, null);
    }

    private static InvalidHttpResponseException newInvalidHttpStatusClassResponseException(
            AggregatedHttpResponse response, HttpStatusClass httpStatusClass) {
        return new InvalidHttpResponseException(
                response, "status: " + response.status() +
                          " (expect: the " + httpStatusClass + " class response: " + response, null);
    }

    private static InvalidHttpResponseException newInvalidPredicateResponseException(
            AggregatedHttpResponse response, Predicate<? super HttpStatus> predicate) {
        return new InvalidHttpResponseException(
                response, "status: " + response.status() +
                          " is not expected by predicate method. response: " + response +
                          ", predicate: " + predicate, null);
    }

    @FunctionalInterface
    private interface JsonDecoder<T> {
        T decode(byte[] bytes) throws IOException;
    }

    private AggregatedResponseAs() {}
}
