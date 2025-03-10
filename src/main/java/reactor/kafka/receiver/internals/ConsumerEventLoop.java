/*
 * Copyright (c) 2020-2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.kafka.receiver.internals;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Since {@link org.apache.kafka.clients.consumer.Consumer} does not support multi-threaded access,
 * this event loop serializes every action we perform on it.
 */
class ConsumerEventLoop<K, V> implements Sinks.EmitFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsumerEventLoop.class.getName());

    final AtomicBoolean isActive = new AtomicBoolean(true);

    final AtmostOnceOffsets atmostOnceOffsets;

    final PollEvent pollEvent;

    final AckMode ackMode;

    final ReceiverOptions<K, V> receiverOptions;

    final Scheduler eventScheduler;

    final CommitEvent commitEvent = new CommitEvent();

    final Predicate<Throwable> isRetriableException;

    final Set<TopicPartition> pausedByUser =  new HashSet<>();

    private final Disposable periodicCommitDisposable;

    // TODO make it final
    org.apache.kafka.clients.consumer.Consumer<K, V> consumer;

    final Sinks.Many<ConsumerRecords<K, V>> sink;

    final AtomicBoolean awaitingTransaction;

    volatile long requested;
    @SuppressWarnings("rawtypes")
    static final AtomicLongFieldUpdater<ConsumerEventLoop> REQUESTED = AtomicLongFieldUpdater.newUpdater(
        ConsumerEventLoop.class,
        "requested"
    );

    ConsumerEventLoop(
        AckMode ackMode,
        AtmostOnceOffsets atmostOnceOffsets,
        ReceiverOptions<K, V> receiverOptions,
        Scheduler eventScheduler,
        org.apache.kafka.clients.consumer.Consumer<K, V> consumer,
        Predicate<Throwable> isRetriableException,
        Sinks.Many<ConsumerRecords<K, V>> sink,
        AtomicBoolean awaitingTransaction
    ) {
        this.ackMode = ackMode;
        this.atmostOnceOffsets = atmostOnceOffsets;
        this.receiverOptions = receiverOptions;
        this.eventScheduler = eventScheduler;
        this.consumer = consumer;
        this.isRetriableException = isRetriableException;
        this.sink = sink;
        this.awaitingTransaction = awaitingTransaction;

        this.pollEvent = new PollEvent();

        commitEvent.commitBatch.outOfOrderCommits = receiverOptions.maxDeferredCommits() > 0;

        eventScheduler.schedule(new SubscribeEvent());

        Duration commitInterval = receiverOptions.commitInterval();
        if (!commitInterval.isZero()) {
            switch (ackMode) {
                case AUTO_ACK:
                case MANUAL_ACK:
                    periodicCommitDisposable = Schedulers.parallel().schedulePeriodically(
                        commitEvent::scheduleIfRequired,
                        commitInterval.toMillis(),
                        commitInterval.toMillis(),
                        TimeUnit.MILLISECONDS
                    );
                    break;
                default:
                    periodicCommitDisposable = Disposables.disposed();
            }
        } else {
            periodicCommitDisposable = Disposables.disposed();
        }
    }

    void paused(Collection<TopicPartition> paused) {
        this.pausedByUser.addAll(paused);
    }

    void resumed(Collection<TopicPartition> resumed) {
        this.pausedByUser.removeAll(resumed);
    }

    void onRequest(long toAdd) {
        if (log.isDebugEnabled()) {
            log.debug("onRequest.toAdd {}, paused {}", toAdd, pollEvent.isPaused());
        }
        Operators.addCap(REQUESTED, this, toAdd);
        if (pollEvent.isPaused()) {
            consumer.wakeup();
        }
        pollEvent.schedule();
    }

    private void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        log.debug("onPartitionsRevoked {}", partitions);
        if (!partitions.isEmpty()) {
            // It is safe to use the consumer here since we are in a poll()
            if (ackMode != AckMode.ATMOST_ONCE) {
                commitEvent.runIfRequired(true);
                long maxDelayRebalance = receiverOptions.maxDelayRebalance().toMillis();
                if (isActive.get() && maxDelayRebalance > 0) {
                    long interval = receiverOptions.commitIntervalDuringDelay();
                    int inPipeline = commitEvent.commitBatch.getInPipeline();
                    if (inPipeline > 0 || this.awaitingTransaction.get()) {
                        long end = maxDelayRebalance + System.currentTimeMillis();
                        do {
                            try {
                                log.debug("Rebalancing; waiting for {} records in pipeline", inPipeline);
                                Thread.sleep(interval);
                                commitEvent.runIfRequired(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            inPipeline = commitEvent.commitBatch.getInPipeline();
                        } while (isActive.get() && (inPipeline > 0 || this.awaitingTransaction.get())
                                && System.currentTimeMillis() < end);
                    }
                }
            }
            for (Consumer<Collection<ReceiverPartition>> onRevoke : receiverOptions.revokeListeners()) {
                onRevoke.accept(toSeekable(partitions));
            }
        }
    }

    private Collection<ReceiverPartition> toSeekable(Collection<TopicPartition> partitions) {
        List<ReceiverPartition> seekableList = new ArrayList<>(partitions.size());
        for (TopicPartition partition : partitions)
            seekableList.add(new SeekablePartition(consumer, partition));
        return seekableList;
    }

    Mono<Void> stop() {
        return Mono
            .defer(() -> {
                log.debug("dispose {}", isActive);

                if (!isActive.compareAndSet(true, false)) {
                    return Mono.empty();
                }

                periodicCommitDisposable.dispose();

                if (consumer == null) {
                    return Mono.empty();
                }

                this.consumer.wakeup();
                return Mono.<Void>fromRunnable(new CloseEvent(receiverOptions.closeTimeout()))
                    .as(flux -> flux.subscribeOn(eventScheduler));
            })
            .onErrorResume(e -> {
                log.warn("Cancel exception: " + e);
                return Mono.empty();
            });
    }

    @Override
    public boolean onEmitFailure(SignalType signalType, EmitResult result) {
        if (!isActive.get()) {
            return false;
        } else {
            return result == EmitResult.FAIL_NON_SERIALIZED;
        }
    }

    class SubscribeEvent implements Runnable {

        @Override
        public void run() {
            try {
                receiverOptions
                    .subscriber(new ConsumerRebalanceListener() {
                        @Override
                        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                            log.debug("onPartitionsAssigned {}", partitions);
                            boolean repausedAll = false;
                            if (!partitions.isEmpty() && ConsumerEventLoop.this.pollEvent.pausedByUs.get()) {
                                log.debug("Rebalance during back pressure, re-pausing new assignments");
                                consumer.pause(partitions);
                                repausedAll = true;
                            }
                            if (!pausedByUser.isEmpty()) {
                                List<TopicPartition> toRepause = new ArrayList<>();
                                Iterator<TopicPartition> iterator = pausedByUser.iterator();
                                while (iterator.hasNext()) {
                                    TopicPartition next = iterator.next();
                                    if (partitions.contains(next)) {
                                        toRepause.add(next);
                                    } else {
                                        iterator.remove();
                                    }
                                }
                                if (!repausedAll && !toRepause.isEmpty()) {
                                    consumer.pause(toRepause);
                                }
                            }
                            // onAssign methods may perform seek. It is safe to use the consumer here since we are in a poll()
                            for (Consumer<Collection<ReceiverPartition>> onAssign :
                                    receiverOptions.assignListeners()) {
                                onAssign.accept(toSeekable(partitions));
                            }
                            if (log.isTraceEnabled()) {
                                try {
                                    List<String> positions = new ArrayList<>();
                                    partitions.forEach(part -> positions.add(String.format("%s pos: %d", part,
                                        ConsumerEventLoop.this.consumer.position(part, Duration.ofSeconds(5)))));
                                    log.trace("positions: {}, committed: {}", positions,
                                            ConsumerEventLoop.this.consumer.committed(new HashSet<>(partitions),
                                                    Duration.ofSeconds(5)));
                                } catch (Exception ex) {
                                    log.error("Failed to get positions or committed", ex);
                                }
                            }
                        }

                        @Override
                        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                            ConsumerEventLoop.this.onPartitionsRevoked(partitions);
                            ConsumerEventLoop.this.pollEvent.commitBatch.partitionsRevoked(partitions);
                        }
                    })
                    .accept(consumer);
            } catch (Exception e) {
                if (isActive.get()) {
                    log.error("Unexpected exception", e);
                    sink.emitError(e, ConsumerEventLoop.this);
                }
            }
        }
    }

    class PollEvent implements Runnable {

        private final Duration pollTimeout = receiverOptions.pollTimeout();

        private final AtomicBoolean pausedByUs = new AtomicBoolean();

        private final AtomicBoolean scheduled = new AtomicBoolean();

        private final long maxDeferredCommits = receiverOptions.maxDeferredCommits();

        private final CommittableBatch commitBatch = commitEvent.commitBatch;

        @Override
        public void run() {
            try {
                this.scheduled.set(false);
                if (isActive.get()) {
                    // Ensure that commits are not queued behind polls since number of poll events is
                    // chosen by reactor.
                    commitEvent.runIfRequired(false);
                    long r = requested;
                    boolean pauseForDeferred = this.maxDeferredCommits > 0
                        && this.commitBatch.deferredCount() >= this.maxDeferredCommits;
                    if (pauseForDeferred || commitEvent.retrying.get()) {
                        r = 0;
                    }
                    if (r > 0) {
                        if (!awaitingTransaction.get()) {
                            if (pausedByUs.getAndSet(false)) {
                                Set<TopicPartition> toResume = new HashSet<>(consumer.assignment());
                                toResume.removeAll(ConsumerEventLoop.this.pausedByUser);
                                consumer.resume(toResume);
                                if (log.isDebugEnabled()) {
                                    log.debug("Resumed partitions: " + toResume);
                                }
                            }
                        } else {
                            if (checkAndSetPausedByUs()) {
                                consumer.pause(consumer.assignment());
                                log.debug("Paused - awaiting transaction");
                            }
                        }
                    } else if (checkAndSetPausedByUs()) {
                        consumer.pause(consumer.assignment());
                        if (pauseForDeferred) {
                            log.debug("Paused - too many deferred commits");
                        } else if (commitEvent.retrying.get()) {
                            log.debug("Paused - commits are retrying");
                        } else {
                            log.debug("Paused - back pressure");
                        }
                    }

                    ConsumerRecords<K, V> records;
                    try {
                        records = consumer.poll(pollTimeout);
                    } catch (WakeupException e) {
                        log.debug("Consumer woken");
                        records = ConsumerRecords.empty();
                    }

                    if (isActive.get()) {
                        schedule();
                    }

                    if (!records.isEmpty()) {
                        this.commitBatch.addUncommitted(records);
                        Operators.produced(REQUESTED, ConsumerEventLoop.this, 1);
                        log.debug("Emitting {} records, requested now {}", records.count(), r);
                        sink.emitNext(records, ConsumerEventLoop.this);
                    }
                }
            } catch (Exception e) {
                if (isActive.get()) {
                    log.error("Unexpected exception", e);
                    sink.emitError(e, ConsumerEventLoop.this);
                }
            }
        }

        /*
         * Race condition where onRequest was called to increase requested but we
         * hadn't yet paused the consumer; wake immediately in this case.
         */
        private boolean checkAndSetPausedByUs() {
            boolean pausedNow = !pausedByUs.getAndSet(true);
            if (pausedNow && requested > 0 && !commitEvent.retrying.get()) {
                consumer.wakeup();
            }
            return pausedNow;
        }

        void schedule() {
            if (!this.scheduled.getAndSet(true)) {
                eventScheduler.schedule(this);
            }
        }

        boolean isPaused() {
            return pausedByUs.get();
        }
    }

    class CommitEvent implements Runnable {
        final CommittableBatch commitBatch = new CommittableBatch();
        private final AtomicBoolean isPending = new AtomicBoolean();
        private final AtomicInteger inProgress = new AtomicInteger();
        private final AtomicInteger consecutiveCommitFailures = new AtomicInteger();
        private final AtomicBoolean retrying = new AtomicBoolean();

        @Override
        public void run() {
            if (!isPending.compareAndSet(true, false)) {
                return;
            }
            final CommittableBatch.CommitArgs commitArgs = commitBatch.getAndClearOffsets();
            try {
                if (commitArgs != null) {
                    if (!commitArgs.offsets().isEmpty()) {
                        switch (ackMode) {
                            case ATMOST_ONCE:
                                if (log.isDebugEnabled()) {
                                    log.debug("Sync committing: " + commitArgs.offsets());
                                }
                                consumer.commitSync(commitArgs.offsets());
                                handleSuccess(commitArgs, commitArgs.offsets());
                                atmostOnceOffsets.onCommit(commitArgs.offsets());
                                break;
                            case EXACTLY_ONCE:
                                // Handled separately using transactional KafkaSender
                                break;
                            case AUTO_ACK:
                            case MANUAL_ACK:
                                inProgress.incrementAndGet();
                                try {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Async committing: " + commitArgs.offsets());
                                    }
                                    consumer.commitAsync(commitArgs.offsets(), (offsets, exception) -> {
                                        inProgress.decrementAndGet();
                                        if (exception == null)
                                            handleSuccess(commitArgs, offsets);
                                        else
                                            handleFailure(commitArgs, exception);
                                    });
                                } catch (Throwable e) {
                                    inProgress.decrementAndGet();
                                    throw e;
                                }
                                pollEvent.schedule();
                                break;
                        }
                    } else {
                        handleSuccess(commitArgs, commitArgs.offsets());
                    }
                }
            } catch (Exception e) {
                log.error("Unexpected exception", e);
                handleFailure(commitArgs, e);
            }
        }

        void runIfRequired(boolean force) {
            if (force)
                isPending.set(true);
            if (!this.retrying.get() && isPending.get())
                run();
        }

        private void handleSuccess(CommittableBatch.CommitArgs commitArgs, Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (!offsets.isEmpty())
                consecutiveCommitFailures.set(0);
            pollTaskAfterRetry();
            if (commitArgs.callbackEmitters() != null) {
                for (MonoSink<Void> emitter : commitArgs.callbackEmitters()) {
                    emitter.success();
                }
            }
        }

        private void handleFailure(CommittableBatch.CommitArgs commitArgs, Exception exception) {
            log.warn("Commit failed", exception);
            boolean mayRetry = ConsumerEventLoop.this.isRetriableException.test(exception) &&
                consumer != null &&
                consecutiveCommitFailures.incrementAndGet() < receiverOptions.maxCommitAttempts();
            if (!mayRetry) {
                log.debug("Cannot retry");
                pollTaskAfterRetry();
                List<MonoSink<Void>> callbackEmitters = commitArgs.callbackEmitters();
                if (callbackEmitters != null && !callbackEmitters.isEmpty()) {
                    isPending.set(false);
                    commitBatch.restoreOffsets(commitArgs, false);
                    for (MonoSink<Void> emitter : callbackEmitters) {
                        emitter.error(exception);
                    }
                } else {
                    sink.emitError(exception, ConsumerEventLoop.this);
                }
            } else {
                commitBatch.restoreOffsets(commitArgs, true);
                log.warn("Commit failed with exception" + exception + ", retries remaining "
                            + (receiverOptions.maxCommitAttempts() - consecutiveCommitFailures.get()));
                isPending.set(true);
                this.retrying.set(true);
                pollEvent.schedule();
                eventScheduler.schedule(this, receiverOptions.commitRetryInterval().toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        private void pollTaskAfterRetry() {
            if (log.isTraceEnabled()) {
                log.trace("after retry " + this.retrying.get());
            }
            if (this.retrying.getAndSet(false)) {
                pollEvent.schedule();
            }
        }

        void scheduleIfRequired() {
            if (isActive.get() && !this.retrying.get() && isPending.compareAndSet(false, true)) {
                eventScheduler.schedule(this);
            }
        }

        private void waitFor(long endTimeMillis) {
            while (inProgress.get() > 0 && endTimeMillis - System.currentTimeMillis() > 0) {
                consumer.poll(Duration.ofMillis(1));
            }
        }
    }

    private class CloseEvent implements Runnable {
        private final long closeEndTimeMillis;
        CloseEvent(Duration timeout) {
            this.closeEndTimeMillis = System.currentTimeMillis() + timeout.toMillis();
        }

        @Override
        public void run() {
            try {
                if (consumer != null) {
                    Collection<TopicPartition> manualAssignment = receiverOptions.assignment();
                    if (manualAssignment != null && !manualAssignment.isEmpty())
                        onPartitionsRevoked(manualAssignment);
                    /*
                     * We loop here in case the consumer has had a recent wakeup call (from user code)
                     * which will cause a poll() (in waitFor) to be interrupted while we're
                     * possibly waiting for async commit results.
                     */
                    int attempts = 3;
                    for (int i = 0; i < attempts; i++) {
                        try {
                            boolean forceCommit = true;
                            if (ackMode == AckMode.ATMOST_ONCE)
                                forceCommit = atmostOnceOffsets.undoCommitAhead(commitEvent.commitBatch);
                            // For exactly-once, offsets are committed by a producer, consumer may be closed immediately
                            if (ackMode != AckMode.EXACTLY_ONCE) {
                                commitEvent.runIfRequired(forceCommit);
                                commitEvent.waitFor(closeEndTimeMillis);
                            }

                            long timeoutMillis = closeEndTimeMillis - System.currentTimeMillis();
                            if (timeoutMillis < 0)
                                timeoutMillis = 0;
                            consumer.close(Duration.ofMillis(timeoutMillis));
                            consumer = null;
                            break;
                        } catch (WakeupException e) {
                            if (i == attempts - 1)
                                throw e;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Unexpected exception during close", e);
                sink.emitError(e, ConsumerEventLoop.this);
            }
        }
    }
}
