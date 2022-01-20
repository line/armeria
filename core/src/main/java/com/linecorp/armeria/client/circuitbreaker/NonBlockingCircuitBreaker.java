/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Ticker;

/**
 * A non-blocking implementation of circuit breaker pattern.
 */
final class NonBlockingCircuitBreaker implements CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(NonBlockingCircuitBreaker.class);

    private static final AtomicLong seqNo = new AtomicLong(0);

    private final String name;

    private final CircuitBreakerConfig config;

    private final AtomicReference<State> state;

    private final Ticker ticker;

    /**
     * Creates a new {@link NonBlockingCircuitBreaker} with the specified {@link Ticker} and
     * {@link CircuitBreakerConfig}.
     */
    NonBlockingCircuitBreaker(Ticker ticker, CircuitBreakerConfig config) {
        this.ticker = requireNonNull(ticker, "ticker");
        this.config = requireNonNull(config, "config");
        final String name = config.name();
        this.name = name != null ? name : "circuit-breaker-" + seqNo.getAndIncrement();
        state = new AtomicReference<>(newClosedState());
        logStateTransition(CircuitState.CLOSED, null);
        notifyInitialized();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onSuccess() {
        final State currentState = state.get();
        if (currentState.isClosed()) {
            // fires success event
            final EventCount updatedCount = currentState.counter().onSuccess();
            // notifies the count if it has been updated
            if (updatedCount != null) {
                notifyCountUpdated(updatedCount);
            }
        } else if (currentState.isHalfOpen()) {
            // changes to CLOSED if at least one request succeeds during HALF_OPEN
            if (state.compareAndSet(currentState, newClosedState())) {
                logStateTransition(CircuitState.CLOSED, null);
                notifyStateChanged(CircuitState.CLOSED);
            }
        }
    }

    @Override
    public void onFailure() {
        final State currentState = state.get();
        if (currentState.isClosed()) {
            // fires failure event
            final EventCount updatedCount = currentState.counter().onFailure();
            // checks the count if it has been updated
            if (updatedCount != null) {
                // changes to OPEN if failure rate exceeds the threshold
                if (checkIfExceedingFailureThreshold(updatedCount) &&
                    state.compareAndSet(currentState, newOpenState())) {
                    logStateTransition(CircuitState.OPEN, updatedCount);
                    notifyStateChanged(CircuitState.OPEN);
                } else {
                    notifyCountUpdated(updatedCount);
                }
            }
        } else if (currentState.isHalfOpen()) {
            // returns to OPEN if a request fails during HALF_OPEN
            if (state.compareAndSet(currentState, newOpenState())) {
                logStateTransition(CircuitState.OPEN, null);
                notifyStateChanged(CircuitState.OPEN);
            }
        }
    }

    private boolean checkIfExceedingFailureThreshold(EventCount count) {
        return 0 < count.total() &&
               config.minimumRequestThreshold() <= count.total() &&
               config.failureRateThreshold() < count.failureRate();
    }

    @Override
    public boolean canRequest() {
        final State currentState = state.get();
        if (currentState.isClosed()) {
            // all requests are allowed during CLOSED
            return true;
        }

        if (currentState.isForcedOpen()) {
            notifyRequestRejected();
            return false;
        }

        if (currentState.isHalfOpen() || currentState.isOpen()) {
            if (currentState.checkTimeout() && state.compareAndSet(currentState, newHalfOpenState())) {
                // changes to HALF_OPEN if OPEN state has timed out
                logStateTransition(CircuitState.HALF_OPEN, null);
                notifyStateChanged(CircuitState.HALF_OPEN);
                return true;
            }
            // all other requests are refused
            notifyRequestRejected();
            return false;
        }

        return true;
    }

    @Override
    public void enterState(CircuitState circuitState) {
        final State oldState = state.getAndUpdate(st -> newState(circuitState));
        if (oldState.circuitState() == circuitState) {
            return;
        }
        notifyStateChanged(circuitState);
        return;
    }

    private State newOpenState() {
        return new State(CircuitState.OPEN, config.circuitOpenWindow(), NoOpCounter.INSTANCE);
    }

    private State newHalfOpenState() {
        return new State(CircuitState.HALF_OPEN, config.trialRequestInterval(), NoOpCounter.INSTANCE);
    }

    private State newClosedState() {
        return new State(
                CircuitState.CLOSED,
                Duration.ZERO,
                new SlidingWindowCounter(ticker, config.counterSlidingWindow(),
                                         config.counterUpdateInterval()));
    }

    private State newForcedOpenState() {
        return new State(CircuitState.FORCED_OPEN, Duration.ZERO, NoOpCounter.INSTANCE);
    }

    private void logStateTransition(CircuitState circuitState, @Nullable EventCount count) {
        if (logger.isInfoEnabled()) {
            final int capacity = name.length() + circuitState.name().length() + 32;
            final StringBuilder builder = new StringBuilder(capacity);
            builder.append("name:");
            builder.append(name);
            builder.append(" state:");
            builder.append(circuitState.name());
            if (count != null) {
                builder.append(" fail:");
                builder.append(count.failure());
                builder.append(" total:");
                builder.append(count.total());
            }
            logger.info(builder.toString());
        }
    }

    private void notifyInitialized() {
        config.listeners().forEach(listener -> {
            try {
                listener.onInitialized(name(), CircuitState.CLOSED);
            } catch (Throwable t) {
                logger.warn("An error occurred when notifying an Initialized event", t);
            }
            notifyCountUpdated(listener, EventCount.ZERO);
        });
    }

    private void notifyStateChanged(CircuitState circuitState) {
        config.listeners().forEach(listener -> {
            try {
                listener.onStateChanged(name(), circuitState);
            } catch (Throwable t) {
                logger.warn("An error occurred when notifying a StateChanged event", t);
            }
            notifyCountUpdated(listener, EventCount.ZERO);
        });
    }

    private void notifyCountUpdated(EventCount count) {
        config.listeners().forEach(listener -> notifyCountUpdated(listener, count));
    }

    private void notifyCountUpdated(CircuitBreakerListener listener, EventCount count) {
        try {
            listener.onEventCountUpdated(name(), count);
        } catch (Throwable t) {
            logger.warn("An error occurred when notifying an EventCountUpdated event", t);
        }
    }

    private void notifyRequestRejected() {
        config.listeners().forEach(listener -> {
            try {
                listener.onRequestRejected(name());
            } catch (Throwable t) {
                logger.warn("An error occurred when notifying a RequestRejected event", t);
            }
        });
    }

    @VisibleForTesting
    State state() {
        return state.get();
    }

    @VisibleForTesting
    CircuitBreakerConfig config() {
        return config;
    }

    private State newState(CircuitState circuitState) {
        switch (circuitState) {
            case OPEN:
                return newOpenState();
            case HALF_OPEN:
                return newHalfOpenState();
            case CLOSED:
                return newClosedState();
            case FORCED_OPEN:
                return newForcedOpenState();
            default:
                throw new Error();
        }
    }

    /**
     * The internal state of the circuit breaker.
     */
    final class State {
        private final CircuitState circuitState;
        private final EventCounter counter;
        private final long timedOutTimeNanos;

        /**
         * Creates a new instance.
         *
         * @param circuitState The circuit state
         * @param timeoutDuration The max duration of the state
         * @param counter The event counter to use during the state
         */
        private State(CircuitState circuitState, Duration timeoutDuration, EventCounter counter) {
            this.circuitState = circuitState;
            this.counter = counter;

            if (timeoutDuration.isZero() || timeoutDuration.isNegative()) {
                timedOutTimeNanos = 0L;
            } else {
                timedOutTimeNanos = ticker.read() + timeoutDuration.toNanos();
            }
        }

        private EventCounter counter() {
            return counter;
        }

        /**
         * Returns {@code true} if this state has timed out.
         */
        private boolean checkTimeout() {
            return 0 < timedOutTimeNanos && timedOutTimeNanos <= ticker.read();
        }

        boolean isOpen() {
            return circuitState == CircuitState.OPEN;
        }

        boolean isHalfOpen() {
            return circuitState == CircuitState.HALF_OPEN;
        }

        boolean isClosed() {
            return circuitState == CircuitState.CLOSED;
        }

        boolean isForcedOpen() {
            return circuitState == CircuitState.FORCED_OPEN;
        }

        CircuitState circuitState() {
            return circuitState;
        }
    }

    private static class NoOpCounter implements EventCounter {

        private static final NoOpCounter INSTANCE = new NoOpCounter();

        @Override
        public EventCount count() {
            return EventCount.ZERO;
        }

        @Override
        public EventCount onSuccess() {
            return null;
        }

        @Override
        public EventCount onFailure() {
            return null;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("config", config)
                          .toString();
    }
}
