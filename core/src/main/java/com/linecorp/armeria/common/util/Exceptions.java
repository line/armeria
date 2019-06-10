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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;

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

    private static final Pattern IGNORABLE_SOCKET_ERROR_MESSAGE = Pattern.compile(
            "(?:connection.*(?:reset|closed|abort|broken)|broken.*pipe)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IGNORABLE_HTTP2_ERROR_MESSAGE = Pattern.compile(
            "(?:stream closed)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IGNORABLE_TLS_ERROR_MESSAGE = Pattern.compile(
            "(?:closed already)", Pattern.CASE_INSENSITIVE);

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
    public static void logIfUnexpected(Logger logger, Channel ch,
                                       @Nullable SessionProtocol protocol, Throwable cause) {
        if (!logger.isWarnEnabled() || isExpected(cause)) {
            return;
        }

        logger.warn("{}[{}] Unexpected exception:",
                    ch, protocolName(protocol), cause);
    }

    /**
     * Logs the specified exception if it is {@linkplain #isExpected(Throwable) unexpected}.
     */
    public static void logIfUnexpected(Logger logger, Channel ch, @Nullable SessionProtocol protocol,
                                       String debugData, Throwable cause) {

        if (!logger.isWarnEnabled() || isExpected(cause)) {
            return;
        }

        logger.warn("{}[{}] Unexpected exception: {}",
                    ch, protocolName(protocol), debugData, cause);
    }

    private static String protocolName(@Nullable SessionProtocol protocol) {
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
     *   <li>{@link SSLException} - 'SSLEngine closed already'</li>
     * </ul>
     *
     * @see Flags#verboseSocketExceptions()
     */
    public static boolean isExpected(Throwable cause) {
        if (Flags.verboseSocketExceptions()) {
            return false;
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

            if (cause instanceof SSLException && IGNORABLE_TLS_ERROR_MESSAGE.matcher(msg).find()) {
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
    @SuppressWarnings("ReturnOfNull")
    public static <T> T throwUnsafely(Throwable cause) {
        doThrowUnsafely(requireNonNull(cause, "cause"));
        return null; // Never reaches here.
    }

    // This black magic causes the Java compiler to believe E is an unchecked exception type.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void doThrowUnsafely(Throwable cause) throws E {
        throw (E) cause;
    }

    /**
     * Returns the cause of the specified {@code throwable} peeling it recursively, if it is one of the
     * {@link CompletionException}, {@link ExecutionException}, {@link InvocationTargetException}
     * or {@link ExceptionInInitializerError}.
     * Otherwise returns the {@code throwable}.
     */
    public static Throwable peel(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        Throwable cause = throwable.getCause();
        while (cause != null && cause != throwable &&
               (throwable instanceof CompletionException || throwable instanceof ExecutionException ||
                throwable instanceof InvocationTargetException ||
                throwable instanceof ExceptionInInitializerError)) {
            throwable = cause;
            cause = throwable.getCause();
        }
        return throwable;
    }

    /**
     * Converts the stack trace of the specified {@code exception} into a {@link String}.
     * This method always uses {@code '\n'} as a line delimiter, unlike
     * {@link Throwable#printStackTrace(PrintWriter)} or {@link Throwables#getStackTraceAsString(Throwable)}.
     */
    public static String traceText(Throwable exception) {
        final StackTraceWriter writer = new StackTraceWriter();
        exception.printStackTrace(writer);
        return writer.toString();
    }

    private Exceptions() {}

    /**
     * A variant of {@link PrintWriter} that 1) is backed by a {@link StringWriter}, 2) removes locking,
     * 3) always uses {@code '\n'} as a line delimiter.
     */
    private static final class StackTraceWriter extends PrintWriter {
        StackTraceWriter() {
            super(new StringWriter(512));
        }

        @Override
        public String toString() {
            return out.toString();
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public void write(int c) {
            try {
                out.write(c);
            } catch (IOException e) {
                setError();
            }
        }

        @Override
        public void write(char[] buf, int off, int len) {
            try {
                out.write(buf, off, len);
            } catch (IOException e) {
                setError();
            }
        }

        @Override
        public void write(char[] buf) {
            try {
                out.write(buf);
            } catch (IOException e) {
                setError();
            }
        }

        @Override
        public void write(String s, int off, int len) {
            try {
                out.write(s, off, len);
            } catch (IOException e) {
                setError();
            }
        }

        @Override
        public void write(String s) {
            try {
                out.write(s);
            } catch (IOException e) {
                setError();
            }
        }

        @Override
        public void println() {
            try {
                out.write('\n');
            } catch (IOException e) {
                setError();
            }
        }

        @Override
        public void println(boolean x) {
            print(x);
            println();
        }

        @Override
        public void println(char x) {
            print(x);
            println();
        }

        @Override
        public void println(int x) {
            print(x);
            println();
        }

        @Override
        public void println(long x) {
            print(x);
            println();
        }

        @Override
        public void println(float x) {
            print(x);
            println();
        }

        @Override
        public void println(double x) {
            print(x);
            println();
        }

        @Override
        public void println(char[] x) {
            print(x);
            println();
        }

        @Override
        public void println(String x) {
            print(x);
            println();
        }

        @Override
        public void println(Object x) {
            print(x);
            println();
        }
    }
}
