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

package org.apache.iotdb.db.subscription.task.subtask;

import org.apache.iotdb.commons.pipe.agent.task.connection.UnboundedBlockingPendingQueue;
import org.apache.iotdb.db.pipe.agent.task.execution.PipeSinkSubtaskExecutor;
import org.apache.iotdb.db.pipe.agent.task.subtask.sink.PipeSinkSubtask;
import org.apache.iotdb.db.pipe.agent.task.subtask.sink.PipeSinkSubtaskLifeCycle;
import org.apache.iotdb.db.subscription.agent.SubscriptionAgent;
import org.apache.iotdb.pipe.api.event.Event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SubscriptionSinkSubtaskLifeCycle extends PipeSinkSubtaskLifeCycle {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SubscriptionSinkSubtaskLifeCycle.class);

  public SubscriptionSinkSubtaskLifeCycle(
      final PipeSinkSubtaskExecutor executor, // SubscriptionSubtaskExecutor
      final PipeSinkSubtask subtask, // SubscriptionConnectorSubtask
      final UnboundedBlockingPendingQueue<Event> pendingQueue) {
    super(executor, subtask, pendingQueue);
  }

  @Override
  public synchronized void register() {
    if (registeredTaskCount < 0) {
      throw new IllegalStateException("registeredTaskCount < 0");
    }

    if (registeredTaskCount == 0) {
      // bind prefetching queue
      SubscriptionAgent.broker().bindPrefetchingQueue((SubscriptionSinkSubtask) subtask);
      executor.register(subtask);
      runningTaskCount = 0;
    }

    registeredTaskCount++;
    LOGGER.info(
        "Register subtask {}. runningTaskCount: {}, registeredTaskCount: {}",
        subtask,
        runningTaskCount,
        registeredTaskCount);
  }

  @Override
  public synchronized boolean deregister(final String pipeNameToDeregister, int regionId) {
    if (registeredTaskCount <= 0) {
      throw new IllegalStateException("registeredTaskCount <= 0");
    }

    // no need to discard events of pipe

    try {
      if (registeredTaskCount > 1) {
        return false;
      }

      close();
      // This subtask is out of life cycle, should never be used again
      return true;
    } finally {
      registeredTaskCount--;
      LOGGER.info(
          "Deregister subtask {}. runningTaskCount: {}, registeredTaskCount: {}",
          subtask,
          runningTaskCount,
          registeredTaskCount);
    }
  }

  @Override
  public synchronized void close() {
    super.close();

    // Here, the prefetching queue is not actually removed, because it's uncertain whether the
    // corresponding underlying pipe is automatically terminated. The actual removal is carried out
    // when dropping the subscription.
    final String consumerGroupId = ((SubscriptionSinkSubtask) subtask).getConsumerGroupId();
    final String topicName = ((SubscriptionSinkSubtask) subtask).getTopicName();
    SubscriptionAgent.broker().unbindPrefetchingQueue(consumerGroupId, topicName);
  }
}
