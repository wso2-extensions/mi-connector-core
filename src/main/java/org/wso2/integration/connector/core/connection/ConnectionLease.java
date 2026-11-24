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
package org.wso2.integration.connector.core.connection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.integration.connector.core.pool.CircuitBreaker.Admission;
import org.wso2.integration.connector.core.pool.ConnectionPool;

/** A connection and its admission belong to the pool that lent the connection. */
public final class ConnectionLease {

    private static final Log log = LogFactory.getLog(ConnectionLease.class);
    private final ConnectionPool pool;
    private final Connection connection;
    private final Admission admission;

    ConnectionLease(ConnectionPool pool, Connection connection, Admission admission) {
        this.pool = pool;
        this.connection = connection;
        this.admission = admission;
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean isTrial() {
        return admission != null && admission.isTrial();
    }

    public void returnToPool() {
        pool.returnObject(connection);
    }

    public void invalidate() {
        try {
            pool.invalidateObject(connection);
        } catch (Exception e) {
            log.error("Failed to invalidate the connection.", e);
        }
    }

    public void reportSuccess() {
        if (admission != null) {
            admission.reportSuccess();
        }
    }

    public void reportFailure() {
        if (admission != null) {
            admission.reportFailure();
        }
    }

    public void cancelTrial() {
        if (admission != null) {
            admission.cancelTrial();
        }
    }
}
