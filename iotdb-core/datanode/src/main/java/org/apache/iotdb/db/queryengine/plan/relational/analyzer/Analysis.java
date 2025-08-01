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

import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.partition.DataPartition;
import org.apache.iotdb.commons.partition.SchemaPartition;
import org.apache.iotdb.commons.schema.table.InformationSchema;
import org.apache.iotdb.db.queryengine.common.MPPQueryContext;
import org.apache.iotdb.db.queryengine.common.header.DatasetHeader;
import org.apache.iotdb.db.queryengine.plan.analyze.IAnalysis;
import org.apache.iotdb.db.queryengine.plan.execution.memory.StatementMemorySource;
import org.apache.iotdb.db.queryengine.plan.execution.memory.TableModelStatementMemorySourceContext;
import org.apache.iotdb.db.queryengine.plan.execution.memory.TableModelStatementMemorySourceVisitor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.TimePredicate;
import org.apache.iotdb.db.queryengine.plan.relational.analyzer.PatternRecognitionAnalysis.PatternFunctionAnalysis;
import org.apache.iotdb.db.queryengine.plan.relational.analyzer.tablefunction.TableFunctionInvocationAnalysis;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.ColumnSchema;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.QualifiedObjectName;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.ResolvedFunction;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.TableSchema;
import org.apache.iotdb.db.queryengine.plan.relational.planner.Symbol;
import org.apache.iotdb.db.queryengine.plan.relational.security.AccessControl;
import org.apache.iotdb.db.queryengine.plan.relational.security.Identity;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.AllColumns;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.DataType;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.ExistsPredicate;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Expression;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.FieldReference;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Fill;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.FunctionCall;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Identifier;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.InPredicate;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Join;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Literal;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Node;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Offset;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.OrderBy;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Parameter;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.QualifiedName;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.QuantifiedComparisonExpression;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Query;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.QuerySpecification;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.RangeQuantifier;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Relation;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.RowPattern;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.ShowStatement;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Statement;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.SubqueryExpression;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.SubsetDefinition;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Table;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.TableFunctionInvocation;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.WindowFrame;
import org.apache.iotdb.db.queryengine.plan.statement.component.FillPolicy;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.google.errorprone.annotations.Immutable;
import org.apache.tsfile.read.common.block.TsBlock;
import org.apache.tsfile.read.common.type.Type;
import org.apache.tsfile.utils.TimeDuration;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

public class Analysis implements IAnalysis {

  private String databaseName;
  private List<TEndPoint> redirectNodeList;

  @Nullable private Statement root;

  private final Map<NodeRef<Parameter>, Expression> parameters;

  private String updateType;

  private final Map<NodeRef<Table>, Query> namedQueries = new LinkedHashMap<>();

  // map expandable query to the node being the inner recursive reference
  private final Map<NodeRef<Query>, Node> expandableNamedQueries = new LinkedHashMap<>();

  // map inner recursive reference in the expandable query to the recursion base scope
  private final Map<NodeRef<Node>, Scope> expandableBaseScopes = new LinkedHashMap<>();

  // Synthetic scope when a query does not have a FROM clause
  // We need to track this separately because there's no node we can attach it to.
  private final Map<NodeRef<QuerySpecification>, Scope> implicitFromScopes = new LinkedHashMap<>();
  private final Map<NodeRef<Node>, Scope> scopes = new LinkedHashMap<>();

  private final Map<NodeRef<Expression>, ResolvedField> columnReferences = new LinkedHashMap<>();

  // a map of users to the columns per table that they access
  private final Map<AccessControlInfo, Map<QualifiedObjectName, Set<String>>>
      tableColumnReferences = new LinkedHashMap<>();

  // Record fields prefixed with labels in row pattern recognition context
  private final Map<NodeRef<Expression>, Optional<String>> labels = new LinkedHashMap<>();
  private final Map<NodeRef<RangeQuantifier>, Range> ranges = new LinkedHashMap<>();
  private final Map<NodeRef<RowPattern>, Set<String>> undefinedLabels = new LinkedHashMap<>();

  // Pattern function analysis (classifier, match_number, aggregations and prev/next/first/last) in
  // the context of the given node
  private final Map<NodeRef<Expression>, List<PatternFunctionAnalysis>> patternFunctionAnalysis =
      new LinkedHashMap<>();

  // FunctionCall nodes corresponding to any of the special pattern recognition functions
  private final Set<NodeRef<FunctionCall>> patternRecognitionFunctionCalls = new LinkedHashSet<>();

  // FunctionCall nodes corresponding to any of the navigation functions (prev/next/first/last)
  private final Set<NodeRef<FunctionCall>> patternNavigationFunctions = new LinkedHashSet<>();

  private final Map<NodeRef<Identifier>, String> resolvedLabels = new LinkedHashMap<>();
  private final Map<NodeRef<SubsetDefinition>, Set<String>> subsets = new LinkedHashMap<>();

  private final Map<NodeRef<Fill>, FillAnalysis> fill = new LinkedHashMap<>();
  private final Map<NodeRef<Offset>, Long> offset = new LinkedHashMap<>();
  private final Map<NodeRef<Node>, OptionalLong> limit = new LinkedHashMap<>();
  private final Map<NodeRef<AllColumns>, List<Field>> selectAllResultFields = new LinkedHashMap<>();
  private boolean containsSelectDistinct;

  private final Map<NodeRef<Join>, Expression> joins = new LinkedHashMap<>();
  private final Map<NodeRef<Join>, JoinUsingAnalysis> joinUsing = new LinkedHashMap<>();
  private final Map<NodeRef<Node>, SubqueryAnalysis> subQueries = new LinkedHashMap<>();
  private final Map<NodeRef<Expression>, PredicateCoercions> predicateCoercions =
      new LinkedHashMap<>();

  private final Map<NodeRef<Table>, TableEntry> tables = new LinkedHashMap<>();

  private final Map<NodeRef<Expression>, Type> types = new LinkedHashMap<>();

  private final Map<NodeRef<Expression>, Type> coercions = new LinkedHashMap<>();
  private final Set<NodeRef<Expression>> typeOnlyCoercions = new LinkedHashSet<>();
  private final Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundCalculation =
      new LinkedHashMap<>();
  private final Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundComparison =
      new LinkedHashMap<>();
  private final Map<NodeRef<Expression>, ResolvedFunction> frameBoundCalculations =
      new LinkedHashMap<>();

  private final Map<NodeRef<Relation>, List<Type>> relationCoercions = new LinkedHashMap<>();
  private final Map<NodeRef<Node>, RoutineEntry> resolvedFunctions = new LinkedHashMap<>();

  private final Map<NodeRef<QuerySpecification>, List<FunctionCall>> aggregates =
      new LinkedHashMap<>();
  private final Map<NodeRef<OrderBy>, List<Expression>> orderByAggregates = new LinkedHashMap<>();
  private final Map<NodeRef<QuerySpecification>, GroupingSetAnalysis> groupingSets =
      new LinkedHashMap<>();

  private final Map<NodeRef<Node>, Expression> where = new LinkedHashMap<>();
  private final Map<NodeRef<QuerySpecification>, Expression> having = new LinkedHashMap<>();

  private final Map<NodeRef<QuerySpecification>, FunctionCall> gapFill = new LinkedHashMap<>();
  private final Map<NodeRef<QuerySpecification>, List<Expression>> gapFillGroupingKeys =
      new LinkedHashMap<>();

  private final Map<NodeRef<Node>, List<Expression>> orderByExpressions = new LinkedHashMap<>();
  private final Set<NodeRef<OrderBy>> redundantOrderBy = new HashSet<>();
  private final Map<NodeRef<Node>, List<SelectExpression>> selectExpressions =
      new LinkedHashMap<>();

  private final Multimap<Field, SourceColumn> originColumnDetails = ArrayListMultimap.create();

  private final Multimap<NodeRef<Expression>, Field> fieldLineage = ArrayListMultimap.create();

  private final Map<NodeRef<Relation>, QualifiedName> relationNames = new LinkedHashMap<>();

  private final Set<NodeRef<Relation>> aliasedRelations = new LinkedHashSet<>();

  private final Map<NodeRef<TableFunctionInvocation>, TableFunctionInvocationAnalysis>
      tableFunctionAnalyses = new LinkedHashMap<>();

  private final Map<QualifiedObjectName, Map<Symbol, ColumnSchema>> tableColumnSchemas =
      new HashMap<>();

  private final Map<
          NodeRef<QuerySpecification>, Map<CanonicalizationAware<Identifier>, ResolvedWindow>>
      windowDefinitions = new LinkedHashMap<>();
  private final Map<NodeRef<Node>, ResolvedWindow> windows = new LinkedHashMap<>();
  private final Map<NodeRef<QuerySpecification>, List<FunctionCall>> windowFunctions =
      new LinkedHashMap<>();
  private final Map<NodeRef<OrderBy>, List<FunctionCall>> orderByWindowFunctions =
      new LinkedHashMap<>();

  private Insert insert;

  private DataPartition dataPartition;

  // only be used in write plan and won't be used in query
  private SchemaPartition schemaPartition;

  private DatasetHeader respDatasetHeader;

  private boolean finishQueryAfterAnalyze;

  // indicate if value filter exists in query
  private boolean hasValueFilter = false;

  private TSStatus failStatus;

  // indicate if sort node exists in query
  private boolean hasSortNode = false;

  // if emptyDataSource, there is no need to execute the query in BE
  private boolean emptyDataSource = false;

  private boolean isQuery = false;

  public Analysis(@Nullable Statement root, Map<NodeRef<Parameter>, Expression> parameters) {
    this.root = root;
    this.parameters = ImmutableMap.copyOf(requireNonNull(parameters, "parameters is null"));
  }

  public Map<NodeRef<Parameter>, Expression> getParameters() {
    return parameters;
  }

  public Statement getStatement() {
    requireNonNull(root);
    return root;
  }

  public String getUpdateType() {
    return updateType;
  }

  public void setUpdateType(String updateType) {
    this.updateType = updateType;
  }

  public Query getNamedQuery(Table table) {
    return namedQueries.get(NodeRef.of(table));
  }

  public boolean isAnalyzed(Expression expression) {
    return expression instanceof DataType || types.containsKey(NodeRef.of(expression));
  }

  public void registerNamedQuery(Table tableReference, Query query) {
    requireNonNull(tableReference, "tableReference is null");
    requireNonNull(query, "query is null");

    namedQueries.put(NodeRef.of(tableReference), query);
  }

  public void registerExpandableQuery(Query query, Node recursiveReference) {
    requireNonNull(query, "query is null");
    requireNonNull(recursiveReference, "recursiveReference is null");

    expandableNamedQueries.put(NodeRef.of(query), recursiveReference);
  }

  public boolean isExpandableQuery(Query query) {
    return expandableNamedQueries.containsKey(NodeRef.of(query));
  }

  public Node getRecursiveReference(Query query) {
    checkArgument(isExpandableQuery(query), "query is not registered as expandable");
    return expandableNamedQueries.get(NodeRef.of(query));
  }

  public void setExpandableBaseScope(Node node, Scope scope) {
    expandableBaseScopes.put(NodeRef.of(node), scope);
  }

  public Optional<Scope> getExpandableBaseScope(Node node) {
    return Optional.ofNullable(expandableBaseScopes.get(NodeRef.of(node)));
  }

  public Scope getScope(Node node) {
    return tryGetScope(node)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    format("Analysis does not contain information for node: %s", node)));
  }

  public void setImplicitFromScope(QuerySpecification node, Scope scope) {
    implicitFromScopes.put(NodeRef.of(node), scope);
  }

  public Scope getImplicitFromScope(QuerySpecification node) {
    return implicitFromScopes.get(NodeRef.of(node));
  }

  public Optional<Scope> tryGetScope(Node node) {
    NodeRef<Node> key = NodeRef.of(node);
    if (scopes.containsKey(key)) {
      return Optional.of(scopes.get(key));
    }

    return Optional.empty();
  }

  public Scope getRootScope() {
    return getScope(root);
  }

  public void setStatement(Statement statement) {
    this.root = statement;
  }

  public void setScope(Node node, Scope scope) {
    scopes.put(NodeRef.of(node), scope);
  }

  public Set<ResolvedFunction> getResolvedFunctions() {
    return resolvedFunctions.values().stream()
        .map(RoutineEntry::getFunction)
        .collect(toImmutableSet());
  }

  public ResolvedFunction getResolvedFunction(Node node) {
    return resolvedFunctions.get(NodeRef.of(node)).getFunction();
  }

  public void addResolvedFunction(Node node, ResolvedFunction function, String authorization) {
    resolvedFunctions.put(NodeRef.of(node), new RoutineEntry(function, authorization));
  }

  public void addColumnReferences(Map<NodeRef<Expression>, ResolvedField> columnReferences) {
    this.columnReferences.putAll(columnReferences);
  }

  public Set<NodeRef<Expression>> getColumnReferences() {
    return unmodifiableSet(columnReferences.keySet());
  }

  public Map<NodeRef<Expression>, ResolvedField> getColumnReferenceFields() {
    return unmodifiableMap(columnReferences);
  }

  public void setAggregates(QuerySpecification node, List<FunctionCall> aggregates) {
    this.aggregates.put(NodeRef.of(node), ImmutableList.copyOf(aggregates));
  }

  public List<FunctionCall> getAggregates(QuerySpecification query) {
    return aggregates.get(NodeRef.of(query));
  }

  public boolean noAggregates() {
    return aggregates.isEmpty()
        || (aggregates.size() == 1 && aggregates.entrySet().iterator().next().getValue().isEmpty());
  }

  public void setOrderByAggregates(OrderBy node, List<Expression> aggregates) {
    this.orderByAggregates.put(NodeRef.of(node), ImmutableList.copyOf(aggregates));
  }

  public List<Expression> getOrderByAggregates(OrderBy node) {
    return orderByAggregates.get(NodeRef.of(node));
  }

  public Map<NodeRef<Expression>, Type> getTypes() {
    return unmodifiableMap(types);
  }

  public Type getType(Expression expression) {
    Type type = types.get(NodeRef.of(expression));
    checkArgument(type != null, "Expression not analyzed: %s", expression);
    return type;
  }

  public void addType(Expression expression, Type type) {
    this.types.put(NodeRef.of(expression), type);
  }

  public void addTypes(Map<NodeRef<Expression>, Type> types) {
    this.types.putAll(types);
  }

  public List<Type> getRelationCoercion(Relation relation) {
    return relationCoercions.get(NodeRef.of(relation));
  }

  public void addRelationCoercion(Relation relation, Type[] types) {
    relationCoercions.put(NodeRef.of(relation), ImmutableList.copyOf(types));
  }

  public Map<NodeRef<Expression>, Type> getCoercions() {
    return unmodifiableMap(coercions);
  }

  public Type getCoercion(Expression expression) {
    return coercions.get(NodeRef.of(expression));
  }

  public void addCoercion(Expression expression, Type type, boolean isTypeOnlyCoercion) {
    this.coercions.put(NodeRef.of(expression), type);
    if (isTypeOnlyCoercion) {
      this.typeOnlyCoercions.add(NodeRef.of(expression));
    }
  }

  public void addCoercions(
      Map<NodeRef<Expression>, Type> coercions,
      Set<NodeRef<Expression>> typeOnlyCoercions,
      Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundCalculation,
      Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundComparison) {
    this.coercions.putAll(coercions);
    this.typeOnlyCoercions.addAll(typeOnlyCoercions);
    this.sortKeyCoercionsForFrameBoundCalculation.putAll(sortKeyCoercionsForFrameBoundCalculation);
    this.sortKeyCoercionsForFrameBoundComparison.putAll(sortKeyCoercionsForFrameBoundComparison);
  }

  public Type getSortKeyCoercionForFrameBoundCalculation(Expression frameOffset) {
    return sortKeyCoercionsForFrameBoundCalculation.get(NodeRef.of(frameOffset));
  }

  public Type getSortKeyCoercionForFrameBoundComparison(Expression frameOffset) {
    return sortKeyCoercionsForFrameBoundComparison.get(NodeRef.of(frameOffset));
  }

  public void addFrameBoundCalculations(
      Map<NodeRef<Expression>, ResolvedFunction> frameBoundCalculations) {
    this.frameBoundCalculations.putAll(frameBoundCalculations);
  }

  public ResolvedFunction getFrameBoundCalculation(Expression frameOffset) {
    return frameBoundCalculations.get(NodeRef.of(frameOffset));
  }

  public Set<NodeRef<Expression>> getTypeOnlyCoercions() {
    return unmodifiableSet(typeOnlyCoercions);
  }

  public boolean isTypeOnlyCoercion(Expression expression) {
    return typeOnlyCoercions.contains(NodeRef.of(expression));
  }

  public void setGroupingSets(QuerySpecification node, GroupingSetAnalysis groupingSets) {
    this.groupingSets.put(NodeRef.of(node), groupingSets);
  }

  public boolean isAggregation(QuerySpecification node) {
    return groupingSets.containsKey(NodeRef.of(node));
  }

  public boolean containsAggregationQuery() {
    return !groupingSets.isEmpty() || containsSelectDistinct;
  }

  public GroupingSetAnalysis getGroupingSets(QuerySpecification node) {
    return groupingSets.get(NodeRef.of(node));
  }

  public void setWhere(Node node, Expression expression) {
    where.put(NodeRef.of(node), expression);
  }

  public Expression getWhere(Node node) {
    return where.get(NodeRef.of(node));
  }

  public Map<NodeRef<Node>, Expression> getWhereMap() {
    return this.where;
  }

  public void setOrderByExpressions(Node node, List<Expression> items) {
    orderByExpressions.put(NodeRef.of(node), ImmutableList.copyOf(items));
  }

  public List<Expression> getOrderByExpressions(Node node) {
    return orderByExpressions.get(NodeRef.of(node));
  }

  public void markRedundantOrderBy(OrderBy orderBy) {
    redundantOrderBy.add(NodeRef.of(orderBy));
  }

  public boolean isOrderByRedundant(OrderBy orderBy) {
    return redundantOrderBy.contains(NodeRef.of(orderBy));
  }

  public void setFill(Fill node, FillAnalysis fillAnalysis) {
    fill.put(NodeRef.of(node), fillAnalysis);
  }

  public FillAnalysis getFill(Fill node) {
    checkState(fill.containsKey(NodeRef.of(node)), "missing FillAnalysis for node %s", node);
    return fill.get(NodeRef.of(node));
  }

  public void setOffset(Offset node, long rowCount) {
    offset.put(NodeRef.of(node), rowCount);
  }

  public long getOffset(Offset node) {
    checkState(offset.containsKey(NodeRef.of(node)), "missing OFFSET value for node %s", node);
    return offset.get(NodeRef.of(node));
  }

  public void setLimit(Node node, OptionalLong rowCount) {
    limit.put(NodeRef.of(node), rowCount);
  }

  public void setLimit(Node node, long rowCount) {
    limit.put(NodeRef.of(node), OptionalLong.of(rowCount));
  }

  public OptionalLong getLimit(Node node) {
    checkState(limit.containsKey(NodeRef.of(node)), "missing LIMIT value for node %s", node);
    return limit.get(NodeRef.of(node));
  }

  public void setSelectAllResultFields(AllColumns node, List<Field> expressions) {
    selectAllResultFields.put(NodeRef.of(node), ImmutableList.copyOf(expressions));
  }

  public List<Field> getSelectAllResultFields(AllColumns node) {
    return selectAllResultFields.get(NodeRef.of(node));
  }

  public void setSelectExpressions(Node node, List<SelectExpression> expressions) {
    selectExpressions.put(NodeRef.of(node), ImmutableList.copyOf(expressions));
  }

  public List<SelectExpression> getSelectExpressions(Node node) {
    return selectExpressions.get(NodeRef.of(node));
  }

  public void setContainsSelectDistinct() {
    this.containsSelectDistinct = true;
  }

  public void setHaving(QuerySpecification node, Expression expression) {
    having.put(NodeRef.of(node), expression);
  }

  public Expression getHaving(QuerySpecification query) {
    return having.get(NodeRef.of(query));
  }

  public void setGapFill(QuerySpecification node, FunctionCall dateBinGapFill) {
    gapFill.put(NodeRef.of(node), dateBinGapFill);
  }

  public FunctionCall getGapFill(QuerySpecification query) {
    return gapFill.get(NodeRef.of(query));
  }

  public void setGapFillGroupingKeys(
      QuerySpecification node, List<Expression> gaoFillGroupingKeys) {
    gapFillGroupingKeys.put(NodeRef.of(node), gaoFillGroupingKeys);
  }

  public List<Expression> getGapFillGroupingKeys(QuerySpecification query) {
    return gapFillGroupingKeys.get(NodeRef.of(query));
  }

  public void setJoinUsing(Join node, JoinUsingAnalysis analysis) {
    joinUsing.put(NodeRef.of(node), analysis);
  }

  public JoinUsingAnalysis getJoinUsing(Join node) {
    return joinUsing.get(NodeRef.of(node));
  }

  public void setJoinCriteria(Join node, Expression criteria) {
    joins.put(NodeRef.of(node), criteria);
  }

  public Expression getJoinCriteria(Join join) {
    return joins.get(NodeRef.of(join));
  }

  public boolean hasJoinNode() {
    return !joinUsing.isEmpty() || !joins.isEmpty();
  }

  public void recordSubqueries(Node node, ExpressionAnalysis expressionAnalysis) {
    SubqueryAnalysis subqueries =
        this.subQueries.computeIfAbsent(NodeRef.of(node), key -> new SubqueryAnalysis());
    subqueries.addInPredicates(dereference(expressionAnalysis.getSubqueryInPredicates()));
    subqueries.addSubqueries(dereference(expressionAnalysis.getSubqueries()));
    subqueries.addExistsSubqueries(dereference(expressionAnalysis.getExistsSubqueries()));
    subqueries.addQuantifiedComparisons(dereference(expressionAnalysis.getQuantifiedComparisons()));
  }

  private <T extends Node> List<T> dereference(Collection<NodeRef<T>> nodeRefs) {
    if (nodeRefs.isEmpty()) {
      return Collections.emptyList();
    }
    return nodeRefs.stream().map(NodeRef::getNode).collect(toImmutableList());
  }

  public SubqueryAnalysis getSubqueries(Node node) {
    return subQueries.computeIfAbsent(NodeRef.of(node), key -> new SubqueryAnalysis());
  }

  public TableSchema getTableHandle(Table table) {
    return tables
        .get(NodeRef.of(table))
        .getHandle()
        .orElseThrow(
            () -> new IllegalArgumentException(format("%s is not a table reference", table)));
  }

  public Collection<TableSchema> getTables() {
    return tables.values().stream()
        .map(TableEntry::getHandle)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(toImmutableList());
  }

  public void registerTable(Table table, Optional<TableSchema> handle, QualifiedObjectName name) {
    tables.put(NodeRef.of(table), new TableEntry(handle, name));
  }

  public ResolvedField getResolvedField(Expression expression) {
    checkArgument(
        isColumnReference(expression), "Expression is not a column reference: %s", expression);
    return columnReferences.get(NodeRef.of(expression));
  }

  public boolean isColumnReference(Expression expression) {
    requireNonNull(expression, "expression is null");
    return columnReferences.containsKey(NodeRef.of(expression));
  }

  public Set<String> getUsedColumns(QualifiedObjectName tableName) {
    for (Map<QualifiedObjectName, Set<String>> map : tableColumnReferences.values()) {
      Set<String> fields = map.get(tableName);
      if (fields != null) {
        return fields;
      }
    }
    return Collections.emptySet();
  }

  public void addTableColumnReferences(
      AccessControl accessControl,
      Identity identity,
      Multimap<QualifiedObjectName, String> tableColumnMap) {
    AccessControlInfo accessControlInfo = new AccessControlInfo(accessControl, identity);
    Map<QualifiedObjectName, Set<String>> references =
        tableColumnReferences.computeIfAbsent(accessControlInfo, k -> new LinkedHashMap<>());
    tableColumnMap
        .asMap()
        .forEach(
            (key, value) -> references.computeIfAbsent(key, k -> new HashSet<>()).addAll(value));
  }

  public void addEmptyColumnReferencesForTable(
      AccessControl accessControl, Identity identity, QualifiedObjectName table) {
    AccessControlInfo accessControlInfo = new AccessControlInfo(accessControl, identity);
    tableColumnReferences
        .computeIfAbsent(accessControlInfo, k -> new LinkedHashMap<>())
        .computeIfAbsent(table, k -> new HashSet<>());
  }

  public Map<AccessControlInfo, Map<QualifiedObjectName, Set<String>>> getTableColumnReferences() {
    return tableColumnReferences;
  }

  public void addLabels(Map<NodeRef<Expression>, Optional<String>> labels) {
    this.labels.putAll(labels);
  }

  public Optional<String> getLabel(Expression expression) {
    return labels.get(NodeRef.of(expression));
  }

  public void setRanges(Map<NodeRef<RangeQuantifier>, Range> quantifierRanges) {
    ranges.putAll(quantifierRanges);
  }

  public Range getRange(RangeQuantifier quantifier) {
    Range range = ranges.get(NodeRef.of(quantifier));
    checkNotNull(range, "missing range for quantifier %s", quantifier);
    return range;
  }

  public void setUndefinedLabels(RowPattern pattern, Set<String> labels) {
    undefinedLabels.put(NodeRef.of(pattern), labels);
  }

  public void setUndefinedLabels(Map<NodeRef<RowPattern>, Set<String>> labels) {
    undefinedLabels.putAll(labels);
  }

  public Set<String> getUndefinedLabels(RowPattern pattern) {
    Set<String> labels = undefinedLabels.get(NodeRef.of(pattern));
    checkNotNull(labels, "missing undefined labels for %s", pattern);
    return labels;
  }

  public void addPatternRecognitionInputs(
      Map<NodeRef<Expression>, List<PatternFunctionAnalysis>> functions) {
    patternFunctionAnalysis.putAll(functions);

    functions.values().stream()
        .flatMap(List::stream)
        .map(PatternFunctionAnalysis::getExpression)
        .filter(FunctionCall.class::isInstance)
        .map(FunctionCall.class::cast)
        .map(NodeRef::of)
        .forEach(patternRecognitionFunctionCalls::add);
  }

  public List<PatternFunctionAnalysis> getPatternInputsAnalysis(Expression expression) {
    return patternFunctionAnalysis.get(NodeRef.of(expression));
  }

  public void addPatternNavigationFunctions(Set<NodeRef<FunctionCall>> functions) {
    patternNavigationFunctions.addAll(functions);
  }

  public boolean isPatternRecognitionFunction(FunctionCall functionCall) {
    return patternRecognitionFunctionCalls.contains(NodeRef.of(functionCall));
  }

  public boolean isPatternNavigationFunction(FunctionCall node) {
    return patternNavigationFunctions.contains(NodeRef.of(node));
  }

  public void addResolvedLabel(Identifier label, String resolved) {
    resolvedLabels.put(NodeRef.of(label), resolved);
  }

  public void addResolvedLabels(Map<NodeRef<Identifier>, String> labels) {
    resolvedLabels.putAll(labels);
  }

  public String getResolvedLabel(Identifier identifier) {
    return resolvedLabels.get(NodeRef.of(identifier));
  }

  public void addSubsetLabels(SubsetDefinition subset, Set<String> labels) {
    subsets.put(NodeRef.of(subset), labels);
  }

  public void addSubsetLabels(Map<NodeRef<SubsetDefinition>, Set<String>> subsets) {
    this.subsets.putAll(subsets);
  }

  public Set<String> getSubsetLabels(SubsetDefinition subset) {
    return subsets.get(NodeRef.of(subset));
  }

  public RelationType getOutputDescriptor() {
    return getOutputDescriptor(root);
  }

  public RelationType getOutputDescriptor(final Node node) {
    return getScope(node).getRelationType();
  }

  public void addSourceColumns(final Field field, final Set<SourceColumn> sourceColumn) {
    originColumnDetails.putAll(field, sourceColumn);
  }

  public Set<SourceColumn> getSourceColumns(final Field field) {
    return ImmutableSet.copyOf(originColumnDetails.get(field));
  }

  public void addExpressionFields(final Expression expression, final Collection<Field> fields) {
    fieldLineage.putAll(NodeRef.of(expression), fields);
  }

  public Set<SourceColumn> getExpressionSourceColumns(final Expression expression) {
    return fieldLineage.get(NodeRef.of(expression)).stream()
        .flatMap(field -> getSourceColumns(field).stream())
        .collect(toImmutableSet());
  }

  public void setRelationName(final Relation relation, final QualifiedName name) {
    relationNames.put(NodeRef.of(relation), name);
  }

  public QualifiedName getRelationName(final Relation relation) {
    return relationNames.get(NodeRef.of(relation));
  }

  public void addAliased(final Relation relation) {
    aliasedRelations.add(NodeRef.of(relation));
  }

  public boolean isAliased(Relation relation) {
    return aliasedRelations.contains(NodeRef.of(relation));
  }

  public void addTableSchema(
      final QualifiedObjectName qualifiedObjectName,
      final Map<Symbol, ColumnSchema> tableColumnSchema) {
    tableColumnSchemas.put(qualifiedObjectName, tableColumnSchema);
  }

  public Map<Symbol, ColumnSchema> getTableColumnSchema(
      final QualifiedObjectName qualifiedObjectName) {
    return tableColumnSchemas.get(qualifiedObjectName);
  }

  public void addPredicateCoercions(final Map<NodeRef<Expression>, PredicateCoercions> coercions) {
    predicateCoercions.putAll(coercions);
  }

  public PredicateCoercions getPredicateCoercions(final Expression expression) {
    return predicateCoercions.get(NodeRef.of(expression));
  }

  public boolean hasValueFilter() {
    return hasValueFilter;
  }

  public void setValueFilter(boolean hasValueFilter) {
    this.hasValueFilter = hasValueFilter;
  }

  public boolean hasSortNode() {
    return hasSortNode;
  }

  public void setSortNode(final boolean hasSortNode) {
    this.hasSortNode = hasSortNode;
  }

  public boolean isEmptyDataSource() {
    return emptyDataSource;
  }

  public void setEmptyDataSource(final boolean emptyDataSource) {
    this.emptyDataSource = emptyDataSource;
  }

  @Override
  public boolean isFailed() {
    return failStatus != null;
  }

  @Override
  public TSStatus getFailStatus() {
    return failStatus;
  }

  @Override
  public void setFailStatus(final TSStatus failStatus) {
    this.failStatus = failStatus;
  }

  @Override
  public boolean canSkipExecute(final MPPQueryContext context) {
    return isFinishQueryAfterAnalyze();
  }

  public void setFinishQueryAfterAnalyze() {
    this.finishQueryAfterAnalyze = true;
  }

  @Override
  public void setFinishQueryAfterAnalyze(final boolean finishQueryAfterAnalyze) {
    this.finishQueryAfterAnalyze = finishQueryAfterAnalyze;
  }

  public boolean isFinishQueryAfterAnalyze() {
    return finishQueryAfterAnalyze;
  }

  @Override
  public void setDataPartitionInfo(final DataPartition dataPartition) {
    this.dataPartition = dataPartition;
  }

  @Override
  public TsBlock constructResultForMemorySource(final MPPQueryContext context) {
    requireNonNull(getStatement(), "root statement is analysis is null");
    final StatementMemorySource memorySource =
        new TableModelStatementMemorySourceVisitor()
            .process(getStatement(), new TableModelStatementMemorySourceContext(context, this));
    setRespDatasetHeader(memorySource.getDatasetHeader());
    return memorySource.getTsBlock();
  }

  @Override
  public boolean isQuery() {
    return isQuery;
  }

  public void setQuery(boolean query) {
    isQuery = query;
  }

  @Override
  public boolean needSetHighestPriority() {
    return root instanceof ShowStatement
        && ((ShowStatement) root).getTableName().equals(InformationSchema.QUERIES);
  }

  @Override
  public DatasetHeader getRespDatasetHeader() {
    return respDatasetHeader;
  }

  public void setRespDatasetHeader(DatasetHeader respDatasetHeader) {
    this.respDatasetHeader = respDatasetHeader;
  }

  @Override
  public String getStatementType() {
    return null;
  }

  @Override
  public SchemaPartition getSchemaPartitionInfo() {
    return schemaPartition;
  }

  @Override
  public void setSchemaPartitionInfo(SchemaPartition schemaPartition) {
    this.schemaPartition = schemaPartition;
  }

  @Override
  public DataPartition getDataPartitionInfo() {
    return dataPartition;
  }

  public void setDataPartition(final DataPartition dataPartition) {
    this.dataPartition = dataPartition;
  }

  public void upsertDataPartition(final DataPartition targetDataPartition) {
    if (this.dataPartition == null) {
      this.dataPartition = targetDataPartition;
    } else {
      this.dataPartition.upsertDataPartition(targetDataPartition);
    }
  }

  @Override
  public List<TEndPoint> getRedirectNodeList() {
    return redirectNodeList;
  }

  @Override
  public void setRedirectNodeList(final List<TEndPoint> redirectNodeList) {
    this.redirectNodeList = redirectNodeList;
  }

  @Override
  public void addEndPointToRedirectNodeList(final TEndPoint endPoint) {
    if (redirectNodeList == null) {
      redirectNodeList = new ArrayList<>();
    }
    redirectNodeList.add(endPoint);
  }

  public void setTableFunctionAnalysis(
      TableFunctionInvocation node, TableFunctionInvocationAnalysis analysis) {
    tableFunctionAnalyses.put(NodeRef.of(node), analysis);
  }

  public TableFunctionInvocationAnalysis getTableFunctionAnalysis(TableFunctionInvocation node) {
    return tableFunctionAnalyses.get(NodeRef.of(node));
  }

  @Override
  public TimePredicate getConvertedTimePredicate() {
    return null;
  }

  public static final class AccessControlInfo {
    private final AccessControl accessControl;
    private final Identity identity;

    public AccessControlInfo(AccessControl accessControl, Identity identity) {
      this.accessControl = requireNonNull(accessControl, "accessControl is null");
      this.identity = requireNonNull(identity, "identity is null");
    }

    public AccessControl getAccessControl() {
      return accessControl;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      AccessControlInfo that = (AccessControlInfo) o;
      return Objects.equals(accessControl, that.accessControl)
          && Objects.equals(identity, that.identity);
    }

    @Override
    public int hashCode() {
      return Objects.hash(accessControl, identity);
    }

    @Override
    public String toString() {
      return format("AccessControl: %s, Identity: %s", accessControl.getClass(), identity);
    }
  }

  private static class TableEntry {
    private final Optional<TableSchema> handle;
    private final QualifiedObjectName name;

    public TableEntry(Optional<TableSchema> handle, QualifiedObjectName name) {
      this.handle = requireNonNull(handle, "handle is null");
      this.name = requireNonNull(name, "name is null");
    }

    public Optional<TableSchema> getHandle() {
      return handle;
    }

    public QualifiedObjectName getName() {
      return name;
    }
  }

  public static class SourceColumn {
    private final QualifiedObjectName tableName;
    private final String columnName;

    public SourceColumn(QualifiedObjectName tableName, String columnName) {
      this.tableName = requireNonNull(tableName, "tableName is null");
      this.columnName = requireNonNull(columnName, "columnName is null");
    }

    public QualifiedObjectName getTableName() {
      return tableName;
    }

    public String getColumnName() {
      return columnName;
    }

    @Override
    public int hashCode() {
      return Objects.hash(tableName, columnName);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if ((obj == null) || (getClass() != obj.getClass())) {
        return false;
      }
      SourceColumn entry = (SourceColumn) obj;
      return Objects.equals(tableName, entry.tableName)
          && Objects.equals(columnName, entry.columnName);
    }

    @Override
    public String toString() {
      return toStringHelper(this)
          .add("tableName", tableName)
          .add("columnName", columnName)
          .toString();
    }
  }

  @Immutable
  public static final class SelectExpression {
    // expression refers to a select item, either to be returned directly, or unfolded by all-fields
    // reference
    // unfoldedExpressions applies to the latter case, and is a list of subscript expressions
    // referencing each field of the row.
    private final Expression expression;
    private final Optional<List<Expression>> unfoldedExpressions;

    public SelectExpression(Expression expression, Optional<List<Expression>> unfoldedExpressions) {
      this.expression = requireNonNull(expression, "expression is null");
      this.unfoldedExpressions = requireNonNull(unfoldedExpressions);
    }

    public Expression getExpression() {
      return expression;
    }

    public Optional<List<Expression>> getUnfoldedExpressions() {
      return unfoldedExpressions;
    }
  }

  public static final class JoinUsingAnalysis {
    private final List<Integer> leftJoinFields;
    private final List<Integer> rightJoinFields;
    private final List<Integer> otherLeftFields;
    private final List<Integer> otherRightFields;

    JoinUsingAnalysis(
        List<Integer> leftJoinFields,
        List<Integer> rightJoinFields,
        List<Integer> otherLeftFields,
        List<Integer> otherRightFields) {
      this.leftJoinFields = ImmutableList.copyOf(leftJoinFields);
      this.rightJoinFields = ImmutableList.copyOf(rightJoinFields);
      this.otherLeftFields = ImmutableList.copyOf(otherLeftFields);
      this.otherRightFields = ImmutableList.copyOf(otherRightFields);

      checkArgument(
          leftJoinFields.size() == rightJoinFields.size(),
          "Expected join fields for left and right to have the same size");
    }

    public List<Integer> getLeftJoinFields() {
      return leftJoinFields;
    }

    public List<Integer> getRightJoinFields() {
      return rightJoinFields;
    }

    public List<Integer> getOtherLeftFields() {
      return otherLeftFields;
    }

    public List<Integer> getOtherRightFields() {
      return otherRightFields;
    }
  }

  public static class GroupingSetAnalysis {
    private final List<Expression> originalExpressions;

    private final List<List<Set<FieldId>>> cubes;
    private final List<List<Set<FieldId>>> rollups;
    private final List<List<Set<FieldId>>> ordinarySets;
    private final List<Expression> complexExpressions;

    public GroupingSetAnalysis(
        List<Expression> originalExpressions,
        List<List<Set<FieldId>>> cubes,
        List<List<Set<FieldId>>> rollups,
        List<List<Set<FieldId>>> ordinarySets,
        List<Expression> complexExpressions) {
      this.originalExpressions = ImmutableList.copyOf(originalExpressions);
      this.cubes = ImmutableList.copyOf(cubes);
      this.rollups = ImmutableList.copyOf(rollups);
      this.ordinarySets = ImmutableList.copyOf(ordinarySets);
      this.complexExpressions = ImmutableList.copyOf(complexExpressions);
    }

    public List<Expression> getOriginalExpressions() {
      return originalExpressions;
    }

    public List<List<Set<FieldId>>> getCubes() {
      return cubes;
    }

    public List<List<Set<FieldId>>> getRollups() {
      return rollups;
    }

    public List<List<Set<FieldId>>> getOrdinarySets() {
      return ordinarySets;
    }

    public List<Expression> getComplexExpressions() {
      return complexExpressions;
    }

    public Set<FieldId> getAllFields() {
      return Streams.concat(
              cubes.stream().flatMap(Collection::stream).flatMap(Collection::stream),
              rollups.stream().flatMap(Collection::stream).flatMap(Collection::stream),
              ordinarySets.stream().flatMap(Collection::stream).flatMap(Collection::stream))
          .collect(toImmutableSet());
    }
  }

  private static class RoutineEntry {
    private final ResolvedFunction function;
    private final String authorization;

    public RoutineEntry(ResolvedFunction function, String authorization) {
      this.function = requireNonNull(function, "function is null");
      this.authorization = requireNonNull(authorization, "authorization is null");
    }

    public ResolvedFunction getFunction() {
      return function;
    }

    public String getAuthorization() {
      return authorization;
    }
  }

  public static class SubqueryAnalysis {
    private final List<InPredicate> inPredicatesSubqueries = new ArrayList<>();
    private final List<SubqueryExpression> subqueries = new ArrayList<>();
    private final List<ExistsPredicate> existsSubqueries = new ArrayList<>();
    private final List<QuantifiedComparisonExpression> quantifiedComparisonSubqueries =
        new ArrayList<>();

    public void addInPredicates(List<InPredicate> expressions) {
      inPredicatesSubqueries.addAll(expressions);
    }

    public void addSubqueries(List<SubqueryExpression> expressions) {
      subqueries.addAll(expressions);
    }

    public void addExistsSubqueries(List<ExistsPredicate> expressions) {
      existsSubqueries.addAll(expressions);
    }

    public void addQuantifiedComparisons(List<QuantifiedComparisonExpression> expressions) {
      quantifiedComparisonSubqueries.addAll(expressions);
    }

    public List<InPredicate> getInPredicatesSubqueries() {
      return unmodifiableList(inPredicatesSubqueries);
    }

    public List<SubqueryExpression> getSubqueries() {
      return unmodifiableList(subqueries);
    }

    public List<ExistsPredicate> getExistsSubqueries() {
      return unmodifiableList(existsSubqueries);
    }

    public List<QuantifiedComparisonExpression> getQuantifiedComparisonSubqueries() {
      return unmodifiableList(quantifiedComparisonSubqueries);
    }
  }

  /**
   * Analysis for predicates such as <code>x IN (subquery)</code> or <code>x = SOME (subquery)
   * </code>
   */
  public static class PredicateCoercions {
    private final Type valueType;
    private final Optional<Type> valueCoercion;
    private final Optional<Type> subqueryCoercion;

    public PredicateCoercions(
        Type valueType, Optional<Type> valueCoercion, Optional<Type> subqueryCoercion) {
      this.valueType = requireNonNull(valueType, "valueType is null");
      this.valueCoercion = requireNonNull(valueCoercion, "valueCoercion is null");
      this.subqueryCoercion = requireNonNull(subqueryCoercion, "subqueryCoercion is null");
    }

    public Type getValueType() {
      return valueType;
    }

    public Optional<Type> getValueCoercion() {
      return valueCoercion;
    }

    public Optional<Type> getSubqueryCoercion() {
      return subqueryCoercion;
    }
  }

  public void addWindowDefinition(
      QuerySpecification query, CanonicalizationAware<Identifier> name, ResolvedWindow window) {
    windowDefinitions
        .computeIfAbsent(NodeRef.of(query), key -> new LinkedHashMap<>())
        .put(name, window);
  }

  public ResolvedWindow getWindowDefinition(
      QuerySpecification query, CanonicalizationAware<Identifier> name) {
    Map<CanonicalizationAware<Identifier>, ResolvedWindow> windows =
        windowDefinitions.get(NodeRef.of(query));
    if (windows != null) {
      return windows.get(name);
    }

    return null;
  }

  public void setWindow(Node node, ResolvedWindow window) {
    windows.put(NodeRef.of(node), window);
  }

  public ResolvedWindow getWindow(Node node) {
    return windows.get(NodeRef.of(node));
  }

  public void setWindowFunctions(QuerySpecification node, List<FunctionCall> functions) {
    windowFunctions.put(NodeRef.of(node), ImmutableList.copyOf(functions));
  }

  public List<FunctionCall> getWindowFunctions(QuerySpecification query) {
    return windowFunctions.get(NodeRef.of(query));
  }

  public void setOrderByWindowFunctions(OrderBy node, List<FunctionCall> functions) {
    orderByWindowFunctions.put(NodeRef.of(node), ImmutableList.copyOf(functions));
  }

  public List<FunctionCall> getOrderByWindowFunctions(OrderBy query) {
    return orderByWindowFunctions.get(NodeRef.of(query));
  }

  public static class ResolvedWindow {
    private final List<Expression> partitionBy;
    private final Optional<OrderBy> orderBy;
    private final Optional<WindowFrame> frame;
    private final boolean partitionByInherited;
    private final boolean orderByInherited;
    private final boolean frameInherited;

    public ResolvedWindow(
        List<Expression> partitionBy,
        Optional<OrderBy> orderBy,
        Optional<WindowFrame> frame,
        boolean partitionByInherited,
        boolean orderByInherited,
        boolean frameInherited) {
      this.partitionBy = requireNonNull(partitionBy, "partitionBy is null");
      this.orderBy = requireNonNull(orderBy, "orderBy is null");
      this.frame = requireNonNull(frame, "frame is null");
      this.partitionByInherited = partitionByInherited;
      this.orderByInherited = orderByInherited;
      this.frameInherited = frameInherited;
    }

    public List<Expression> getPartitionBy() {
      return partitionBy;
    }

    public Optional<OrderBy> getOrderBy() {
      return orderBy;
    }

    public Optional<WindowFrame> getFrame() {
      return frame;
    }

    public boolean isPartitionByInherited() {
      return partitionByInherited;
    }

    public boolean isOrderByInherited() {
      return orderByInherited;
    }

    public boolean isFrameInherited() {
      return frameInherited;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ResolvedWindow that = (ResolvedWindow) o;
      return partitionByInherited == that.partitionByInherited
          && orderByInherited == that.orderByInherited
          && frameInherited == that.frameInherited
          && partitionBy.equals(that.partitionBy)
          && orderBy.equals(that.orderBy)
          && frame.equals(that.frame);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          partitionBy, orderBy, frame, partitionByInherited, orderByInherited, frameInherited);
    }
  }

  public static class Range {
    private final Optional<Integer> atLeast;
    private final Optional<Integer> atMost;

    public Range(Optional<Integer> atLeast, Optional<Integer> atMost) {
      this.atLeast = requireNonNull(atLeast, "atLeast is null");
      this.atMost = requireNonNull(atMost, "atMost is null");
    }

    public Optional<Integer> getAtLeast() {
      return atLeast;
    }

    public Optional<Integer> getAtMost() {
      return atMost;
    }
  }

  public static class FillAnalysis {
    protected final FillPolicy fillMethod;

    protected FillAnalysis(FillPolicy fillMethod) {
      this.fillMethod = fillMethod;
    }

    public FillPolicy getFillMethod() {
      return fillMethod;
    }
  }

  public static class ValueFillAnalysis extends FillAnalysis {
    private final Literal filledValue;

    public ValueFillAnalysis(Literal filledValue) {
      super(FillPolicy.CONSTANT);
      requireNonNull(filledValue, "filledValue is null");
      this.filledValue = filledValue;
    }

    public Literal getFilledValue() {
      return filledValue;
    }
  }

  public static class PreviousFillAnalysis extends FillAnalysis {
    @Nullable private final TimeDuration timeBound;
    @Nullable private final FieldReference fieldReference;
    @Nullable private final List<FieldReference> groupingKeys;

    public PreviousFillAnalysis(
        TimeDuration timeBound, FieldReference fieldReference, List<FieldReference> groupingKeys) {
      super(FillPolicy.PREVIOUS);
      this.timeBound = timeBound;
      this.fieldReference = fieldReference;
      this.groupingKeys = groupingKeys;
    }

    public Optional<TimeDuration> getTimeBound() {
      return Optional.ofNullable(timeBound);
    }

    public Optional<FieldReference> getFieldReference() {
      return Optional.ofNullable(fieldReference);
    }

    public Optional<List<FieldReference>> getGroupingKeys() {
      return Optional.ofNullable(groupingKeys);
    }
  }

  public static class LinearFillAnalysis extends FillAnalysis {
    private final FieldReference fieldReference;
    @Nullable private final List<FieldReference> groupingKeys;

    public LinearFillAnalysis(FieldReference fieldReference, List<FieldReference> groupingKeys) {
      super(FillPolicy.LINEAR);
      requireNonNull(fieldReference, "fieldReference is null");
      this.fieldReference = fieldReference;
      this.groupingKeys = groupingKeys;
    }

    public FieldReference getFieldReference() {
      return fieldReference;
    }

    public Optional<List<FieldReference>> getGroupingKeys() {
      return Optional.ofNullable(groupingKeys);
    }
  }

  @Override
  public void setDatabaseName(String databaseName) {
    this.databaseName = databaseName;
  }

  @Override
  public String getDatabaseName() {
    return databaseName;
  }

  public void setInsert(Insert insert) {
    this.insert = insert;
  }

  public Insert getInsert() {
    return insert;
  }

  public static final class Insert {
    private final Table table;
    private final List<ColumnSchema> columns;

    public Insert(Table table, List<ColumnSchema> columns) {
      this.table = requireNonNull(table, "table is null");
      this.columns = requireNonNull(columns, "columns is null");
      checkArgument(!columns.isEmpty(), "No columns given to insert");
    }

    public Table getTable() {
      return table;
    }

    public List<ColumnSchema> getColumns() {
      return columns;
    }
  }
}
