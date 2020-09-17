/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr.hive;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hadoop.hive.ql.io.sarg.ExpressionTree;
import org.apache.hadoop.hive.ql.io.sarg.PredicateLeaf;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.iceberg.common.DynFields;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.util.DateTimeUtil;

import static org.apache.iceberg.expressions.Expressions.*;


public class HiveIcebergFilterFactory {

  private HiveIcebergFilterFactory() {
  }

  public static Expression generateFilterExpression(SearchArgument sarg) {
    return translate(sarg.getExpression(), sarg.getLeaves());
  }

  /**
   * Recursive method to traverse down the ExpressionTree to evaluate each expression and its leaf nodes.
   * @param tree Current ExpressionTree where the 'top' node is being evaluated.
   * @param leaves List of all leaf nodes within the tree.
   * @return Expression that is translated from the Hive SearchArgument.
   */
  private static Expression translate(ExpressionTree tree, List<PredicateLeaf> leaves) {
    List<ExpressionTree> childNodes = tree.getChildren();
    switch (tree.getOperator()) {
      case OR:
        Expression orResult = Expressions.alwaysFalse();
        for (ExpressionTree child : childNodes) {
          orResult = or(orResult, translate(child, leaves));
        }
        return orResult;
      case AND:
        Expression result = Expressions.alwaysTrue();
        for (ExpressionTree child : childNodes) {
          result = and(result, translate(child, leaves));
        }
        return result;
      case NOT:
        return not(translate(childNodes.get(0), leaves));
      case LEAF:
        return translateLeaf(leaves.get(tree.getLeaf()));
      case CONSTANT:
        throw new UnsupportedOperationException("CONSTANT operator is not supported");
      default:
        throw new UnsupportedOperationException("Unknown operator: " + tree.getOperator());
    }
  }

  /**
   * Translate leaf nodes from Hive operator to Iceberg operator.
   * @param leaf Leaf node
   * @return Expression fully translated from Hive PredicateLeaf
   */
  private static Expression translateLeaf(PredicateLeaf leaf) {
    String column = leaf.getColumnName();
    switch (leaf.getOperator()) {
      case EQUALS:
        return equal(column, leafToLiteral(leaf));
      case LESS_THAN:
        return lessThan(column, leafToLiteral(leaf));
      case LESS_THAN_EQUALS:
        return lessThanOrEqual(column, leafToLiteral(leaf));
      case IN:
        return in(column, leafToLiteralList(leaf));
      case BETWEEN:
        List<Object> icebergLiterals = leafToLiteralList(leaf);
        return and(greaterThanOrEqual(column, icebergLiterals.get(0)),
                lessThanOrEqual(column, icebergLiterals.get(1)));
      case IS_NULL:
        return isNull(column);
      default:
        throw new UnsupportedOperationException("Unknown operator: " + leaf.getOperator());
    }
  }

  // PredicateLeafImpl has a work-around for Kryo serialization with java.util.Date objects where it converts values to
  // Timestamp using Date#getTime. This conversion discards microseconds, so this is a necessary to avoid it.
  private static DynFields.UnboundField<?> LITERAL_FIELD = DynFields.builder()
      .hiddenImpl(getSearchArgumentImplClass(), "literal")
      .build();

  private static DynFields.UnboundField<?> LITERAL_LIST_FIELD = DynFields.builder()
      .hiddenImpl(getSearchArgumentImplClass(), "literalList")
      .build();

  private static Object leafToLiteral(PredicateLeaf leaf) {
    switch (leaf.getType()) {
      case LONG:
      case BOOLEAN:
      case STRING:
      case FLOAT:
        return LITERAL_FIELD.get(leaf);
      case DATE:
        return daysFromTimestamp(new Timestamp(((java.util.Date)LITERAL_FIELD.get(leaf)).getTime()));
      case TIMESTAMP:
        return microsFromTimestamp((Timestamp) LITERAL_FIELD.get(leaf));
      case DECIMAL:
        return hiveDecimalToBigDecimal((HiveDecimalWritable) LITERAL_FIELD.get(leaf));

      default:
        throw new UnsupportedOperationException("Unknown type: " + leaf.getType());
    }
  }

  private static List<Object> leafToLiteralList(PredicateLeaf leaf) {
    switch (leaf.getType()) {
      case LONG:
      case BOOLEAN:
      case FLOAT:
      case STRING:
        return (List<Object>) LITERAL_LIST_FIELD.get(leaf);
      case DATE:
        return ((List<Object>) LITERAL_LIST_FIELD.get(leaf)).stream().map(value -> daysFromDate((Date) value))
            .collect(Collectors.toList());
      case DECIMAL:
        return ((List<Object>) LITERAL_LIST_FIELD.get(leaf)).stream()
            .map(value -> hiveDecimalToBigDecimal((HiveDecimalWritable) value))
            .collect(Collectors.toList());
      case TIMESTAMP:
        return ((List<Object>) LITERAL_LIST_FIELD.get(leaf)).stream()
            .map(value -> microsFromTimestamp((Timestamp) value))
            .collect(Collectors.toList());
      default:
        throw new UnsupportedOperationException("Unknown type: " + leaf.getType());
    }
  }

  private static BigDecimal hiveDecimalToBigDecimal(HiveDecimalWritable hiveDecimalWritable) {
    return hiveDecimalWritable.getHiveDecimal().bigDecimalValue().setScale(hiveDecimalWritable.scale());
  }

  private static int daysFromDate(Date date) {
    return DateTimeUtil.daysFromDate(date.toLocalDate());
  }

  private static int daysFromTimestamp(Timestamp timestamp) {
    return DateTimeUtil.daysFromInstant(timestamp.toInstant());
  }

  private static long microsFromTimestamp(Timestamp timestamp) {
    return DateTimeUtil.microsFromInstant(timestamp.toInstant());
  }

  private static Class getSearchArgumentImplClass() {
    Class<?>  PREDICATE_LEAF_IMPL;
    try {
      // SearchArgumentImpl is a package-private class in Hive 1.1, but not in Hive > 2
      PREDICATE_LEAF_IMPL = Stream.of(
          Class.forName("org.apache.hadoop.hive.ql.io.sarg.SearchArgumentImpl").getDeclaredClasses())
          .filter(subClass -> "PredicateLeafImpl".equals(subClass.getSimpleName()))
          .findFirst()
          .orElse(null);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    return PREDICATE_LEAF_IMPL;
  }

}
