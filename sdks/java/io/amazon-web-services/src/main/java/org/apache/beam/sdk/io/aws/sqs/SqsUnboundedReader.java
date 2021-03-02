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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.aws.sqs;

import static java.util.stream.Collectors.groupingBy;

import com.amazonaws.services.sqs.model.*;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.beam.sdk.io.UnboundedSource;
import org.apache.beam.sdk.io.UnboundedSource.CheckpointMark;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.joda.time.Instant;

class SqsUnboundedReader extends UnboundedSource.UnboundedReader<Message> implements Serializable {

  public static final int MAX_NUMBER_OF_MESSAGES = 10;
  private final SqsUnboundedSource source;
  private Message current;
  private final Queue<Message> messagesNotYetRead;
  private List<Message> messagesToDelete;
  private Instant oldestPendingTimestamp = BoundedWindow.TIMESTAMP_MIN_VALUE;

  public SqsUnboundedReader(SqsUnboundedSource source, SqsCheckpointMark sqsCheckpointMark) {
    this.source = source;
    this.current = null;

    this.messagesNotYetRead = new ArrayDeque<>();
    this.messagesToDelete = new ArrayList<>();

    if (sqsCheckpointMark != null) {
      this.messagesToDelete.addAll(sqsCheckpointMark.getMessagesToDelete());
    }
  }

  @Override
  public Instant getWatermark() {
    return oldestPendingTimestamp;
  }

  @Override
  public Message getCurrent() throws NoSuchElementException {
    if (current == null) {
      throw new NoSuchElementException();
    }
    return current;
  }

  @Override
  public Instant getCurrentTimestamp() throws NoSuchElementException {
    if (current == null) {
      throw new NoSuchElementException();
    }

    return getTimestamp(current);
  }

  @Override
  public byte[] getCurrentRecordId() throws NoSuchElementException {
    if (current == null) {
      throw new NoSuchElementException();
    }
    return current.getMessageId().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public CheckpointMark getCheckpointMark() {
    return new SqsCheckpointMark(this, messagesToDelete);
  }

  @Override
  public SqsUnboundedSource getCurrentSource() {
    return source;
  }

  @Override
  public boolean start() {
    return advance();
  }

  @Override
  public boolean advance() {
    if (messagesNotYetRead.isEmpty()) {
      pull();
    }

    current = messagesNotYetRead.poll();
    if (current == null) {
      return false;
    }

    messagesToDelete.add(current);

    Instant currentMessageTimestamp = getCurrentTimestamp();
    if (getCurrentTimestamp().isBefore(oldestPendingTimestamp)) {
      oldestPendingTimestamp = currentMessageTimestamp;
    }

    return true;
  }

  @Override
  public void close() {}

  void delete(final Collection<Message> messages) {
    AtomicInteger counter = new AtomicInteger();
    messages.stream()
        .filter(m -> messagesToDelete.contains(m))
        .collect(groupingBy(x -> counter.getAndIncrement() / 10))
        .values()
        .forEach(
            batch -> {
              List<DeleteMessageBatchRequestEntry> entries =
                  batch.stream()
                      .map(
                          m ->
                              new DeleteMessageBatchRequestEntry(
                                  m.getMessageId(), m.getReceiptHandle()))
                      .collect(Collectors.toList());
              source.getSqs().deleteMessageBatch(source.getRead().queueUrl(), entries);
              Instant currentMessageTimestamp =
                  batch.stream()
                      .map(this::getTimestamp)
                      .max(Comparator.comparing(Instant::getMillis))
                      .orElse(oldestPendingTimestamp);
              if (currentMessageTimestamp.isAfter(oldestPendingTimestamp)) {
                oldestPendingTimestamp = currentMessageTimestamp;
              }
            });
  }

  private void pull() {
    final ReceiveMessageRequest receiveMessageRequest =
        new ReceiveMessageRequest(source.getRead().queueUrl());

    receiveMessageRequest.setMaxNumberOfMessages(MAX_NUMBER_OF_MESSAGES);
    receiveMessageRequest.setAttributeNames(
        Arrays.asList(MessageSystemAttributeName.SentTimestamp.toString()));
    final ReceiveMessageResult receiveMessageResult =
        source.getSqs().receiveMessage(receiveMessageRequest);

    final List<Message> messages = receiveMessageResult.getMessages();

    if (messages == null || messages.isEmpty()) {
      return;
    }

    for (Message message : messages) {
      messagesNotYetRead.add(message);
    }
  }

  private Instant getTimestamp(final Message message) {
    return new Instant(
        Long.parseLong(
            message.getAttributes().get(MessageSystemAttributeName.SentTimestamp.toString())));
  }
}
