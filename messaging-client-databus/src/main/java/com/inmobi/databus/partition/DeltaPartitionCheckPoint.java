package com.inmobi.databus.partition;

import com.inmobi.databus.files.StreamFile;
import com.inmobi.messaging.consumer.databus.MessageCheckpoint;

import java.util.HashMap;
import java.util.Map;

public class DeltaPartitionCheckPoint implements MessageCheckpoint {
  private Map<Integer, PartitionCheckpoint> deltaCheckpoint;

  public DeltaPartitionCheckPoint(StreamFile streamFile, long lineNum,
      Integer minId, Map<Integer, PartitionCheckpoint> deltaCheckpoint) {
    this.deltaCheckpoint = new HashMap<Integer, PartitionCheckpoint>();
    this.deltaCheckpoint.putAll(deltaCheckpoint);
    this.deltaCheckpoint.put(minId,
        new PartitionCheckpoint(streamFile, lineNum));
  }

  @Override
  public String toString() {
    return this.deltaCheckpoint.toString();
  }

  public Map<Integer, PartitionCheckpoint> getDeltaCheckpoint() {
    return deltaCheckpoint;
  }

  @Override
  public boolean isNULL() {
    return false;
  }
}
