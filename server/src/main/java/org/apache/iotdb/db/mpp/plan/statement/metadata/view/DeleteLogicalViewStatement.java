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

package org.apache.iotdb.db.mpp.plan.statement.metadata.view;

import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.mpp.plan.analyze.QueryType;
import org.apache.iotdb.db.mpp.plan.statement.IConfigStatement;
import org.apache.iotdb.db.mpp.plan.statement.Statement;
import org.apache.iotdb.db.mpp.plan.statement.StatementType;
import org.apache.iotdb.db.mpp.plan.statement.StatementVisitor;

import java.util.List;

public class DeleteLogicalViewStatement extends Statement implements IConfigStatement {
  List<PartialPath> pathPatternList;

  public DeleteLogicalViewStatement() {
    super();
    statementType = StatementType.DELETE_LOGICAL_VIEW;
  }

  public DeleteLogicalViewStatement(List<PartialPath> pathPatternList) {
    this();
    this.pathPatternList = pathPatternList;
  }

  @Override
  public List<PartialPath> getPaths() {
    return pathPatternList;
  }

  public List<PartialPath> getPathPatternList() {
    return pathPatternList;
  }

  public void setPathPatternList(List<PartialPath> pathPatternList) {
    this.pathPatternList = pathPatternList;
  }

  @Override
  public <R, C> R accept(StatementVisitor<R, C> visitor, C context) {
    return visitor.visitDeleteLogicalView(this, context);
  }

  @Override
  public QueryType getQueryType() {
    return QueryType.WRITE;
  }
}
