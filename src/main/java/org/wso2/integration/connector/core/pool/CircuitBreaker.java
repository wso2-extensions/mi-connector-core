/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.integration.connector.core.pool;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.integration.connector.core.connection.CircuitOpenException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static java.lang.String.format;

public final class CircuitBreaker {

    private static final Log log = LogFactory.getLog(CircuitBreaker.class);

    public enum Mode {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final long initialDuration;
    private final double progressionFactor;
    private final long maximumDuration;
    private final LongSupplier clock;
    private final AtomicReference<State> state;

    public CircuitBreaker(int failureThreshold, long initialDuration, double progressionFactor,
                          long maximumDuration) {
        this(failureThreshold, initialDuration, progressionFactor, maximumDuration,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public CircuitBreaker(int failureThreshold, long initialDuration, double progressionFactor,
                          long maximumDuration, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.initialDuration = initialDuration;
        this.progressionFactor = progressionFactor;
        this.maximumDuration = maximumDuration;
        this.clock = clock;
        this.state = new AtomicReference<>(new State(Mode.CLOSED, 0, 0, 0, initialDuration, false));
    }

    public Mode getMode() {
        return state.get().mode;
    }

    public boolean isOpenBeforeDeadline() {
        return isOpenBeforeDeadline(state.get());
    }

    private boolean isOpenBeforeDeadline(State current) {
        return current.mode == Mode.OPEN && clock.getAsLong() - current.openedAt < current.openDuration;
    }

    State snapshot() {
        return state.get();
    }

    public Admission admit() throws CircuitOpenException {
        while (true) {
            State current = state.get();
            if (current.mode == Mode.CLOSED) {
                return new Admission(current.generation, false);
            }
            if (current.trialInFlight) {
                throw new CircuitOpenException("Circuit breaker trial is in progress.");
            }
            if (isOpenBeforeDeadline(current)) {
                throw new CircuitOpenException("Circuit breaker is OPEN. Requests are blocked.");
            }
            long generation = current.mode == Mode.OPEN ? current.generation + 1 : current.generation;
            State trial = new State(Mode.HALF_OPEN, current.failures, generation, current.openedAt,
                    current.openDuration, true);
            if (state.compareAndSet(current, trial)) {
                if (current.mode == Mode.OPEN) {
                    log.info("Switching circuit breaker to HALF_OPEN state after open duration expired.");
                }
                return new Admission(generation, true);
            }
        }
    }

    private long report(long generation, boolean failure) {
        while (true) {
            State current = state.get();
            if (current.generation != generation || current.mode == Mode.OPEN) {
                return generation;
            }
            State next;
            if (failure) {
                long failures = current.failures + 1;
                if (current.mode == Mode.HALF_OPEN || failures > failureThreshold) {
                    long duration = current.mode == Mode.CLOSED ? initialDuration
                            : Math.min(maximumDuration, (long) (current.openDuration * progressionFactor));
                    next = new State(Mode.OPEN, failures, generation + 1, clock.getAsLong(), duration, false);
                } else {
                    next = new State(Mode.CLOSED, failures, generation, 0, initialDuration, false);
                }
            } else {
                if (current.mode == Mode.CLOSED && current.failures == 0) {
                    return generation;
                }
                long closedGeneration = current.mode == Mode.HALF_OPEN ? generation + 1 : generation;
                next = new State(Mode.CLOSED, 0, closedGeneration, 0, initialDuration, false);
            }
            if (state.compareAndSet(current, next)) {
                if (next.mode == Mode.OPEN && current.mode == Mode.HALF_OPEN) {
                    log.warn("Connection failed in HALF_OPEN state. Switching to OPEN state.");
                } else if (next.mode == Mode.OPEN) {
                    log.warn(format("Circuit breaker tripped after %d failures. Switching to OPEN state.",
                            next.failures));
                }
                return next.generation;
            }
        }
    }

    // The operation owns the admission until it hands it to its stream or returns it unused.
    public final class Admission {
        private final boolean trial;
        private long generation;
        private int reports;

        private Admission(long generation, boolean trial) {
            this.generation = generation;
            this.trial = trial;
        }

        public boolean isTrial() {
            return trial;
        }

        public void reportSuccess() {
            if (reports == 0) {
                generation = report(generation, false);
                reports = 1;
            } else {
                reports = 2;
            }
        }

        public void reportFailure() {
            if (reports < 2) {
                generation = report(generation, true);
                reports++;
            }
        }

        public void cancelTrial() {
            if (reports != 0) {
                return;
            }
            reports = 2;
            if (trial) {
                State current = state.get();
                if (current.generation == generation && current.trialInFlight) {
                    State available = new State(Mode.HALF_OPEN, current.failures, generation,
                            current.openedAt, current.openDuration, false);
                    state.compareAndSet(current, available);
                }
            }
        }
    }

    static final class State {
        final Mode mode;
        final long failures;
        final long generation;
        final long openedAt;
        final long openDuration;
        final boolean trialInFlight;

        private State(Mode mode, long failures, long generation, long openedAt, long openDuration,
                      boolean trialInFlight) {
            this.mode = mode;
            this.failures = failures;
            this.generation = generation;
            this.openedAt = openedAt;
            this.openDuration = openDuration;
            this.trialInFlight = trialInFlight;
        }
    }
}
