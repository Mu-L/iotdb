/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.pipe.sink.payload.evolvable.batch;

import org.apache.iotdb.commons.pipe.event.EnrichedEvent;
import org.apache.iotdb.db.pipe.resource.PipeDataNodeResourceManager;
import org.apache.iotdb.db.pipe.resource.memory.PipeDynamicMemoryBlock;
import org.apache.iotdb.db.pipe.resource.memory.PipeMemoryBlockType;
import org.apache.iotdb.db.pipe.resource.memory.PipeModelFixedMemoryBlock;
import org.apache.iotdb.db.pipe.sink.protocol.thrift.async.IoTDBDataRegionAsyncSink;
import org.apache.iotdb.db.storageengine.dataregion.wal.exception.WALPipeException;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class PipeTabletEventBatch implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeTabletEventBatch.class);
  private static PipeModelFixedMemoryBlock pipeModelFixedMemoryBlock = null;

  protected final List<EnrichedEvent> events = new ArrayList<>();
  protected final TriLongConsumer recordMetric;

  private final int maxDelayInMs;
  private long firstEventProcessingTime = Long.MIN_VALUE;

  protected long totalBufferSize = 0;
  private final PipeDynamicMemoryBlock allocatedMemoryBlock;

  protected volatile boolean isClosed = false;

  protected PipeTabletEventBatch(
      final int maxDelayInMs,
      final long requestMaxBatchSizeInBytes,
      final TriLongConsumer recordMetric) {
    if (pipeModelFixedMemoryBlock == null) {
      init();
    }

    this.maxDelayInMs = maxDelayInMs;
    if (recordMetric != null) {
      this.recordMetric = recordMetric;
    } else {
      this.recordMetric =
          (timeInterval, bufferSize, events) -> {
            // do nothing
          };
    }

    // limit in buffer size
    this.allocatedMemoryBlock =
        pipeModelFixedMemoryBlock.registerPipeBatchMemoryBlock(requestMaxBatchSizeInBytes);

    if (getMaxBatchSizeInBytes() != allocatedMemoryBlock.getMemoryUsageInBytes()) {
      LOGGER.info(
          "PipeTabletEventBatch: the max batch size is adjusted from {} to {} due to the "
              + "memory restriction",
          requestMaxBatchSizeInBytes,
          getMaxBatchSizeInBytes());
    }
  }

  /**
   * Try offer {@link Event} into batch if the given {@link Event} is not duplicated.
   *
   * @param event the given {@link Event}
   * @return {@code true} if the batch can be transferred
   */
  public synchronized boolean onEvent(final TabletInsertionEvent event)
      throws WALPipeException, IOException {
    if (isClosed || !(event instanceof EnrichedEvent)) {
      return false;
    }

    // The deduplication logic here is to avoid the accumulation of
    // the same event in a batch when retrying.
    if (events.isEmpty() || !Objects.equals(events.get(events.size() - 1), event)) {
      // We increase the reference count for this event to determine if the event may be released.
      if (((EnrichedEvent) event)
          .increaseReferenceCount(PipeTransferBatchReqBuilder.class.getName())) {

        try {
          if (constructBatch(event)) {
            events.add((EnrichedEvent) event);
          }
        } catch (final Exception e) {
          // If the event is not added to the batch, we need to decrease the reference count.
          ((EnrichedEvent) event)
              .decreaseReferenceCount(PipeTransferBatchReqBuilder.class.getName(), false);
          // Will cause a retry
          throw e;
        }

        if (firstEventProcessingTime == Long.MIN_VALUE) {
          firstEventProcessingTime = System.currentTimeMillis();
        }
      } else {
        LOGGER.warn("Cannot increase reference count for event: {}, ignore it in batch.", event);
      }
    }

    return shouldEmit();
  }

  /**
   * Added an {@link TabletInsertionEvent} into batch.
   *
   * @param event the {@link TabletInsertionEvent} in batch
   * @return {@code true} if the event is calculated into batch, {@code false} if the event is
   *     cached and not emitted in this batch. If there are failure encountered, just throw
   *     exceptions and do not return {@code false} here.
   */
  protected abstract boolean constructBatch(final TabletInsertionEvent event)
      throws WALPipeException, IOException;

  public boolean shouldEmit() {
    final long diff = System.currentTimeMillis() - firstEventProcessingTime;
    if (totalBufferSize >= getMaxBatchSizeInBytes() || diff >= maxDelayInMs) {
      allocatedMemoryBlock.updateCurrentMemoryEfficiencyAdjustMem((double) diff / maxDelayInMs);
      recordMetric.accept(diff, totalBufferSize, events.size());
      return true;
    }
    return false;
  }

  private long getMaxBatchSizeInBytes() {
    return allocatedMemoryBlock.getMemoryUsageInBytes();
  }

  public synchronized void onSuccess() {
    events.clear();

    totalBufferSize = 0;

    firstEventProcessingTime = Long.MIN_VALUE;
  }

  @Override
  public synchronized void close() {
    isClosed = true;

    clearEventsReferenceCount(PipeTabletEventBatch.class.getName());
    events.clear();

    if (allocatedMemoryBlock != null) {
      allocatedMemoryBlock.close();
    }
  }

  /**
   * Discard all events of the given pipe. This method only clears the reference count of the events
   * and discard them, but do not modify other objects (such as buffers) for simplicity.
   */
  public synchronized void discardEventsOfPipe(final String pipeNameToDrop, final int regionId) {
    events.removeIf(
        event -> {
          if (pipeNameToDrop.equals(event.getPipeName()) && regionId == event.getRegionId()) {
            event.clearReferenceCount(IoTDBDataRegionAsyncSink.class.getName());
            return true;
          }
          return false;
        });
  }

  public synchronized void decreaseEventsReferenceCount(
      final String holderMessage, final boolean shouldReport) {
    events.forEach(event -> event.decreaseReferenceCount(holderMessage, shouldReport));
  }

  private void clearEventsReferenceCount(final String holderMessage) {
    events.forEach(event -> event.clearReferenceCount(holderMessage));
  }

  public List<EnrichedEvent> deepCopyEvents() {
    return new ArrayList<>(events);
  }

  public boolean isEmpty() {
    return events.isEmpty();
  }

  // please at PipeLauncher call this method to init pipe model fixed memory block
  public static void init() {
    if (pipeModelFixedMemoryBlock != null) {
      return;
    }

    try {
      long batchSize = PipeDataNodeResourceManager.memory().getAllocatedMemorySizeInBytesOfBatch();
      for (long i = batchSize; i > 0; i = i / 2) {
        try {
          pipeModelFixedMemoryBlock =
              PipeDataNodeResourceManager.memory()
                  .forceAllocateForModelFixedMemoryBlock(i, PipeMemoryBlockType.BATCH);

          LOGGER.info("pipe model fixed memory block initialized with size: {} bytes", i);
          return;
        } catch (Exception ignore) {
          // ignore the exception and try to allocate a smaller size
          LOGGER.info(
              "pipe model fixed memory block initialized with size: {} bytes failed, try smaller size",
              i);
        }
      }
    } catch (Exception e) {
      LOGGER.error("init pipe model fixed memory block failed", e);
      // If the allocation fails, we still need to create a default memory block to avoid NPE.
      pipeModelFixedMemoryBlock =
          PipeDataNodeResourceManager.memory()
              .forceAllocateForModelFixedMemoryBlock(0, PipeMemoryBlockType.BATCH);
    }
  }

  @FunctionalInterface
  public interface TriLongConsumer {
    void accept(long l1, long l2, long l3);
  }
}
