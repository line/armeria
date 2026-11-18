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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RequestOptionsBuilder;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.AttributeKey;

/**
 * Coordinates cancellation of the resources owned by one logical request. The signal is carried by
 * request options and inherited by derived contexts, including retry and redirect attempts.
 * Actions run inline and must dispatch to the resource's executor when required.
 */
public final class RequestCancellation {

    private static final Logger logger = LoggerFactory.getLogger(RequestCancellation.class);
    private static final AttributeKey<RequestCancellation> KEY =
            AttributeKey.valueOf(RequestCancellation.class, "CANCELLATION");

    @Nullable
    public static RequestCancellation get(ClientRequestContext ctx) {
        return ctx.attr(KEY);
    }

    @Nullable
    public static Throwable cancellationCause(ClientRequestContext ctx) {
        final RequestCancellation cancellation = get(ctx);
        return cancellation != null ? cancellation.cause() : null;
    }

    public static void registerStreamCancellation(ClientRequestContext ctx, StreamMessage<?> stream) {
        final RequestCancellation cancellation = get(ctx);
        if (cancellation == null) {
            return;
        }
        cancellation.onCancel(stream::abort, stream.whenComplete());
    }

    public static void registerRequestDuplicatorCancellation(ClientRequestContext ctx,
                                                             HttpRequestDuplicator duplicator,
                                                             CompletionStage<?> finalResponseLifetime) {
        final RequestCancellation cancellation = get(ctx);
        if (cancellation == null) {
            return;
        }
        cancellation.onCancel(duplicator::abort, finalResponseLifetime);
    }

    private final Set<Registration> actions = new HashSet<>();

    // Published for cause() reads outside the actions monitor.
    @Nullable
    private volatile Throwable cause;

    public void attach(RequestOptionsBuilder options) {
        options.attr(KEY, this);
    }

    @Nullable
    public Throwable cause() {
        return cause;
    }

    /**
     * Registers an action and returns a handle that removes this registration. If already canceled,
     * invokes the action immediately with the original cause.
     *
     * <p>The action runs on the thread calling {@link #cancel(Throwable)}, or on the registering thread
     * if already canceled. Closing the handle cannot prevent an invocation that cancellation has
     * already claimed, even if the action has not started running yet.
     */
    public SafeCloseable onCancel(Consumer<? super Throwable> action) {
        requireNonNull(action, "action");
        final Throwable cause;
        synchronized (actions) {
            cause = this.cause;
            if (cause == null) {
                final Registration registration = new Registration(action);
                actions.add(registration);
                return registration;
            }
        }
        invoke(action, cause);
        return () -> {};
    }

    /**
     * Registers an action and removes its registration when the lifetime completes normally or exceptionally.
     * Registration happens first, so an already canceled signal invokes the action even if the lifetime
     * has already completed. The same race with cancellation as in {@link #onCancel(Consumer)} applies.
     */
    public void onCancel(Consumer<? super Throwable> action, CompletionStage<?> lifetime) {
        requireNonNull(lifetime, "lifetime");
        final SafeCloseable registration = onCancel(action);
        lifetime.whenComplete((unused, cause) -> registration.close());
    }

    /**
     * Records the first cancellation cause and invokes the registered actions. Later calls have no effect.
     */
    public void cancel(Throwable cause) {
        requireNonNull(cause, "cause");
        final List<Registration> toCancel;
        synchronized (actions) {
            if (this.cause != null) {
                return;
            }
            this.cause = cause;
            toCancel = new ArrayList<>(actions);
            actions.clear();
        }
        // Actions may complete their resources and close registrations synchronously.
        // Invoke them outside the monitor so resource cleanup does not hold the registry lock.
        for (Registration registration : toCancel) {
            invoke(registration.action, cause);
        }
    }

    private static void invoke(Consumer<? super Throwable> action, Throwable cause) {
        try {
            action.accept(cause);
        } catch (Throwable t) {
            logger.warn("Failed to cancel a request resource.", t);
        }
    }

    // Distinct registrations let the same action have multiple independent lifetimes.
    private final class Registration implements SafeCloseable {

        private final Consumer<? super Throwable> action;

        Registration(Consumer<? super Throwable> action) {
            this.action = action;
        }

        @Override
        public void close() {
            synchronized (actions) {
                actions.remove(this);
            }
        }
    }
}
