/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.common.util;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Insecure {@link SecureRandom} which relies on {@link ThreadLocalRandom} for random number
 * generation.
 */
final class ThreadLocalInsecureRandom extends SecureRandom {

    // Forked from:
    // https://github.com/netty/netty/blob/11e6a77fba9ec7184a558d869373d0ce506d7236/handler/src/main/java/io/netty/handler/ssl/util/ThreadLocalInsecureRandom.java
    //
    // Changes:
    // - Use JDK's ThreadLocalRandom instead of Netty's.

    private static final long serialVersionUID = -8209473337192526191L;

    private static final SecureRandom INSTANCE = new ThreadLocalInsecureRandom();

    static SecureRandom current() {
        return INSTANCE;
    }

    private ThreadLocalInsecureRandom() { }

    @Override
    public String getAlgorithm() {
        return "insecure";
    }

    @Override
    public void setSeed(byte[] seed) { }

    @Override
    public void setSeed(long seed) { }

    @Override
    public void nextBytes(byte[] bytes) {
        random().nextBytes(bytes);
    }

    @Override
    public byte[] generateSeed(int numBytes) {
        final byte[] seed = new byte[numBytes];
        random().nextBytes(seed);
        return seed;
    }

    @Override
    public int nextInt() {
        return random().nextInt();
    }

    @Override
    public int nextInt(int n) {
        return random().nextInt(n);
    }

    @Override
    public boolean nextBoolean() {
        return random().nextBoolean();
    }

    @Override
    public long nextLong() {
        return random().nextLong();
    }

    @Override
    public float nextFloat() {
        return random().nextFloat();
    }

    @Override
    public double nextDouble() {
        return random().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return random().nextGaussian();
    }

    private static Random random() {
        return ThreadLocalRandom.current();
    }
}
