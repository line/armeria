package com.linecorp.armeria.internal.zookeeper;

import org.apache.curator.retry.ExponentialBackoffRetry;

/**
 * Zookeeper related constant values.
 */
public final class Constants {
    public static final int DEFAULT_CONNECT_TIMEOUT = 1000;
    public static final int DEFAULT_SESSION_TIMEOUT = 10000;

    public static final ExponentialBackoffRetry
            DEFAULT_RETRY_POLICY = new ExponentialBackoffRetry(DEFAULT_CONNECT_TIMEOUT, 3);
}
