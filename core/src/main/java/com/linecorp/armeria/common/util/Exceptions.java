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
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.ExceptionClassifier;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2Exception;

/**
 * Provides methods that are useful for handling exceptions.
 */
public final class Exceptions {

    private static final Logger logger = LoggerFactory.getLogger(Exceptions.class);

    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    private static final List<ExceptionClassifier> exceptionClassifiers;

    static {
        exceptionClassifiers = ImmutableList.copyOf(
                ServiceLoader.load(ExceptionClassifier.class,
                                   Exceptions.class.getClassLoader()));

        if (!exceptionClassifiers.isEmpty()) {
            logger.debug("Available {}s: {}", ExceptionClassifier.class.getSimpleName(),
                         exceptionClassifiers);
        }
    }

    /**
     * Logs the specified exception if it is {@linkplain #isExpected(Throwable) unexpected}.
     */
    public static void logIfUnexpected(Logger logger, Channel ch, Throwable cause) {
        requireNonNull(logger, "logger");
        requireNonNull(ch, "ch");
        requireNonNull(cause, "cause");
        if (!logger.isWarnEnabled() || isExpected(cause)) {
            return;
        }

        logger.warn("{} Unexpected exception:", ch, cause);
    }

    /**
     * Logs the specified exception if it is {@linkplain #isExpected(Throwable) unexpected}.
     */
    public static void logIfUnexpected(Logger logger, Channel ch, String debugData, Throwable cause) {
        requireNonNull(logger, "logger");
        requireNonNull(ch, "ch");
        requireNonNull(debugData, "debugData");
        requireNonNull(cause, "cause");
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
        requireNonNull(logger, "logger");
        requireNonNull(ch, "ch");
        requireNonNull(cause, "cause");
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
        requireNonNull(logger, "logger");
        requireNonNull(ch, "ch");
        requireNonNull(debugData, "debugData");
        requireNonNull(cause, "cause");
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
        requireNonNull(cause, "cause");
        for (ExceptionClassifier classifier : exceptionClassifiers) {
            try {
                if (classifier.isExpected(cause)) {
                    return true;
                }
            } catch (Throwable t) {
                // ignore it.
            }
        }

        return false;
    }

    /**
     * Returns {@code true} if the specified exception will cancel the current request or response stream.
     */
    public static boolean isStreamCancelling(Throwable cause) {
        // TODO(minwoox): return true if the cause is "io.grpc.StatusRuntimeException: CANCELLED"
        requireNonNull(cause, "cause");
        if (cause instanceof UnprocessedRequestException) {
            cause = cause.getCause();
        }

        for (ExceptionClassifier classifier : exceptionClassifiers) {
            try {
                if (classifier.isStreamCancelling(cause)) {
                    return true;
                }
            } catch (Throwable t) {
                // ignore it.
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

    // This is copied from
    // https://github.com/ReactiveX/RxJava/blob/v3.0.0/src/main/java/io/reactivex/rxjava3/exceptions/Exceptions.java

    /**
     * Throws a particular {@code Throwable} only if it belongs to a set of "fatal" error varieties. These
     * varieties are as follows:
     * <ul>
     * <li>{@code VirtualMachineError}</li>
     * <li>{@code ThreadDeath}</li>
     * <li>{@code LinkageError}</li>
     * </ul>
     * This can be useful if you are writing an operator that calls user-supplied code, and you want to
     * notify subscribers of errors encountered in that code by calling their {@code onError} methods, but only
     * if the errors are not so catastrophic that such a call would be futile, in which case you simply want to
     * rethrow the error.
     *
     * @param t the {@code Throwable} to test and perhaps throw
     * @see <a href="https://github.com/ReactiveX/RxJava/issues/748#issuecomment-32471495">
     *     RxJava: StackOverflowError is swallowed (Issue #748)</a>
     */
    public static void throwIfFatal(Throwable t) {
        requireNonNull(t, "t");
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        } else if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        } else if (t instanceof LinkageError) {
            throw (LinkageError) t;
        }
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
        requireNonNull(exception, "exception");
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
