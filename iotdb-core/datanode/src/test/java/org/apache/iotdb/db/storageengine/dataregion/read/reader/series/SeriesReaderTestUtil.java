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

package org.apache.iotdb.db.storageengine.dataregion.read.reader.series;

import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.db.storageengine.buffer.BloomFilterCache;
import org.apache.iotdb.db.storageengine.buffer.ChunkCache;
import org.apache.iotdb.db.storageengine.buffer.TimeSeriesMetadataCache;
import org.apache.iotdb.db.storageengine.dataregion.read.control.FileReaderManager;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileResource;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileResourceStatus;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.db.utils.constant.TestConstant;

import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.exception.write.WriteProcessException;
import org.apache.tsfile.file.metadata.IDeviceID;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.write.TsFileWriter;
import org.apache.tsfile.write.record.TSRecord;
import org.apache.tsfile.write.record.datapoint.DataPoint;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.apache.tsfile.write.schema.MeasurementSchema;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.iotdb.commons.conf.IoTDBConstant.PATH_SEPARATOR;

/**
 * This util contains 5 seqFiles and 5 unseqFiles in default.
 *
 * <p>Sequence time range of data: [0, 99], [100, 199], [200, 299], [300, 399], [400, 499]
 *
 * <p>UnSequence time range of data: [0, 19], [100, 139], [200, 259], [300, 379], [400, 499], [0,
 * 199]
 *
 * <p>The time series generated by this util are:
 *
 * <p>time value
 *
 * <p>0-99 20000-20099
 *
 * <p>100-199 20100-20199
 *
 * <p>200-259 10200-10259
 *
 * <p>260-299 260-299
 *
 * <p>300-379 10300-10379
 *
 * <p>380-399 380-399
 *
 * <p>400-499 10400-10499
 */
public class SeriesReaderTestUtil {

  private static int seqFileNum = 5;
  private static int unseqFileNum = 5;
  private static int measurementNum = 10;
  private static int deviceNum = 10;
  private static long ptNum = 100;
  private static long flushInterval = 20;
  private static TSEncoding encoding = TSEncoding.PLAIN;

  public static void setUp(
      List<IMeasurementSchema> measurementSchemas,
      List<String> deviceIds,
      List<TsFileResource> seqResources,
      List<TsFileResource> unseqResources,
      String sgName)
      throws MetadataException, IOException, WriteProcessException {
    prepareSeries(measurementSchemas, deviceIds, sgName);
    prepareFiles(seqResources, unseqResources, measurementSchemas, deviceIds, sgName);
  }

  public static void tearDown(
      List<TsFileResource> seqResources, List<TsFileResource> unseqResources) throws IOException {
    removeFiles(seqResources, unseqResources);
    seqResources.clear();
    unseqResources.clear();
    ChunkCache.getInstance().clear();
    TimeSeriesMetadataCache.getInstance().clear();
    BloomFilterCache.getInstance().clear();
    EnvironmentUtils.cleanAllDir();
  }

  private static void prepareFiles(
      List<TsFileResource> seqResources,
      List<TsFileResource> unseqResources,
      List<IMeasurementSchema> measurementSchemas,
      List<String> deviceIds,
      String sgName)
      throws IOException, WriteProcessException {
    for (int i = 0; i < seqFileNum; i++) {
      File file = new File(TestConstant.getTestTsFilePath(sgName, 0, 0, i));
      TsFileResource tsFileResource = new TsFileResource(file);
      tsFileResource.setStatusForTest(TsFileResourceStatus.NORMAL);
      tsFileResource.setMinPlanIndex(i);
      tsFileResource.setMaxPlanIndex(i);
      tsFileResource.setVersion(i);
      seqResources.add(tsFileResource);
      prepareFile(tsFileResource, i * ptNum, ptNum, 0, measurementSchemas, deviceIds);
    }
    for (int i = 0; i < unseqFileNum; i++) {
      File file = new File(TestConstant.getTestTsFilePath(sgName, 0, 0, i + seqFileNum));
      TsFileResource tsFileResource = new TsFileResource(file);
      tsFileResource.setStatusForTest(TsFileResourceStatus.NORMAL);
      tsFileResource.setMinPlanIndex(i + seqFileNum);
      tsFileResource.setMaxPlanIndex(i + seqFileNum);
      tsFileResource.setVersion(i + seqFileNum);
      unseqResources.add(tsFileResource);
      prepareFile(
          tsFileResource,
          i * ptNum,
          ptNum * (i + 1) / unseqFileNum,
          10000,
          measurementSchemas,
          deviceIds);
    }

    File file = new File(TestConstant.getTestTsFilePath(sgName, 0, 0, seqFileNum + unseqFileNum));
    TsFileResource tsFileResource = new TsFileResource(file);
    tsFileResource.setStatusForTest(TsFileResourceStatus.NORMAL);
    tsFileResource.setMinPlanIndex(seqFileNum + unseqFileNum);
    tsFileResource.setMaxPlanIndex(seqFileNum + unseqFileNum);
    tsFileResource.setVersion(seqFileNum + unseqFileNum);
    unseqResources.add(tsFileResource);
    prepareFile(tsFileResource, 0, ptNum * 2, 20000, measurementSchemas, deviceIds);
  }

  private static void prepareFile(
      TsFileResource tsFileResource,
      long timeOffset,
      long ptNum,
      long valueOffset,
      List<IMeasurementSchema> measurementSchemas,
      List<String> deviceIds)
      throws IOException, WriteProcessException {
    File file = tsFileResource.getTsFile();
    if (!file.getParentFile().exists()) {
      Assert.assertTrue(file.getParentFile().mkdirs());
    }
    TsFileWriter fileWriter = new TsFileWriter(file);
    Map<String, IMeasurementSchema> template = new HashMap<>();
    for (IMeasurementSchema measurementSchema : measurementSchemas) {
      template.put(measurementSchema.getMeasurementName(), measurementSchema);
    }
    fileWriter.registerSchemaTemplate("template0", template, false);
    for (String deviceId : deviceIds) {
      fileWriter.registerDevice(deviceId, "template0");
    }
    for (long i = timeOffset; i < timeOffset + ptNum; i++) {
      for (String deviceId : deviceIds) {
        TSRecord record = new TSRecord(deviceId, i);
        for (IMeasurementSchema measurementSchema : measurementSchemas) {
          record.addTuple(
              DataPoint.getDataPoint(
                  measurementSchema.getType(),
                  measurementSchema.getMeasurementName(),
                  String.valueOf(i + valueOffset)));
        }
        fileWriter.writeRecord(record);
        tsFileResource.updateStartTime(IDeviceID.Factory.DEFAULT_FACTORY.create(deviceId), i);
        tsFileResource.updateEndTime(IDeviceID.Factory.DEFAULT_FACTORY.create(deviceId), i);
      }
      if ((i + 1) % flushInterval == 0) {
        fileWriter.flush();
      }
    }
    fileWriter.close();
  }

  private static void prepareSeries(
      List<IMeasurementSchema> measurementSchemas, List<String> deviceIds, String sgName) {
    for (int i = 0; i < measurementNum; i++) {
      measurementSchemas.add(
          new MeasurementSchema(
              "sensor" + i, TSDataType.INT32, encoding, CompressionType.UNCOMPRESSED));
    }
    for (int i = 0; i < deviceNum; i++) {
      deviceIds.add(sgName + PATH_SEPARATOR + "device" + i);
    }
  }

  private static void removeFiles(
      List<TsFileResource> seqResources, List<TsFileResource> unseqResources) throws IOException {
    for (TsFileResource tsFileResource : seqResources) {
      tsFileResource.remove();
    }
    for (TsFileResource tsFileResource : unseqResources) {
      tsFileResource.remove();
    }

    FileReaderManager.getInstance().closeAndRemoveAllOpenedReaders();
  }

  static void assertWithHasNext(SeriesScanHasNextSupplier supplier, boolean value)
      throws IOException {
    while (true) {
      Optional<Boolean> b = supplier.get();
      if (!b.isPresent()) {
        continue;
      }
      Assert.assertEquals(b.get(), value);
      break;
    }
  }

  interface SeriesScanHasNextSupplier {
    Optional<Boolean> get() throws IOException;
  }
}
