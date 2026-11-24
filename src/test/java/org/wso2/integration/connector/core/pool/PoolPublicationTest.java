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

import org.apache.commons.pool.impl.GenericObjectPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.Verifier;
import org.junit.rules.TemporaryFolder;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.CircuitOpenException;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.util.PoolTestPoints;
import org.wso2.integration.connector.core.util.TestBuild;
import org.wso2.integration.connector.core.util.TestPoints;

import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

@Category(TestBuild.class)
public class PoolPublicationTest extends PoolFixture {

    private static final String EXPIRED = "pool.after-read-expired";
    private static final String PUBLISH = "pool.before-publish";
    private static final String REGISTER = "pool.before-register";
    private static final String BORROW = "pool.before-borrow";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    private final Set<String> mismatches = new LinkedHashSet<>();
    @Rule
    public final Verifier observations = new Verifier() {
        @Override
        protected void verify() {
            assertTrue("Publication observations failed: " + mismatches, mismatches.isEmpty());
        }
    };
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private ConnectionPool originalObservedPool;
    private ConnectionPool agedObservedPool;
    private Path copy;
    private long start;

    @Before
    public void enablePoints() throws Exception {
        Path root = folder.getRoot().toPath();
        System.setProperty("vfs.testpoints.dir", root.toString());
        TestPoints.wallMillis("setup");
        try (Stream<Path> paths = Files.list(root)) {
            copy = paths.filter(Files::isDirectory).findFirst().get();
        }
    }

    @After
    public void releasePoints() throws Exception {
        Files.createFile(copy.resolve("release.all"));
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        handler.shutdownConnections(connector);
        System.clearProperty("vfs.testpoints.dir");
    }

    @Test
    public void activeCountsFollowEachPhysicalPoolAndItsFactory() throws Exception {
        begin();
        ConnectionLease old = borrow();
        originalObservedPool = current();
        Map<String, Integer> initial = PoolTestPoints.activeCounts(key, first);
        assertEquals(1, initial.size());
        String oldId = initial.keySet().iterator().next();
        assertEquals(Integer.valueOf(1), initial.get(oldId));
        expire();
        ConnectionLease aged = borrow();
        agedObservedPool = current();
        ConnectionLease other = borrow();
        Map<String, Integer> rotated = PoolTestPoints.activeCounts(key, first);
        assertEquals(2, rotated.size());
        assertEquals(Integer.valueOf(1), rotated.remove(oldId));
        String agedId = rotated.keySet().iterator().next();
        assertEquals(Integer.valueOf(2), rotated.get(agedId));
        other.returnToPool();
        assertEquals(Integer.valueOf(1), PoolTestPoints.activeCounts(key, first).get(agedId));
        handler.shutdownConnections(connector);
        register(second, config);
        ConnectionLease replacement = borrow();
        Map<String, Integer> fresh = PoolTestPoints.activeCounts(key, second);
        assertEquals(1, fresh.size());
        assertFalse(fresh.containsKey(oldId));
        assertFalse(fresh.containsKey(agedId));
        assertEquals(Integer.valueOf(1), fresh.values().iterator().next());
        assertTrue(PoolTestPoints.activeCounts(key + "other", first).isEmpty());
        assertEquals(2, PoolTestPoints.activeCounts(key, first).size());
        old.returnToPool();
        aged.returnToPool();
        replacement.returnToPool();
        assertEquals(Integer.valueOf(0), PoolTestPoints.activeCounts(key, first).get(oldId));
        assertEquals(Integer.valueOf(0), PoolTestPoints.activeCounts(key, first).get(agedId));
        assertEquals(Integer.valueOf(0), PoolTestPoints.activeCounts(key, second).values().iterator().next());
        assertNotSame(originalObservedPool, agedObservedPool);
        assertEquals(0, originalObservedPool.getNumActive());
        assertEquals(0, agedObservedPool.getNumActive());
    }

    private void begin() throws Exception {
        first = new Factory("first");
        second = new Factory("second");
        start = Files.size(copy.resolve("events.log"));
        register(first, config);
    }

    private void hold(String point, String selector) throws Exception {
        Files.write(copy.resolve(point + ".hold"), ("wait=20\n" + selector).getBytes(StandardCharsets.UTF_8));
    }

    private List<Path> reached(String point, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            try (Stream<Path> paths = Files.list(copy)) {
                List<Path> hits = paths.filter(p -> p.getFileName().toString().startsWith(point + "@")
                        && p.getFileName().toString().endsWith(".reached")).sorted().collect(Collectors.toList());
                if (hits.size() >= count) {
                    return hits;
                }
            }
            Thread.sleep(2);
        } while (System.nanoTime() < end);
        throw new AssertionError("No arrival at " + point);
    }

    private void release(String point) throws Exception {
        Files.delete(copy.resolve(point + ".hold"));
    }

    private void releaseOne(Path hit) throws Exception {
        Files.createFile(hit.resolveSibling(hit.getFileName().toString().replace(".reached", ".release")));
    }

    private ConnectionLease result(Future<ConnectionLease> task) throws Exception {
        try {
            return task.get(4, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private Throwable failedBorrow(Future<ConnectionLease> task) throws Exception {
        try {
            task.get(4, TimeUnit.SECONDS).returnToPool();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private void expectClosedBorrow(Future<ConnectionLease> task) throws Exception {
        Throwable failure = failedBorrow(task);
        check("closed borrow", failure instanceof ConnectException && failure.getCause() instanceof IllegalStateException);
    }

    private void permitClosedBorrow(Future<ConnectionLease> task) throws Exception {
        try {
            result(task).returnToPool();
        } catch (ConnectException failure) {
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
    }

    private Map<String, List<Map<String, String>>> events() throws Exception {
        Map<String, List<Map<String, String>>> events = new HashMap<>();
        try (RandomAccessFile log = new RandomAccessFile(copy.resolve("events.log").toFile(), "r")) {
            log.seek(start);
            String line;
            while ((line = log.readLine()) != null) {
                Map<String, String> fields = new HashMap<>();
                for (String pair : line.split("\t")) {
                    int eq = pair.indexOf('=');
                    fields.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
                if (key.equals(fields.get("connection"))) {
                    events.computeIfAbsent(fields.get("point"), ignored -> new ArrayList<>()).add(fields);
                }
            }
        }
        return events;
    }

    private List<Map<String, String>> rows(String point) throws Exception {
        return events().getOrDefault(point, Collections.emptyList());
    }

    private Set<String> poolIds(List<Map<String, String>> rows) {
        Set<String> ids = new HashSet<>();
        for (Map<String, String> row : rows) {
            ids.add(row.get("pool"));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<ConnectionPool, String> liveIds() throws Exception {
        Field field = PoolTestPoints.class.getDeclaredField("ids");
        field.setAccessible(true);
        synchronized (PoolTestPoints.class) {
            return new HashMap<>((Map<ConnectionPool, String>) field.get(null));
        }
    }

    // Record independent end-state checks before JUnit reports a failed round.
    private void observe(String label, boolean condition) {
        record(label, condition);
        if (!condition) {
            mismatches.add(label);
        }
    }

    private CircuitBreaker mappedBreaker() throws Exception {
        ConnectionPool pool = current();
        return pool == null ? null : pool.getBreaker();
    }

    private void observeLifetime(Factory expected, Configuration settings, CircuitBreaker installed,
                                 CircuitBreaker prior) throws Exception {
        ConnectionPool pool = current();
        observe("mapped factory and context", pool != null && pool.getConnectionFactory() == expected
                && pool.getConfiguration() == settings && !expected.retired
                && handler.getConnectionFactory(connector, name) == expected
                && entries("connectionFactoryMap").get(key) == expected
                && entries("configurationMap").get(key) == settings);
        CircuitBreaker actual = mappedBreaker();
        observe("breaker identity", actual != null && actual == installed && actual != prior);
        observe("breaker state", actual != null && actual.getMode() == CircuitBreaker.Mode.CLOSED);
    }

    private void inventory(int min, int max) throws Exception {
        Map<String, List<Map<String, String>>> seen = events();
        List<Map<String, String>> builds = seen.getOrDefault("pool.constructed", Collections.emptyList());
        List<Map<String, String>> publications = seen.getOrDefault("pool.published", Collections.emptyList());
        List<Map<String, String>> closures = seen.getOrDefault("pool.closed", Collections.emptyList());
        Set<String> made = poolIds(builds);
        Set<String> published = poolIds(publications);
        Set<String> closed = poolIds(closures);
        boolean allPublished = !made.contains(null) && made.size() == builds.size()
                && published.size() == publications.size() && made.equals(published);
        observe("construction", allPublished && made.size() >= min && made.size() <= max);
        Map<ConnectionPool, String> ids = liveIds();
        ConnectionPool mapped = current();
        Set<String> detached = new HashSet<>(made);
        boolean mappedAccounted = mapped == null || detached.remove(ids.get(mapped));
        boolean accounted = allPublished && mappedAccounted && closed.size() == closures.size() && detached.equals(closed);
        Field evictor = GenericObjectPool.class.getDeclaredField("_evictor");
        evictor.setAccessible(true);
        for (Map.Entry<ConnectionPool, String> entry : ids.entrySet()) {
            if (made.contains(entry.getValue())) {
                ConnectionPool pool = entry.getKey();
                accounted &= (pool != mapped) == pool.isClosed() && (pool == mapped) == (evictor.get(pool) != null);
            }
        }
        observe("pool inventory", accounted);
    }

    @Test
    public void twoBorrowersRotateOnce() throws Exception {
        for (boolean stopBeforePublish : new boolean[] {false, true}) {
            begin();
            CircuitBreaker breaker = current().getBreaker();
            expire();
            hold(EXPIRED, "");
            if (stopBeforePublish) {
                hold(PUBLISH, "");
            }
            Future<ConnectionLease> b = workers.submit(this::borrow);
            Future<ConnectionLease> c = workers.submit(this::borrow);
            List<Path> arrivals = reached(EXPIRED, stopBeforePublish ? 4 : 2);
            if (stopBeforePublish) {
                releaseOne(arrivals.get(2));
                reached(PUBLISH, 1);
            }
            release(EXPIRED);
            if (stopBeforePublish) {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!lock.hasQueuedThreads() && System.nanoTime() < end) {
                    Thread.sleep(1);
                }
                assertTrue(lock.hasQueuedThreads());
                release(PUBLISH);
            }
            result(b).returnToPool();
            result(c).returnToPool();
            observeLifetime(first, config, breaker, null);
            inventory(2, 2);
            handler.shutdownConnections(connector);
        }
    }

    @Test
    public void retiredReaderCannotRecreateTheOldPool() throws Exception {
        begin();
        CircuitBreaker prior = mappedBreaker();
        expire();
        hold(EXPIRED, "");
        Future<ConnectionLease> b = workers.submit(this::borrow);
        reached(EXPIRED, 1);
        handler.removeLocalEntryConnections(name);
        observe("retired context", first.retired);
        check("no recreation", entries("connectionFactoryMap").get(key) == null
                && entries("configurationMap").get(key) == null);
        hold(REGISTER, "");
        Configuration changed = settings();
        changed.setMaxActiveConnections(3);
        Future<?> registration = workers.submit(() -> register(second, changed));
        reached(REGISTER, 1);
        release(EXPIRED);
        check("no recreation", failedBorrow(b) instanceof ConnectException);
        check("no recreation", current() == null && rows("pool.constructed").size() == 1
                && !entries("connectionFactoryMap").containsKey(key) && !entries("configurationMap").containsKey(key));
        release(REGISTER);
        registration.get(4, TimeUnit.SECONDS);
        observeLifetime(second, changed, mappedBreaker(), prior);
        observe("retired context", first.retired);
        inventory(2, 2);
    }

    @Test
    public void expiredReaderUsesTheNewRegistration() throws Exception {
        begin();
        CircuitBreaker breaker = current().getBreaker();
        expire();
        hold(EXPIRED, "");
        Future<ConnectionLease> b = workers.submit(this::borrow);
        reached(EXPIRED, 1);
        handler.removeLocalEntryConnections(name);
        observe("retired context", first.retired);
        register(second, config);
        CircuitBreaker installed = mappedBreaker();
        release(EXPIRED);
        ConnectionLease next = result(b);
        observeLifetime(second, config, installed, breaker);
        observe("retired context", first.retired);
        check("new registration", current() != null && breaker != current().getBreaker()
                && current().getConnectionFactory() == second && ((Item) next.getConnection()).factory == second);
        next.returnToPool();
        inventory(2, 2);
    }

    @Test
    public void retirementWaitsForPublication() throws Exception {
        begin();
        expire();
        hold(PUBLISH, "");
        Future<ConnectionLease> b = workers.submit(this::borrow);
        reached(PUBLISH, 1);
        Set<String> constructed = poolIds(rows("pool.constructed"));
        List<ConnectionPool> detached = liveIds().entrySet().stream()
                .filter(entry -> constructed.contains(entry.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toList());
        AtomicReference<Thread> retiring = new AtomicReference<>();
        Future<?> retire = workers.submit(() -> {
            retiring.set(Thread.currentThread());
            handler.removeLocalEntryConnections(name);
        });
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((retiring.get() == null || !lock.hasQueuedThread(retiring.get())) && System.nanoTime() < end) {
            Thread.sleep(1);
        }
        assertTrue(lock.hasQueuedThread(retiring.get()));
        release(PUBLISH);
        permitClosedBorrow(b);
        retire.get(4, TimeUnit.SECONDS);
        observe("mapping removed", current() == null && !entries("connectionFactoryMap").containsKey(key)
                && !entries("configurationMap").containsKey(key));
        observe("both detached pools closed", detached.size() == 2 && detached.stream().allMatch(ConnectionPool::isClosed));
        observe("retired context", first.retired);
        check("retirement after publication", current() == null && !first.callerHeldLock && first.reasons.size() == 2
                && first.reasons.contains(PoolCloseReason.AGE) && first.reasons.contains(PoolCloseReason.RETIRE));
        inventory(2, 2);
    }

    @Test
    public void redeployAloneAndBetweenRotations() throws Exception {
        for (boolean rotate : new boolean[] {false, true}) {
            begin();
            CircuitBreaker prior = mappedBreaker();
            if (rotate) {
                expire();
                borrow().returnToPool();
                observe("prior AGE breaker", mappedBreaker() != null && mappedBreaker() == prior);
            }
            handler.removeLocalEntryConnections(name);
            observe("retired context", first.retired);
            Configuration changed = settings();
            changed.setMaxActiveConnections(3);
            register(second, changed);
            CircuitBreaker installed = mappedBreaker();
            if (rotate) {
                expire();
                borrow().returnToPool();
            }
            observeLifetime(second, changed, installed, prior);
            observe("retired context", first.retired);
            inventory(rotate ? 4 : 2, rotate ? 4 : 2);
            handler.shutdownConnections(connector);
        }
    }

    @Test
    public void heldBorrowUsesOnlyTheSelectedPool() throws Exception {
        for (boolean trial : new boolean[] {false, true}) {
            begin();
            ConnectionPool old = current();
            if (trial) {
                failOperation();
                clock.addAndGet(1000);
            }
            hold(BORROW, "pool=" + liveIds().get(old));
            Future<ConnectionLease> borrower = workers.submit(this::borrow);
            reached(BORROW, trial ? 2 : 1);
            int before = rows("pool.borrow").size();
            expire();
            if (trial) {
                assertThrows(CircuitOpenException.class, this::borrow);
            } else {
                borrow().returnToPool();
            }
            release(BORROW);
            expectClosedBorrow(borrower);
            List<Map<String, String>> calls = rows("pool.borrow");
            check("one physical attempt", calls.size() == before + (trial ? 1 : 2)
                    && liveIds().get(old).equals(calls.get(calls.size() - 1).get("pool")));
            observe("admission released", old.getBreaker() == mappedBreaker()
                    && !old.getBreaker().snapshot().trialInFlight);
            ConnectionLease next = borrow();
            check("next admission", next.isTrial() == trial);
            next.reportSuccess();
            next.returnToPool();
            observeLifetime(first, config, old.getBreaker(), null);
            inventory(2, 2);
            handler.shutdownConnections(connector);
        }
    }

    @Test
    public void exhaustedBorrowOnRotatedPoolSpendsOneWait() throws Exception {
        config.setMaxActiveConnections(1);
        config.setMaxWaitTime(600L);
        for (boolean trial : new boolean[] {false, true}) {
            begin();
            CircuitBreaker original = mappedBreaker();
            ConnectionLease occupant = borrow();
            if (trial) {
                occupant.reportFailure();
                clock.addAndGet(1000);
            }
            int before = rows("pool.borrow").size();
            AtomicReference<Thread> borrower = new AtomicReference<>();
            long began = System.nanoTime();
            Future<ConnectionLease> waiting = workers.submit(() -> {
                borrower.set(Thread.currentThread());
                return borrow();
            });
            awaitPoolWait(borrower, waiting);
            expire();
            if (trial) {
                assertThrows(CircuitOpenException.class, this::borrow);
            } else {
                borrow().returnToPool();
            }
            expectClosedBorrow(waiting);
            check("one wait", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 950);
            check("one physical attempt", rows("pool.borrow").size() == before + (trial ? 1 : 2));
            check("admission released", !current().getBreaker().snapshot().trialInFlight);
            ConnectionLease next = borrow();
            check("next admission", next.isTrial() == trial);
            next.reportSuccess();
            next.returnToPool();
            occupant.invalidate();
            observeLifetime(first, config, original, null);
            inventory(2, 2);
            handler.shutdownConnections(connector);
        }
    }

    @Test
    public void heldBorrowAcrossRedeployFailsOnce() throws Exception {
        begin();
        ConnectionLease oldLease = borrow();
        ConnectionPool old = current();
        hold(BORROW, "pool=" + liveIds().get(old));
        Future<ConnectionLease> b = workers.submit(this::borrow);
        reached(BORROW, 1);
        handler.removeLocalEntryConnections(name);
        Configuration changed = settings();
        changed.setMaxActiveConnections(3);
        register(second, changed);
        release(BORROW);
        expectClosedBorrow(b);
        List<Map<String, String>> calls = rows("pool.borrow");
        check("old borrow failed once", calls.size() == 2
                && liveIds().get(old).equals(calls.get(1).get("pool")));
        ConnectionLease next = borrow();
        check("replacement settings", "second".equals(((Item) next.getConnection()).folder) && current().getMaxActive() == 3);
        next.getConnection().connect(null);
        oldLease.returnToPool();
        check("old lease destroyed", first.destroyed.equals(Collections.singletonList(oldLease.getConnection()))
                && current().getNumActive() == 1 && current().getNumIdle() == 0);
        expire();
        ConnectionLease latest = borrow();
        assertSettings(second, changed);
        assertEquals(3, current().getMaxActive());
        check("old context retired", first.reasons.equals(Collections.singletonList(PoolCloseReason.RETIRE))
                && !first.lockHeld && first.retired);
        next.returnToPool();
        latest.returnToPool();
        inventory(3, 3);
    }

    @Test
    public void snapshotsDistinguishMappedAndDetachedPools() throws Exception {
        begin();
        ConnectionPool old = current();
        expire();
        borrow().returnToPool();
        ConnectionPool live = current();
        Map<ConnectionPool, String> ids = liveIds();
        Path ask = copy.resolve("pool.inventory.ask");
        Files.createFile(ask);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (Files.exists(ask) && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertFalse(Files.exists(ask));
        List<String> snapshots = new ArrayList<>();
        try (Stream<Path> paths = Files.list(copy)) {
            for (Path entry : paths.filter(path -> path.getFileName().toString().endsWith(".snap"))
                    .collect(Collectors.toList())) {
                snapshots.addAll(Files.readAllLines(entry, StandardCharsets.UTF_8));
            }
        }
        assertTrue(snapshots.stream().anyMatch(line -> line.contains("pool=" + ids.get(old)
                + " connection=" + key + " in-map=false closed=true evictor=false")));
        assertTrue(snapshots.stream().anyMatch(line -> line.contains("pool=" + ids.get(live)
                + " connection=" + key + " in-map=true closed=false evictor=true")));
        Map<String, String> callback = rows("pool.before-close").get(0);
        assertEquals(ids.get(old), callback.get("pool"));
        assertEquals("AGE", callback.get("reason"));
        assertEquals("age", callback.get("path"));
        assertEquals(Thread.currentThread().getName(), callback.get("thread"));
        assertEquals("false", callback.get("lock-held"));
        assertTrue(callback.get("frames").contains("ConnectionHandler.borrowConnection"));
    }

    @Test
    public void freePublicationRaces() throws Exception {
        for (int round = 0; round < 200; round++) {
            Factory old = new Factory("old");
            Factory fresh = new Factory("fresh");
            start = Files.size(copy.resolve("events.log"));
            register(old, config);
            CircuitBreaker breaker = current().getBreaker();
            expire();
            CountDownLatch ready = new CountDownLatch(3);
            CountDownLatch go = new CountDownLatch(1);
            AtomicLong target = new AtomicLong();
            Callable<Void> raceBorrow = () -> {
                awaitStart(ready, go, target);
                try {
                    borrow().returnToPool();
                } catch (ConnectException failure) {
                    if (failure.getCause() != null) {
                        assertTrue(failure.getCause() instanceof IllegalStateException);
                    } else {
                        assertTrue(failure.getMessage().startsWith("No connection pool for "));
                    }
                }
                return null;
            };
            Future<Void> b = workers.submit(raceBorrow);
            Future<Void> c = workers.submit(raceBorrow);
            Future<CircuitBreaker> retire = workers.submit(() -> {
                awaitStart(ready, go, target);
                handler.removeLocalEntryConnections(name);
                register(fresh, config);
                return mappedBreaker();
            });
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            target.set(System.nanoTime() + 100000);
            go.countDown();
            b.get(4, TimeUnit.SECONDS);
            c.get(4, TimeUnit.SECONDS);
            CircuitBreaker installed = retire.get(4, TimeUnit.SECONDS);
            observeLifetime(fresh, config, installed, breaker);
            observe("retired context", old.retired);
            inventory(2, 3);
            handler.shutdownConnections(connector);
        }
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch go, AtomicLong target)
            throws InterruptedException {
        ready.countDown();
        assertTrue(go.await(3, TimeUnit.SECONDS));
        while (System.nanoTime() < target.get()) {
            Thread.yield();
        }
    }
}
