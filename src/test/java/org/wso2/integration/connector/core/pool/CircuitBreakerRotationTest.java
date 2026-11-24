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

import com.google.gson.Gson;
import org.junit.Test;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionLease;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CircuitBreakerRotationTest extends PoolFixture {

    @Test
    public void rotationAndRedeploy() throws Exception {
        Map<String, Map<String, Object>> observations = new LinkedHashMap<>();
        try {
            exerciseRow(observations, "shortOpenTime", 1000, 300, 400, 1000);
            exerciseRow(observations, "longOpenTime", 10000, 2000, 9900, 10100);
        } finally {
            String path = System.getenv("BREAKER_OBSERVATIONS");
            if (path != null) {
                try (Writer writer = Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8)) {
                    new Gson().toJson(observations, writer);
                }
            }
        }
    }

    private void exerciseRow(Map<String, Map<String, Object>> observations, String row, long duration,
                             long rotation, long early, long deadline) throws Exception {
        Factory original = new Factory();
        Factory replacement = new Factory();
        Map<String, Object> seen = new LinkedHashMap<>();
        observations.put(row, seen);
        clock.set(0);
        config.setOpenDurationMillis(duration);
        register(original, config);
        ConnectionPool old = current();
        seen.put("initialMs", old.getConfiguration().getOpenDurationMillis());
        seen.put("failedAtMs", clock.get());
        failOperation();
        seen.put("opened", mode() == CircuitBreaker.Mode.OPEN);
        assertEquals(true, seen.get("opened"));

        expire();
        clock.set(rotation);
        seen.put("rotatedAtMs", clock.get());
        seen.put("rotationBorrowException", borrowOutcome());
        seen.put("poolReplaced", current() != old && old.isClosed());
        assertEquals(true, seen.get("poolReplaced"));
        assertSame(old.getBreaker(), current().getBreaker());
        assertEquals("CircuitOpenException", seen.get("rotationBorrowException"));
        clock.set(early);
        seen.put("refusedAtMs", clock.get());
        seen.put("exception", borrowOutcome());
        assertEquals("CircuitOpenException", seen.get("exception"));
        clock.set(deadline);
        seen.put("admittedAtMs", clock.get());
        ConnectionLease trial = borrow();
        seen.put("deadlineTrial", trial.isTrial());
        assertTrue(trial.isTrial());
        trial.reportFailure();
        trial.invalidate();
        seen.put("reopenedBeforeRetire", mode() == CircuitBreaker.Mode.OPEN);
        assertEquals(true, seen.get("reopenedBeforeRetire"));

        handler.removeLocalEntryConnections(name);
        seen.put("retired", original.retired && current() == null);
        register(replacement, config);
        seen.put("recreated", current().getBreaker() != old.getBreaker()
                && handler.getConnectionFactory(connector, name) == replacement);
        seen.put("redeployedMode", mode().name());
        ConnectionLease ordinary = borrow();
        seen.put("redeployAdmitted", ordinary.getConnection() instanceof Item);
        seen.put("redeployTrial", ordinary.isTrial());
        ordinary.returnToPool();
        assertEquals(true, seen.get("retired"));
        assertEquals(true, seen.get("recreated"));
        assertEquals("CLOSED", seen.get("redeployedMode"));
        assertEquals(true, seen.get("redeployAdmitted"));
        assertEquals(false, seen.get("redeployTrial"));
        handler.shutdownConnections(connector);
    }

    private String borrowOutcome() {
        try {
            ConnectionLease lease = borrow();
            lease.cancelTrial();
            lease.returnToPool();
            return "admitted";
        } catch (ConnectException failure) {
            return failure.getClass().getSimpleName();
        }
    }
}
