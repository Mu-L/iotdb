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

package org.apache.iotdb.db.queryengine.plan.relational.analyzer;

import org.apache.iotdb.db.queryengine.plan.planner.plan.DistributedQueryPlan;
import org.apache.iotdb.db.queryengine.plan.planner.plan.LogicalQueryPlan;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.sink.IdentitySinkNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.PlanTester;
import org.apache.iotdb.db.queryengine.plan.relational.planner.SymbolAllocator;
import org.apache.iotdb.db.queryengine.plan.relational.planner.TableLogicalPlanner;
import org.apache.iotdb.db.queryengine.plan.relational.planner.distribute.TableDistributedPlanner;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.DeviceTableScanNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.ExchangeNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.FilterNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.LimitNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.OffsetNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.OutputNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.ProjectNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.StreamSortNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.TopKNode;

import org.junit.Test;

import java.util.Arrays;

import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.AnalyzerTest.analyzeSQL;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.ALL_DEVICE_ENTRIES;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.DEFAULT_WARNING;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.QUERY_CONTEXT;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.SESSION_INFO;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.TEST_MATADATA;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.assertNodeMatches;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.assertTableScan;
import static org.apache.iotdb.db.queryengine.plan.relational.analyzer.TestUtils.getChildrenNode;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.assertions.PlanAssert.assertPlan;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.assertions.PlanMatchPattern.aggregation;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.assertions.PlanMatchPattern.exchange;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.assertions.PlanMatchPattern.output;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.assertions.PlanMatchPattern.project;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.assertions.PlanMatchPattern.tableScan;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.assertions.PlanMatchPattern.topK;
import static org.apache.iotdb.db.queryengine.plan.statement.component.Ordering.ASC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SubQueryTest {
  String sql;
  Analysis analysis;
  LogicalQueryPlan logicalQueryPlan;
  PlanNode logicalPlanNode;
  OutputNode outputNode;
  ProjectNode projectNode;
  TableDistributedPlanner distributionPlanner;
  DistributedQueryPlan distributedQueryPlan;
  DeviceTableScanNode deviceTableScanNode;

  @Test
  public void subQueryTest1() {
    // outer query has limit and sort,
    // sub query only has sort,
    // sort in sub query is invalid,
    // limit can be pushed down into TableScan.
    sql =
        "SELECT time, tag2, attr2, CAST(add_s2 as double) "
            + "FROM (SELECT time, SUBSTRING(tag1, 1) as sub_tag1, tag2, attr2, s1, s2+1 as add_s2 FROM table1 WHERE s1>1 ORDER BY tag1 DESC) "
            + "ORDER BY tag2 OFFSET 3 LIMIT 6";
    analysis = analyzeSQL(sql, TEST_MATADATA, QUERY_CONTEXT);
    SymbolAllocator symbolAllocator = new SymbolAllocator();
    logicalQueryPlan =
        new TableLogicalPlanner(
                QUERY_CONTEXT, TEST_MATADATA, SESSION_INFO, symbolAllocator, DEFAULT_WARNING)
            .plan(analysis);
    logicalPlanNode = logicalQueryPlan.getRootNode();

    // LogicalPlan: `Output - Offset - Limit - StreamSort - Project - TableScan`
    assertTrue(logicalPlanNode instanceof OutputNode);
    assertTrue(getChildrenNode(logicalPlanNode, 1) instanceof OffsetNode);
    assertTrue(getChildrenNode(logicalPlanNode, 2) instanceof LimitNode);
    assertTrue(getChildrenNode(logicalPlanNode, 3) instanceof StreamSortNode);
    assertTrue(getChildrenNode(logicalPlanNode, 4) instanceof ProjectNode);
    assertTrue(getChildrenNode(logicalPlanNode, 5) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(logicalPlanNode, 5);
    assertEquals(9, deviceTableScanNode.getPushDownLimit());
    assertTrue(deviceTableScanNode.isPushLimitToEachDevice());
    assertEquals("(\"s1\" > 1)", deviceTableScanNode.getPushDownPredicate().toString());

    // Before ExchangeNode optimize
    /*
     * IdentitySinkNode-163
     *   └──OutputNode-14
     *       └──OffsetNode-10
     *           └──TopKNode-11
     *               ├──ExchangeNode-159: [SourceAddress:192.0.12.1/test_query.2.0/161]
     *               ├──LimitNode-137
     *               │       └──ProjectNode-118
     *               │           └──DeviceTableScanNode-115
     *               └──ExchangeNode-160: [SourceAddress:192.0.10.1/test_query.3.0/162]
     */
    distributionPlanner =
        new TableDistributedPlanner(
            analysis, symbolAllocator, logicalQueryPlan, TEST_MATADATA, null);
    distributedQueryPlan = distributionPlanner.plan();
    assertEquals(4, distributedQueryPlan.getFragments().size());
    IdentitySinkNode identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(0).getPlanNodeTree();
    outputNode = (OutputNode) getChildrenNode(identitySinkNode, 1);
    assertTrue(getChildrenNode(outputNode, 1) instanceof OffsetNode);
    assertTrue(getChildrenNode(outputNode, 2) instanceof TopKNode);
    TopKNode topKNode = (TopKNode) getChildrenNode(outputNode, 2);
    assertTrue(topKNode.getChildren().get(0) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(1) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(2) instanceof ExchangeNode);

    projectNode =
        (ProjectNode)
            getChildrenNode(distributedQueryPlan.getFragments().get(1).getPlanNodeTree(), 2);
    assertTrue(getChildrenNode(projectNode, 1) instanceof DeviceTableScanNode);

    deviceTableScanNode =
        (DeviceTableScanNode)
            getChildrenNode(distributedQueryPlan.getFragments().get(2).getPlanNodeTree(), 3);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList(
            "table1.shanghai.A3.YY",
            "table1.shenzhen.B1.XX",
            "table1.shenzhen.B2.ZZ",
            "table1.shanghai.B3.YY"),
        ASC,
        9,
        0,
        true,
        "");
    /*
     * IdentitySinkNode-161
     *   └──LimitNode-136
     *           └──ProjectNode-117
     *               └──DeviceTableScanNode-114
     */
    identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(1).getPlanNodeTree();
    assertTrue(getChildrenNode(identitySinkNode, 1) instanceof LimitNode);
    assertTrue(getChildrenNode(identitySinkNode, 2) instanceof ProjectNode);
    assertTrue(getChildrenNode(identitySinkNode, 3) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(identitySinkNode, 3);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList("table1.shenzhen.B1.XX", "table1.shenzhen.B2.ZZ"),
        ASC,
        9,
        0,
        true,
        "");
  }

  @Test
  public void subQueryTest2() {
    // outer query has limit_1(count=3) and sort,
    // sub query has limit_2(count=9) and sort,
    // only the sort in sub query can be pushed down into TableScan.
    sql =
        "SELECT time, tag2, attr2, CAST(add_s2 as double) "
            + "FROM (SELECT time, SUBSTRING(tag1, 1) as sub_tag1, tag2, attr2, s1, s2+1 as add_s2 FROM table1 WHERE s1>1 ORDER BY tag1 DESC limit 3) "
            + "ORDER BY tag2 ASC OFFSET 5 LIMIT 10";
    analysis = analyzeSQL(sql, TEST_MATADATA, QUERY_CONTEXT);
    SymbolAllocator symbolAllocator = new SymbolAllocator();
    logicalQueryPlan =
        new TableLogicalPlanner(
                QUERY_CONTEXT, TEST_MATADATA, SESSION_INFO, symbolAllocator, DEFAULT_WARNING)
            .plan(analysis);
    logicalPlanNode = logicalQueryPlan.getRootNode();

    // LogicalPlan: `Output - Offset - TopK - Limit - Project - StreamSort - Project -
    // TableScan`
    assertTrue(logicalPlanNode instanceof OutputNode);
    assertTrue(getChildrenNode(logicalPlanNode, 1) instanceof OffsetNode);
    assertTrue(getChildrenNode(logicalPlanNode, 2) instanceof TopKNode);
    assertTrue(getChildrenNode(logicalPlanNode, 3) instanceof LimitNode);
    assertTrue(getChildrenNode(logicalPlanNode, 4) instanceof ProjectNode);
    assertTrue(getChildrenNode(logicalPlanNode, 5) instanceof StreamSortNode);
    assertTrue(getChildrenNode(logicalPlanNode, 6) instanceof ProjectNode);
    assertTrue(getChildrenNode(logicalPlanNode, 7) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(logicalPlanNode, 7);
    assertEquals(3, deviceTableScanNode.getPushDownLimit());
    assertTrue(deviceTableScanNode.isPushLimitToEachDevice());
    assertEquals("(\"s1\" > 1)", deviceTableScanNode.getPushDownPredicate().toString());

    // Before ExchangeNode optimize
    /*
     * IdentitySinkNode-199
     *   └──OutputNode-16
     *       └──OffsetNode-12
     *           └──TopKNode-13
     *                   └──ProjectNode-59
     *                       └──TopKNode-6
     *                           ├──ExchangeNode-195: [SourceAddress:192.0.12.1/test_query.2.0/197]
     *                           ├──LimitNode-172 (Notice: child StreamSort has been eliminated)
     *                           │   └──ProjectNode-150
     *                           │       └──DeviceTableScanNode-147
     *                           └──ExchangeNode-196: [SourceAddress:192.0.10.1/test_query.3.0/198]
     */
    distributionPlanner =
        new TableDistributedPlanner(
            analysis, symbolAllocator, logicalQueryPlan, TEST_MATADATA, null);
    distributedQueryPlan = distributionPlanner.plan();
    assertEquals(4, distributedQueryPlan.getFragments().size());
    IdentitySinkNode identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(0).getPlanNodeTree();
    outputNode = (OutputNode) getChildrenNode(identitySinkNode, 1);
    assertTrue(getChildrenNode(outputNode, 1) instanceof OffsetNode);
    assertTrue(getChildrenNode(outputNode, 2) instanceof TopKNode);
    assertTrue(getChildrenNode(outputNode, 3) instanceof ProjectNode);
    assertTrue(getChildrenNode(outputNode, 4) instanceof TopKNode);
    TopKNode topKNode = (TopKNode) getChildrenNode(outputNode, 4);
    assertTrue(topKNode.getChildren().get(0) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(1) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(2) instanceof ExchangeNode);

    LimitNode limitNode =
        (LimitNode)
            distributedQueryPlan.getFragments().get(2).getPlanNodeTree().getChildren().get(0);
    assertTrue(getChildrenNode(limitNode, 1) instanceof ProjectNode);
    assertTrue(getChildrenNode(limitNode, 2) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(limitNode, 2);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList(
            "table1.shenzhen.B1.XX",
            "table1.shenzhen.B2.ZZ",
            "table1.shanghai.B3.YY",
            "table1.shanghai.A3.YY"),
        ASC,
        3,
        0,
        true,
        "");
    /*
     * IdentitySinkNode-161
     *   └──LimitNode-136
     *           └──ProjectNode-117
     *               └──DeviceTableScanNode-114
     */
    identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(1).getPlanNodeTree();
    assertTrue(getChildrenNode(identitySinkNode, 1) instanceof LimitNode);
    assertTrue(getChildrenNode(identitySinkNode, 2) instanceof ProjectNode);
    assertTrue(getChildrenNode(identitySinkNode, 3) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(identitySinkNode, 3);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList("table1.shenzhen.B1.XX", "table1.shenzhen.B2.ZZ"),
        ASC,
        3,
        0,
        true,
        "");
  }

  @Test
  public void subQueryTest3() {
    // outer query has limit_1 and sort,
    // sub query has limit_2 and sort,
    // only the limit and sort in sub query can be pushed down into TableScan.
    sql =
        "SELECT time, tag2, attr2, CAST(add_s2 as double) "
            + "FROM (SELECT time, SUBSTRING(tag1, 1) as sub_tag1, tag2, attr2, s1, s2+1 as add_s2 FROM table1 WHERE s1>1 ORDER BY tag1 DESC limit 3) "
            + "ORDER BY s1,tag2 ASC OFFSET 5 LIMIT 10";
    analysis = analyzeSQL(sql, TEST_MATADATA, QUERY_CONTEXT);
    SymbolAllocator symbolAllocator = new SymbolAllocator();
    logicalQueryPlan =
        new TableLogicalPlanner(
                QUERY_CONTEXT, TEST_MATADATA, SESSION_INFO, symbolAllocator, DEFAULT_WARNING)
            .plan(analysis);
    logicalPlanNode = logicalQueryPlan.getRootNode();

    // LogicalPlan: `Output - Offset - ProjectNode - TopK - Limit - Project - StreamSort -
    // Project -
    // TableScan`
    assertTrue(logicalPlanNode instanceof OutputNode);
    assertTrue(getChildrenNode(logicalPlanNode, 1) instanceof OffsetNode);
    assertTrue(getChildrenNode(logicalPlanNode, 2) instanceof ProjectNode);
    assertTrue(getChildrenNode(logicalPlanNode, 3) instanceof TopKNode);
    assertTrue(getChildrenNode(logicalPlanNode, 4) instanceof LimitNode);
    assertTrue(getChildrenNode(logicalPlanNode, 5) instanceof ProjectNode);
    assertTrue(getChildrenNode(logicalPlanNode, 6) instanceof StreamSortNode);
    assertTrue(getChildrenNode(logicalPlanNode, 7) instanceof ProjectNode);
    assertTrue(getChildrenNode(logicalPlanNode, 8) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(logicalPlanNode, 8);
    assertEquals(3, deviceTableScanNode.getPushDownLimit());
    assertTrue(deviceTableScanNode.isPushLimitToEachDevice());
    assertEquals("(\"s1\" > 1)", deviceTableScanNode.getPushDownPredicate().toString());

    /*
     * IdentitySinkNode-199
     *   └──OutputNode-16
     *       └──OffsetNode-12
     *           └──TopKNode-13
     *                   └──ProjectNode-59
     *                       └──TopKNode-6
     *                           ├──ExchangeNode-195: [SourceAddress:192.0.12.1/test_query.2.0/197]
     *                           ├──LimitNode-172 (Notice: child StreamSort has been eliminated)
     *                           │   └──ProjectNode-150
     *                           │       └──DeviceTableScanNode-147
     *                           └──ExchangeNode-196: [SourceAddress:192.0.10.1/test_query.3.0/198]
     */
    // Before ExchangeNode optimize
    /*
     * IdentitySinkNode-205
     *   └──OutputNode-16
     *       └──OffsetNode-12
     *           └──ProjectNode-43
     *               └──TopKNode-13
     *                       └──ProjectNode-59
     *                           └──TopKNode-6
     *                               ├──ExchangeNode-201: [SourceAddress:192.0.12.1/test_query.2.0/203]
     *                               ├──LimitNode-177
     *                               │   └──ProjectNode-154
     *                               │       └──DeviceTableScanNode-151
     *                               └──ExchangeNode-202: [SourceAddress:192.0.10.1/test_query.3.0/204]
     */
    distributionPlanner =
        new TableDistributedPlanner(
            analysis, symbolAllocator, logicalQueryPlan, TEST_MATADATA, null);
    distributedQueryPlan = distributionPlanner.plan();
    assertEquals(4, distributedQueryPlan.getFragments().size());
    IdentitySinkNode identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(0).getPlanNodeTree();
    outputNode = (OutputNode) getChildrenNode(identitySinkNode, 1);
    assertTrue(getChildrenNode(outputNode, 1) instanceof OffsetNode);
    assertTrue(getChildrenNode(outputNode, 2) instanceof ProjectNode);
    assertTrue(getChildrenNode(outputNode, 3) instanceof TopKNode);
    assertTrue(getChildrenNode(outputNode, 4) instanceof ProjectNode);
    assertTrue(getChildrenNode(outputNode, 5) instanceof TopKNode);
    TopKNode topKNode = (TopKNode) getChildrenNode(outputNode, 5);
    assertTrue(topKNode.getChildren().get(0) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(1) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(2) instanceof ExchangeNode);

    LimitNode limitNode =
        (LimitNode)
            distributedQueryPlan.getFragments().get(2).getPlanNodeTree().getChildren().get(0);
    assertTrue(getChildrenNode(limitNode, 1) instanceof ProjectNode);
    assertTrue(getChildrenNode(limitNode, 2) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(limitNode, 2);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList(
            "table1.shenzhen.B1.XX",
            "table1.shenzhen.B2.ZZ",
            "table1.shanghai.B3.YY",
            "table1.shanghai.A3.YY"),
        ASC,
        3,
        0,
        true,
        "");
    /*
     * IdentitySinkNode-161
     *   └──LimitNode-136
     *           └──ProjectNode-117
     *               └──DeviceTableScanNode-114
     */
    identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(1).getPlanNodeTree();
    assertTrue(getChildrenNode(identitySinkNode, 1) instanceof LimitNode);
    assertTrue(getChildrenNode(identitySinkNode, 2) instanceof ProjectNode);
    assertTrue(getChildrenNode(identitySinkNode, 3) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(identitySinkNode, 3);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList("table1.shenzhen.B1.XX", "table1.shenzhen.B2.ZZ"),
        ASC,
        3,
        0,
        true,
        "");
  }

  @Test
  public void subQueryTest4() {
    // outer query has limit_1, sort and filter,
    // sub query has limit_2 and sort,
    // only the limit and sort in sub query can be pushed down into TableScan.
    sql =
        "SELECT time, tag2, attr2, CAST(add_s2 as double) "
            + "FROM (SELECT time, SUBSTRING(tag1, 1) as sub_tag1, tag2, attr2, s1, s2+1 as add_s2 FROM table1 "
            + "WHERE s1>1 ORDER BY tag1 DESC limit 3) "
            + "WHERE s1>1 ORDER BY s1,tag2 ASC OFFSET 5 LIMIT 10";
    analysis = analyzeSQL(sql, TEST_MATADATA, QUERY_CONTEXT);
    SymbolAllocator symbolAllocator = new SymbolAllocator();
    logicalQueryPlan =
        new TableLogicalPlanner(
                QUERY_CONTEXT, TEST_MATADATA, SESSION_INFO, symbolAllocator, DEFAULT_WARNING)
            .plan(analysis);
    logicalPlanNode = logicalQueryPlan.getRootNode();

    // LogicalPlan: `Output - Offset - ProjectNode - TopK - Project - Filter - Limit - Project -
    // StreamSort - Project -
    // TableScan`
    assertNodeMatches(
        logicalPlanNode,
        OutputNode.class,
        OffsetNode.class,
        ProjectNode.class,
        TopKNode.class,
        // Notice that, the filter in outer query can not be pushed down into subquery
        FilterNode.class,
        LimitNode.class,
        ProjectNode.class,
        StreamSortNode.class,
        ProjectNode.class,
        DeviceTableScanNode.class);
    assertEquals(
        "(\"s1\" > 1)",
        ((FilterNode) getChildrenNode(logicalPlanNode, 4)).getPredicate().toString());
    assertEquals(3, ((LimitNode) getChildrenNode(logicalPlanNode, 5)).getCount());
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(logicalPlanNode, 9);
    assertEquals(3, deviceTableScanNode.getPushDownLimit());
    assertTrue(deviceTableScanNode.isPushLimitToEachDevice());
    assertEquals("(\"s1\" > 1)", deviceTableScanNode.getPushDownPredicate().toString());

    /*
     * IdentitySinkNode-219
     *   └──OutputNode-17
     *       └──OffsetNode-13
     *           └──ProjectNode-45
     *               └──TopKNode-14
     *                       └──FilterNode-9
     *                           └──ProjectNode-65
     *                               └──TopKNode-6
     *                                   ├──ExchangeNode-215: [SourceAddress:192.0.12.1/test_query.2.0/217]
     *                                   ├──LimitNode-190
     *                                   │   └──ProjectNode-166
     *                                   │       └──DeviceTableScanNode-163
     *                                   └──ExchangeNode-216: [SourceAddress:192.0.10.1/test_query.3.0/218]
     */
    distributionPlanner =
        new TableDistributedPlanner(
            analysis, symbolAllocator, logicalQueryPlan, TEST_MATADATA, null);
    distributedQueryPlan = distributionPlanner.plan();
    assertEquals(4, distributedQueryPlan.getFragments().size());
    IdentitySinkNode identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(0).getPlanNodeTree();
    outputNode = (OutputNode) getChildrenNode(identitySinkNode, 1);
    assertNodeMatches(
        outputNode,
        OutputNode.class,
        OffsetNode.class,
        ProjectNode.class,
        TopKNode.class,
        FilterNode.class,
        ProjectNode.class,
        TopKNode.class);
    TopKNode topKNode = (TopKNode) getChildrenNode(outputNode, 6);
    assertTrue(topKNode.getChildren().get(0) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(1) instanceof ExchangeNode);
    assertTrue(topKNode.getChildren().get(2) instanceof ExchangeNode);

    LimitNode limitNode =
        (LimitNode)
            distributedQueryPlan.getFragments().get(2).getPlanNodeTree().getChildren().get(0);
    assertTrue(getChildrenNode(limitNode, 1) instanceof ProjectNode);
    assertTrue(getChildrenNode(limitNode, 2) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(limitNode, 2);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList(
            "table1.shenzhen.B1.XX",
            "table1.shenzhen.B2.ZZ",
            "table1.shanghai.B3.YY",
            "table1.shanghai.A3.YY"),
        ASC,
        3,
        0,
        true,
        "");
    /*
     * IdentitySinkNode-161
     *   └──LimitNode-136
     *           └──ProjectNode-117
     *               └──DeviceTableScanNode-114
     */
    identitySinkNode =
        (IdentitySinkNode) distributedQueryPlan.getFragments().get(1).getPlanNodeTree();
    assertTrue(getChildrenNode(identitySinkNode, 1) instanceof LimitNode);
    assertTrue(getChildrenNode(identitySinkNode, 2) instanceof ProjectNode);
    assertTrue(getChildrenNode(identitySinkNode, 3) instanceof DeviceTableScanNode);
    deviceTableScanNode = (DeviceTableScanNode) getChildrenNode(identitySinkNode, 3);
    assertTableScan(
        deviceTableScanNode,
        Arrays.asList("table1.shenzhen.B1.XX", "table1.shenzhen.B2.ZZ"),
        ASC,
        3,
        0,
        true,
        "");
  }

  // test MergeFilters
  @Test
  public void subQueryTest5() {
    sql = "SELECT * FROM (SELECT * FROM table1 WHERE s1>1) WHERE s2>2";
    analysis = analyzeSQL(sql, TEST_MATADATA, QUERY_CONTEXT);
    SymbolAllocator symbolAllocator = new SymbolAllocator();
    logicalPlanNode =
        new TableLogicalPlanner(
                QUERY_CONTEXT, TEST_MATADATA, SESSION_INFO, symbolAllocator, DEFAULT_WARNING)
            .plan(analysis)
            .getRootNode();
    assertNodeMatches(logicalPlanNode, OutputNode.class, DeviceTableScanNode.class);
    DeviceTableScanNode deviceTableScanNode =
        (DeviceTableScanNode) getChildrenNode(logicalPlanNode, 1);
    assertTableScan(
        deviceTableScanNode,
        ALL_DEVICE_ENTRIES,
        ASC,
        0,
        0,
        true,
        "((\"s1\" > 1) AND (\"s2\" > 2))");
  }

  // test MergeLimits
  @Test
  public void subQueryTest6() {
    sql = "SELECT * FROM (SELECT * FROM table1 limit 10) limit 5";
    analysis = analyzeSQL(sql, TEST_MATADATA, QUERY_CONTEXT);
    SymbolAllocator symbolAllocator = new SymbolAllocator();
    logicalPlanNode =
        new TableLogicalPlanner(
                QUERY_CONTEXT, TEST_MATADATA, SESSION_INFO, symbolAllocator, DEFAULT_WARNING)
            .plan(analysis)
            .getRootNode();
    assertNodeMatches(
        logicalPlanNode, OutputNode.class, LimitNode.class, DeviceTableScanNode.class);
    LimitNode limitNode = (LimitNode) getChildrenNode(logicalPlanNode, 1);
    assertEquals(5, limitNode.getCount());
    DeviceTableScanNode deviceTableScanNode =
        (DeviceTableScanNode) getChildrenNode(logicalPlanNode, 2);
    assertTableScan(deviceTableScanNode, ALL_DEVICE_ENTRIES, ASC, 5, 0, false);
  }

  @Test
  public void subQueryTest7() {
    PlanTester planTester = new PlanTester();
    assertPlan(
        planTester.createPlan("select count(*) from (select * from table1 order by tag1 limit 1)"),
        output(aggregation(project(topK(tableScan("testdb.table1"))))));

    assertPlan(
        planTester.getFragmentPlan(0),
        output(aggregation(project(topK(exchange(), exchange(), exchange())))));

    assertPlan(planTester.getFragmentPlan(1), tableScan("testdb.table1"));

    assertEquals(1, ((DeviceTableScanNode) planTester.getFragmentPlan(1)).getPushDownLimit());
  }
}
