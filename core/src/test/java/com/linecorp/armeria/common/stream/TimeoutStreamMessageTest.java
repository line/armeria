package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;

class TimeoutStreamMessageTest {
    private EventExecutor executor;

    @BeforeEach
    public void setUp() {
        executor = new DefaultEventExecutor();
    }

    @AfterEach
    public void tearDown() {
        executor.shutdownGracefully();
    }

    @Test
    public void timeoutNextMode() {
        final StreamMessage<String> timeoutStreamMessage = StreamMessage.of("message1", "message2").timeout(
                Duration.ofSeconds(1), StreamTimeoutMode.UNTIL_NEXT);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        timeoutStreamMessage.subscribe(new Subscriber<String>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                subscription.request(1);
            }

            @Override
            public void onNext(String s) {
                executor.schedule(() -> subscription.request(1), 2, TimeUnit.SECONDS);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(null);
            }
        }, executor);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void noTimeoutNextMode() throws Exception {
        final StreamMessage<String> timeoutStreamMessage = StreamMessage.of("message1", "message2").timeout(
                Duration.ofSeconds(1), StreamTimeoutMode.UNTIL_NEXT);

        final CompletableFuture<Void> future = new CompletableFuture<>();

        timeoutStreamMessage.subscribe(new Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(2);
            }

            @Override
            public void onNext(String s) {
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(null);
            }
        }, executor);

        assertThat(future.get()).isNull();
    }

    @Test
    void timeoutFirstMode() {
        final StreamMessage<String> timeoutStreamMessage = StreamMessage.of("message1", "message2").timeout(
                Duration.ofSeconds(1), StreamTimeoutMode.UNTIL_FIRST);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        timeoutStreamMessage.subscribe(new Subscriber<String>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                executor.schedule(() -> subscription.request(1), 2, TimeUnit.SECONDS);
            }

            @Override
            public void onNext(String s) {
                subscription.request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(null);
            }
        }, executor);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void noTimeoutModeFirst() throws Exception {
        final StreamMessage<String> timeoutStreamMessage = StreamMessage.of("message1", "message2").timeout(
                Duration.ofSeconds(1), StreamTimeoutMode.UNTIL_FIRST);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        timeoutStreamMessage.subscribe(new Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(2);
            }

            @Override
            public void onNext(String s) {
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(null);
            }
        }, executor);

        assertThat(future.get()).isNull();
    }

    @Test
    void timeoutEOSMode() {
        final StreamMessage<String> timeoutStreamMessage = StreamMessage.of("message1", "message2").timeout(
                Duration.ofSeconds(2), StreamTimeoutMode.UNTIL_EOS);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        timeoutStreamMessage.subscribe(new Subscriber<String>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                executor.schedule(() -> subscription.request(1), 1, TimeUnit.SECONDS);
            }

            @Override
            public void onNext(String s) {
                executor.schedule(() -> subscription.request(1), 2, TimeUnit.SECONDS);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(null);
            }
        }, executor);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void noTimeoutEOSMode() throws Exception {
        final StreamMessage<String> timeoutStreamMessage = StreamMessage.of("message1", "message2").timeout(
                Duration.ofSeconds(2), StreamTimeoutMode.UNTIL_EOS);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        timeoutStreamMessage.subscribe(new Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(2);
            }

            @Override
            public void onNext(String s) {
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(null);
            }
        }, executor);

        assertThat(future.get()).isNull();
    }
}