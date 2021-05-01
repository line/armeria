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
 * provided by this class must be used with extreme care, because otherwise it will result in unpredictable
 * behavior.
 *
 * <p>Most common mistake is to call or recurse info a method that uses the same thread-local variable.
 * For example, the following code will produce a garbled string:
 * <pre>{@code
 * > class A {
 * >     @Override
 * >     public String toString() {
 * >         return TemporaryThreadLocals.get().append('"').append(new B()).append('"').toString();
 * >     }
 * > }
 * > class B {
 * >     @Override
 * >     public String toString() {
 * >         return TemporaryThreadLocals.get().append("foo").toString();
 * >     }
 * > }
 * > // The following assertion fails, because A.toString() returns "foofoo\"".
 * > assert "\"foo\"".equals(new A().toString());
 * }</pre></p>
 *
 * <p>A general rule of thumb is not to call other methods while using the thread-local variables provided by
 * this class, unless you are sure the methods you're calling never uses the same thread-local variables.</p>
 */
public final class TemporaryThreadLocals {

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
     * Returns the current {@link Thread}'s {@link TemporaryThreadLocals}.
     */
    public static TemporaryThreadLocals get() {
        final Thread thread = Thread.currentThread();
        if (thread instanceof EventLoopThread) {
            return ((EventLoopThread) thread).temporaryThreadLocals;
        } else {
            return fallback.get();
        }
    }

    private byte[] byteArray;
    private char[] charArray;
    private int[] intArray;
    private StringBuilder stringBuilder;

    private boolean byteArrayLock;
    private boolean charArrayLock;
    private boolean intArrayLock;
    private boolean stringBuilderLock;

    TemporaryThreadLocals() {
        clear();
    }

    @VisibleForTesting
    void clear() {
        byteArray = EmptyArrays.EMPTY_BYTES;
        charArray = EmptyArrays.EMPTY_CHARS;
        intArray = EmptyArrays.EMPTY_INTS;
        stringBuilder = inflate(new StringBuilder());

        byteArrayLock = false;
        charArrayLock = false;
        intArrayLock = false;
        stringBuilderLock = false;
    }

    /**
     * Returns a thread-local byte array whose length is equal to or greater than the specified
     * {@code minCapacity}.
     */
    public byte[] byteArray(int minCapacity) {
        checkState(!byteArrayLock, "Cannot be called before releasing");
        byteArrayLock = true;
        final byte[] byteArray = this.byteArray;
        if (byteArray.length >= minCapacity) {
            return byteArray;
        }

        return allocateByteArray(minCapacity);
    }

    /**
     * Release the byte array to be reused.
     */
    public void releaseByteArray() {
        byteArrayLock = false;
    }

    private byte[] allocateByteArray(int minCapacity) {
        final byte[] byteArray = new byte[minCapacity];
        if (minCapacity <= MAX_BYTE_ARRAY_CAPACITY) {
            this.byteArray = byteArray;
        } else {
            byteArrayLock = false;
        }
        return byteArray;
    }

    /**
     * Returns a thread-local character array whose length is equal to or greater than the specified
     * {@code minCapacity}.
     */
    public char[] charArray(int minCapacity) {
        checkState(!charArrayLock, "Cannot be called before releasing");
        charArrayLock = true;
        final char[] charArray = this.charArray;
        if (charArray.length >= minCapacity) {
            return charArray;
        }

        return allocateCharArray(minCapacity);
    }

    /**
     * Release the char array to be reused.
     */
    public void releaseCharArray() {
        charArrayLock = false;
    }

    private char[] allocateCharArray(int minCapacity) {
        final char[] charArray = new char[minCapacity];
        if (minCapacity <= MAX_CHAR_ARRAY_CAPACITY) {
            this.charArray = charArray;
        } else {
            charArrayLock = false;
        }
        return charArray;
    }

    /**
     * Returns a thread-local integer array whose length is equal to or greater than the specified
     * {@code minCapacity}.
     */
    public int[] intArray(int minCapacity) {
        checkState(!intArrayLock, "Cannot be called before releasing");
        intArrayLock = true;
        final int[] intArray = this.intArray;
        if (intArray.length >= minCapacity) {
            return intArray;
        }

        return allocateIntArray(minCapacity);
    }

    /**
     * Release the int array to be reused.
     */
    public void releaseIntArray() {
        intArrayLock = false;
    }

    private int[] allocateIntArray(int minCapacity) {
        final int[] intArray = new int[minCapacity];
        if (minCapacity <= MAX_INT_ARRAY_CAPACITY) {
            this.intArray = intArray;
        } else {
            intArrayLock = false;
        }
        return intArray;
    }

    /**
     * Returns a thread-local {@link StringBuilder}.
     */
    public StringBuilder stringBuilder() {
        checkState(!stringBuilderLock, "Cannot be called before releasing");
        stringBuilderLock = true;
        final StringBuilder stringBuilder = this.stringBuilder;
        if (stringBuilder.capacity() > MAX_STRING_BUILDER_CAPACITY) {
            return this.stringBuilder = inflate(new StringBuilder(MAX_STRING_BUILDER_CAPACITY));
        } else {
            stringBuilder.setLength(0);
            return stringBuilder;
        }
    }

    /**
     * Release the string builder to be reused.
     */
    public void releaseStringBuilder() {
        stringBuilderLock = false;
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
