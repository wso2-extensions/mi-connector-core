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

import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.integration.connector.core.pool.ConnectionFactory;

import java.util.Collections;
import java.util.Map;

/** Release form of the pool test points. */
public final class PoolTestPoints {
    private PoolTestPoints() {
    }

    public static void constructed(String key, ConnectionPool pool, Map<String, Object> pools) {
    }

    public static Map<String, Integer> activeCounts(String key, ConnectionFactory factory) {
        return Collections.emptyMap();
    }

    public static void hold(String point, String key, ConnectionPool pool) {
    }

    public static void event(String point, String key, ConnectionPool pool) {
    }

    public static void beforeClose(String key, ConnectionPool pool, PoolCloseReason reason, String path,
                                   boolean lockHeld) {
    }
}
