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
package org.wso2.integration.connector.core.pool;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.integration.connector.core.ConnectException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static java.lang.String.format;

public class CircuitBreakerConnectionPool extends ConnectionPool {

    private static final Log log = LogFactory.getLog(CircuitBreakerConnectionPool.class);

    private static final long DEFAULT_OPEN_DURATION_MILLIS = 60000;

    // Circuit breaker configuration
    private final PoolState poolState;
    private final int failureThreshold;
    private final long openDurationMillis;
    private final int openDurationProgressFactor;
    private final long maxOpenDurationMillis;

    // Circuit breaker state variables
    private long currentOpenDurationMillis;
    private int failureCount;

    public CircuitBreakerConnectionPool(ConnectionFactory factory, Configuration configuration, PoolState poolState) {

        super(factory, configuration);
        this.poolState = poolState;
        failureThreshold = configuration.getFailureThreshold();
        openDurationMillis = configuration.getOpenDurationMillis();
        maxOpenDurationMillis = configuration.getMaxOpenDurationMillis();
        openDurationProgressFactor = configuration.getOpenDurationProgressFactor();
        failureCount = 0;
        currentOpenDurationMillis = openDurationMillis;
    }

    @Override
    public synchronized Object borrowObject() throws ConnectException {

        if (poolState.getState() == PoolState.OPEN) {
            if (ChronoUnit.MILLIS.between(poolState.getOpenTime(), Instant.now()) >= currentOpenDurationMillis) {
                log.info("Switching circuit breaker to HALF_OPEN state after open duration expired.");
                poolState.halfOpen();
            } else {
                throw new ConnectException("Circuit breaker is OPEN. Requests are blocked.");
            }
        }
        try {
            Object obj = super.borrowObject();
            if (poolState.getState() == PoolState.HALF_OPEN) {
                closeBreaker();
            }
            return obj;
        } catch (ConnectException e) {
            if (poolState.getState() == PoolState.CLOSED) {
                failureCount++;
                if (failureCount >= failureThreshold) {
                    log.warn(format("Circuit breaker tripped after %d failures. Switching to OPEN state.",
                            failureCount));
                    tripBreaker();
                }
            } else if (poolState.getState() == PoolState.HALF_OPEN) {
                log.warn("Connection failed in HALF_OPEN state. Switching to OPEN state.");
                tripBreaker();
            }
            throw e;
        }
    }

    private void tripBreaker() {

        poolState.open();
        currentOpenDurationMillis = calculateNextOpenDuration();
    }

    private void closeBreaker() {

        poolState.close();
        failureCount = 0;
        currentOpenDurationMillis = openDurationMillis;
    }

    private long calculateNextOpenDuration() {

        long nextDuration;
        nextDuration = currentOpenDurationMillis * openDurationProgressFactor;
        if (nextDuration > maxOpenDurationMillis) {
            nextDuration = maxOpenDurationMillis;
        } else if (nextDuration < 0) {
            nextDuration = DEFAULT_OPEN_DURATION_MILLIS;
        }
        return nextDuration;
    }
}
