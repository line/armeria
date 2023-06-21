/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */
package com.linecorp.armeria.common.util;

/**
 * Sample if one of the samplers samples.
 */
final class OrSampler<T> implements Sampler<T> {

    private final Sampler<T> left;
    private final Sampler<T> right;

    OrSampler(Sampler<T> left, Sampler<T> right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean isSampled(T t) {
        // Assign the variables otherwise the short-circuiting will cause sampler to not be used.
        final boolean leftSampled = left.isSampled(t);
        final boolean rightSampled = right.isSampled(t);
        return leftSampled || rightSampled;
    }

    @Override
    public String toString() {
        return left.toString() + " or " + right.toString();
    }
}
