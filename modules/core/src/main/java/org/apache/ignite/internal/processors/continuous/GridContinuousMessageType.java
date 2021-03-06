/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.continuous;

import org.jetbrains.annotations.*;

/**
 * Continuous processor message types.
 */
enum GridContinuousMessageType {
    /** Consume start request. */
    MSG_START_REQ,

    /** Consume start acknowledgement. */
    MSG_START_ACK,

    /** Consume stop request. */
    MSG_STOP_REQ,

    /** Consume stop acknowledgement. */
    MSG_STOP_ACK,

    /** Remote event notification. */
    MSG_EVT_NOTIFICATION,

    /** Event notification acknowledgement for synchronous events. */
    MSG_EVT_ACK;

    /** Enumerated values. */
    private static final GridContinuousMessageType[] VALS = values();

    /**
     * Efficiently gets enumerated value from its ordinal.
     *
     * @param ord Ordinal value.
     * @return Enumerated value.
     */
    @Nullable public static GridContinuousMessageType fromOrdinal(byte ord) {
        return ord >= 0 && ord < VALS.length ? VALS[ord] : null;
    }
}
