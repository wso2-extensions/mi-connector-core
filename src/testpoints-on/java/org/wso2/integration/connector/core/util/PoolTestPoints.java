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
package org.wso2.integration.connector.core.util;

import org.apache.commons.pool.impl.GenericObjectPool;
import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.integration.connector.core.pool.ConnectionFactory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/** Pool identities and observations for the marked build. */
public final class PoolTestPoints {
    private static final Map<ConnectionPool, String> ids = new WeakHashMap<>();
    private static final Map<ConnectionPool, String> keys = new WeakHashMap<>();
    private static final ReferenceQueue<ConnectionPool> collected = new ReferenceQueue<>();
    private static long sequence;

    private PoolTestPoints() {
    }

    public static synchronized void constructed(String key, ConnectionPool pool, Map<String, Object> pools) {
        String id = Long.toString(++sequence);
        ids.put(pool, id);
        keys.put(pool, key);
        PoolReference ref = new PoolReference(pool);
        ref.handle = TestPoints.snapshot("pool.inventory", id, () -> {
            ConnectionPool live = ref.get();
            if (live == null) {
                ref.handle.remove();
                return "";
            }
            return "pool=" + id + " connection=" + key + " in-map=" + (pools.get(key) == live)
                    + " closed=" + live.isClosed() + " evictor=" + hasEvictor(live);
        });
        event("pool.constructed", key, pool);
    }

    static synchronized void removeCollected() {
        PoolReference ref;
        while ((ref = (PoolReference) collected.poll()) != null) {
            ref.handle.remove();
        }
    }

    private static synchronized String id(ConnectionPool pool) {
        return ids.get(pool);
    }

    public static Map<String, Integer> activeCounts(String key, ConnectionFactory factory) {
        Map<ConnectionPool, String> matched = new LinkedHashMap<>();
        synchronized (PoolTestPoints.class) {
            for (Map.Entry<ConnectionPool, String> entry : ids.entrySet()) {
                ConnectionPool pool = entry.getKey();
                if (pool != null && key.equals(keys.get(pool)) && pool.getConnectionFactory() == factory) {
                    matched.put(pool, entry.getValue());
                }
            }
        }
        Map<String, Integer> counts = new TreeMap<>();
        matched.forEach((pool, id) -> counts.put(id, pool.getNumActive()));
        return counts;
    }

    private static boolean hasEvictor(ConnectionPool pool) {
        try {
            Field field = GenericObjectPool.class.getDeclaredField("_evictor");
            field.setAccessible(true);
            synchronized (pool) {
                return field.get(pool) != null;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void hold(String point, String key, ConnectionPool pool) {
        TestPoints.hold(point, key, "pool=" + id(pool), "connection=" + key);
    }

    public static void event(String point, String key, ConnectionPool pool) {
        TestPoints.event(point, key, "pool=" + id(pool), "connection=" + key);
    }

    public static void beforeClose(String key, ConnectionPool pool, PoolCloseReason reason, String path,
                                   boolean lockHeld) {
        String frames = Arrays.stream(Thread.currentThread().getStackTrace())
                .map(StackTraceElement::toString).collect(Collectors.joining("|"));
        TestPoints.event("pool.before-close", key, "pool=" + id(pool), "connection=" + key,
                "reason=" + reason, "path=" + path, "frames=" + frames, "lock-held=" + lockHeld);
    }

    private static final class PoolReference extends WeakReference<ConnectionPool> {
        private TestPoints.Handle handle;

        PoolReference(ConnectionPool pool) {
            super(pool, collected);
        }
    }
}
