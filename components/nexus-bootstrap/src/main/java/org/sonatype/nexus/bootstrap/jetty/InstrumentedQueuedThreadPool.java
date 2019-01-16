/**
 * Copyright (C) 2006-2019 Talend Inc. - www.talend.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sonatype.nexus.bootstrap.jetty;

import java.util.concurrent.BlockingQueue;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.MetricsRegistry;

import org.eclipse.jetty.util.thread.QueuedThreadPool;

// TALEND patch: ensure the queue size is monitored
public class InstrumentedQueuedThreadPool extends com.yammer.metrics.jetty.InstrumentedQueuedThreadPool {
    public InstrumentedQueuedThreadPool() {
        this(Metrics.defaultRegistry());
    }

    public InstrumentedQueuedThreadPool(final MetricsRegistry registry) {
        super(registry);
        registry.newGauge(QueuedThreadPool.class, "jobs", new Gauge<Integer>() {
            @Override
            public Integer value() {
                final BlockingQueue<Runnable> queue = getQueue();
                return queue == null ? -1 : queue.size();
            }
        });
    }
}
