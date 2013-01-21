package com.inmobi.databus.partition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.inmobi.databus.files.StreamFile;
import com.inmobi.messaging.Message;
import com.inmobi.messaging.consumer.databus.DataEncodingType;
import com.inmobi.messaging.consumer.databus.QueueEntry;
import com.inmobi.messaging.metrics.CollectorReaderStatsExposer;
import com.inmobi.messaging.metrics.PartitionReaderStatsExposer;

public class PartitionReader {

  private static final Log LOG = LogFactory.getLog(PartitionReader.class);

  private final PartitionId partitionId;
  private final BlockingQueue<QueueEntry> buffer;
  private PartitionStreamReader reader;

  private Thread thread;
  private volatile boolean stopped;
  private boolean inited = false;
  private final DataEncodingType dataEncoding;
  private final PartitionReaderStatsExposer prMetrics;
  private static final byte[] magicBytes = { (byte) 0xAB, (byte) 0xCD,
      (byte) 0xEF };
  private static final byte[] versions = { 1 };

  public PartitionReader(PartitionId partitionId,
      PartitionCheckpoint partitionCheckpoint, Configuration conf,
      FileSystem fs, Path collectorDataDir,
      Path streamsLocalDir, BlockingQueue<QueueEntry> buffer, String streamName,
      Date startTime, long waitTimeForFlush,
      long waitTimeForFileCreate, DataEncodingType dataEncoding,
      PartitionReaderStatsExposer prMetrics)
          throws IOException {
    this(partitionId, partitionCheckpoint, conf, fs, collectorDataDir,
        streamsLocalDir, buffer, streamName, startTime,
        waitTimeForFlush, waitTimeForFileCreate, dataEncoding, prMetrics, false);
  }

  public PartitionReader(PartitionId partitionId,
      PartitionCheckpoint partitionCheckpoint, FileSystem fs,
      BlockingQueue<QueueEntry> buffer, Path streamDir,
      Configuration conf, String inputFormatClass,
      Date startTime, long waitTimeForFileCreate, boolean isDatabusData,
      DataEncodingType dataEncoding, PartitionReaderStatsExposer prMetrics)
          throws IOException {
    this(partitionId, partitionCheckpoint, fs, buffer, streamDir,
        conf, inputFormatClass, startTime, waitTimeForFileCreate, isDatabusData,
        dataEncoding, prMetrics, false);
  }

  PartitionReader(PartitionId partitionId,
      PartitionCheckpoint partitionCheckpoint, Configuration conf,
      FileSystem fs,
      Path collectorDataDir,
      Path streamLocalDir, 
      BlockingQueue<QueueEntry> buffer, String streamName, Date startTime,
      long waitTimeForFlush, long waitTimeForFileCreate,
      DataEncodingType dataEncoding, PartitionReaderStatsExposer prMetrics,
      boolean noNewFiles)
          throws IOException {
    this(partitionId, partitionCheckpoint, buffer, startTime, dataEncoding,
        prMetrics);
    reader = new CollectorReader(partitionId, partitionCheckpoint, fs,
        streamName, collectorDataDir, streamLocalDir, conf,
        startTime, waitTimeForFlush, waitTimeForFileCreate,
        ((CollectorReaderStatsExposer)prMetrics), noNewFiles);
    // initialize cluster and its directories
    LOG.info("Partition reader initialized with partitionId:" + partitionId +
        " checkPoint:" + partitionCheckpoint +  
        " startTime:" + startTime +
        " currentReader:" + reader);
  }

  PartitionReader(PartitionId partitionId,
      PartitionCheckpoint partitionCheckpoint, FileSystem fs,
      BlockingQueue<QueueEntry> buffer, Path streamDir,
      Configuration conf, String inputFormatClass,
      Date startTime, long waitTimeForFileCreate, boolean isDatabusData,
      DataEncodingType dataEncoding, PartitionReaderStatsExposer prMetrics,
      boolean noNewFiles)
          throws IOException {
    this(partitionId, partitionCheckpoint, buffer, startTime, dataEncoding,
        prMetrics);
    reader = new ClusterReader(partitionId, partitionCheckpoint,
        fs, streamDir, conf, inputFormatClass, startTime,
        waitTimeForFileCreate, isDatabusData, prMetrics, noNewFiles);
    // initialize cluster and its directories
    LOG.info("Partition reader initialized with partitionId:" + partitionId +
        " checkPoint:" + partitionCheckpoint +  
        " startTime:" + startTime +
        " currentReader:" + reader);
  }

  private PartitionReader(PartitionId partitionId,
      PartitionCheckpoint partitionCheckpoint,
      BlockingQueue<QueueEntry> buffer, Date startTime,
      DataEncodingType dataEncoding,
      PartitionReaderStatsExposer prMetrics)
          throws IOException {
    if (startTime == null && partitionCheckpoint == null) {
      String msg = "StartTime and checkpoint both" +
          " cannot be null in PartitionReader";
      LOG.warn(msg);
      throw new IllegalArgumentException(msg);
    }
    this.partitionId = partitionId;
    this.buffer = buffer;
    this.dataEncoding = dataEncoding;
    this.prMetrics = prMetrics;
  }

  public synchronized void start() {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        while (!stopped && !thread.isInterrupted()) {
          long startTime = System.currentTimeMillis();
          try {
            while (!stopped && !inited) {
              init();
            }
            LOG.info("Started streaming the data from reader:" + reader);
            execute();
            if (stopped || thread.isInterrupted())
              return;
          } catch (Throwable e) {
            LOG.warn("Error in run", e);
            prMetrics.incrementHandledExceptions();
          }
          long finishTime = System.currentTimeMillis();
          LOG.debug("Execution took ms : " + (finishTime - startTime));
          try {
            long sleep = 1000;
            if (sleep > 0) {
              LOG.debug("Sleeping for " + sleep);
              Thread.sleep(sleep);
            }
          } catch (InterruptedException e) {
            LOG.warn("thread interrupted " + thread.getName(), e);
            return;
          }
        }
      }

    };
    thread = new Thread(runnable, this.partitionId.toString());
    LOG.info("Starting thread " + thread.getName());
    thread.start();
  }

  void init() throws IOException, InterruptedException {
    if (!inited) {
      reader.initializeCurrentFile();
      inited = true;
    }
  }

  public void close() {
    stopped = true;
    LOG.info(Thread.currentThread().getName() + " stopped [" + stopped + "]");
    if (reader != null) {
      try {
        reader.close();
      } catch (IOException e) {
        LOG.warn("Error closing current stream", e);
      }
    }
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException ie) {
        LOG.warn("thread join interrupted " + thread.getName(), ie);
        return;
      }
    }
  }

  StreamFile getCurrentFile() {
    return reader.getCurrentFile();
  }

  PartitionStreamReader getReader() {
    return reader;
  }

  private Message removeHeader(byte data[]) {
    boolean isValidHeaders = true;
    if (data.length < 16) {
      LOG.debug("Total size of data in message is less than length of headers");
      isValidHeaders = false;
    }
    ByteBuffer buffer = ByteBuffer.wrap(data);
    boolean isVersionValid = false;
    if (isValidHeaders) {
      for (byte version : versions) {
        if (buffer.get() == version) {
          isVersionValid = true;
          break;
        }
      }
      if (isVersionValid) {
        // compare all 3 magicBytes
        byte[] mBytesRead = new byte[3];
        buffer.get(mBytesRead);
        if (mBytesRead[0] != magicBytes[0] || mBytesRead[1] != magicBytes[1]
            || mBytesRead[2] != magicBytes[2])
          isValidHeaders = false;
      } else {
        LOG.debug("Invalid version in the headers");
      }
    }
    // TODO add validation for timestamp
    long timestamp = buffer.getLong();

    int messageSize = buffer.getInt();
    if (isValidHeaders && data.length != 16 + messageSize) {
      isValidHeaders = false;
      LOG.debug("Invalid size of messag in headers");
    }

    if (isValidHeaders) {
      ByteBuffer tmp = buffer.slice();
      return new Message(tmp);
    }
 else
      return new Message(data);

  }
  void execute() {
    assert (reader != null);
    try {
      reader.openStream();
      LOG.info("Reading file " + reader.getCurrentFile() + 
          " and lineNum:" + reader.getCurrentLineNum());
      while (!stopped) {
        byte[] line = reader.readLine();
        if (line != null) {
          // add the data to queue
          byte[] data;
          if (dataEncoding.equals(DataEncodingType.BASE64)) {
            data = Base64.decodeBase64(line);
          } else {
            data = line;
          }
          Message msg = removeHeader(data);
          buffer.put(new QueueEntry(msg, partitionId,
              new PartitionCheckpoint(reader.getCurrentFile(),
                  reader.getCurrentLineNum())));
          prMetrics.incrementMessagesAddedToBuffer();
        } else {
          LOG.info("No stream to read");
          return;
        }
      }
    } catch (InterruptedException ie) {
      LOG.info("Interrupted while reading stream", ie);
    } catch (Throwable e) {
      LOG.warn("Error while reading stream", e);
      prMetrics.incrementHandledExceptions();
    } finally {
      try {
        reader.closeStream();
      } catch (Exception e) {
        LOG.warn("Error while closing stream", e);
        prMetrics.incrementHandledExceptions();
      }
    }
  }

  public PartitionReaderStatsExposer getStatsExposer() {
    return prMetrics;
  }
}
