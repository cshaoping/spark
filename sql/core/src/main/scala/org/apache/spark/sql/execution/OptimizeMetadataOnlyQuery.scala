/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.util.Locale

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.catalog.{HiveTableRelation, SessionCatalog}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.util.{CaseInsensitiveMap, DateTimeUtils}
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, LogicalRelation}
import org.apache.spark.sql.internal.SQLConf

/**
 * This rule optimizes the execution of queries that can be answered by looking only at
 * partition-level metadata. This applies when all the columns scanned are partition columns, and
 * the query has an aggregate operator that satisfies the following conditions:
 * 1. aggregate expression is partition columns.
 *  e.g. SELECT col FROM tbl GROUP BY col.
 * 2. aggregate function on partition columns with DISTINCT.
 *  e.g. SELECT col1, count(DISTINCT col2) FROM tbl GROUP BY col1.
 * 3. aggregate function on partition columns which have same result w or w/o DISTINCT keyword.
 *  e.g. SELECT col1, Max(col2) FROM tbl GROUP BY col1.
 */
case class OptimizeMetadataOnlyQuery(catalog: SessionCatalog) extends Rule[LogicalPlan] {

  def apply(plan: LogicalPlan): LogicalPlan = {
    if (!SQLConf.get.optimizerMetadataOnly) {
      return plan
    }

    plan.transform {
      case a @ Aggregate(_, aggExprs, child @ PartitionedRelation(_, attrs, filters, rel)) =>
        // We only apply this optimization when only partitioned attributes are scanned.
        if (a.references.subsetOf(attrs)) {
          val aggFunctions = aggExprs.flatMap(_.collect {
            case agg: AggregateExpression => agg
          })
          val isAllDistinctAgg = aggFunctions.forall { agg =>
            agg.isDistinct || (agg.aggregateFunction match {
              // `Max`, `Min`, `First` and `Last` are always distinct aggregate functions no matter
              // they have DISTINCT keyword or not, as the result will be same.
              case _: Max => true
              case _: Min => true
              case _: First => true
              case _: Last => true
              case _ => false
            })
          }
          if (isAllDistinctAgg) {
            a.withNewChildren(Seq(replaceTableScanWithPartitionMetadata(child, rel, filters)))
          } else {
            a
          }
        } else {
          a
        }
    }
  }

  /**
   * Returns the partition attributes of the table relation plan.
   */
  private def getPartitionAttrs(
      partitionColumnNames: Seq[String],
      relation: LogicalPlan): Seq[Attribute] = {
    val attrMap = relation.output.map(a => a.name.toLowerCase(Locale.ROOT) -> a).toMap
    partitionColumnNames.map { colName =>
      attrMap.getOrElse(colName.toLowerCase(Locale.ROOT),
        throw new AnalysisException(s"Unable to find the column `$colName` " +
          s"given [${relation.output.map(_.name).mkString(", ")}]")
      )
    }
  }

  /**
   * Transform the given plan, find its table scan nodes that matches the given relation, and then
   * replace the table scan node with its corresponding partition values.
   */
  private def replaceTableScanWithPartitionMetadata(
      child: LogicalPlan,
      relation: LogicalPlan,
      partFilters: Seq[Expression]): LogicalPlan = {
    // this logic comes from PruneFileSourcePartitions. it ensures that the filter names match the
    // relation's schema. PartitionedRelation ensures that the filters only reference partition cols
    val relFilters = partFilters.map { e =>
      e transform {
        case a: AttributeReference =>
          a.withName(relation.output.find(_.semanticEquals(a)).get.name)
      }
    }

    child transform {
      case plan if plan eq relation =>
        relation match {
          case l @ LogicalRelation(fsRelation: HadoopFsRelation, _, _, isStreaming) =>
            val partAttrs = getPartitionAttrs(fsRelation.partitionSchema.map(_.name), l)
            val partitionData = fsRelation.location.listFiles(relFilters, Nil)
            // partition data may be a stream, which can cause serialization to hit stack level too
            // deep exceptions because it is a recursive structure in memory. converting to array
            // avoids the problem.
            LocalRelation(partAttrs, partitionData.map(_.values).toArray, isStreaming)

          case relation: HiveTableRelation =>
            val partAttrs = getPartitionAttrs(relation.tableMeta.partitionColumnNames, relation)
            val caseInsensitiveProperties =
              CaseInsensitiveMap(relation.tableMeta.storage.properties)
            val timeZoneId = caseInsensitiveProperties.get(DateTimeUtils.TIMEZONE_OPTION)
              .getOrElse(SQLConf.get.sessionLocalTimeZone)
            val partitions = if (partFilters.nonEmpty) {
              catalog.listPartitionsByFilter(relation.tableMeta.identifier, relFilters)
            } else {
              catalog.listPartitions(relation.tableMeta.identifier)
            }

            val partitionData = partitions.map { p =>
              InternalRow.fromSeq(partAttrs.map { attr =>
                Cast(Literal(p.spec(attr.name)), attr.dataType, Option(timeZoneId)).eval()
              })
            }
            // partition data may be a stream, which can cause serialization to hit stack level too
            // deep exceptions because it is a recursive structure in memory. converting to array
            // avoids the problem.
            LocalRelation(partAttrs, partitionData.toArray)

          case _ =>
            throw new IllegalStateException(s"unrecognized table scan node: $relation, " +
              s"please turn off ${SQLConf.OPTIMIZER_METADATA_ONLY.key} and try again.")
        }
    }
  }

  /**
   * A pattern that finds the partitioned table relation node inside the given plan, and returns a
   * pair of the partition attributes, partition filters, and the table relation node.
   *
   * It keeps traversing down the given plan tree if there is a [[Project]] or [[Filter]] with
   * deterministic expressions, and returns result after reaching the partitioned table relation
   * node.
   */
  object PartitionedRelation extends PredicateHelper {

    def unapply(
        plan: LogicalPlan): Option[(AttributeSet, AttributeSet, Seq[Expression], LogicalPlan)] = {
      plan match {
        case l @ LogicalRelation(fsRelation: HadoopFsRelation, _, _, _)
          if fsRelation.partitionSchema.nonEmpty =>
          val partAttrs = AttributeSet(getPartitionAttrs(fsRelation.partitionSchema.map(_.name), l))
          Some((partAttrs, partAttrs, Nil, l))

        case relation: HiveTableRelation if relation.tableMeta.partitionColumnNames.nonEmpty =>
          val partAttrs = AttributeSet(
            getPartitionAttrs(relation.tableMeta.partitionColumnNames, relation))
          Some((partAttrs, partAttrs, Nil, relation))

        case p @ Project(projectList, child) if projectList.forall(_.deterministic) =>
          unapply(child).flatMap { case (partAttrs, attrs, filters, relation) =>
            if (p.references.subsetOf(attrs)) {
              Some((partAttrs, p.outputSet, filters, relation))
            } else {
              None
            }
          }

        case f @ Filter(condition, child) if condition.deterministic =>
          unapply(child).flatMap { case (partAttrs, attrs, filters, relation) =>
            if (f.references.subsetOf(partAttrs)) {
              Some((partAttrs, attrs, splitConjunctivePredicates(condition) ++ filters, relation))
            } else {
              None
            }
          }

        case _ => None
      }
    }
  }
}
