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

import org.apache.synapse.MessageContext;
import org.apache.synapse.config.AbstractSynapseObserver;
import org.apache.synapse.config.Entry;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.config.SynapseObserver;
import org.apache.synapse.libraries.model.SynapseLibrary;
import org.junit.Test;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolCloseReason;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class PoolRedeployTest extends PoolFixture {

    private final SynapseConfiguration synapse = new SynapseConfiguration();

    private MessageContext message(String entry) {
        return message(synapse, entry);
    }

    @Test
    public void realEntryRemovalsRetireEachRegisteredFactoryOnce() throws Exception {
        for (boolean drainBefore : new boolean[] {true, false}) {
            first = new Factory("first");
            second = new Factory("second");
            MessageContext context = message(name);
            synapse.addEntry(name, new Entry(name));
            handler.createConnection(connector, name, first, config, context);
            assertSame(first, handler.getConnectionFactory(connector, name));
            synapse.removeEntry(name);
            assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), first.reasons);
            assertNull(current());
            if (drainBefore) {
                removeLibrary();
            }
            synapse.addEntry(name, new Entry(name));
            handler.createConnection(connector, name, second, config, context);
            assertSame(second, handler.getConnectionFactory(connector, name));
            ConnectionLease lease = borrow();
            assertSame(second, ((Item) lease.getConnection()).factory);
            lease.returnToPool();
            if (!drainBefore) {
                removeLibrary();
            }
            assertEquals(1, synapse.getObservers().size());
            synapse.removeEntry(name);
            assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), second.reasons);
            assertEquals(1, first.reasons.size());
            assertNull(current());
        }
    }

    @Test
    public void registrationAlreadyInFlightDuringRemoval() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Factory closingFactory = new Factory("closing") {
            public void beforePoolClose(PoolCloseReason reason) {
                super.beforePoolClose(reason);
                closing.countDown();
                try {
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            synapse.addEntry("other", new Entry("other"));
            synapse.addEntry(name, new Entry(name));
            MessageContext inFlight = message(name);
            handler.createConnection(connector, name, closingFactory, config, inFlight);
            AtomicBoolean notified = new AtomicBoolean();
            synapse.registerObserver(new AbstractSynapseObserver() {
                @Override
                public void entryRemoved(Entry entry) {
                    if (name.equals(entry.getKey())) {
                        notified.set(true);
                    }
                }
            });
            Future<?> retired = workers.submit(() -> synapse.removeEntry(name));
            assertTrue(closing.await(4, TimeUnit.SECONDS));
            Future<?> added = workers.submit(() -> synapse.addEntry(name, new Entry(name)));
            int observers = synapse.getObservers().size();
            assertThrows(IllegalStateException.class,
                    () -> handler.createConnection(connector, name, first, config, inFlight));
            AtomicBoolean closed = new AtomicBoolean();
            Item unused = new Item(first) {
                @Override
                public void close() {
                    closed.set(true);
                }
            };
            assertThrows(IllegalStateException.class,
                    () -> handler.createConnection(connector, "single", unused, inFlight));
            assertFalse(closed.get());
            assertFalse(handler.checkIfConnectionExists(connector, "single"));
            assertFalse(entries("connectionLocalEntryMap").containsKey(connector + ":single"));
            assertFalse(entries("configurationMap").containsKey(key));
            assertFalse(entries("connectionFactoryMap").containsKey(key));
            assertEquals(observers, synapse.getObservers().size());
            assertNull(current());
            assertFalse(added.isDone());
            Factory extra = new Factory("other");
            handler.createConnection(connector, "unrelated", extra, settings(), message("other"));
            assertEquals(observers, synapse.getObservers().size());
            ConnectionLease other = handler.borrowConnection(connector, "unrelated");
            other.getConnection().connect(null);
            other.returnToPool();
            finish.countDown();
            retired.get(4, TimeUnit.SECONDS);
            assertTrue(notified.get());
            added.get(4, TimeUnit.SECONDS);
            handler.createConnection(connector, name, second, settings(), message(name));
            boolean replacement = handler.getConnectionFactory(connector, name) == second;
            synapse.removeEntry(name);
            System.out.println("second factory published=" + replacement + " old retire=" + closingFactory.reasons
                    + " in-flight retire=" + first.reasons + " replacement retire=" + second.reasons);
            assertTrue("The replacement configuration must serve the new entry.", replacement);
            assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), second.reasons);
            assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), closingFactory.reasons);
            assertTrue(first.reasons.isEmpty());
            assertNull(current());
            assertTrue(handler.checkIfConnectionExists(connector, "unrelated"));
            synapse.removeEntry("other");
            assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), extra.reasons);
        } finally {
            finish.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void removeLibrary() {
        synapse.addSynapseLibrary("otherlib", new SynapseLibrary("otherlib", "unit"));
        synapse.removeSynapseLibrary("otherlib");
    }

    @Test
    public void observerIsSharedUntilTheLastConnectorShutsDown() throws Exception {
        synapse.addEntry("left", new Entry("left"));
        synapse.addEntry("right", new Entry("right"));
        handler.createConnection(connector, "left", first, config, message("left"));
        handler.createConnection("other", "right", second, config, message("right"));
        try {
            assertEquals(1, synapse.getObservers().size());
            handler.shutdownConnections(connector);
            removeLibrary();
            assertEquals(1, synapse.getObservers().size());
            assertSame(second, handler.getConnectionFactory("other", "right"));
            synapse.removeEntry("right");
            assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), second.reasons);
            assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), first.reasons);
        } finally {
            handler.shutdownConnections("other");
        }
        removeLibrary();
        assertTrue(synapse.getObservers().isEmpty());
    }

    @Test
    public void pendingObserverCannotRetireANewConfigurationOwner() throws Exception {
        Entry entry = new Entry(name);
        synapse.addEntry(name, entry);
        handler.createConnection(connector, name, first, config, message(name));
        SynapseObserver old = synapse.getObservers().get(0);
        handler.shutdownConnections(connector);
        handler.createConnection(connector, name, second, config, message(name));
        assertEquals(2, synapse.getObservers().size());
        old.entryRemoved(entry);
        assertSame(second, handler.getConnectionFactory(connector, name));
        removeLibrary();
        assertEquals(1, synapse.getObservers().size());
        assertNotSame(old, synapse.getObservers().get(0));
        synapse.removeEntry(name);
        assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), second.reasons);
    }

    @Test
    public void identicalEntryNamesAreScopedToTheOwningConfiguration() throws Exception {
        SynapseConfiguration another = new SynapseConfiguration();
        synapse.addEntry(name, new Entry(name));
        another.addEntry(name, new Entry(name));
        handler.createConnection(connector, "left", first, config, message(name));
        handler.createConnection(connector, "right", second, config, message(another, name));
        synapse.removeEntry(name);
        assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), first.reasons);
        assertTrue(second.reasons.isEmpty());
        assertSame(second, handler.getConnectionFactory(connector, "right"));
        another.removeEntry(name);
        assertEquals(Collections.singletonList(PoolCloseReason.RETIRE), second.reasons);
    }
}
