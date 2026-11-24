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

import org.apache.commons.pool.BasePoolableObjectFactory;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.mediators.template.TemplateContext;
import org.wso2.integration.connector.core.util.Constants;
import java.lang.reflect.Proxy;
import java.util.Stack;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.SynapseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.wso2.integration.connector.core.util.TestPoints;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.Connection;
import org.wso2.integration.connector.core.connection.ConnectionConfig;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolCloseListener;
import org.wso2.integration.connector.core.connection.PoolCloseReason;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.*;

public abstract class PoolFixture {

    @Rule
    public TestName testName = new TestName();

    void check(String observation, boolean met) {
        record(observation, met);
        assertTrue(observation, met);
    }

    void record(String observation, boolean met) {
        String path = System.getenv("POOL_OBSERVATIONS");
        if (path != null) {
            String line = testName.getMethodName() + "\t" + observation + "\t" + met + "\n";
            try {
                Files.write(Paths.get(path), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    final ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
    final String connector = "test";
    private final SynapseConfiguration testConfig = new SynapseConfiguration();
    final String name = "files";
    final String key = connector + ":" + name;
    final AtomicLong clock = new AtomicLong();
    Factory first = new Factory("first");
    Factory second = new Factory("second");
    Configuration config;
    ReentrantLock lock;

    @BeforeClass
    public static void nameTestCopy() {
        TestPoints.copy("unit", Test.class);
        for (Class<?> type : new Class<?>[] {org.apache.commons.pool.BaseObjectPool.class,
                org.apache.commons.pool.impl.GenericObjectPool.class}) {
            System.out.println(type.getName() + " loaded from "
                    + type.getProtectionDomain().getCodeSource().getLocation());
        }
    }

    @Before
    public void preparePool() throws Exception {
        lock = publicationLock();
        config = settings();
    }

    @After
    public void cleanPool() throws Exception {
        handler.shutdownConnections(connector);
    }

    Configuration settings() {
        Configuration result = new Configuration();
        result.setMaxActiveConnections(8);
        result.setPoolConnectionAgedTimeout(60000);
        result.setEvictionCheckInterval(60000L);
        result.setCircuitBreakerEnabled(true);
        result.setFailureThreshold(0);
        result.setOpenDurationMillis(1000);
        result.setMaxOpenDurationMillis(300000);
        result.setBreakerClockForTesting(clock::get);
        return result;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> entries(String field) throws Exception {
        Field declared = ConnectionHandler.class.getDeclaredField(field);
        declared.setAccessible(true);
        return (Map<String, Object>) declared.get(handler);
    }

    MessageContext message(SynapseConfiguration synapse, String entry) {
        TemplateContext template = new TemplateContext("file", Collections.emptyList());
        template.getMappedValues().put(Constants.INIT_CONFIG_KEY, entry);
        Stack<TemplateContext> templates = new Stack<>();
        templates.push(template);
        SynapseEnvironment environment = (SynapseEnvironment) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SynapseEnvironment.class}, (proxy, method, args) -> {
                    if ("getSynapseConfiguration".equals(method.getName())) {
                        return synapse;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (MessageContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {MessageContext.class},
                (proxy, method, args) -> {
                    if ("getEnvironment".equals(method.getName())) {
                        return environment;
                    }
                    if ("getProperty".equals(method.getName()) && SynapseConstants.SYNAPSE__FUNCTION__STACK.equals(args[0])) {
                        return templates;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    void register(Factory factory, Configuration settings) {
        handler.createConnection(connector, name, factory, settings, message(testConfig, name));
    }

    ConnectionPool current() throws Exception {
        return (ConnectionPool) entries("connectionMap").get(key);
    }

    void expire() throws Exception {
        current().setStrat(Instant.now().minusMillis(current().getPoolConnectionAgedTimeout() + 1));
    }

    ConnectionLease borrow() throws ConnectException {
        return handler.borrowConnection(connector, name);
    }

    CircuitBreaker.Mode mode() {
        return handler.getBreakerState(connector, name);
    }

    void failOperation() throws Exception {
        ConnectionLease lease = borrow();
        lease.reportFailure();
        lease.invalidate();
    }

    static class Item implements Connection {
        final Factory factory;
        final String folder;
        int uses;
        Item(Factory factory) {
            this.factory = factory;
            folder = factory.folder;
        }
        public void connect(ConnectionConfig config) {
            uses++;
        }
        public void close() {
        }
    }

    class Factory extends BasePoolableObjectFactory implements ConnectionFactory, PoolCloseListener {
        final List<PoolCloseReason> reasons = Collections.synchronizedList(new ArrayList<>());
        final List<Object> destroyed = Collections.synchronizedList(new ArrayList<>());
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final String folder;
        final List<Thread> callers = Collections.synchronizedList(new ArrayList<>());
        int made;
        boolean lockHeld;
        volatile boolean callerHeldLock;

        Factory() {
            this("default");
        }

        Factory(String folder) {
            this.folder = folder;
        }
        boolean throwOnClose;
        boolean failCreate;
        volatile boolean retired;
        int validations;

        public Object makeObject() throws Exception {
            if (failCreate) {
                throw new Exception("Cannot make the connection.");
            }
            made++;
            return new Item(this);
        }

        public void destroyObject(Object item) {
            destroyed.add(item);
            order.add("destroy");
        }

        public boolean validateObject(Object item) {
            validations++;
            return true;
        }

        public void beforePoolClose(PoolCloseReason reason) {
            callers.add(Thread.currentThread());
            callerHeldLock |= lock.isHeldByCurrentThread();
            lockHeld |= lock.isLocked();
            reasons.add(reason);
            if (reason == PoolCloseReason.RETIRE) {
                retired = true;
            }
            order.add(reason.name());
            if (throwOnClose) {
                throw new IllegalStateException("Listener failed.");
            }
        }
    }

    static void awaitPoolWait(AtomicReference<Thread> borrower, Future<?> task) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && !task.isDone()) {
            Thread thread = borrower.get();
            if (thread != null && thread.getState() == Thread.State.TIMED_WAITING) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if ("org.apache.commons.pool.impl.GenericObjectPool".equals(frame.getClassName())
                            && "borrowObject".equals(frame.getMethodName())) {
                        return;
                    }
                }
            }
            Thread.sleep(1);
        }
        fail("The borrower did not reach the wait in Commons Pool.");
    }

    void assertSettings(Factory expected, Configuration settings) throws Exception {
        assertSame(expected, handler.getConnectionFactory(connector, name));
        assertSame(expected, current().getConnectionFactory());
        assertSame(settings, current().getConfiguration());
        assertSame(expected, entries("connectionFactoryMap").get(key));
        assertSame(settings, entries("configurationMap").get(key));
    }

    ReentrantLock publicationLock() throws Exception {
        Field field = ConnectionHandler.class.getDeclaredField("poolLock");
        field.setAccessible(true);
        return (ReentrantLock) field.get(handler);
    }
}
