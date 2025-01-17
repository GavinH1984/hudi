/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.connect.writers;

import org.apache.hudi.client.HoodieJavaWriteClient;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.model.HoodieAvroPayload;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.util.DefaultSizeEstimator;
import org.apache.hudi.common.util.HoodieRecordSizeEstimator;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.ExternalSpillableMap;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.io.IOUtils;
import org.apache.hudi.keygen.KeyGenerator;
import org.apache.hudi.schema.SchemaProvider;

import org.apache.avro.Schema;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Specific implementation of a Hudi Writer that buffers all incoming records,
 * and writes them to Hudi files on the end of a transaction using Bulk Insert.
 */
public class BufferedConnectWriter extends AbstractConnectWriter {

  private static final Logger LOG = LogManager.getLogger(BufferedConnectWriter.class);

  private final HoodieEngineContext context;
  private final HoodieJavaWriteClient writeClient;
  private final String instantTime;
  private final HoodieWriteConfig config;
  private ExternalSpillableMap<String, HoodieRecord<HoodieAvroPayload>> bufferedRecords;

  public BufferedConnectWriter(HoodieEngineContext context,
                               HoodieJavaWriteClient writeClient,
                               String instantTime,
                               KafkaConnectConfigs connectConfigs,
                               HoodieWriteConfig config,
                               KeyGenerator keyGenerator,
                               SchemaProvider schemaProvider) {
    super(connectConfigs, keyGenerator, schemaProvider);
    this.context = context;
    this.writeClient = writeClient;
    this.instantTime = instantTime;
    this.config = config;
    init();
  }

  private void init() {
    try {
      // Load and batch all incoming records in a map
      long memoryForMerge = IOUtils.getMaxMemoryPerPartitionMerge(context.getTaskContextSupplier(), config);
      LOG.info("MaxMemoryPerPartitionMerge => " + memoryForMerge);
      this.bufferedRecords = new ExternalSpillableMap<>(memoryForMerge,
          config.getSpillableMapBasePath(),
          new DefaultSizeEstimator(),
          new HoodieRecordSizeEstimator(new Schema.Parser().parse(config.getSchema())),
          config.getCommonConfig().getSpillableDiskMapType(),
          config.getCommonConfig().isBitCaskDiskMapCompressionEnabled());
    } catch (IOException io) {
      throw new HoodieIOException("Cannot instantiate an ExternalSpillableMap", io);
    }
  }

  @Override
  public void writeHudiRecord(HoodieRecord<HoodieAvroPayload> record) {
    bufferedRecords.put(record.getRecordKey(), record);
  }

  @Override
  public List<WriteStatus> flushHudiRecords() throws IOException {
    try {
      LOG.info("Number of entries in MemoryBasedMap => "
          + bufferedRecords.getInMemoryMapNumEntries()
          + "Total size in bytes of MemoryBasedMap => "
          + bufferedRecords.getCurrentInMemoryMapSize() + "Number of entries in BitCaskDiskMap => "
          + bufferedRecords.getDiskBasedMapNumEntries() + "Size of file spilled to disk => "
          + bufferedRecords.getSizeOfFileOnDiskInBytes());
      List<WriteStatus> writeStatuses = new ArrayList<>();
      // Write out all records if non-empty
      if (!bufferedRecords.isEmpty()) {
        writeStatuses = writeClient.bulkInsertPreppedRecords(
          bufferedRecords.values().stream().collect(Collectors.toList()),
        instantTime, Option.empty());
      }
      bufferedRecords.close();
      LOG.info("Flushed hudi records and got writeStatuses: "
          + writeStatuses);
      return writeStatuses;
    } catch (Exception e) {
      throw new IOException("Write records failed", e);
    }
  }
}
