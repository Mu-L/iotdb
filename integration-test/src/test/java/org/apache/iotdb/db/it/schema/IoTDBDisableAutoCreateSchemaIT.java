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

package org.apache.iotdb.db.it.schema;

import org.apache.iotdb.it.env.EnvFactory;
import org.apache.iotdb.itbase.category.ClusterIT;
import org.apache.iotdb.itbase.category.LocalStandaloneIT;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Category({LocalStandaloneIT.class, ClusterIT.class})
public class IoTDBDisableAutoCreateSchemaIT {

  @BeforeClass
  public static void setUp() throws Exception {
    EnvFactory.getEnv().getConfig().getCommonConfig().setAutoCreateSchemaEnabled(false);

    // Init 1C1D environment
    EnvFactory.getEnv().initClusterEnvironment(1, 1);
  }

  @AfterClass
  public static void tearDown() {
    EnvFactory.getEnv().cleanClusterEnvironment();
  }

  @Test
  public void disableAutoCreateSchemaTest() throws SQLException {
    try (Connection connection = EnvFactory.getEnv().getConnection();
        Statement statement = connection.createStatement()) {
      try {
        statement.executeQuery("insert into root.db.d(timestamp, s) values(233, 666)");
      } catch (SQLException e) {
        Assert.assertTrue(e.getMessage().contains("because enable_auto_create_schema is FALSE."));
      }
      try {
        ResultSet databaseResultSet = statement.executeQuery("count databases root.db");
        databaseResultSet.next();
        Assert.assertEquals(0, databaseResultSet.getInt("count"));
        ResultSet timeseriesResultSet = statement.executeQuery("count timeseries root.db.**");
        timeseriesResultSet.next();
        Assert.assertEquals(0, timeseriesResultSet.getInt("count(timeseries)"));
      } catch (SQLException e) {
        Assert.fail(e.getMessage());
      }
    }
  }
}
