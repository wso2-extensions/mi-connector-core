/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.integration.connector.core.pool;

import org.junit.Test;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.CircuitOpenException;
import org.wso2.integration.connector.core.connection.Connection;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolBusyException;
import org.wso2.integration.connector.core.connection.PoolCloseReason;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.LogRecord;

import static org.junit.Assert.*;

public class ConnectionPoolTest extends PoolFixture {

    @Test
    public void thresholdCountsOperations() throws Exception {
        config.setFailureThreshold(1);
        register(first, config);
        failOperation();
        check("first failure", current().getBreaker().snapshot().failures == 1 && mode() == CircuitBreaker.Mode.CLOSED);
        failOperation();
        check("second failure", mode() == CircuitBreaker.Mode.OPEN);
    }

    @Test
    public void decimalConfigurationReachesThePool() throws Exception {
        config.setOpenDurationProgressFactor(1.5);
        register(first, config);
        for (long duration : new long[] {1000, 1500, 2250}) {
            failOperation();
            CircuitBreaker.State state = current().getBreaker().snapshot();
            check("duration", duration == state.openDuration);
            clock.addAndGet(duration - 1);
            assertThrows(CircuitOpenException.class, this::borrow);
            clock.incrementAndGet();
        }
        ConnectionLease trial = borrow();
        assertTrue(trial.isTrial());
        trial.cancelTrial();
        trial.returnToPool();
    }

    @Test
    public void validatesOnlyOnReturn() throws Exception {
        config.setTestOnReturn(true);
        config.setTestOnBorrow(false);
        register(first, config);
        ConnectionLease lease = borrow();
        check("no borrow validation", first.validations == 0);
        lease.returnToPool();
        check("return validation", first.validations == 1);
    }

    @Test
    public void activeLeasesReturnToTheirOwnPools() throws Exception {
        config.setPoolConnectionAgedTimeout(200);
        register(first, config);
        ConnectionPool one = current();
        ConnectionLease oldest = borrow();
        expire();
        ConnectionLease middle = borrow();
        ConnectionPool two = current();
        check("factory and breaker", one.getBreaker() == two.getBreaker() && two.getConnectionFactory() == first);
        assertSettings(first, config);
        expire();
        ConnectionLease latest = borrow();
        ConnectionPool three = current();
        check("factory and breaker", one.getConnectionFactory() == two.getConnectionFactory()
                && two.getConnectionFactory() == three.getConnectionFactory()
                && one.getBreaker() == two.getBreaker() && two.getBreaker() == three.getBreaker()
                && mode() == CircuitBreaker.Mode.CLOSED);
        check("age closures", one != two && two != three && one.isClosed() && two.isClosed() && !three.isClosed()
                && first.reasons.equals(Arrays.asList(PoolCloseReason.AGE, PoolCloseReason.AGE)));
        assertSettings(first, config);
        oldest.returnToPool();
        check("original pools", one.getNumActive() == 0 && two.getNumActive() == 1);
        check("new active count", three.getNumActive() == 1);
        middle.returnToPool();
        check("new active count", three.getNumActive() == 1);
        check("original pools", first.destroyed.equals(Arrays.asList(oldest.getConnection(), middle.getConnection()))
                && one.getNumActive() == 0 && two.getNumActive() == 0 && three.getNumIdle() == 0);
        ConnectionLease next = borrow();
        next.getConnection().connect(null);
        check("next borrow", ((Item) next.getConnection()).uses == 1);
        next.returnToPool();
        latest.returnToPool();
    }

    @Test
    public void redeployUsesNewSettingsAndDestroysOldLease() throws Exception {
        register(first, config);
        ConnectionPool old = current();
        ConnectionLease outstanding = borrow();
        handler.removeLocalEntryConnections(name);
        assertTrue(first.retired);
        assertNull(handler.getConnectionFactory(connector, name));
        Configuration changed = settings();
        changed.setMaxActiveConnections(3);
        register(second, changed);
        ConnectionLease replacement = borrow();
        assertSame(second, ((Item) replacement.getConnection()).factory);
        assertSettings(second, changed);
        assertEquals(3, current().getMaxActive());
        assertNotSame(old.getBreaker(), current().getBreaker());
        outstanding.returnToPool();
        assertEquals(Collections.singletonList(outstanding.getConnection()), first.destroyed);
        assertEquals(1, current().getNumActive());
        assertEquals(0, current().getNumIdle());
        expire();
        int oldCreations = first.made;
        ConnectionLease latest = borrow();
        check("replacement rotated", first.made == oldCreations && latest.getConnection() instanceof Item
                && "second".equals(((Item) latest.getConnection()).folder) && current().getMaxActive() == 3);
        assertSettings(second, changed);
        assertEquals(3, current().getMaxActive());
        replacement.invalidate();
        assertEquals(Collections.singletonList(replacement.getConnection()), second.destroyed);
        assertEquals(1, current().getNumActive());
        assertTrue(first.retired);
        latest.returnToPool();
    }

    @Test
    public void trialReportsAfterReturnUseTheirGeneration() throws Exception {
        config.setOpenDurationMillis(10000);
        config.setOpenDurationProgressFactor(2);
        register(first, config);
        failOperation();
        clock.addAndGet(10000);
        ConnectionLease trial = borrow();
        assertTrue(trial.isTrial());
        trial.returnToPool();
        trial.reportSuccess();
        check("trial then late failure", mode() == CircuitBreaker.Mode.CLOSED);
        trial.reportFailure();
        check("trial then late failure", mode() == CircuitBreaker.Mode.OPEN
                && current().getBreaker().snapshot().openDuration == 10000);
        clock.addAndGet(10000);
        ConnectionLease recover = borrow();
        recover.reportSuccess();
        recover.returnToPool();
        trial.reportFailure();
        check("excess reports", mode() == CircuitBreaker.Mode.CLOSED && current().getBreaker().snapshot().failures == 0);
        ConnectionLease old = borrow();
        old.reportSuccess();
        old.returnToPool();
        failOperation();
        clock.addAndGet(10000);
        recover = borrow();
        recover.reportSuccess();
        recover.returnToPool();
        old.reportFailure();
        check("stale report", mode() == CircuitBreaker.Mode.CLOSED && current().getBreaker().snapshot().failures == 0);
        ConnectionLease repeated = borrow();
        repeated.reportSuccess();
        repeated.reportSuccess();
        repeated.reportFailure();
        repeated.returnToPool();
        check("excess reports", mode() == CircuitBreaker.Mode.CLOSED);
    }

    @Test
    public void duplicateSuccessPreservesAnInterveningFailure() throws Exception {
        Configuration thresholdOne = settings();
        thresholdOne.setFailureThreshold(1);
        register(first, thresholdOne);
        ConnectionLease repeated = borrow();
        repeated.reportSuccess();
        repeated.returnToPool();
        failOperation();
        repeated.reportSuccess();
        failOperation();
        check("duplicate success preserves failures", mode() == CircuitBreaker.Mode.OPEN);
    }

    @Test
    public void failedPhysicalBorrowHandsBackTheTrial() throws Exception {
        config.setMaxActiveConnections(1);
        config.setMaxWaitTime(30L);
        register(first, config);
        ConnectionLease occupant = borrow();
        occupant.reportFailure();
        clock.addAndGet(1000);
        assertThrows(PoolBusyException.class, this::borrow);
        assertEquals(CircuitBreaker.Mode.HALF_OPEN, mode());
        assertFalse(current().getBreaker().snapshot().trialInFlight);
        occupant.invalidate();
        first.failCreate = true;
        ConnectException cause = assertThrows(ConnectException.class, this::borrow);
        assertEquals("Cannot make the connection.", cause.getCause().getMessage());
        assertFalse(current().getBreaker().snapshot().trialInFlight);
        first.failCreate = false;
        ConnectionLease next = borrow();
        assertTrue(next.isTrial());
        next.reportSuccess();
        next.returnToPool();
    }

    @Test
    public void interruptedWaitReturnsTheTrialAndPreservesTheInterrupt() throws Exception {
        config.setMaxActiveConnections(1);
        config.setMaxWaitTime(5000L);
        register(first, config);
        ConnectionLease occupant = borrow();
        occupant.reportFailure();
        clock.addAndGet(1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> borrower = new AtomicReference<>();
        try {
            Future<Boolean> waiting = executor.submit(() -> {
                borrower.set(Thread.currentThread());
                ConnectException error = assertThrows(ConnectException.class, this::borrow);
                return error.getCause() instanceof InterruptedException && Thread.currentThread().isInterrupted();
            });
            awaitPoolWait(borrower, waiting);
            borrower.get().interrupt();
            assertTrue(waiting.get(2, TimeUnit.SECONDS));
            assertFalse(current().getBreaker().snapshot().trialInFlight);
            occupant.invalidate();
            ConnectionLease next = borrow();
            assertTrue(next.isTrial());
            next.reportSuccess();
            next.returnToPool();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void returningAnObjectAllowsTheWaitingBorrowToFinish() throws Exception {
        config.setMaxActiveConnections(1);
        config.setMaxWaitTime(2500L);
        register(first, config);
        ConnectionLease firstLease = borrow();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Thread> borrower = new AtomicReference<>();
            Future<ConnectionLease> waiting = executor.submit(() -> {
                borrower.set(Thread.currentThread());
                return borrow();
            });
            awaitPoolWait(borrower, waiting);
            firstLease.returnToPool();
            ConnectionLease next = waiting.get(2, TimeUnit.SECONDS);
            assertSame(firstLease.getConnection(), next.getConnection());
            next.returnToPool();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void legacyBorrowLeavesTheTrialForALease() throws Exception {
        config.setMaxActiveConnections(1);
        config.setMaxWaitTime(30L);
        register(first, config);
        ConnectionLease occupant = borrow();
        occupant.reportFailure();
        assertThrows(CircuitOpenException.class, () -> handler.getConnection(connector, name));
        clock.addAndGet(1000);
        assertThrows(PoolBusyException.class, () -> handler.getConnection(connector, name));
        assertEquals(CircuitBreaker.Mode.HALF_OPEN, mode());
        assertFalse(current().getBreaker().snapshot().trialInFlight);
        occupant.returnToPool();
        ConnectionLease next = borrow();
        assertTrue(next.isTrial());
        next.reportSuccess();
        next.returnToPool();
    }

    @Test
    public void legacyBorrowReportsItsResultToTheBreaker() throws Exception {
        config.setMaxActiveConnections(1);
        config.setMaxWaitTime(30L);
        register(first, config);
        first.failCreate = true;
        ConnectException failed = assertThrows(ConnectException.class,
                () -> handler.getConnection(connector, name));
        assertEquals("Cannot make the connection.", failed.getCause().getMessage());
        assertEquals(CircuitBreaker.Mode.OPEN, mode());
        assertThrows(CircuitOpenException.class, () -> handler.getConnection(connector, name));
        clock.addAndGet(1000);
        failed = assertThrows(ConnectException.class, () -> handler.getConnection(connector, name));
        assertEquals("Cannot make the connection.", failed.getCause().getMessage());
        assertEquals(CircuitBreaker.Mode.OPEN, mode());
        first.failCreate = false;
        clock.addAndGet(1000);
        Connection raw = handler.getConnection(connector, name);
        assertEquals(CircuitBreaker.Mode.CLOSED, mode());
        assertThrows(PoolBusyException.class, () -> handler.getConnection(connector, name));
        assertEquals(CircuitBreaker.Mode.CLOSED, mode());
        assertEquals(0, current().getBreaker().snapshot().failures);
        handler.returnConnection(connector, name, raw);
    }

    @Test
    public void onePoolTrialReachesTheFactory() throws Exception {
        register(first, config);
        failOperation();
        clock.addAndGet(1000);
        ConnectionLease trial = borrow();
        int made = first.made;
        for (int caller = 1; caller < 20; caller++) {
            assertThrows(CircuitOpenException.class, this::borrow);
        }
        assertTrue(trial.isTrial());
        assertEquals(made, first.made);
        trial.reportSuccess();
        trial.returnToPool();
    }

    @Test
    public void reportedTrialFailuresIncreaseUntilCapped() throws Exception {
        config.setOpenDurationProgressFactor(2);
        config.setMaxOpenDurationMillis(3000);
        register(first, config);
        for (long wait : new long[] {1000, 2000, 3000, 3000}) {
            failOperation();
            assertEquals(wait, current().getBreaker().snapshot().openDuration);
            clock.addAndGet(wait);
        }
    }

    @Test
    public void reportedTrialSuccessResetsTheNextOpenTime() throws Exception {
        config.setOpenDurationProgressFactor(2);
        register(first, config);
        failOperation();
        clock.addAndGet(1000);
        failOperation();
        clock.addAndGet(2000);
        ConnectionLease recovered = borrow();
        assertTrue(recovered.isTrial());
        recovered.reportSuccess();
        recovered.returnToPool();
        assertEquals(CircuitBreaker.Mode.CLOSED, mode());
        failOperation();
        assertEquals(1000, current().getBreaker().snapshot().openDuration);
    }

    @Test
    public void lateLeaseFailureLeavesTheOpenStateIntact() throws Exception {
        register(first, config);
        ConnectionLease late = borrow();
        failOperation();
        CircuitBreaker breaker = current().getBreaker();
        CircuitBreaker.State state = breaker.snapshot();
        clock.addAndGet(400);
        late.invalidate();
        late.reportFailure();
        assertSame(state, breaker.snapshot());
        assertEquals(1000, state.openDuration);
        assertEquals(CircuitBreaker.Mode.OPEN, state.mode);
    }

    @Test
    public void agedTimeoutUsesMilliseconds() throws Exception {
        config.setPoolConnectionAgedTimeout(60000);
        register(first, config);
        ConnectionPool pool = current();
        Instant start = Instant.now();
        pool.setStrat(start);
        assertFalse(pool.isPoolExpired(start.plusMillis(59999)));
        assertTrue(pool.isPoolExpired(start.plusMillis(60000)));
    }

    @Test
    public void closeListenerPrecedesDestructionOnEveryPath() throws Exception {
        Logger logger = Logger.getLogger(ConnectionHandler.class.getName());
        AtomicInteger errors = new AtomicInteger();
        Handler listenerErrors = new Handler() {
            public void publish(LogRecord record) {
                if ("Pool close listener failed.".equals(record.getMessage()) && record.getThrown() != null) {
                    errors.incrementAndGet();
                }
            }
            public void flush() {
            }
            public void close() {
            }
        };
        logger.addHandler(listenerErrors);
        try {
            checkClosePaths(errors);
        } finally {
            logger.removeHandler(listenerErrors);
        }
    }

    private void checkClosePaths(AtomicInteger errors) throws Exception {
        for (boolean throwsError : new boolean[] {false, true}) {
            for (String path : new String[] {"age", "all", "connector", "entry"}) {
                int before = errors.get();
                Factory factory = new Factory();
                factory.throwOnClose = throwsError;
                register(factory, config);
                ConnectionLease a = borrow();
                ConnectionLease b = borrow();
                a.returnToPool();
                b.returnToPool();
                ConnectionPool old = current();
                PoolCloseReason reason = PoolCloseReason.RETIRE;
                if ("age".equals(path)) {
                    reason = PoolCloseReason.AGE;
                    expire();
                    borrow().returnToPool();
                } else if ("all".equals(path)) {
                    handler.shutdownConnections();
                } else if ("connector".equals(path)) {
                    handler.shutdownConnections(connector);
                } else {
                    handler.removeLocalEntryConnections(name);
                }
                check("single callback", factory.reasons.equals(Collections.singletonList(reason)));
                check("callback order", factory.order.equals(Arrays.asList(reason.name(), "destroy", "destroy")));
                check("callback unlocked", !factory.lockHeld);
                assertEquals(Collections.singletonList(Thread.currentThread()), factory.callers);
                if (throwsError) {
                    check("listener failure cleanup", errors.get() == before + 1 && factory.destroyed.size() == 2
                            && old.isClosed() && current() != old);
                } else {
                    assertEquals(before, errors.get());
                }
                assertEquals(2, factory.destroyed.size());
                assertTrue(factory.destroyed.containsAll(Arrays.asList(a.getConnection(), b.getConnection())));
                assertTrue(old.isClosed());
                assertNotSame(old, current());
                handler.shutdownConnections(connector);
            }
        }
    }

    @Test
    public void legacyBorrowStillSupportsPooledAndSingleConnections() throws Exception {
        config.setCircuitBreakerEnabled(false);
        register(first, config);
        expire();
        Connection legacy = handler.getConnection(connector, name);
        handler.returnConnection(connector, name, legacy);
        assertEquals(1, current().getNumIdle());
        ConnectionLease lease = borrow();
        assertFalse(lease.isTrial());
        lease.reportFailure();
        assertEquals(CircuitBreaker.Mode.CLOSED, mode());
        lease.invalidate();
        handler.shutdownConnections(connector);
        handler.createConnection(connector, name, legacy);
        assertSame(legacy, handler.getConnection(connector, name));
    }
}
