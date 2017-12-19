/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.handler.codec.http2.Http2Exception;

/**
 * Provides methods that are useful for handling exceptions.
 */
public final class Exceptions {

    private static final Logger logger = LoggerFactory.getLogger(Exceptions.class);

    private static final Pattern IGNORABLE_SOCKET_ERROR_MESSAGE = Pattern.compile(
            "(?:connection.*(?:reset|closed|abort|broken)|broken.*pipe)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IGNORABLE_HTTP2_ERROR_MESSAGE = Pattern.compile(
            "(?:stream closed)", Pattern.CASE_INSENSITIVE);

    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    /**
     * Returns whether the verbose exception mode is enabled. When enabled, the exceptions frequently thrown by
     * Armeria will have full stack trace. When disabled, such exceptions will have empty stack trace to
     * eliminate the cost of capturing the stack trace.
     *
     * @deprecated Use {@link Flags#verboseExceptions()} instead.
     */
    @Deprecated
    public static boolean isVerbose() {
        return Flags.verboseExceptions();
    }

    /**
     * Logs the specified exception if it is {@linkplain #isExpected(Throwable) unexpected}.
     */
    public static void logIfUnexpected(Logger logger, Channel ch, Throwable cause) {
        if (!logger.isWarnEnabled() || isExpected(cause)) {
            return;
        }

        logger.warn("{} Unexpected exception:", ch, cause);
    }

    /**
     * Logs the specified exception if it is {@linkplain #isExpected(Throwable) unexpected}.
     */
    public static void logIfUnexpected(Logger logger, Channel ch, String debugData, Throwable cause) {

        if (!logger.isWarnEnabled() || isExpected(cause)) {
            return;
        }

        logger.warn("{} Unexpected exception: {}", ch, debugData, cause);
    }

    /**
     * Logs the specified exception if it is {@linkplain #isExpected(Throwable) unexpected}.
     */
    public static void logIfUnexpected(Logger logger, Channel ch, SessionProtocol protocol, Throwable cause) {
        if (!logger.isWarnEnabled() || isExpected(cause)) {
            return;
        }

        logger.warn("{}[{}] Unexpected exception:",
                    ch, protocolName(protocol), cause);
    }

    /**
     * Logs the specified exception if it is {@linkplain #isExpected(Throwable) unexpected}.
     */
    public static void logIfUnexpected(Logger logger, Channel ch, SessionProtocol protocol,
                                       String debugData, Throwable cause) {

        if (!logger.isWarnEnabled() || isExpected(cause)) {
            return;
        }

        logger.warn("{}[{}] Unexpected exception: {}",
                    ch, protocolName(protocol), debugData, cause);
    }

    private static String protocolName(SessionProtocol protocol) {
        return protocol != null ? protocol.uriText() : "<unknown>";
    }

    /**
     * Returns {@code true} if the specified exception is expected to occur in well-known circumstances.
     * <ul>
     *   <li>{@link ClosedChannelException}</li>
     *   <li>{@link ClosedSessionException}</li>
     *   <li>{@link IOException} - 'Connection reset/closed/aborted by peer'</li>
     *   <li>'Broken pipe'</li>
     *   <li>{@link Http2Exception} - 'Stream closed'</li>
     * </ul>
     */
    public static boolean isExpected(Throwable cause) {
        if (Flags.verboseExceptions()) {
            return true;
        }

        // We do not need to log every exception because some exceptions are expected to occur.

        if (cause instanceof ClosedChannelException || cause instanceof ClosedSessionException) {
            // Can happen when attempting to write to a channel closed by the other end.
            return true;
        }

        final String msg = cause.getMessage();
        if (msg != null) {
            if ((cause instanceof IOException || cause instanceof ChannelException) &&
                IGNORABLE_SOCKET_ERROR_MESSAGE.matcher(msg).find()) {
                // Can happen when socket error occurs.
                return true;
            }

            if (cause instanceof Http2Exception && IGNORABLE_HTTP2_ERROR_MESSAGE.matcher(msg).find()) {
                // Can happen when disconnected prematurely.
                return true;
            }
        }

        return false;
    }

    /**
     * Empties the stack trace of the specified {@code exception}.
     */
    public static <T extends Throwable> T clearTrace(T exception) {
        requireNonNull(exception, "exception");
        exception.setStackTrace(EMPTY_STACK_TRACE);
        return exception;
    }

    /**
     * Throws the specified exception violating the {@code throws} clause of the enclosing method.
     * This method is useful when you need to rethrow a checked exception in {@link Function}, {@link Consumer},
     * {@link Supplier} and {@link Runnable}, only if you are sure that the rethrown exception will be handled
     * as a {@link Throwable} or an {@link Exception}. For example:
     * <pre>{@code
     * CompletableFuture.supplyAsync(() -> {
     *     try (FileInputStream fin = new FileInputStream(...)) {
     *         ....
     *         return someValue;
     *     } catch (IOException e) {
     *         // 'throw e;' won't work because Runnable.run() does not allow any checked exceptions.
     *         return Exceptions.throwUnsafely(e);
     *     }
     * }).exceptionally(CompletionActions::log);
     * }</pre>
     *
     * @return This method never returns because it always throws an exception. However, combined with an
     *         arbitrary return clause, you can terminate any non-void function with a single statement.
     *         e.g. {@code return Exceptions.throwUnsafely(...);} vs.
     *              {@code Exceptions.throwUnsafely(...); return null;}
     */
    public static <T> T throwUnsafely(Throwable cause) {
        doThrowUnsafely(requireNonNull(cause, "cause"));
        return null;
    }

    // This black magic causes the Java compiler to believe E is an unchecked exception type.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void doThrowUnsafely(Throwable cause) throws E {
        throw (E) cause;
    }

    /**
     * Returns the stack trace of the specified {@code exception} as a {@link String} instead.
     *
     * @deprecated Use {@link Throwables#getStackTraceAsString(Throwable)}.
     */
    @Deprecated
    public static String traceText(Throwable exception) {
        return Throwables.getStackTraceAsString(exception);
    }

    private Exceptions() {}
}
