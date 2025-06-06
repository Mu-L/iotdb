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

package org.apache.iotdb.commons.schema.table.column;

import org.apache.tsfile.enums.ColumnCategory;
import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public enum TsTableColumnCategory {
  TAG((byte) 0),
  ATTRIBUTE((byte) 1),
  TIME((byte) 2),
  FIELD((byte) 3);

  private final byte category;

  TsTableColumnCategory(final byte category) {
    this.category = category;
  }

  byte getValue() {
    return category;
  }

  public void serialize(final OutputStream stream) throws IOException {
    ReadWriteIOUtils.write(category, stream);
  }

  public void serialize(final ByteBuffer byteBuffer) {
    ReadWriteIOUtils.write(category, byteBuffer);
  }

  public static TsTableColumnCategory deserialize(final InputStream stream) throws IOException {
    return deserialize((byte) stream.read());
  }

  public static TsTableColumnCategory deserialize(final ByteBuffer stream) {
    return deserialize(stream.get());
  }

  public static TsTableColumnCategory deserialize(final byte category) {
    switch (category) {
      case 0:
        return TAG;
      case 1:
        return ATTRIBUTE;
      case 2:
        return TIME;
      case 3:
        return FIELD;
      default:
        throw new IllegalArgumentException();
    }
  }

  public ColumnCategory toTsFileColumnType() {
    switch (this) {
      case TAG:
        return ColumnCategory.TAG;
      case ATTRIBUTE:
        return ColumnCategory.ATTRIBUTE;
      case FIELD:
        return ColumnCategory.FIELD;
      default:
        throw new IllegalArgumentException("Unsupported column type in TsFile: " + this);
    }
  }

  public static TsTableColumnCategory fromTsFileColumnCategory(ColumnCategory columnType) {
    switch (columnType) {
      case FIELD:
        return FIELD;
      case TAG:
        return TAG;
      case ATTRIBUTE:
        return ATTRIBUTE;
      default:
        throw new IllegalArgumentException("Unknown column type: " + columnType);
    }
  }

  public byte getCategory() {
    return category;
  }
}
