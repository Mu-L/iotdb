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

package org.apache.iotdb.db.pipe.agent.task.subtask.connector;

import org.apache.iotdb.commons.pipe.agent.task.connection.BlockingPendingQueue;
import org.apache.iotdb.commons.pipe.agent.task.connection.UnboundedBlockingPendingQueue;
import org.apache.iotdb.commons.pipe.config.PipeConfig;
import org.apache.iotdb.commons.pipe.event.EnrichedEvent;
import org.apache.iotdb.db.pipe.agent.task.connection.PipeEventCollector;
import org.apache.iotdb.db.pipe.event.common.heartbeat.PipeHeartbeatEvent;
import org.apache.iotdb.db.pipe.metric.source.PipeDataRegionEventCounter;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;

import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class PipeRealtimePriorityBlockingQueue extends UnboundedBlockingPendingQueue<Event> {

  private static final PipeConfig PIPE_CONFIG = PipeConfig.getInstance();

  private final BlockingDeque<TsFileInsertionEvent> tsfileInsertEventDeque =
      new LinkedBlockingDeque<>();

  private final AtomicInteger pollTsFileCounter = new AtomicInteger(0);

  private final AtomicLong pollHistoricalTsFileCounter = new AtomicLong(0);

  // Need to ensure that NPE does not occur
  private AtomicInteger offerTsFileCounter = new AtomicInteger(0);

  public PipeRealtimePriorityBlockingQueue() {
    super(new PipeDataRegionEventCounter());
  }

  @Override
  public boolean directOffer(final Event event) {
    checkBeforeOffer(event);

    if (event instanceof TsFileInsertionEvent) {
      tsfileInsertEventDeque.add((TsFileInsertionEvent) event);
      return true;
    }

    if (event instanceof PipeHeartbeatEvent && super.peekLast() instanceof PipeHeartbeatEvent) {
      // We can NOT keep too many PipeHeartbeatEvent in bufferQueue because they may cause OOM.
      ((EnrichedEvent) event).decreaseReferenceCount(PipeEventCollector.class.getName(), false);
      return false;
    } else {
      return super.directOffer(event);
    }
  }

  @Override
  public boolean waitedOffer(final Event event) {
    return directOffer(event);
  }

  @Override
  public boolean put(final Event event) {
    directOffer(event);
    return true;
  }

  @Override
  public Event directPoll() {
    Event event = null;
    final int pollHistoricalTsFileThreshold =
        PIPE_CONFIG.getPipeRealTimeQueuePollHistoricalTsFileThreshold();
    final int realTimeQueueMaxWaitingTsFileSize =
        PIPE_CONFIG.getPipeRealTimeQueueMaxWaitingTsFileSize();

    if (pollTsFileCounter.get() >= PIPE_CONFIG.getPipeRealTimeQueuePollTsFileThreshold()
        && offerTsFileCounter.get() < realTimeQueueMaxWaitingTsFileSize) {
      event =
          pollHistoricalTsFileCounter.incrementAndGet() % pollHistoricalTsFileThreshold == 0
              ? tsfileInsertEventDeque.pollFirst()
              : tsfileInsertEventDeque.pollLast();
      pollTsFileCounter.set(0);
    }

    if (Objects.isNull(event)) {
      // Sequentially poll the first offered non-TsFileInsertionEvent
      event = super.directPoll();
      if (Objects.isNull(event) && offerTsFileCounter.get() < realTimeQueueMaxWaitingTsFileSize) {
        event =
            pollHistoricalTsFileCounter.incrementAndGet() % pollHistoricalTsFileThreshold == 0
                ? tsfileInsertEventDeque.pollFirst()
                : tsfileInsertEventDeque.pollLast();
      }
      if (event != null) {
        pollTsFileCounter.incrementAndGet();
      }
    }

    return event;
  }

  /**
   * When the number of polls exceeds the pollHistoryThreshold, the {@link TsFileInsertionEvent} of
   * the earliest write to the queue is returned. if the pollHistoryThreshold is not reached then an
   * attempt is made to poll the queue for the latest insertion {@link Event}. First, it tries to
   * poll the first provided If there is no such {@link Event}, poll the last supplied {@link
   * TsFileInsertionEvent}. If no {@link Event} is available, it blocks until a {@link Event} is
   * available.
   *
   * @return the freshest insertion {@link Event}. can be {@code null} if no {@link Event} is
   *     available.
   */
  @Override
  public Event waitedPoll() {
    Event event = null;
    final int pollHistoricalTsFileThreshold =
        PIPE_CONFIG.getPipeRealTimeQueuePollHistoricalTsFileThreshold();
    final int realTimeQueueMaxWaitingTsFileSize =
        PIPE_CONFIG.getPipeRealTimeQueueMaxWaitingTsFileSize();

    if (pollTsFileCounter.get() >= PIPE_CONFIG.getPipeRealTimeQueuePollTsFileThreshold()
        && offerTsFileCounter.get() < realTimeQueueMaxWaitingTsFileSize) {
      event =
          pollHistoricalTsFileCounter.incrementAndGet() % pollHistoricalTsFileThreshold == 0
              ? tsfileInsertEventDeque.pollFirst()
              : tsfileInsertEventDeque.pollLast();
      pollTsFileCounter.set(0);
    }
    if (event == null) {
      // Sequentially poll the first offered non-TsFileInsertionEvent
      event = super.directPoll();
      if (event == null && !tsfileInsertEventDeque.isEmpty()) {
        event =
            pollHistoricalTsFileCounter.incrementAndGet() % pollHistoricalTsFileThreshold == 0
                ? tsfileInsertEventDeque.pollFirst()
                : tsfileInsertEventDeque.pollLast();
      }
      if (event != null) {
        pollTsFileCounter.incrementAndGet();
      }
    }

    // If no event is available, block until an event is available
    if (Objects.isNull(event) && offerTsFileCounter.get() < realTimeQueueMaxWaitingTsFileSize) {
      event = super.waitedPoll();
      if (Objects.isNull(event)) {
        event =
            pollHistoricalTsFileCounter.incrementAndGet() % pollHistoricalTsFileThreshold == 0
                ? tsfileInsertEventDeque.pollFirst()
                : tsfileInsertEventDeque.pollLast();
      }
      if (event != null) {
        pollTsFileCounter.incrementAndGet();
      }
    }

    return event;
  }

  @Override
  public Event peek() {
    final Event event = pendingQueue.peek();
    if (Objects.nonNull(event)) {
      return event;
    }
    return tsfileInsertEventDeque.peek();
  }

  @Override
  public void clear() {
    super.clear();
    tsfileInsertEventDeque.clear();
  }

  @Override
  public void forEach(final Consumer<? super Event> action) {
    super.forEach(action);
    tsfileInsertEventDeque.forEach(action);
  }

  @Override
  public void discardAllEvents() {
    super.discardAllEvents();
    tsfileInsertEventDeque.removeIf(
        event -> {
          if (event instanceof EnrichedEvent) {
            if (((EnrichedEvent) event).clearReferenceCount(BlockingPendingQueue.class.getName())) {
              eventCounter.decreaseEventCount(event);
            }
          }
          return true;
        });
    eventCounter.reset();
  }

  @Override
  public void discardEventsOfPipe(final String pipeNameToDrop, final int regionId) {
    super.discardEventsOfPipe(pipeNameToDrop, regionId);
    tsfileInsertEventDeque.removeIf(
        event -> {
          if (event instanceof EnrichedEvent
              && pipeNameToDrop.equals(((EnrichedEvent) event).getPipeName())
              && regionId == ((EnrichedEvent) event).getRegionId()) {
            if (((EnrichedEvent) event)
                .clearReferenceCount(PipeRealtimePriorityBlockingQueue.class.getName())) {
              eventCounter.decreaseEventCount(event);
            }
            return true;
          }
          return false;
        });
  }

  @Override
  public boolean isEmpty() {
    return super.isEmpty() && tsfileInsertEventDeque.isEmpty();
  }

  @Override
  public int size() {
    return super.size() + tsfileInsertEventDeque.size();
  }

  @Override
  public int getTsFileInsertionEventCount() {
    return tsfileInsertEventDeque.size();
  }

  public void setOfferTsFileCounter(AtomicInteger offerTsFileCounter) {
    this.offerTsFileCounter = offerTsFileCounter;
  }
}
