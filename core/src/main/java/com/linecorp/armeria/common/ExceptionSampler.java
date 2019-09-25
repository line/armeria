package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Function;

import org.jctools.maps.NonBlockingHashMap;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Sampler;

final class ExceptionSampler implements Sampler<Class<? extends Throwable>> {

    private final Map<Class<? extends Throwable>, Sampler<Class<? extends Throwable>>> samplers =
            new NonBlockingHashMap<>();
    private final Function<? super Class<? extends Throwable>,
            ? extends Sampler<Class<? extends Throwable>>> samplerFactory;
    private final String spec;

    ExceptionSampler(String spec) {
        samplerFactory = unused -> Sampler.of(spec);
        this.spec = spec;
    }

    @Override
    public boolean isSampled(Class<? extends Throwable> exceptionType) {
        requireNonNull(exceptionType, "exceptionType");
        return samplers.computeIfAbsent(exceptionType, samplerFactory).isSampled(exceptionType);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).addValue(spec).toString();
    }
}
