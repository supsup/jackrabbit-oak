/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.segment.file;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.text.DateFormat.getDateTimeInstance;
import static org.slf4j.helpers.MessageFormatter.arrayFormat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

import javax.annotation.Nonnull;

import org.apache.jackrabbit.oak.spi.gc.GCMonitor;
import org.apache.jackrabbit.oak.stats.Clock;

/**
 * {@link GCMonitor} implementation providing the file store gc status.
 */
public class FileStoreGCMonitor implements GCMonitor {
    private final Clock clock;

    private long lastCompaction;
    private long lastCleanup;
    private long lastRepositorySize;
    private long lastReclaimedSize;
    private String lastError;
    private String status = "NA";

    public FileStoreGCMonitor(@Nonnull Clock clock) {
        this.clock = checkNotNull(clock);
    }

    //------------------------------------------------------------< GCMonitor >---

    @Override
    public void info(String message, Object... arguments) {
        status = arrayFormat(message, arguments).getMessage();
    }

    @Override
    public void warn(String message, Object... arguments) {
        status = arrayFormat(message, arguments).getMessage();
    }

    @Override
    public void error(String message, Exception exception) {
        StringWriter sw = new StringWriter();
        sw.write(message + ": ");
        exception.printStackTrace(new PrintWriter(sw));
        lastError = sw.toString();
    }

    @Override
    public void skipped(String reason, Object... arguments) {
        status = arrayFormat(reason, arguments).getMessage();
    }

    @Override
    public void compacted() {
        lastCompaction = clock.getTime();
    }

    @Override
    public void cleaned(long reclaimed, long current) {
        lastCleanup = clock.getTime();
        lastReclaimedSize = reclaimed;
        lastRepositorySize = current;
    }

    public String getLastCompaction() {
        return toString(lastCompaction);
    }

    public String getLastCleanup() {
        return toString(lastCleanup);
    }

    public long getLastRepositorySize() {
        return lastRepositorySize;
    }

    public long getLastReclaimedSize() {
        return lastReclaimedSize;
    }

    private static String toString(long timestamp) {
        if (timestamp != 0) {
            return getDateTimeInstance().format(new Date(timestamp));
        } else {
            return null;
        }
    }

    public String getLastError() {
        return lastError;
    }

    @Nonnull
    public String getStatus() {
        return status;
    }
}
