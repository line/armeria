package com.linecorp.armeria.client.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.limit.ConcurrencyLimit.Permit;
import com.linecorp.armeria.client.limit.AsyncConcurrencyLimitTest.ConcurrencyLimitParameterResolver;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

@ExtendWith(ConcurrencyLimitParameterResolver.class)
public class AsyncConcurrencyLimitTest {
    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();
    private final ClientRequestContext ctx = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                                 .eventLoop(eventLoop.get())
                                                                 .build();

    private static ClientRequestContext newContext() {
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                   .eventLoop(eventLoop.get())
                                   .build();
    }

    @Test
    void itShouldExecuteImmediatelyWhilePermitsAreAvailable(AsyncConcurrencyLimit semaphore) {
        assertThat(semaphore.availablePermitCount()).isEqualTo(2);

        semaphore.acquire(ctx);
        await().untilAsserted(() -> {
            assertThat(semaphore.acquiredPermitCount()).isEqualTo(1);
            assertThat(semaphore.availablePermitCount()).isEqualTo(1);
        });

        semaphore.acquire(ctx);
        await().untilAsserted(() -> {
            assertThat(semaphore.acquiredPermitCount()).isEqualTo(2);
            assertThat(semaphore.availablePermitCount()).isEqualTo(0);
        });

        semaphore.acquire(ctx);
        await().untilAsserted(() -> {
            assertThat(semaphore.acquiredPermitCount()).isEqualTo(2);
            assertThat(semaphore.availablePermitCount()).isEqualTo(0);
        });

    }

    @Test
    void itShouldExecuteDeferredComputationsWhenPermitsAreReleased(AsyncConcurrencyLimit sem) {
        CountingSemaphore semaphore = new CountingSemaphore(sem);

        assertThat(semaphore.availablePermitCount()).isEqualTo(2);

        semaphore.acquire(ctx);
        semaphore.acquire(ctx);
        semaphore.acquire(ctx);
        semaphore.acquire(ctx);

        await().untilAsserted(() -> {
            assertThat(semaphore.count.get()).isEqualTo(2);
            assertThat(semaphore.acquiredPermitCount()).isEqualTo(2);
        });

        semaphore.permits.poll().release();

        await().untilAsserted(() -> {
            assertThat(semaphore.count.get()).isEqualTo(3);
            assertThat(semaphore.acquiredPermitCount()).isEqualTo(2);
        });

        semaphore.permits.poll().release();

        await().untilAsserted(() -> {
            assertThat(semaphore.count.get()).isEqualTo(4);
            assertThat(semaphore.acquiredPermitCount()).isEqualTo(2);
        });

    }

    private static class CountingSemaphore {
        private final AsyncConcurrencyLimit sem;
        private final AtomicInteger count = new AtomicInteger(0);
        private final ConcurrentLinkedQueue<Permit> permits = new ConcurrentLinkedQueue<>();

        private CountingSemaphore(AsyncConcurrencyLimit sem) {this.sem = sem;}

        public int availablePermitCount() {
            return sem.availablePermitCount();
        }

        public CompletableFuture<Permit> acquire(ClientRequestContext ctx) {
            CompletableFuture<Permit> fPermit = sem.acquire(ctx);
            fPermit.whenComplete((permit, throwable) -> {
                if (throwable == null) {
                    count.incrementAndGet();
                    permits.offer(permit);
                }
            });
            return fPermit;
        }

        public int acquiredPermitCount() {
            return sem.acquiredPermitCount();
        }
    }

    static class ConcurrencyLimitParameterResolver implements ParameterResolver {
        @Override
        public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
                throws ParameterResolutionException {
            return parameterContext.getParameter().getType() == AsyncConcurrencyLimit.class;
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
                throws ParameterResolutionException {
            return new AsyncConcurrencyLimit(100000, 2, 100);
        }
    }

}