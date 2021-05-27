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
package com.linecorp.armeria.internal.common.util;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;

import io.netty.util.internal.EmptyArrays;

/**
 * Provides various thread-local variables used by Armeria internally, mostly to avoid allocating
 * short-living objects, such as {@code byte[]} and {@link StringBuilder}. Keep in mind that the variables
 * provided by this class must be used and released with extreme care, because otherwise it will result in
 * unpredictable behavior.
 *
 * <p>Most common mistake is to call or recurse info a method that uses the same thread-local variable.
 * For example, the following code will produce a garbled string:
 * <pre>{@code
 * > class A {
 * >     @Override
 * >     public String toString() {
 * >         TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire();
 * >         StringBuilder stringBuilder = tempThreadLocals.stringBuilder();
 * >         tempThreadLocals.close();
 * >         return stringBuilder.append('"').append(new B()).append('"').toString();
 * >     }
 * > }
 * > class B {
 * >     @Override
 * >     public String toString() {
 * >         StringBuilder stringBuilder = TemporaryThreadLocals.acquire().stringBuilder();
 * >         return stringBuilder.append("foo").toString();
 * >     }
 * > }
 * > // The following assertion fails, because A.toString() returns "foofoo\"".
 * > assert "\"foo\"".equals(new A().toString());
 * }</pre>
 *
 * <p>A release method is helpful to not only prevent from being corrupted but also recognize the situation
 * about nested use. Specifically, as this utility implements {@link AutoCloseable}, the release method will
 * be called successfully with try-with-resources statement.
 * <pre>{@code
 * > class A {
 * >     @Override
 * >     public String toString() {
 * >         try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
 * >             StringBuilder stringBuilder = tempThreadLocals.stringBuilder();
 * >             return stringBuilder.append('"').append(new B()).append('"').toString();
 * >         }
 * >     }
 * > }
 * > class B {
 * >     @Override
 * >     public String toString() {
 * >         try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
 * >             StringBuilder stringBuilder = tempThreadLocals.stringBuilder();
 * >             return stringBuilder.append("foo").toString();
 * >         }
 * >     }
 * > }
 * }</pre>
 * In this case, instead of unpredictable behavior, an {@link IllegalStateException} occurs.
 *
 * <p>A general rule of thumb is not to call other methods while using the thread-local variables provided by
 * this class, unless you are sure the methods you're calling never uses the same thread-local variables.</p>
 */
public final class TemporaryThreadLocals implements AutoCloseable {

    @VisibleForTesting
    static final int MAX_BYTE_ARRAY_CAPACITY = 4096;

    @VisibleForTesting
    static final int MAX_CHAR_ARRAY_CAPACITY = 4096;

    @VisibleForTesting
    static final int MAX_INT_ARRAY_CAPACITY = 4096;

    @VisibleForTesting
    static final int MAX_STRING_BUILDER_CAPACITY = 4096;

    private static final ThreadLocal<TemporaryThreadLocals> fallback =
            ThreadLocal.withInitial(TemporaryThreadLocals::new);

    /**
     * Acquire the current {@link Thread}'s {@link TemporaryThreadLocals} with lock. It should be used with
     * try-with-resources statement.
     */
    public static TemporaryThreadLocals acquire() {
        final Thread thread = Thread.currentThread();
        final TemporaryThreadLocals tempThreadLocals;
        if (thread instanceof EventLoopThread) {
            tempThreadLocals = ((EventLoopThread) thread).temporaryThreadLocals;
        } else {
            tempThreadLocals = fallback.get();
        }
        tempThreadLocals.lock();
        return tempThreadLocals;
    }

    private boolean lock;
    private byte[] byteArray;
    private char[] charArray;
    private int[] intArray;
    private StringBuilder stringBuilder;

    TemporaryThreadLocals() {
        clear();
    }

    public void lock() {
        checkState(!lock, "Cannot be acquired before releasing the resource");
        lock = true;
    }

    @Override
    public void close() {
        lock = false;
    }

    @VisibleForTesting
    void clear() {
        lock = false;
        byteArray = EmptyArrays.EMPTY_BYTES;
        charArray = EmptyArrays.EMPTY_CHARS;
        intArray = EmptyArrays.EMPTY_INTS;
        stringBuilder = inflate(new StringBuilder());
    }

    /**
     * Returns a thread-local byte array whose length is equal to or greater than the specified
     * {@code minCapacity}.
     */
    public byte[] byteArray(int minCapacity) {
        final byte[] byteArray = this.byteArray;
        if (byteArray.length >= minCapacity) {
            return byteArray;
        }
        return allocateByteArray(minCapacity);
    }

    private byte[] allocateByteArray(int minCapacity) {
        final byte[] byteArray = new byte[minCapacity];
        if (minCapacity <= MAX_BYTE_ARRAY_CAPACITY) {
            this.byteArray = byteArray;
        }
        return byteArray;
    }

    /**
     * Returns a thread-local character array whose length is equal to or greater than the specified
     * {@code minCapacity}.
     */
    public char[] charArray(int minCapacity) {
        final char[] charArray = this.charArray;
        if (charArray.length >= minCapacity) {
            return charArray;
        }
        return allocateCharArray(minCapacity);
    }

    private char[] allocateCharArray(int minCapacity) {
        final char[] charArray = new char[minCapacity];
        if (minCapacity <= MAX_CHAR_ARRAY_CAPACITY) {
            this.charArray = charArray;
        }
        return charArray;
    }

    /**
     * Returns a thread-local integer array whose length is equal to or greater than the specified
     * {@code minCapacity}.
     */
    public int[] intArray(int minCapacity) {
        final int[] intArray = this.intArray;
        if (intArray.length >= minCapacity) {
            return intArray;
        }
        return allocateIntArray(minCapacity);
    }

    private int[] allocateIntArray(int minCapacity) {
        final int[] intArray = new int[minCapacity];
        if (minCapacity <= MAX_INT_ARRAY_CAPACITY) {
            this.intArray = intArray;
        }
        return intArray;
    }

    /**
     * Returns a thread-local {@link StringBuilder}.
     */
    public StringBuilder stringBuilder() {
        final StringBuilder stringBuilder = this.stringBuilder;
        if (stringBuilder.capacity() > MAX_STRING_BUILDER_CAPACITY) {
            return this.stringBuilder = inflate(new StringBuilder(MAX_STRING_BUILDER_CAPACITY));
        } else {
            stringBuilder.setLength(0);
            return stringBuilder;
        }
    }

    /**
     * Switches the internal representation of the specified {@link StringBuilder} from LATIN1 to UTF16,
     * so that character operations do not have performance penalty.
     */
    @SuppressWarnings("checkstyle:AvoidEscapedUnicodeCharacters")
    private static StringBuilder inflate(StringBuilder stringBuilder) {
        stringBuilder.append('\u0100');
        stringBuilder.setLength(0);
        return stringBuilder;
    }
}
