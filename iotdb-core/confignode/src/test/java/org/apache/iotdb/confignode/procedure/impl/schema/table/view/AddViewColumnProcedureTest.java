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

package org.apache.iotdb.confignode.procedure.impl.schema.table.view;

import org.apache.iotdb.commons.schema.table.column.TagColumnSchema;
import org.apache.iotdb.confignode.procedure.store.ProcedureType;

import org.apache.tsfile.enums.TSDataType;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

public class AddViewColumnProcedureTest {
  @Test
  public void serializeDeserializeTest() throws IOException {
    final AddViewColumnProcedure addViewColumnProcedure =
        new AddViewColumnProcedure(
            "database1",
            "table1",
            "0",
            Collections.singletonList(new TagColumnSchema("Id", TSDataType.STRING)),
            false);

    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    final DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
    addViewColumnProcedure.serialize(dataOutputStream);

    final ByteBuffer byteBuffer = ByteBuffer.wrap(byteArrayOutputStream.toByteArray());

    Assert.assertEquals(
        ProcedureType.ADD_VIEW_COLUMN_PROCEDURE.getTypeCode(), byteBuffer.getShort());

    final AddViewColumnProcedure deserializedProcedure = new AddViewColumnProcedure(false);
    deserializedProcedure.deserialize(byteBuffer);

    Assert.assertEquals(addViewColumnProcedure.getDatabase(), deserializedProcedure.getDatabase());
    Assert.assertEquals(
        addViewColumnProcedure.getTableName(), deserializedProcedure.getTableName());
  }
}
