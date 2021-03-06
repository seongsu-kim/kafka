/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor;

import org.apache.kafka.common.annotation.InterfaceStability;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.StreamsMetrics;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.kstream.ValueTransformer;
import org.apache.kafka.streams.processor.internals.RecordCollector;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.state.internals.InMemoryKeyValueStore;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * {@link MockProcessorContext} is a mock of {@link ProcessorContext} for users to test their {@link Processor},
 * {@link Transformer}, and {@link ValueTransformer} implementations.
 * <p>
 * The tests for this class (org.apache.kafka.streams.MockProcessorContextTest) include several behavioral
 * tests that serve as example usage.
 * <p>
 * Note that this class does not take any automated actions (such as firing scheduled punctuators).
 * It simply captures any data it witnesses.
 * If you require more automated tests, we recommend wrapping your {@link Processor} in a minimal source-processor-sink
 * {@link Topology} and using the {@link TopologyTestDriver}.
 */
@InterfaceStability.Evolving
public class MockProcessorContext implements ProcessorContext, RecordCollector.Supplier {
    // Immutable fields ================================================
    private final StreamsMetricsImpl metrics;
    private final TaskId taskId;
    private final StreamsConfig config;
    private final File stateDir;

    // settable record metadata ================================================
    private String topic;
    private Integer partition;
    private Long offset;
    private Long timestamp;

    // mocks ================================================
    private final Map<String, StateStore> stateStores = new HashMap<>();
    private final List<CapturedPunctuator> punctuators = new LinkedList<>();
    private final List<CapturedForward> capturedForwards = new LinkedList<>();
    private boolean committed = false;

    /**
     * {@link CapturedPunctuator} holds captured punctuators, along with their scheduling information.
     */
    public static class CapturedPunctuator {
        private final long intervalMs;
        private final PunctuationType type;
        private final Punctuator punctuator;
        private boolean cancelled = false;

        private CapturedPunctuator(final long intervalMs, final PunctuationType type, final Punctuator punctuator) {
            this.intervalMs = intervalMs;
            this.type = type;
            this.punctuator = punctuator;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public PunctuationType getType() {
            return type;
        }

        public Punctuator getPunctuator() {
            return punctuator;
        }

        @SuppressWarnings("WeakerAccess")
        public void cancel() {
            cancelled = true;
        }

        public boolean cancelled() {
            return cancelled;
        }
    }


    public static class CapturedForward {
        private final String childName;
        private final long timestamp;
        private final KeyValue keyValue;

        private CapturedForward(final To to, final KeyValue keyValue) {
            if (keyValue == null) throw new IllegalArgumentException();

            this.childName = to.childName;
            this.timestamp = to.timestamp;
            this.keyValue = keyValue;
        }

        /**
         * The child this data was forwarded to.
         *
         * @return The child name, or {@code null} if it was broadcast.
         */
        public String childName() {
            return childName;
        }

        /**
         * The timestamp attached to the forwarded record.
         *
         * @return A timestamp, or {@code -1} if none was forwarded.
         */
        public long timestamp() {
            return timestamp;
        }

        /**
         * The data forwarded.
         *
         * @return A key/value pair. Not null.
         */
        public KeyValue keyValue() {
            return keyValue;
        }
    }

    // constructors ================================================

    /**
     * Create a {@link MockProcessorContext} with dummy {@code config} and {@code taskId} and {@code null} {@code stateDir}.
     * Most unit tests using this mock won't need to know the taskId,
     * and most unit tests should be able to get by with the
     * {@link InMemoryKeyValueStore}, so the stateDir won't matter.
     */
    public MockProcessorContext() {
        //noinspection DoubleBraceInitialization
        this(
            new Properties() {
                {
                    put(StreamsConfig.APPLICATION_ID_CONFIG, "");
                    put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "");
                }
            },
            new TaskId(0, 0),
            null);
    }

    /**
     * Create a {@link MockProcessorContext} with dummy {@code taskId} and {@code null} {@code stateDir}.
     * Most unit tests using this mock won't need to know the taskId,
     * and most unit tests should be able to get by with the
     * {@link InMemoryKeyValueStore}, so the stateDir won't matter.
     *
     * @param config a Properties object, used to configure the context and the processor.
     */
    public MockProcessorContext(final Properties config) {
        this(config, new TaskId(0, 0), null);
    }

    /**
     * Create a {@link MockProcessorContext} with a specified taskId and null stateDir.
     *
     * @param config   a {@link Properties} object, used to configure the context and the processor.
     * @param taskId   a {@link TaskId}, which the context makes available via {@link MockProcessorContext#taskId()}.
     * @param stateDir a {@link File}, which the context makes available viw {@link MockProcessorContext#stateDir()}.
     */
    public MockProcessorContext(final Properties config, final TaskId taskId, final File stateDir) {
        final StreamsConfig streamsConfig = new StreamsConfig(config);
        this.taskId = taskId;
        this.config = streamsConfig;
        this.stateDir = stateDir;
        this.metrics = new StreamsMetricsImpl(new Metrics(), "mock-processor-context-virtual-thread");
    }

    @Override
    public String applicationId() {
        return config.getString(StreamsConfig.APPLICATION_ID_CONFIG);
    }

    @Override
    public TaskId taskId() {
        return taskId;
    }

    @Override
    public Map<String, Object> appConfigs() {
        final Map<String, Object> combined = new HashMap<>();
        combined.putAll(config.originals());
        combined.putAll(config.values());
        return combined;
    }

    @Override
    public Map<String, Object> appConfigsWithPrefix(final String prefix) {
        return config.originalsWithPrefix(prefix);
    }

    @Override
    public Serde<?> keySerde() {
        return config.defaultKeySerde();
    }

    @Override
    public Serde<?> valueSerde() {
        return config.defaultValueSerde();
    }

    @Override
    public File stateDir() {
        return stateDir;
    }

    @Override
    public StreamsMetrics metrics() {
        return metrics;
    }

    // settable record metadata ================================================

    /**
     * The context exposes these metadata for use in the processor. Normally, they are set by the Kafka Streams framework,
     * but for the purpose of driving unit tests, you can set them directly.
     *
     * @param topic     A topic name
     * @param partition A partition number
     * @param offset    A record offset
     * @param timestamp A record timestamp
     */
    public void setRecordMetadata(final String topic, final int partition, final long offset, final long timestamp) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.timestamp = timestamp;
    }


    /**
     * The context exposes this metadata for use in the processor. Normally, they are set by the Kafka Streams framework,
     * but for the purpose of driving unit tests, you can set it directly. Setting this attribute doesn't affect the others.
     *
     * @param topic A topic name
     */
    public void setTopic(final String topic) {
        this.topic = topic;
    }

    /**
     * The context exposes this metadata for use in the processor. Normally, they are set by the Kafka Streams framework,
     * but for the purpose of driving unit tests, you can set it directly. Setting this attribute doesn't affect the others.
     *
     * @param partition A partition number
     */
    public void setPartition(final int partition) {
        this.partition = partition;
    }


    /**
     * The context exposes this metadata for use in the processor. Normally, they are set by the Kafka Streams framework,
     * but for the purpose of driving unit tests, you can set it directly. Setting this attribute doesn't affect the others.
     *
     * @param offset A record offset
     */
    public void setOffset(final long offset) {
        this.offset = offset;
    }


    /**
     * The context exposes this metadata for use in the processor. Normally, they are set by the Kafka Streams framework,
     * but for the purpose of driving unit tests, you can set it directly. Setting this attribute doesn't affect the others.
     *
     * @param timestamp A record timestamp
     */
    public void setTimestamp(final long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String topic() {
        if (topic == null) {
            throw new IllegalStateException("Topic must be set before use via setRecordMetadata() or setTopic().");
        }
        return topic;
    }

    @Override
    public int partition() {
        if (partition == null) {
            throw new IllegalStateException("Partition must be set before use via setRecordMetadata() or setPartition().");
        }
        return partition;
    }

    @Override
    public long offset() {
        if (offset == null) {
            throw new IllegalStateException("Offset must be set before use via setRecordMetadata() or setOffset().");
        }
        return offset;
    }

    @Override
    public long timestamp() {
        if (timestamp == null) {
            throw new IllegalStateException("Timestamp must be set before use via setRecordMetadata() or setTimestamp().");
        }
        return timestamp;
    }

    // mocks ================================================


    @Override
    public void register(final StateStore store,
                         final boolean loggingEnabledIsDeprecatedAndIgnored,
                         final StateRestoreCallback stateRestoreCallbackIsIgnoredInMock) {
        stateStores.put(store.name(), store);
    }

    @Override
    public StateStore getStateStore(final String name) {
        return stateStores.get(name);
    }

    @Override
    public Cancellable schedule(final long intervalMs, final PunctuationType type, final Punctuator callback) {
        final CapturedPunctuator capturedPunctuator = new CapturedPunctuator(intervalMs, type, callback);

        punctuators.add(capturedPunctuator);

        return new Cancellable() {
            @Override
            public void cancel() {
                capturedPunctuator.cancel();
            }
        };
    }

    /**
     * Get the punctuators scheduled so far. The returned list is not affected by subsequent calls to {@code schedule(...)}.
     *
     * @return A list of captured punctuators.
     */
    public List<CapturedPunctuator> scheduledPunctuators() {
        final LinkedList<CapturedPunctuator> capturedPunctuators = new LinkedList<>();
        capturedPunctuators.addAll(punctuators);
        return capturedPunctuators;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K, V> void forward(final K key, final V value) {
        capturedForwards.add(new CapturedForward(To.all(), new KeyValue(key, value)));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K, V> void forward(final K key, final V value, final To to) {
        capturedForwards.add(new CapturedForward(to, new KeyValue(key, value)));
    }

    @Override
    public <K, V> void forward(final K key, final V value, final int childIndex) {
        throw new UnsupportedOperationException(
            "Forwarding to a child by index is deprecated. " +
                "Please transition processors to forward using a 'To' object instead."
        );
    }

    @Override
    public <K, V> void forward(final K key, final V value, final String childName) {
        throw new UnsupportedOperationException(
            "Forwarding to a child by name is deprecated. " +
                "Please transition processors to forward using 'To.child(childName)' instead."
        );
    }

    /**
     * Get all the forwarded data this context has observed. The returned list will not be
     * affected by subsequent interactions with the context. The data in the list is in the same order as the calls to
     * {@code forward(...)}.
     *
     * @return A list of key/value pairs that were previously passed to the context.
     */
    public List<CapturedForward> forwarded() {
        final LinkedList<CapturedForward> result = new LinkedList<>();
        result.addAll(capturedForwards);
        return result;
    }

    /**
     * Get all the forwarded data this context has observed for a specific child by name.
     * The returned list will not be affected by subsequent interactions with the context.
     * The data in the list is in the same order as the calls to {@code forward(...)}.
     *
     * @param childName The child name to retrieve forwards for
     * @return A list of key/value pairs that were previously passed to the context.
     */
    public List<CapturedForward> forwarded(final String childName) {
        final LinkedList<CapturedForward> result = new LinkedList<>();
        for (final CapturedForward capture : capturedForwards) {
            if (capture.childName() == null || capture.childName().equals(childName)) {
                result.add(capture);
            }
        }
        return result;
    }

    /**
     * Clear the captured forwarded data.
     */
    public void resetForwards() {
        capturedForwards.clear();
    }

    @Override
    public void commit() {
        committed = true;
    }

    /**
     * Whether {@link ProcessorContext#commit()} has been called in this context.
     *
     * @return {@code true} iff {@link ProcessorContext#commit()} has been called in this context since construction or reset.
     */
    public boolean committed() {
        return committed;
    }

    /**
     * Reset the commit capture to {@code false} (whether or not it was previously {@code true}).
     */
    public void resetCommit() {
        committed = false;
    }

    @Override
    public RecordCollector recordCollector() {
        // This interface is assumed by state stores that add change-logging.
        // Rather than risk a mysterious ClassCastException during unit tests, throw an explanatory exception.

        throw new UnsupportedOperationException("MockProcessorContext does not provide record collection. " +
            "For processor unit tests, use an in-memory state store with change-logging disabled. " +
            "Alternatively, use the TopologyTestDriver for testing processor/store/topology integration.");
    }
}
