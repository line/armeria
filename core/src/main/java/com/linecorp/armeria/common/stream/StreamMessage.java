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

package com.linecorp.armeria.common.stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.createStreamMessageFrom;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.EMPTY_OPTIONS;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.stream.AbortedStreamMessage;
import com.linecorp.armeria.internal.common.stream.DecodedStreamMessage;
import com.linecorp.armeria.internal.common.stream.EmptyFixedStreamMessage;
import com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil;
import com.linecorp.armeria.internal.common.stream.OneElementFixedStreamMessage;
import com.linecorp.armeria.internal.common.stream.RecoverableStreamMessage;
import com.linecorp.armeria.internal.common.stream.RegularFixedStreamMessage;
import com.linecorp.armeria.internal.common.stream.SurroundingPublisher;
import com.linecorp.armeria.internal.common.stream.ThreeElementFixedStreamMessage;
import com.linecorp.armeria.internal.common.stream.TwoElementFixedStreamMessage;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

/**
 * A variant of <a href="http://www.reactive-streams.org/">Reactive Streams</a> {@link Publisher}, which allows
 * only one {@link Subscriber}. Unlike a usual {@link Publisher}, a {@link StreamMessage} can stream itself
 * only once. It has the following additional operations on top of what the Reactive Streams API provides:
 * <ul>
 *   <li>{@link #isOpen()}</li>
 *   <li>{@link #isEmpty()}</li>
 *   <li>{@link #whenComplete()}</li>
 *   <li>{@link #abort()}</li>
 * </ul>
 *
 * <h2>When is a {@link StreamMessage} fully consumed?</h2>
 *
 * <p>A {@link StreamMessage} is <em>complete</em> (or 'fully consumed') when:
 * <ul>
 *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
 *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
 *   <li>the {@link Subscription} has been cancelled or</li>
 *   <li>{@link #abort()} has been requested.</li>
 * </ul>
 *
 * <p>When fully consumed, the {@link CompletableFuture} returned by {@link StreamMessage#whenComplete()}
 * will complete, which you may find useful because {@link Subscriber} does not notify you when a stream is
 * {@linkplain Subscription#cancel() cancelled}.
 *
 * <h2>Publication and Consumption of pooled {@link HttpData} objects</h2>
 *
 * <p>{@link StreamMessage} will discard the publication request of a pooled {@link HttpData} silently and
 * release it automatically when the publication is attempted after the stream is closed.
 *
 * <p>For pooled {@link HttpData}, {@link StreamMessage} will convert them into its unpooled version that
 * never leak, so that the {@link Subscriber} does not need to worry about leaks.
 *
 * <p>If a {@link Subscriber} does not want a {@link StreamMessage} to make a copy of a pooled {@link HttpData},
 * specify {@link SubscriptionOption#WITH_POOLED_OBJECTS} when you subscribe. Note that the {@link Subscriber}
 * is responsible for releasing the objects given with {@link Subscriber#onNext(Object)}.
 *
 * <p>{@link Subscriber#onError(Throwable)} is invoked when any exception is raised except the
 * {@link CancelledSubscriptionException} which is caused by {@link Subscription#cancel()}. If you want your
 * {@link Subscriber} get notified by {@link Subscriber#onError(Throwable)} when {@link Subscription#cancel()}
 * is called, specify {@link SubscriptionOption#NOTIFY_CANCELLATION} when you subscribe.
 *
 * @param <T> the type of element signaled
 */
@SuppressWarnings("OverloadMethodsDeclarationOrder")
public interface StreamMessage<T> extends Publisher<T> {
    /**
     * Creates a new {@link StreamMessage} that will publish no objects, just a close event.
     */
    static <T> StreamMessage<T> of() {
        return new EmptyFixedStreamMessage<>();
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the single {@code obj}.
     */
    static <T> StreamMessage<T> of(T obj) {
        requireNonNull(obj, "obj");
        return new OneElementFixedStreamMessage<>(obj);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the two {@code obj1} and {@code obj2}.
     */
    static <T> StreamMessage<T> of(T obj1, T obj2) {
        requireNonNull(obj1, "obj1");
        requireNonNull(obj2, "obj2");
        return new TwoElementFixedStreamMessage<>(obj1, obj2);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the three {@code obj1}, {@code obj2} and
     * {@code obj3}.
     */
    static <T> StreamMessage<T> of(T obj1, T obj2, T obj3) {
        requireNonNull(obj1, "obj1");
        requireNonNull(obj2, "obj2");
        requireNonNull(obj3, "obj3");
        return new ThreeElementFixedStreamMessage<>(obj1, obj2, obj3);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the given {@code objs}.
     */
    @SafeVarargs
    static <T> StreamMessage<T> of(T... objs) {
        requireNonNull(objs, "objs");
        switch (objs.length) {
            case 0:
                return of();
            case 1:
                return of(objs[0]);
            case 2:
                return of(objs[0], objs[1]);
            case 3:
                return of(objs[0], objs[1], objs[2]);
            default:
                for (int i = 0; i < objs.length; i++) {
                    if (objs[i] == null) {
                        throw new NullPointerException("objs[" + i + "] is null");
                    }
                }
                return new RegularFixedStreamMessage<>(objs);
        }
    }

    /**
     * Creates a new {@link StreamMessage} from the specified {@link Publisher}.
     */
    static <T> StreamMessage<T> of(Publisher<? extends T> publisher) {
        requireNonNull(publisher, "publisher");

        if (publisher instanceof StreamMessage) {
            @SuppressWarnings("unchecked")
            final StreamMessage<T> cast = (StreamMessage<T>) publisher;
            return cast;
        } else {
            return new PublisherBasedStreamMessage<>(publisher);
        }
    }

    /**
     * Creates a new {@link StreamMessage} that delegates to the {@link StreamMessage} produced by the specified
     * {@link CompletableFuture}. If the specified {@link CompletableFuture} fails, the returned
     * {@link StreamMessage} will be closed with the same cause as well.
     *
     * @param future the {@link CompletableFuture} which will produce the actual {@link StreamMessage}
     */
    @UnstableApi
    static <T> StreamMessage<T> of(CompletableFuture<? extends Publisher<? extends T>> future) {
        requireNonNull(future, "stage");
        return createStreamMessageFrom(future);
    }

    /**
     * Creates a new {@link StreamMessage} that delegates to the {@link StreamMessage} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned
     * {@link StreamMessage} will be closed with the same cause as well.
     *
     * @param stage the {@link CompletionStage} which will produce the actual {@link StreamMessage}
     */
    @UnstableApi
    static <T> StreamMessage<T> of(CompletionStage<? extends Publisher<? extends T>> stage) {
        requireNonNull(stage, "stage");

        final DeferredStreamMessage<T> deferred = new DeferredStreamMessage<>();
        //noinspection unchecked
        deferred.delegateOnCompletion((CompletionStage<? extends Publisher<T>>) stage);
        return deferred;
    }

    /**
     * Creates a new {@link StreamMessage} that delegates to the {@link StreamMessage} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned
     * {@link StreamMessage} will be closed with the same cause as well.
     *
     * @param stage the {@link CompletionStage} which will produce the actual {@link StreamMessage}
     * @param subscriberExecutor the {@link EventExecutor} which will be used when a user subscribes
     *                           the returned {@link StreamMessage} using {@link #subscribe(Subscriber)}
     *                           or {@link #subscribe(Subscriber, SubscriptionOption...)}.
     */
    static <T> StreamMessage<T> of(CompletionStage<? extends StreamMessage<? extends T>> stage,
                                   EventExecutor subscriberExecutor) {
        requireNonNull(stage, "stage");
        requireNonNull(subscriberExecutor, "subscriberExecutor");
        // Have to use DeferredStreamMessage to use the subscriberExecutor.
        final DeferredStreamMessage<T> deferred = new DeferredStreamMessage<>(subscriberExecutor);
        //noinspection unchecked
        deferred.delegateOnCompletion((CompletionStage<? extends Publisher<T>>) stage);
        return deferred;
    }

    /**
     * Creates a new {@link StreamMessage} that streams the specified {@link File}.
     * The default buffer size({@value InternalStreamMessageUtil#DEFAULT_FILE_BUFFER_SIZE}) is used to
     * create a buffer used to read data from the {@link File}.
     * Therefore, the returned {@link StreamMessage} will emit {@link HttpData}s chunked to
     * size less than or equal to {@value InternalStreamMessageUtil#DEFAULT_FILE_BUFFER_SIZE}.
     */
    static ByteStreamMessage of(File file) {
        requireNonNull(file, "file");
        return of(file.toPath());
    }

    /**
     * Creates a new {@link StreamMessage} that streams the specified {@link Path}.
     * The default buffer size({@value InternalStreamMessageUtil#DEFAULT_FILE_BUFFER_SIZE}) is used to
     * create a buffer used to read data from the {@link Path}.
     * Therefore, the returned {@link StreamMessage} will emit {@link HttpData}s chunked to
     * size less than or equal to {@value InternalStreamMessageUtil#DEFAULT_FILE_BUFFER_SIZE}.
     */
    static ByteStreamMessage of(Path path) {
        requireNonNull(path, "path");
        return builder(path).build();
    }

    /**
     * Returns a new {@link PathStreamMessageBuilder} with the specified {@link Path}.
     */
    @UnstableApi
    static PathStreamMessageBuilder builder(Path path) {
        requireNonNull(path, "path");
        return new PathStreamMessageBuilder(path);
    }

    /**
     * Creates a new {@link StreamMessage} that streams the specified {@link Path}.
     * The specified {@code bufferSize} is used to create a buffer used to read data from the {@link Path}.
     * Therefore, the returned {@link StreamMessage} will emit {@link HttpData}s chunked to
     * size less than or equal to {@code bufferSize}.
     *
     * @param path the path of the file
     * @param bufferSize the maximum allowed size of the {@link HttpData} buffers
     *
     * @deprecated Use {@link #builder(Path)} with {@link PathStreamMessageBuilder#bufferSize(int)}
     */
    @Deprecated
    static ByteStreamMessage of(Path path, int bufferSize) {
        return builder(path).bufferSize(bufferSize).build();
    }

    /**
     * Creates a new {@link StreamMessage} that streams the specified {@link Path}.
     * The specified {@code bufferSize} is used to create a buffer used to read data from the {@link Path}.
     * Therefore, the returned {@link StreamMessage} will emit {@link HttpData}s chunked to
     * size less than or equal to {@code bufferSize}.
     *
     * @param path the path of the file
     * @param alloc the {@link ByteBufAllocator} which will allocate the content buffer
     * @param bufferSize the maximum allowed size of the {@link HttpData} buffers
     *
     * @deprecated Use {@link #builder(Path)} with {@link PathStreamMessageBuilder#alloc(ByteBufAllocator)} and
     *             {@link PathStreamMessageBuilder#bufferSize(int)}.
     */
    @Deprecated
    static ByteStreamMessage of(Path path, ByteBufAllocator alloc, int bufferSize) {
        requireNonNull(path, "path");
        requireNonNull(alloc, "alloc");
        checkArgument(bufferSize > 0, "bufferSize: %s (expected: > 0)", bufferSize);
        return builder(path).alloc(alloc).bufferSize(bufferSize).build();
    }

    /**
     * Creates a new {@link StreamMessage} that streams the specified {@link Path}.
     * The specified {@code bufferSize} is used to create a buffer used to read data from the {@link Path}.
     * Therefore, the returned {@link StreamMessage} will emit {@link HttpData}s chunked to
     * size less than or equal to {@code bufferSize}.
     *
     * @param path the path of the file
     * @param executor the {@link ExecutorService} which performs blocking IO read
     * @param alloc the {@link ByteBufAllocator} which will allocate the content buffer
     * @param bufferSize the maximum allowed size of the {@link HttpData} buffers
     *
     * @deprecated Use {@link #builder(Path)} with
     *             {@link PathStreamMessageBuilder#executor(ExecutorService)},
     *             {@link PathStreamMessageBuilder#alloc(ByteBufAllocator)} and
     *             {@link PathStreamMessageBuilder#bufferSize(int)}
     */
    @Deprecated
    static ByteStreamMessage of(Path path, @Nullable ExecutorService executor, ByteBufAllocator alloc,
                                int bufferSize) {
        requireNonNull(path, "path");
        requireNonNull(alloc, "alloc");
        final PathStreamMessageBuilder builder = builder(path);
        if (executor != null) {
            builder.executor(executor);
        }
        return builder.alloc(alloc).bufferSize(bufferSize).build();
    }

    /**
     * Creates a new {@link StreamMessage} that streams bytes from the specified {@link InputStream}.
     * The default buffer size({@value InternalStreamMessageUtil#DEFAULT_FILE_BUFFER_SIZE}) is used to
     * create a buffer that is used to read data from the {@link InputStream}.
     * Therefore, the returned {@link StreamMessage} will emit {@link HttpData}s chunked to
     * size less than or equal to {@value InternalStreamMessageUtil#DEFAULT_FILE_BUFFER_SIZE}.
     */
    @UnstableApi
    static ByteStreamMessage of(InputStream inputStream) {
        requireNonNull(inputStream, "inputStream");
        return builder(inputStream).build();
    }

    /**
     * Returns a new {@link InputStreamStreamMessageBuilder} with the specified {@link InputStream}.
     */
    @UnstableApi
    static InputStreamStreamMessageBuilder builder(InputStream inputStream) {
        requireNonNull(inputStream, "inputStream");
        return new InputStreamStreamMessageBuilder(inputStream);
    }

    /**
     * Creates a new {@link ByteStreamMessage} that publishes {@link HttpData}s from the specified
     * {@linkplain Consumer outputStreamConsumer}.
     *
     * <p>For example:<pre>{@code
     * ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
     *     try {
     *         for (int i = 0; i < 5; i++) {
     *             os.write(i);
     *         }
     *         os.close();
     *     } catch (IOException e) {
     *         throw new UncheckedIOException(e);
     *     }
     * });
     * byte[] result = byteStreamMessage.collectBytes().join();
     *
     * assert Arrays.equals(result, new byte[] { 0, 1, 2, 3, 4 });
     * }</pre>
     *
     * <p>Please note that the try-with-resources statement is not used to call {@code os.close()}
     * automatically. It's because when an exception is raised in the {@link Consumer},
     * the {@link OutputStream} is closed by the {@link StreamMessage} and the exception is propagated
     * to the {@link Subscriber} automatically.
     */
    static ByteStreamMessage fromOutputStream(Consumer<? super OutputStream> outputStreamConsumer) {
        final RequestContext ctx = RequestContext.currentOrNull();
        final ExecutorService blockingTaskExecutor;
        if (ctx instanceof ServiceRequestContext) {
            blockingTaskExecutor = ((ServiceRequestContext) ctx).blockingTaskExecutor();
        } else {
            blockingTaskExecutor = CommonPools.blockingTaskExecutor();
        }
        return fromOutputStream(outputStreamConsumer, blockingTaskExecutor);
    }

    /**
     * Creates a new {@link ByteStreamMessage} that publishes {@link HttpData}s from the specified
     * {@linkplain Consumer outputStreamConsumer}.
     *
     * <p>For example:<pre>{@code
     * ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
     *     try {
     *         for (int i = 0; i < 5; i++) {
     *             os.write(i);
     *         }
     *         os.close();
     *     } catch (IOException e) {
     *         throw new UncheckedIOException(e);
     *     }
     * });
     * byte[] result = byteStreamMessage.collectBytes().join();
     *
     * assert Arrays.equals(result, new byte[] { 0, 1, 2, 3, 4 });
     * }</pre>
     *
     * <p>Please note that the try-with-resources statement is not used to call {@code os.close()}
     * automatically. It's because when an exception is raised in the {@link Consumer},
     * the {@link OutputStream} is closed by the {@link StreamMessage} and the exception is propagated
     * to the {@link Subscriber} automatically.
     *
     * @param blockingTaskExecutor the blocking task executor to execute {@link OutputStream#write(int)}
     */
    static ByteStreamMessage fromOutputStream(Consumer<? super OutputStream> outputStreamConsumer,
                                              Executor blockingTaskExecutor) {
        return new ByteStreamMessageOutputStream(outputStreamConsumer, blockingTaskExecutor);
    }

    /**
     * Returns a concatenated {@link StreamMessage} which relays items of the specified array of
     * {@link Publisher}s in order, non-overlappingly, one after the other finishes.
     */
    @SafeVarargs
    static <T> StreamMessage<T> concat(Publisher<? extends T>... publishers) {
        requireNonNull(publishers, "publishers");
        return concat(ImmutableList.copyOf(publishers));
    }

    /**
     * Returns a concatenated {@link StreamMessage} which relays items of the specified {@link Publisher}s
     * in order, non-overlappingly, one after the other finishes.
     */
    static <T> StreamMessage<T> concat(Iterable<? extends Publisher<? extends T>> publishers) {
        requireNonNull(publishers, "publishers");

        if (Iterables.isEmpty(publishers)) {
            return of();
        }
        final List<StreamMessage<? extends T>> streamMessages = ImmutableList.copyOf(publishers)
                                                                             .stream()
                                                                             .map(StreamMessage::of)
                                                                             .collect(toImmutableList());
        return new ConcatArrayStreamMessage<>(streamMessages);
    }

    /**
     * Returns a concatenated {@link StreamMessage} which relays items of the specified {@link Publisher} of
     * {@link Publisher}s in order, non-overlappingly, one after the other finishes.
     */
    static <T> StreamMessage<T> concat(Publisher<? extends Publisher<? extends T>> publishers) {
        requireNonNull(publishers, "publishers");
        return new ConcatPublisherStreamMessage<>(of(publishers));
    }

    /**
     * Returns an aborted {@link StreamMessage} that terminates with the specified {@link Throwable}
     * via {@link Subscriber#onError(Throwable)} immediately after being subscribed to.
     */
    static <T> StreamMessage<T> aborted(Throwable cause) {
        requireNonNull(cause, "cause");
        return new AbortedStreamMessage<>(cause);
    }

    /**
     * Creates a new {@link StreamWriter} that publishes the objects written via
     * {@link StreamWriter#write(Object)}.
     */
    @UnstableApi
    static <T> StreamWriter<T> streaming() {
        return new DefaultStreamMessage<>();
    }

    /**
     * Returns {@code true} if this stream is not closed yet. Note that a stream may not be
     * {@linkplain #whenComplete() complete} even if it's closed; a stream is complete when it's fully
     * consumed by a {@link Subscriber}.
     */
    boolean isOpen();

    /**
     * Returns {@code true} if this stream has been closed and did not publish any elements.
     * Note that this method will not return {@code true} when the stream is open even if it has not
     * published anything so far, because it may publish something later.
     */
    boolean isEmpty();

    /**
     * Returns the current demand of this stream.
     */
    long demand();

    /**
     * Returns {@code true} if this stream is complete, either successfully or exceptionally,
     * including cancellation and abortion.
     *
     * <p>A {@link StreamMessage} is <em>complete</em> (or 'fully consumed') when:
     * <ul>
     *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
     *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
     *   <li>the {@link Subscription} has been cancelled or</li>
     *   <li>{@link #abort()} has been requested.</li>
     * </ul>
     */
    default boolean isComplete() {
        return whenComplete().isDone();
    }

    /**
     * Returns a {@link CompletableFuture} that completes when this stream is complete,
     * either successfully or exceptionally, including cancellation and abortion.
     *
     * <p>A {@link StreamMessage} is <em>complete</em>
     * (or 'fully consumed') when:
     * <ul>
     *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
     *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
     *   <li>the {@link Subscription} has been cancelled or</li>
     *   <li>{@link #abort()} has been requested.</li>
     * </ul>
     */
    CompletableFuture<Void> whenComplete();

    /**
     * Drains and discards all objects in this {@link StreamMessage}.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Integer> source = StreamMessage.of(1, 2, 3);
     * List<Integer> collected = new ArrayList<>();
     * CompletableFuture<Void> future = source.peek(collected::add).subscribe();
     * future.join();
     * assert collected.equals(List.of(1, 2, 3));
     * assert future.isDone();
     * }</pre>
     */
    default CompletableFuture<Void> subscribe() {
        return subscribe(defaultSubscriberExecutor());
    }

    /**
     * Drains and discards all objects in this {@link StreamMessage}.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Integer> source = StreamMessage.of(1, 2, 3);
     * List<Integer> collected = new ArrayList<>();
     * CompletableFuture<Void> future = source.peek(collected::add).subscribe();
     * future.join();
     * assert collected.equals(List.of(1, 2, 3));
     * assert future.isDone();
     * }</pre>
     *
     * @param executor the executor to subscribe
     */
    @UnstableApi
    default CompletableFuture<Void> subscribe(EventExecutor executor) {
        requireNonNull(executor, "executor");
        subscribe(NoopSubscriber.get(), executor);
        return whenComplete();
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     */
    @Override
    default void subscribe(Subscriber<? super T> subscriber) {
        subscribe(subscriber, defaultSubscriberExecutor());
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    default void subscribe(Subscriber<? super T> subscriber, SubscriptionOption... options) {
        subscribe(subscriber, defaultSubscriberExecutor(), options);
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     */
    default void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, EMPTY_OPTIONS);
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    void subscribe(Subscriber<? super T> subscriber, EventExecutor executor, SubscriptionOption... options);

    /**
     * Returns a new {@link StreamMessageDuplicator} that duplicates this {@link StreamMessage} into one or
     * more {@link StreamMessage}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link StreamMessage} anymore after you call this method.
     * To subscribe, call {@link StreamMessageDuplicator#duplicate()} from the returned
     * {@link StreamMessageDuplicator}.
     */
    default StreamMessageDuplicator<T> toDuplicator() {
        return toDuplicator(defaultSubscriberExecutor());
    }

    /**
     * Returns a new {@link StreamMessageDuplicator} that duplicates this {@link StreamMessage} into one or
     * more {@link StreamMessage}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link StreamMessage} anymore after you call this method.
     * To subscribe, call {@link StreamMessageDuplicator#duplicate()} from the returned
     * {@link StreamMessageDuplicator}.
     *
     * @param executor the executor to duplicate
     */
    default StreamMessageDuplicator<T> toDuplicator(EventExecutor executor) {
        requireNonNull(executor, "executor");
        return new DefaultStreamMessageDuplicator<>(this, unused -> 0, executor, 0 /* no limit for length */);
    }

    /**
     * Returns the default {@link EventExecutor} which will be used when a user subscribes using
     * {@link #subscribe(Subscriber)}, {@link #subscribe(Subscriber, SubscriptionOption...)}.
     *
     * <p>Please note that if this method is called multiple times, the returned {@link EventExecutor}s can be
     * different depending on this {@link StreamMessage} implementation.
     */
    default EventExecutor defaultSubscriberExecutor() {
        final EventLoop eventExecutor = RequestContext.mapCurrent(RequestContext::eventLoop,
                                                                  CommonPools.workerGroup()::next);
        assert eventExecutor != null;
        return eventExecutor;
    }

    /**
     * Closes this stream with {@link AbortedStreamException} and prevents further subscription.
     * A {@link Subscriber} that attempts to subscribe to an aborted stream will be notified with
     * an {@link AbortedStreamException} via {@link Subscriber#onError(Throwable)}. Calling this method
     * on a closed or aborted stream has no effect.
     */
    void abort();

    /**
     * Closes this stream with the specified {@link Throwable} and prevents further subscription.
     * A {@link Subscriber} that attempts to subscribe to an aborted stream will be notified with
     * the specified {@link Throwable} via {@link Subscriber#onError(Throwable)}. Calling this method
     * on a closed or aborted stream has no effect.
     */
    void abort(Throwable cause);

    /**
     * Creates a decoded {@link StreamMessage} which is decoded from a stream of {@code T} type objects using
     * the specified {@link StreamDecoder}.
     */
    @UnstableApi
    default <U> StreamMessage<U> decode(StreamDecoder<T, U> decoder) {
        requireNonNull(decoder, "decoder");
        return decode(decoder, ByteBufAllocator.DEFAULT);
    }

    /**
     * Creates a decoded {@link StreamMessage} which is decoded from a stream of {@code T} type objects using
     * the specified {@link StreamDecoder} and {@link ByteBufAllocator}.
     */
    @UnstableApi
    default <U> StreamMessage<U> decode(StreamDecoder<T, U> decoder, ByteBufAllocator alloc) {
        return new DecodedStreamMessage<>(this, decoder, alloc);
    }

    /**
     * Collects the elements published by this {@link StreamMessage}.
     * The returned {@link CompletableFuture} will be notified when the elements are fully consumed.
     *
     * <p>Note that if this {@link StreamMessage} was subscribed by other {@link Subscriber} already,
     * the returned {@link CompletableFuture} will be completed with an {@link IllegalStateException}.
     *
     * <pre>{@code
     * StreamMessage<Integer> stream = StreamMessage.of(1, 2, 3);
     * CompletableFuture<List<Integer>> collected = stream.collect();
     * assert collected.join().equals(List.of(1, 2, 3));
     * }</pre>
     */
    default CompletableFuture<List<T>> collect() {
        return collect(EMPTY_OPTIONS);
    }

    /**
     * Collects the elements published by this {@link StreamMessage} with the specified
     * {@link SubscriptionOption}s. The returned {@link CompletableFuture} will be notified when the elements
     * are fully consumed.
     *
     * <p>Note that if this {@link StreamMessage} was subscribed by other {@link Subscriber} already,
     * the returned {@link CompletableFuture} will be completed with an {@link IllegalStateException}.
     */
    default CompletableFuture<List<T>> collect(SubscriptionOption... options) {
        return collect(defaultSubscriberExecutor(), options);
    }

    /**
     * Collects the elements published by this {@link StreamMessage} with the specified
     * {@link EventExecutor} and {@link SubscriptionOption}s. The returned {@link CompletableFuture} will be
     * notified when the elements are fully consumed.
     *
     * <p>Note that if this {@link StreamMessage} was subscribed by other {@link Subscriber} already,
     * the returned {@link CompletableFuture} will be completed with an {@link IllegalStateException}.
     */
    default CompletableFuture<List<T>> collect(EventExecutor executor, SubscriptionOption... options) {
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        final StreamMessageCollector<T> collector = new StreamMessageCollector<>(options);
        subscribe(collector, executor, options);
        return collector.collect();
    }

    /**
     * Filters values emitted by this {@link StreamMessage}.
     * If the {@link Predicate} test succeeds, the value is emitted.
     * If the {@link Predicate} test fails, the value is ignored and a request of {@code 1} is made to upstream.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5);
     * StreamMessage<Integer> even = source.filter(x -> x % 2 == 0);
     * }</pre>
     */
    default StreamMessage<T> filter(Predicate<? super T> predicate) {
        requireNonNull(predicate, "predicate");
        return FuseableStreamMessage.of(this, predicate);
    }

    /**
     * Transforms values emitted by this {@link StreamMessage} by applying the specified {@link Function}.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5);
     * StreamMessage<Boolean> isEven = source.map(x -> x % 2 == 0);
     * }</pre>
     */
    default <U> StreamMessage<U> map(Function<? super T, ? extends U> function) {
        requireNonNull(function, "function");
        if (function == Function.identity()) {
            @SuppressWarnings("unchecked")
            final StreamMessage<U> cast = (StreamMessage<U>) this;
            return cast;
        }

        return FuseableStreamMessage.of(this, function);
    }

    /**
     * Transforms values emitted by this {@link StreamMessage} by applying the specified asynchronous
     * {@link Function} and emitting the value the future completes with.
     * The {@link StreamMessage} publishes items in order, non-overlappingly, one after the other finishes.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value nor a future which completes with a {@code null} value.
     *
     * <p>Example:<pre>{@code
     * StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5);
     * StreamMessage<Integer> transformed =
     *     streamMessage.mapAsync(x -> UnmodifiableFuture.completedFuture(x + 1));
     * }</pre>
     */
    default <U> StreamMessage<U> mapAsync(
            Function<? super T, ? extends CompletableFuture<? extends U>> function) {
        requireNonNull(function, "function");
        return mapParallel(function, 1);
    }

    /**
     * Transforms values emitted by this {@link StreamMessage} by applying the specified asynchronous
     * {@link Function} and emitting the value the future completes with.
     * The {@link StreamMessage} publishes items eagerly in the order that the futures complete.
     * It does not necessarily preserve the order of the original stream.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value nor a future which completes with a {@code null} value.
     *
     * <p>Example:<pre>{@code
     * StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5);
     * StreamMessage<Integer> transformed =
     *     streamMessage.mapParallel(x -> UnmodifiableFuture.completedFuture(x + 1));
     * }</pre>
     */
    @UnstableApi
    default <U> StreamMessage<U> mapParallel(
            Function<? super T, ? extends CompletableFuture<? extends U>> function) {
        requireNonNull(function, "function");
        return mapParallel(function, Integer.MAX_VALUE);
    }

    /**
     * Transforms values emitted by this {@link StreamMessage} by applying the specified asynchronous
     * {@link Function} and emitting the value the future completes with.
     * The {@link StreamMessage} publishes items eagerly in the order that the futures complete.
     * The number of pending futures will at most be {@code maxConcurrency}
     * It does not necessarily preserve the order of the original stream.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value nor a future which completes with a {@code null} value.
     *
     * <p>Example:<pre>{@code
     * StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5);
     * StreamMessage<Integer> transformed =
     *     streamMessage.mapParallel(x -> UnmodifiableFuture.completedFuture(x + 1), 20);
     * }</pre>
     */
    @UnstableApi
    default <U> StreamMessage<U> mapParallel(
            Function<? super T, ? extends CompletableFuture<? extends U>> function,
            int maxConcurrency) {
        requireNonNull(function, "function");
        checkArgument(maxConcurrency > 0, "maxConcurrency: %s (expected > 0)", maxConcurrency);
        return new AsyncMapStreamMessage<>(this, function, maxConcurrency);
    }

    /**
     * Transforms values emitted by this {@link StreamMessage} by applying the specified {@link Function} and
     * emitting the values of the resulting {@link StreamMessage}.
     * The inner {@link StreamMessage}s are subscribed to eagerly and
     * publish values as soon as they are received.
     * It allows inner {@link StreamMessage}s to interleave.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value.
     *
     * <p>Example:<pre>{@code
     * StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3);
     * StreamMessage<Integer> transformed =
     *     streamMessage.flatMap(x -> StreamMessage.of(x, x + 1));
     * }</pre>
     * {@code transformed} will produce {@code 1, 2, 2, 3, 3, 4} (order is not guaranteed).
     */
    default <U> StreamMessage<U> flatMap(
            Function<? super T, ? extends StreamMessage<? extends U>> function) {
        requireNonNull(function, "function");
        return flatMap(function, Integer.MAX_VALUE);
    }

    /**
     * Transforms values emitted by this {@link StreamMessage} by applying the specified {@link Function} and
     * emitting the values of the resulting {@link StreamMessage}.
     * The inner {@link StreamMessage}s are subscribed to eagerly, up to a limit of {@code maxConcurrency}, and
     * publish values as soon as they are received.
     * It allows inner {@link StreamMessage}s to interleave.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value.
     *
     * <p>Example:<pre>{@code
     * StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3);
     * StreamMessage<Integer> transformed =
     *     streamMessage.flatMap(x -> StreamMessage.of(x, x + 1));
     * }</pre>
     * {@code transformed} will produce {@code 1, 2, 2, 3, 3, 4} (order is not guaranteed).
     */
    default <U> StreamMessage<U> flatMap(
            Function<? super T, ? extends StreamMessage<? extends U>> function, int maxConcurrency) {
        checkArgument(maxConcurrency > 0, "maxConcurrency: %s (expected: > 0)", maxConcurrency);
        requireNonNull(function, "function");
        return new FlatMapStreamMessage<>(this, function, maxConcurrency);
    }

    /**
     * Transforms an error emitted by this {@link StreamMessage} by applying the specified {@link Function}.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Void> streamMessage = StreamMessage
     *     .aborted(new IllegalStateException("Something went wrong."));
     * StreamMessage<Void> transformed = streamMessage.mapError(ex -> {
     *     if (ex instanceof IllegalStateException) {
     *         return new MyDomainException(ex);
     *     } else {
     *         return ex;
     *     }
     * });
     * }</pre>
     */
    default StreamMessage<T> mapError(Function<? super Throwable, ? extends Throwable> function) {
        requireNonNull(function, "function");
        return FuseableStreamMessage.error(this, function);
    }

    /**
     * Peeks values emitted by this {@link StreamMessage} and applies the specified {@link Consumer}.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5);
     * StreamMessage<Integer> ifEvenExistsThenThrow = source.peek(x -> {
     *      if (x % 2 == 0) {
     *          throw new IllegalArgumentException();
     *      }
     * });
     * }</pre>
     */
    default StreamMessage<T> peek(Consumer<? super T> action) {
        requireNonNull(action, "action");
        final Function<T, T> function = obj -> {
            action.accept(obj);
            return obj;
        };
        return map(function);
    }

    /**
     * Peeks values emitted by this {@link StreamMessage} and applies the specified {@link Consumer}.
     * Only values which are an instance of the specified {@code type} are peeked.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Number> source = StreamMessage.of(0.1, 1, 0.2, 2, 0.3, 3);
     * List<Integer> collected = new ArrayList<>();
     * List<Number> peeked = source.peek(x -> collected.add(x), Integer.class).collect().join();
     *
     * assert collected.equals(List.of(1, 2, 3));
     * assert peeked.equals(List.of(0.1, 1, 0.2, 2, 0.3, 3));
     * }</pre>
     */
    default <U extends T> StreamMessage<T> peek(Consumer<? super U> action, Class<? extends U> type) {
        requireNonNull(action, "action");
        requireNonNull(type, "type");
        final Function<T, T> function = obj -> {
            if (type.isInstance(obj)) {
                //noinspection unchecked
                action.accept((U) obj);
            }
            return obj;
        };
        return map(function);
    }

    /**
     * Peeks an error emitted by this {@link StreamMessage} and applies the specified {@link Consumer}.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Void> streamMessage = StreamMessage
     *     .aborted(new IllegalStateException("Something went wrong."));
     * StreamMessage<Void> peeked = streamMessage.peekError(ex -> {
     *     assert ex instanceof IllegalStateException;
     * });
     * }</pre>
     */
    default StreamMessage<T> peekError(Consumer<? super Throwable> action) {
        requireNonNull(action, "action");
        final Function<? super Throwable, ? extends Throwable> function = obj -> {
            action.accept(obj);
            return obj;
        };
        return mapError(function);
    }

    /**
     * Recovers a failed {@link StreamMessage} and resumes by subscribing to a returned fallback
     * {@link StreamMessage} when any error occurs.
     *
     * <p>Example:<pre>{@code
     * StreamWriter<Integer> stream = StreamMessage.streaming();
     * stream.write(1);
     * stream.write(2);
     * stream.close(new IllegalStateException("Oops..."));
     * StreamMessage<Integer> resumed = stream.recoverAndResume(cause -> StreamMessage.of(3, 4));
     *
     * assert resumed.collect().join().equals(List.of(1, 2, 3, 4));
     * }</pre>
     */
    default StreamMessage<T> recoverAndResume(
            Function<? super Throwable, ? extends StreamMessage<T>> function) {
        requireNonNull(function, "function");
        return new RecoverableStreamMessage<>(this, function, /* allowResuming */ true);
    }

    /**
     * Recovers a failed {@link StreamMessage} and resumes by subscribing to a returned fallback
     * {@link StreamMessage} when the thrown {@link Throwable} is the same type or a subtype of the
     * specified {@code causeClass}.
     *
     * <p>Example:<pre>{@code
     * StreamWriter<Integer> stream = StreamMessage.streaming();
     * stream.write(1);
     * stream.write(2);
     * stream.close(new IllegalStateException("Oops..."));
     * StreamMessage<Integer> resumed =
     *     stream.recoverAndResume(IllegalStateException.class, cause -> StreamMessage.of(3, 4));
     *
     * assert resumed.collect().join().equals(List.of(1, 2, 3, 4));
     *
     * StreamWriter<Integer> stream = StreamMessage.streaming();
     * stream.write(1);
     * stream.write(2);
     * stream.write(3);
     * stream.close(new IllegalStateException("test exception"));
     * // Use the shortcut recover method as a chain.
     * StreamMessage<Integer> recoverChain =
     *     stream.recoverAndResume(RuntimeException.class, cause -> {
     *         final IllegalArgumentException ex = new IllegalArgumentException("oops..");
     *         // If a aborted StreamMessage returned from the first chain
     *         return StreamMessage.aborted(ex);
     *     })
     *     // If the shortcut exception type is correct, catch and recover in the second chain.
     *     .recoverAndResume(IllegalArgumentException.class, cause -> StreamMessage.of(4, 5));
     *
     * recoverChain.collect().join();
     *
     * StreamWriter<Integer> stream = StreamMessage.streaming();
     * stream.write(1);
     * stream.write(2);
     * stream.close(ClosedStreamException.get());
     * // If the exception type does not match
     * StreamMessage<Integer> mismatchRecovered =
     *     stream.recoverAndResume(IllegalStateException.class, cause -> StreamMessage.of(3, 4));
     *
     * // In this case, CompletionException is thrown. (can't recover exception)
     * mismatchRecovered.collect().join();
     * }</pre>
     */
    @UnstableApi
    default <E extends Throwable> StreamMessage<T> recoverAndResume(
            Class<E> causeClass, Function<? super E, ? extends StreamMessage<T>> function) {
        requireNonNull(causeClass, "causeClass");
        requireNonNull(function, "function");
        return recoverAndResume(cause -> {
            if (!causeClass.isInstance(cause)) {
                return Exceptions.throwUnsafely(cause);
            }
            try {
                final StreamMessage<T> recoveredStreamMessage = function.apply((E) cause);
                requireNonNull(recoveredStreamMessage, "recoveredStreamMessage");
                return recoveredStreamMessage;
            } catch (Throwable t) {
                return Exceptions.throwUnsafely(cause);
            }
        });
    }

    /**
     * Writes this {@link StreamMessage} to the given {@link Path} with {@link OpenOption}s.
     * If the {@link OpenOption} is not specified, defaults to {@link StandardOpenOption#CREATE},
     * {@link StandardOpenOption#TRUNCATE_EXISTING} and {@link StandardOpenOption#WRITE}.
     *
     * <p>Example:<pre>{@code
     * Path destination = Paths.get("foo.bin");
     * ByteBuf[] bufs = new ByteBuf[10];
     * for(int i = 0; i < 10; i++) {
     *     bufs[i] = Unpooled.wrappedBuffer(Integer.toString(i).getBytes());
     * }
     * StreamMessage<ByteBuf> streamMessage = StreamMessage.of(bufs);
     * streamMessage.writeTo(HttpData::wrap, destination).join();
     *
     * assert Files.readString(destination).equals("0123456789");
     * }</pre>
     *
     * @see StreamMessages#writeTo(StreamMessage, Path, OpenOption...)
     */
    default CompletableFuture<Void> writeTo(Function<? super T, ? extends HttpData> mapper, Path destination,
                                            OpenOption... options) {
        requireNonNull(mapper, "mapper");
        requireNonNull(destination, "destination");
        requireNonNull(options, "options");
        return StreamMessages.writeTo(map(mapper), destination, options);
    }

    /**
     * Adapts this {@link StreamMessage} to {@link InputStream}.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<String> streamMessage = StreamMessage.of("foo", "bar", "baz");
     * InputStream inputStream = streamMessage.toInputStream(x -> HttpData.wrap(x.getBytes()));
     * byte[] expected = "foobarbaz".getBytes();
     *
     * ByteBuf result = Unpooled.buffer();
     * int read;
     * while ((read = inputStream.read()) != -1) {
     *     result.writeByte(read);
     * }
     *
     * int readableBytes = result.readableBytes();
     * byte[] actual = new byte[readableBytes];
     * for (int i = 0; i < readableBytes; i++) {
     *     actual[i] = result.readByte();
     * }
     * assert Arrays.equals(actual, expected);
     * assert inputStream.available() == 0;
     * }</pre>
     */
    default InputStream toInputStream(Function<? super T, ? extends HttpData> httpDataConverter) {
        return toInputStream(httpDataConverter, defaultSubscriberExecutor());
    }

    /**
     * Adapts this {@link StreamMessage} to {@link InputStream}.
     *
     * @param executor the executor to subscribe
     */
    default InputStream toInputStream(Function<? super T, ? extends HttpData> httpDataConverter,
                                      EventExecutor executor) {
        requireNonNull(httpDataConverter, "httpDataConverter");
        requireNonNull(executor, "executor");
        return new StreamMessageInputStream<>(this, httpDataConverter, executor);
    }

    /**
     * Dynamically emits the last value depending on whether this {@link StreamMessage} completes successfully
     * or exceptionally.
     *
     * <p>For example:<pre>{@code
     * StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
     * StreamMessage<Integer> aborted = source
     *     .peek(i -> {
     *         if (i > 5) {
     *             source.abort();
     *         }
     *     });
     * StreamMessage<Integer> endWith = aborted
     *     .endWith(th -> {
     *          if (th instanceof AbortedStreamException) {
     *              return 100;
     *          }
     *          return -1;
     *     });
     * List<Integer> collected = endWith.collect().join();
     *
     * assert collected.equals(List.of(1, 2, 3, 4, 5, 100));
     * }</pre>
     *
     * <p>Note that if {@code null} is returned by the {@link Function}, the {@link StreamMessage} will complete
     * successfully without emitting an additional value when this stream is complete successfully,
     * or complete exceptionally when this stream is complete exceptionally.
     */
    @UnstableApi
    default StreamMessage<T> endWith(Function<@Nullable Throwable, ? extends @Nullable T> finalizer) {
        return new SurroundingPublisher<>(null, this, finalizer);
    }

    /**
     * Calls {@link #subscribe(Subscriber, EventExecutor)} to the upstream
     * {@link StreamMessage} using the specified {@link EventExecutor} and relays the stream
     * transparently downstream. This may be useful if one would like to hide an
     * {@link EventExecutor} from an upstream {@link Publisher}.
     *
     * <p>For example:<pre>{@code
     * Subscriber<Integer> mySubscriber = null;
     * StreamMessage<Integer> upstream = ...; // publisher callbacks are invoked by eventLoop1
     * upstream.subscribeOn(eventLoop1)
     *         .subscribe(mySubscriber, eventLoop2); // mySubscriber callbacks are invoked with eventLoop2
     * }</pre>
     */
    default StreamMessage<T> subscribeOn(EventExecutor eventExecutor) {
        requireNonNull(eventExecutor, "eventExecutor");
        return new SubscribeOnStreamMessage<>(this, eventExecutor);
    }
}
