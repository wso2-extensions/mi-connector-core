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

public class PoolState {

    public static final int OPEN = 0;
    public static final int HALF_OPEN = 1;
    public static final int CLOSED = 2;

    private int state = CLOSED;

    public void open() {

        if (state == OPEN) {
            throw new IllegalStateException("Cannot transition to OPEN from state: " + state);
        }
        this.state = OPEN;
    }

    public void halfOpen() {

        if (state != OPEN) {
            throw new IllegalStateException("Cannot transition to HALF_OPEN from state: " + state);
        }
        this.state = HALF_OPEN;
    }

    public void close() {

        if (state == CLOSED) {
            throw new IllegalStateException("Cannot transition to CLOSED from state: " + state);
        }
        this.state = CLOSED;
    }

    public int getState() {

        return state;
    }
}
