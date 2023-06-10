package com.linecorp.armeria.client;

import java.util.function.Predicate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;

final class BlockingConditionalResponseAs<V> extends ConditionalResponseAs<HttpResponse, AggregatedHttpResponse, ResponseEntity<V>> {

    BlockingConditionalResponseAs(ResponseAs<HttpResponse, AggregatedHttpResponse> originalResponseAs,
                                  ResponseAs<AggregatedHttpResponse, ResponseEntity<V>> responseAs,
                                  Predicate<AggregatedHttpResponse> predicate) {
        super(originalResponseAs, responseAs, predicate);
    }

    public BlockingConditionalResponseAs<V> andThenJson(
            Class<? extends V> clazz, Predicate<AggregatedHttpResponse> predicate) {
        return (BlockingConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(clazz), predicate);
    }

    public BlockingConditionalResponseAs<V> andThenJson(
            Class<? extends V> clazz, ObjectMapper objectMapper, Predicate<AggregatedHttpResponse> predicate) {
        return (BlockingConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(clazz, objectMapper), predicate);
    }

    public BlockingConditionalResponseAs<V> andThenJson(
            TypeReference<? extends V> typeRef, Predicate<AggregatedHttpResponse> predicate) {
        return (BlockingConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(typeRef), predicate);
    }

    public BlockingConditionalResponseAs<V> andThenJson(
            TypeReference<? extends V> typeRef, ObjectMapper objectMapper, Predicate<AggregatedHttpResponse> predicate) {
        return (BlockingConditionalResponseAs<V>) andThen(AggregatedResponseAs.json(typeRef, objectMapper), predicate);
    }

    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(Class<? extends V> clazz) {
        return super.orElse(AggregatedResponseAs.json(clazz));
    }

    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(Class<? extends V> clazz, ObjectMapper objectMapper) {
        return super.orElse(AggregatedResponseAs.json(clazz, objectMapper));
    }

    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(TypeReference<? extends V> typeRef) {
        return super.orElse(AggregatedResponseAs.json(typeRef));
    }

    public ResponseAs<HttpResponse, ResponseEntity<V>> orElseJson(TypeReference<? extends V> typeRef, ObjectMapper objectMapper) {
        return super.orElse(AggregatedResponseAs.json(typeRef, objectMapper));
    }
}
