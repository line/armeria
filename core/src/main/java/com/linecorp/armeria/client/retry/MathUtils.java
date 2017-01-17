package com.linecorp.armeria.client.retry;

final class MathUtils {
    MathUtils() {}

    static long safeAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static long safeMultiply(long left, float right) {
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return (long) (left * right);
    }
}
