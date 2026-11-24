/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.integration.connector.core.connection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.AbstractSynapseObserver;
import org.apache.synapse.config.Entry;
import org.apache.synapse.config.SynapseConfiguration;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.integration.connector.core.pool.CircuitBreakerConnectionPool;
import org.wso2.integration.connector.core.pool.Configuration;
import org.wso2.integration.connector.core.pool.ConnectionFactory;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.integration.connector.core.util.Constants;
import org.wso2.integration.connector.core.util.PoolTestPoints;
import org.wso2.integration.connector.core.util.TestPoints;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import static java.lang.String.format;

/**
 * Handles the connections
 */
public class ConnectionHandler implements LocalEntryUndeployCallBack {

    private static final Log log = LogFactory.getLog(ConnectionHandler.class);
    private static final ConnectionHandler handler;
    // Stores connections/connection pools against connection code name
    // defined as <connector_name>:<connection_name>
    private final Map<String, Object> connectionMap;
    private final Map<String, LocalEntry> connectionLocalEntryMap = new HashMap<>();
    private final Map<String, ConnectionFactory> connectionFactoryMap;
    private final Map<String, Configuration> configurationMap;
    private final Map<SynapseConfiguration, EntryObserver> observers = new WeakHashMap<>();
    private final ReentrantLock poolLock = new ReentrantLock();

    static {
        TestPoints.copy("connector-core", ConnectionHandler.class);
        handler = new ConnectionHandler();
    }

    private ConnectionHandler() {
        this.connectionMap = new ConcurrentHashMap<>();
        this.connectionFactoryMap = new ConcurrentHashMap<>();
        this.configurationMap = new ConcurrentHashMap<>();
    }

    /**
     * Gets the Connection Handler instance
     *
     * @return ConnectionHandler instance
     */
    public static ConnectionHandler getConnectionHandler() {

        return handler;
    }

    /**
     * Initialize local entry connection mapping
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @param messageContext Message Context
     */
    public void initializeLocalEntryConnectionMapping(String connector, String connectionName,
                                                      MessageContext messageContext) {
        register(connector, getCode(connector, connectionName), messageContext, () -> { });
    }

    private void register(String connector, String key, MessageContext context, Runnable publish) {
        String entry = context == null ? null : (String) ConnectorUtils.lookupTemplateParamater(context,
                Constants.INIT_CONFIG_KEY);
        SynapseConfiguration config = entry == null ? null : context.getEnvironment().getSynapseConfiguration();
        if (register(connector, key, config, entry, publish, false)) {
            return;
        }
        // Synapse iterates its observers under this monitor during entry removal.
        synchronized (config) {
            register(connector, key, config, entry, publish, true);
        }
    }

    private boolean register(String connector, String key, SynapseConfiguration config, String entry,
                             Runnable publish, boolean canInstallObserver) {
        poolLock.lock();
        try {
            if (config != null) {
                EntryObserver owner = observers.get(config);
                if (owner == null) {
                    if (!canInstallObserver) {
                        return false;
                    }
                    owner = new EntryObserver(config);
                    config.registerObserver(owner);
                    observers.put(config, owner);
                }
                if (owner.retiring.contains(entry)) {
                    throw new IllegalStateException("The local entry is being removed.");
                }
                owner.connectors.add(connector);
                connectionLocalEntryMap.putIfAbsent(key, new LocalEntry(owner, entry));
            }
            publish.run();
            return true;
        } finally {
            poolLock.unlock();
        }
    }

    private final class EntryObserver extends AbstractSynapseObserver {
        private final WeakReference<SynapseConfiguration> config;
        private final Set<String> connectors = new HashSet<>();
        private final Set<String> retiring = new HashSet<>();

        private EntryObserver(SynapseConfiguration config) {
            this.config = new WeakReference<>(config);
        }

        @Override
        public void entryRemoved(Entry entry) {
            if (entry.getKey() != null) {
                retireEntry(this, entry.getKey());
            }
        }
    }

    private static final class LocalEntry {
        private final EntryObserver owner;
        private final String name;

        private LocalEntry(EntryObserver owner, String name) {
            this.owner = owner;
            this.name = name;
        }

        private boolean matches(EntryObserver observer, String entry) {
            return owner == observer && name.equals(entry);
        }
    }

    @Override
    public void onLocalEntryUndeploy(String localEntryKey) {
        if (localEntryKey == null) {
            return;
        }
        List<EntryObserver> current;
        poolLock.lock();
        try {
            current = new ArrayList<>(observers.values());
        } finally {
            poolLock.unlock();
        }
        for (EntryObserver owner : current) {
            retireEntry(owner, localEntryKey);
        }
    }

    private void retireEntry(EntryObserver owner, String entry) {
        Map<String, Object> removed;
        poolLock.lock();
        try {
            if (!owner.retiring.add(entry)) {
                return;
            }
            removed = detach(key -> {
                LocalEntry binding = connectionLocalEntryMap.get(key);
                return binding != null && binding.matches(owner, entry);
            });
            connectionLocalEntryMap.values().removeIf(binding -> binding.matches(owner, entry));
        } finally {
            poolLock.unlock();
        }
        try {
            closeRemoved(removed, "removeLocalEntryConnections");
        } finally {
            SynapseConfiguration unused = null;
            poolLock.lock();
            try {
                owner.retiring.remove(entry);
                if (owner.connectors.isEmpty() && owner.retiring.isEmpty()) {
                    SynapseConfiguration config = owner.config.get();
                    if (config != null && observers.remove(config, owner)) {
                        unused = config;
                    }
                }
            } finally {
                poolLock.unlock();
            }
            if (unused != null) {
                unused.unregisterObserver(owner);
            }
        }
    }

    /**
     * Creates a new connection pool and stores the connection
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @param factory        Connection Factory that defines how to create connections
     * @param configuration  Configurations for the connection pool
     * @param messageContext Message Context
     */
    public void createConnection(String connector, String connectionName, ConnectionFactory factory,
                                 Configuration configuration, MessageContext messageContext) {
        String key = getCode(connector, connectionName);
        if (TestPoints.enabled()) {
            TestPoints.hold("pool.before-register", key, "connection=" + key);
        }
        register(connector, key, messageContext, () -> {
            if (!connectionMap.containsKey(key)) {
                log.info("Creating connection pool for " + connectionName);
                CircuitBreaker breaker = configuration.isCircuitBreakerEnabled() ? configuration.newBreaker() : null;
                createConnectionPool(key, factory, configuration, breaker, false);
            }
        });
    }

    private ConnectionPool createConnectionPool(String key, ConnectionFactory factory, Configuration configuration,
                                                CircuitBreaker breaker, boolean rotation) {
        ConnectionPool pool = breaker == null ? new ConnectionPool(factory, configuration)
                : new CircuitBreakerConnectionPool(factory, configuration, breaker);
        if (TestPoints.enabled()) {
            PoolTestPoints.constructed(key, pool, connectionMap);
            if (rotation) {
                PoolTestPoints.hold("pool.before-publish", key, pool);
            }
        }
        connectionFactoryMap.put(key, factory);
        configurationMap.put(key, configuration);
        connectionMap.put(key, pool);
        if (TestPoints.enabled()) {
            PoolTestPoints.event("pool.published", key, pool);
        }
        return pool;
    }

    /**
     * Stores a new single connection
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @param connection     Connection to be stored
     * @param messageContext Message Context
     */
    public void createConnection(String connector, String connectionName, Connection connection
            , MessageContext messageContext) {
        String key = getCode(connector, connectionName);
        register(connector, key, messageContext, () -> connectionMap.putIfAbsent(key, connection));
    }

    /**
     * Creates a new connection pool and stores the connection
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @param factory        Connection Factory that defines how to create connections
     * @param configuration  Configurations for the connection pool
     */
    public void createConnection(String connector, String connectionName, ConnectionFactory factory,
                                 Configuration configuration) {
        createConnection(connector, connectionName, factory, configuration, null);
    }

    /**
     * Stores a new single connection
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @param connection     Connection to be stored
     */
    public void createConnection(String connector, String connectionName, Connection connection) {
        createConnection(connector, connectionName, connection, null);
    }

    /**
     * Retrieve connection by connector name and connection name
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @return the connection
     * @throws ConnectException if failed to get connection
     */
    public Connection getConnection(String connector, String connectionName) throws ConnectException {

        String key = getCode(connector, connectionName);
        Object value = connectionMap.get(key);
        if (value == null) {
            throw new ConnectException(format("Error occurred during retrieving connection. " +
                    "Connection %s for %s connector does not exist.", connectionName, connector));
        }
        if (value instanceof Connection) {
            return (Connection) value;
        }
        ConnectionPool pool = currentPool(key, value);
        CircuitBreaker breaker = pool.getBreaker();
        if (breaker == null) {
            return checkout(key, pool);
        }
        // This caller reports no operation result, so the borrow result feeds the breaker. A busy pool is no failure.
        CircuitBreaker.Admission admission = breaker.admit();
        try {
            Connection connection = checkout(key, pool);
            admission.reportSuccess();
            return connection;
        } catch (ConnectException e) {
            if (!(e instanceof PoolBusyException)) {
                admission.reportFailure();
            }
            throw e;
        } finally {
            admission.cancelTrial();
        }
    }

    public ConnectionLease borrowConnection(String connector, String connectionName) throws ConnectException {
        String key = getCode(connector, connectionName);
        return borrow(key, currentPool(key, connectionMap.get(key)));
    }

    private ConnectionLease borrow(String key, ConnectionPool pool) throws ConnectException {
        CircuitBreaker breaker = pool.getBreaker();
        CircuitBreaker.Admission admission = breaker == null ? null : breaker.admit();
        boolean handedOff = false;
        try {
            Connection connection = checkout(key, pool);
            ConnectionLease lease = new ConnectionLease(pool, connection, admission);
            handedOff = true;
            return lease;
        } finally {
            if (!handedOff && admission != null) {
                admission.cancelTrial();
            }
        }
    }

    private Connection checkout(String key, ConnectionPool pool) throws ConnectException {
        if (TestPoints.enabled()) {
            PoolTestPoints.hold("pool.before-borrow", key, pool);
            PoolTestPoints.event("pool.borrow", key, pool);
        }
        return (Connection) pool.borrowObject();
    }

    private ConnectionPool currentPool(String key, Object value) throws ConnectException {
        if (!(value instanceof ConnectionPool)) {
            throw new ConnectException("No connection pool for " + key);
        }
        ConnectionPool pool = (ConnectionPool) value;
        if (!pool.isAgedTimeoutEnabled() || !pool.isPoolExpired(Instant.now())) {
            return pool;
        }
        if (TestPoints.enabled()) {
            PoolTestPoints.hold("pool.after-read-expired", key, pool);
        }
        ConnectionPool next;
        poolLock.lock();
        try {
            Object current = connectionMap.get(key);
            if (!(current instanceof ConnectionPool)) {
                throw new ConnectException("No connection pool for " + key);
            }
            if (current != pool || !pool.isPoolExpired(Instant.now())) {
                return (ConnectionPool) current;
            }
            next = createConnectionPool(key, pool.getConnectionFactory(), pool.getConfiguration(),
                    pool.getBreaker(), true);
        } finally {
            poolLock.unlock();
        }
        closeConnection(key, pool, PoolCloseReason.AGE, "age");
        return next;
    }

    public ConnectionFactory getConnectionFactory(String connector, String connectionName) {
        Object pool = connectionMap.get(getCode(connector, connectionName));
        return pool instanceof ConnectionPool ? ((ConnectionPool) pool).getConnectionFactory() : null;
    }

    public CircuitBreaker.Mode getBreakerState(String connector, String connectionName) {
        CircuitBreaker breaker = findBreaker(connector, connectionName);
        return breaker == null ? CircuitBreaker.Mode.CLOSED : breaker.getMode();
    }

    public boolean isOpenBeforeDeadline(String connector, String connectionName) {
        CircuitBreaker breaker = findBreaker(connector, connectionName);
        return breaker != null && breaker.isOpenBeforeDeadline();
    }

    private CircuitBreaker findBreaker(String connector, String connectionName) {
        Object pool = connectionMap.get(getCode(connector, connectionName));
        return pool instanceof ConnectionPool ? ((ConnectionPool) pool).getBreaker() : null;
    }

    /**
     * Return borrowed connection
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @param connection     Connection to be returned to the pool
     */
    public void returnConnection(String connector, String connectionName, Connection connection) {

        String connectorCode = this.getCode(connector, connectionName);
        Object connectionObj = this.connectionMap.get(connectorCode);
        if (connectionObj instanceof ConnectionPool) {
            ((ConnectionPool) connectionObj).returnObject(connection);
        }
    }

    /**
     * Shutdown all the connection pools
     * and unregister from the handler.
     */
    public void shutdownConnections() {

        shutdown(null, "shutdownConnections()");
    }

    public void shutdownConnections(String connector) {
        shutdown(String.valueOf(connector), "shutdownConnections(connector)");
    }

    public void removeLocalEntryConnections(String localEntryName) {
        Map<String, Object> removed;
        poolLock.lock();
        try {
            removed = detach(key -> {
                LocalEntry entry = connectionLocalEntryMap.get(key);
                return entry != null && entry.name.equals(localEntryName);
            });
        } finally {
            poolLock.unlock();
        }
        closeRemoved(removed, "removeLocalEntryConnections");
    }

    private void shutdown(String connector, String path) {
        Predicate<String> matches = key -> connector == null || key.startsWith(connector + ":");
        Map<String, Object> removed;
        Map<SynapseConfiguration, EntryObserver> unused = new HashMap<>();
        poolLock.lock();
        try {
            removed = detach(matches);
            connectionLocalEntryMap.keySet().removeIf(matches);
            observers.entrySet().removeIf(registration -> {
                EntryObserver owner = registration.getValue();
                if (connector == null) {
                    owner.connectors.clear();
                } else {
                    owner.connectors.remove(connector);
                }
                if (owner.connectors.isEmpty() && owner.retiring.isEmpty()) {
                    unused.put(registration.getKey(), owner);
                    return true;
                }
                return false;
            });
        } finally {
            poolLock.unlock();
        }
        try {
            unused.forEach(SynapseConfiguration::unregisterObserver);
        } finally {
            closeRemoved(removed, path);
        }
    }

    private Map<String, Object> detach(Predicate<String> matches) {
        Map<String, Object> removed = new LinkedHashMap<>();
        for (String key : connectionMap.keySet()) {
            if (matches.test(key)) {
                removed.put(key, connectionMap.remove(key));
                connectionFactoryMap.remove(key);
                configurationMap.remove(key);
            }
        }
        return removed;
    }

    private void closeRemoved(Map<String, Object> removed, String path) {
        removed.forEach((key, value) -> closeConnection(key, value, PoolCloseReason.RETIRE, path));
    }

    /**
     * Check if a connection exists for the connector by the same connection name
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @return true if a connection exists, false otherwise
     */
    public boolean checkIfConnectionExists(String connector, String connectionName) {

        return connectionMap.containsKey(getCode(connector, connectionName));
    }

    private void closeConnection(String key, Object value, PoolCloseReason reason, String path) {
        if (value instanceof ConnectionPool) {
            ConnectionPool pool = (ConnectionPool) value;
            ConnectionFactory factory = pool.getConnectionFactory();
            if (factory instanceof PoolCloseListener) {
                if (TestPoints.enabled()) {
                    PoolTestPoints.beforeClose(key, pool, reason, path, poolLock.isLocked());
                }
                try {
                    ((PoolCloseListener) factory).beforePoolClose(reason);
                } catch (RuntimeException e) {
                    log.error("Pool close listener failed.", e);
                }
            }
            try {
                pool.close();
                if (TestPoints.enabled()) {
                    PoolTestPoints.event("pool.closed", key, pool);
                }
            } catch (ConnectException e) {
                log.error("Failed to close connection pool. ", e);
            }
        } else if (value instanceof Connection) {
            try {
                ((Connection) value).close();
            } catch (ConnectException e) {
                log.error("Failed to close connection " + key, e);
            }
        }
    }

    /**
     * Retrieves the connection code defined as <connector_name>:<connection_name>
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @return the connector code
     */
    private String getCode(String connector, String connectionName) {

        return format("%s:%s", connector, connectionName);
    }

    /**
     * Retrieves whether the connection pool or not
     *
     * @param connector      Name of the connector
     * @param connectionName Name of the connection
     * @return the connection pool status
     */
    public boolean getStatusOfConnection(String connector, String connectionName) {
        String connectorCode = getCode(connector, connectionName);
        Object connectionObj = connectionMap.get(connectorCode);
        if (connectionObj != null) {
            if (connectionObj instanceof ConnectionPool) {
                return true;
            }
        }
        return false;
    }

}
