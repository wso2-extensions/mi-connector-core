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

import org.junit.Test;
import org.wso2.integration.connector.core.connection.CircuitOpenException;
import org.wso2.integration.connector.core.pool.CircuitBreaker.Admission;
import org.wso2.integration.connector.core.pool.CircuitBreaker.Mode;
import org.wso2.integration.connector.core.pool.CircuitBreaker.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong();
    private final CircuitBreaker breaker = new CircuitBreaker(0, 1000, 2, 3000, now::get);

    @Test
    public void observingOpenTimeDoesNotReserveTheTrial() throws Exception {
        assertFalse(breaker.isOpenBeforeDeadline());
        breaker.admit().reportFailure();
        State opened = breaker.snapshot();
        now.set(999);
        assertTrue(breaker.isOpenBeforeDeadline());
        assertTrue(breaker.isOpenBeforeDeadline());
        assertSame(opened, breaker.snapshot());
        now.incrementAndGet();
        assertFalse(breaker.isOpenBeforeDeadline());
        assertSame(opened, breaker.snapshot());
        Admission trial = breaker.admit();
        assertFalse(breaker.isOpenBeforeDeadline());
        assertThrows(CircuitOpenException.class, breaker::admit);
        trial.reportFailure();
        now.set(2999);
        assertTrue(breaker.isOpenBeforeDeadline());
        now.incrementAndGet();
        assertFalse(breaker.isOpenBeforeDeadline());
    }

    private Admission trialAtDeadline(CircuitBreaker target, long duration) throws CircuitOpenException {
        assertEquals(Mode.OPEN, target.getMode());
        now.addAndGet(duration - 1);
        assertThrows(CircuitOpenException.class, target::admit);
        now.incrementAndGet();
        Admission trial = target.admit();
        assertTrue(trial.isTrial());
        assertEquals(Mode.HALF_OPEN, target.getMode());
        return trial;
    }

    @Test
    public void stateTransitionsAreLogged() throws Exception {
        Logger logger = Logger.getLogger(CircuitBreaker.class.getName());
        List<String> lines = new ArrayList<>();
        Handler collector = new Handler() {
            public void publish(LogRecord record) {
                lines.add(record.getLevel() + " " + record.getMessage());
            }
            public void flush() {
            }
            public void close() {
            }
        };
        logger.addHandler(collector);
        try {
            breaker.admit().reportFailure();
            now.set(1000);
            breaker.admit().reportFailure();
        } finally {
            logger.removeHandler(collector);
        }
        assertEquals(Arrays.asList("WARNING Circuit breaker tripped after 1 failures. Switching to OPEN state.",
                "INFO Switching circuit breaker to HALF_OPEN state after open duration expired.",
                "WARNING Connection failed in HALF_OPEN state. Switching to OPEN state."), lines);
    }

    @Test
    public void applicationErrorsKeepTheBreakerClosed() throws Exception {
        CircuitBreaker target = new CircuitBreaker(0, 1000, 2, 3000, () -> {
            throw new AssertionError("The closed success path read the clock");
        });
        State closed = target.snapshot();
        for (int attempt = 0; attempt < 100; attempt++) {
            Admission admission = target.admit();
            assertFalse(admission.isTrial());
            admission.reportSuccess();
            assertEquals(Mode.CLOSED, target.getMode());
            assertSame(closed, target.snapshot());
        }
        assertEquals(0, closed.failures);
    }

    @Test
    public void aBusyBorrowDoesNotCountOrClearConsecutiveFailures() throws Exception {
        CircuitBreaker target = new CircuitBreaker(1, 1000, 1.5, 3000, now::get);
        target.admit().reportFailure();
        assertEquals(Mode.CLOSED, target.getMode());
        target.admit().cancelTrial();
        assertEquals(1, target.snapshot().failures);
        target.admit().reportSuccess();
        assertEquals(0, target.snapshot().failures);
        target.admit().reportFailure();
        assertEquals(Mode.CLOSED, target.getMode());
        target.admit().reportFailure();
        assertEquals(Mode.OPEN, target.getMode());
    }

    @Test
    public void oneTrialWinsInEveryRound() throws Exception {
        int callers = 20;
        ExecutorService workers = Executors.newFixedThreadPool(callers);
        try {
            for (int round = 0; round < 200; round++) {
                CircuitBreaker target = new CircuitBreaker(0, 1000, 2, 3000, now::get);
                target.admit().reportFailure();
                now.addAndGet(1001);
                CyclicBarrier start = new CyclicBarrier(callers);
                List<Future<Admission>> results = new ArrayList<>();
                for (int caller = 0; caller < callers; caller++) {
                    results.add(workers.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            return target.admit();
                        } catch (CircuitOpenException refused) {
                            return null;
                        }
                    }));
                }
                int admitted = 0;
                int refused = 0;
                for (Future<Admission> result : results) {
                    Admission admission = result.get(5, TimeUnit.SECONDS);
                    if (admission == null) {
                        refused++;
                    } else {
                        assertTrue(admission.isTrial());
                        admitted++;
                    }
                }
                assertEquals("round " + round, 1, admitted);
                assertEquals("round " + round, 19, refused);
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void failedTrialsProgressUpToTheMaximum() throws Exception {
        breaker.admit().reportFailure();
        for (long duration : new long[]{1000, 2000, 3000}) {
            trialAtDeadline(breaker, duration).reportFailure();
        }
        trialAtDeadline(breaker, 3000);
    }

    @Test
    public void successfulTrialResetsTheOpenTime() throws Exception {
        breaker.admit().reportFailure();
        for (long duration : new long[]{1000, 2000, 3000}) {
            trialAtDeadline(breaker, duration).reportFailure();
        }
        trialAtDeadline(breaker, 3000).reportSuccess();
        assertEquals(Mode.CLOSED, breaker.getMode());
        Admission next = breaker.admit();
        assertFalse(next.isTrial());
        next.reportFailure();
        trialAtDeadline(breaker, 1000);
    }

    @Test
    public void aDecimalFactorScalesTheOpenTime() throws Exception {
        CircuitBreaker target = new CircuitBreaker(0, 1000, 1.5, 10000, now::get);
        target.admit().reportFailure();
        trialAtDeadline(target, 1000).reportFailure();
        trialAtDeadline(target, 1500).reportFailure();
        trialAtDeadline(target, 2250);
    }

    @Test
    public void lateFailurePreservesTheOpenGenerationCountAndDeadline() throws Exception {
        Admission old = breaker.admit();
        breaker.admit().reportFailure();
        State open = breaker.snapshot();
        assertEquals(1, open.failures);
        assertEquals(1, open.generation);
        assertEquals(1000, open.openDuration);
        now.set(400);
        old.reportFailure();
        assertSame(open, breaker.snapshot());
        now.set(999);
        assertThrows(CircuitOpenException.class, breaker::admit);
        now.set(1000);
        Admission trial = breaker.admit();
        assertTrue(trial.isTrial());
        assertEquals(2, breaker.snapshot().generation);
        trial.reportSuccess();
        old.reportFailure();
        assertEquals(Mode.CLOSED, breaker.getMode());
        assertEquals(3, breaker.snapshot().generation);
    }

    @Test
    public void trialSuccessThenStreamFailureReopensAtTheInitialDuration() throws Exception {
        breaker.admit().reportFailure();
        trialAtDeadline(breaker, 1000).reportFailure();
        Admission trial = trialAtDeadline(breaker, 2000);
        trial.reportSuccess();
        assertEquals(Mode.CLOSED, breaker.getMode());
        trial.reportFailure();
        trialAtDeadline(breaker, 1000).reportSuccess();
        trial.reportFailure();
        assertEquals(Mode.CLOSED, breaker.getMode());
    }

    @Test
    public void secondFailureAfterAnotherOpenAndCloseIsDropped() throws Exception {
        breaker.admit().reportFailure();
        Admission stream = trialAtDeadline(breaker, 1000);
        stream.reportSuccess();
        breaker.admit().reportFailure();
        trialAtDeadline(breaker, 1000).reportSuccess();
        State closed = breaker.snapshot();
        stream.reportFailure();
        assertSame(closed, breaker.snapshot());
    }

    @Test
    public void ordinaryStreamFailureCountsOnceInTheSameGeneration() throws Exception {
        CircuitBreaker target = new CircuitBreaker(1, 1000, 2, 3000, now::get);
        Admission stream = target.admit();
        stream.reportSuccess();
        stream.reportFailure();
        State closed = target.snapshot();
        assertEquals(Mode.CLOSED, closed.mode);
        assertEquals(1, closed.failures);
        stream.reportFailure();
        assertSame(closed, target.snapshot());
    }

    @Test
    public void aSecondSuccessAndAThirdReportAreDropped() throws Exception {
        CircuitBreaker target = new CircuitBreaker(1, 1000, 2, 3000, now::get);
        Admission stream = target.admit();
        stream.reportSuccess();
        target.admit().reportFailure();
        State firstFailure = target.snapshot();
        assertEquals(1, firstFailure.failures);
        stream.reportSuccess();
        stream.reportFailure();
        assertSame(firstFailure, target.snapshot());
    }

    @Test
    public void aCancelledTrialReturnsItsPlaceWithoutReporting() throws Exception {
        breaker.admit().reportFailure();
        Admission cancelled = trialAtDeadline(breaker, 1000);
        State inFlight = breaker.snapshot();
        assertThrows(CircuitOpenException.class, breaker::admit);
        cancelled.cancelTrial();
        State available = breaker.snapshot();
        assertEquals(Mode.HALF_OPEN, available.mode);
        assertFalse(available.trialInFlight);
        assertEquals(inFlight.failures, available.failures);
        assertEquals(inFlight.generation, available.generation);
        assertEquals(inFlight.openDuration, available.openDuration);
        Admission next = breaker.admit();
        assertTrue(next.isTrial());
        cancelled.cancelTrial();
        cancelled.reportSuccess();
        cancelled.reportFailure();
        assertThrows(CircuitOpenException.class, breaker::admit);
        next.reportFailure();
        trialAtDeadline(breaker, 2000);
    }
}
