/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RequestOptionsBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.AnticipatedException;

class RequestCancellationTest {

    @Test
    void firstCancellationWinsAndLateRegistrationRunsImmediately() {
        final RequestCancellation cancellation = new RequestCancellation();
        final Throwable cause = new AnticipatedException();
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<Throwable> actual = new AtomicReference<>();
        cancellation.onCancel(c -> {
            actual.set(c);
            count.incrementAndGet();
        });
        cancellation.cancel(cause);
        cancellation.cancel(new AnticipatedException());
        assertThat(actual).hasValue(cause);
        assertThat(count).hasValue(1);
        cancellation.onCancel(actual::set).close();
        assertThat(actual).hasValue(cause);
    }

    @Test
    void removedRegistrationDoesNotRunAndThrowingActionDoesNotPreventCleanup() {
        final RequestCancellation cancellation = new RequestCancellation();
        final AtomicInteger removed = new AtomicInteger();
        final AtomicInteger cleaned = new AtomicInteger();
        final SafeCloseable registration = cancellation.onCancel(cause -> removed.incrementAndGet());
        registration.close();
        registration.close();
        cancellation.onCancel(cause -> {
            cleaned.incrementAndGet();
            throw new AnticipatedException();
        });
        cancellation.onCancel(cause -> cleaned.incrementAndGet());
        cancellation.onCancel(cause -> {
            cleaned.incrementAndGet();
            throw new AnticipatedException();
        });
        cancellation.cancel(new AnticipatedException());
        assertThat(removed).hasValue(0);
        assertThat(cleaned).hasValue(3);
    }

    @Test
    void registrationsOfTheSameActionHaveIndependentLifetimes() {
        final RequestCancellation cancellation = new RequestCancellation();
        final AtomicInteger count = new AtomicInteger();
        final Consumer<Throwable> action = cause -> count.incrementAndGet();
        final SafeCloseable first = cancellation.onCancel(action);
        cancellation.onCancel(action);
        first.close();
        cancellation.cancel(new AnticipatedException());
        assertThat(count).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    void completedLifetimeRemovesRegistration(boolean alreadyCompleted, boolean exceptional) {
        final RequestCancellation cancellation = new RequestCancellation();
        final CompletableFuture<Void> lifetime = new CompletableFuture<>();
        final AtomicInteger count = new AtomicInteger();
        if (alreadyCompleted) {
            complete(lifetime, exceptional);
        }
        cancellation.onCancel(cause -> count.incrementAndGet(), lifetime);
        if (!alreadyCompleted) {
            complete(lifetime, exceptional);
        }
        cancellation.cancel(new AnticipatedException());
        assertThat(count).hasValue(0);
    }

    @Test
    void completingOneLifetimeLeavesOtherResourcesCancellable() {
        final RequestCancellation cancellation = new RequestCancellation();
        final CompletableFuture<Void> completed = new CompletableFuture<>();
        final CompletableFuture<Void> pending = new CompletableFuture<>();
        final AtomicInteger count = new AtomicInteger();
        final Consumer<Throwable> action = cause -> count.incrementAndGet();
        cancellation.onCancel(action, completed);
        cancellation.onCancel(action, pending);
        completed.complete(null);
        cancellation.cancel(new AnticipatedException());
        assertThat(count).hasValue(1);
        pending.complete(null);
    }

    @Test
    void lateRegistrationCanCompleteItsLifetimeImmediately() {
        final RequestCancellation cancellation = new RequestCancellation();
        final Throwable cause = new AnticipatedException();
        final CompletableFuture<Void> lifetime = new CompletableFuture<>();
        final AtomicInteger count = new AtomicInteger();
        cancellation.cancel(cause);
        cancellation.onCancel(actual -> {
            assertThat(actual).isSameAs(cause);
            count.incrementAndGet();
            lifetime.complete(null);
        }, lifetime);
        assertThat(lifetime).isCompleted();
        cancellation.cancel(new AnticipatedException());
        assertThat(count).hasValue(1);
    }

    private static void complete(CompletableFuture<Void> lifetime, boolean exceptional) {
        if (exceptional) {
            lifetime.completeExceptionally(new AnticipatedException());
        } else {
            lifetime.complete(null);
        }
    }

    @Test
    void derivedContextsShareTheSignal() {
        final RequestCancellation cancellation = new RequestCancellation();
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final RequestOptionsBuilder options = RequestOptions.builder();
        cancellation.attach(options);
        final ClientRequestContext ctx = ClientRequestContext.builder(request)
                                                            .requestOptions(options.build())
                                                            .build();
        assertThat(RequestCancellation.get(ctx.newDerivedContext(ctx.id(), request, null, ctx.endpoint())))
                .isSameAs(cancellation);
    }
}
