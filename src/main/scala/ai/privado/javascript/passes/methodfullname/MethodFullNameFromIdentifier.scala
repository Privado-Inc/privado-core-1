/*
 * This file is part of Privado OSS.
 *
 * Privado is an open source static code analysis tool to discover data flows in the code.
 * Copyright (C) 2022 Privado, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, contact support@privado.ai
 *
 */

package ai.privado.javascript.passes.methodfullname

import io.shiftleft.codepropertygraph.generated.nodes.Call.PropertyNames
import io.shiftleft.codepropertygraph.generated.{Cpg, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Expression}
import io.shiftleft.passes.ConcurrentWriterCpgPass
import io.shiftleft.semanticcpg.language._
import overflowdb.traversal.Traversal

import scala.jdk.CollectionConverters.CollectionHasAsScala

class MethodFullNameFromIdentifier(cpg: Cpg) extends ConcurrentWriterCpgPass[(Expression, Expression)](cpg) {

  val ANY_VALUE = "ANY"

  /** We are solving the following scenario
    *
    * const log4js = require("log4js");
    *
    * log4js.configure({ appenders: { cheese: { type: "file", filename: "cheese.log" } }, categories: { default: {
    * appenders: ["cheese"], level: "error" } }, });
    *
    * const logger = log4js.getLogger("cheese");
    *
    * logger.debug("Information is available);
    *
    * We are looking to add methodFullName to `debug`
    *
    * In this case the methodFullName will be pkg.log4js.getLogger.debug
    *
    * @return
    *   \- (left hand side of Assignment, right hand side call node of assignment)
    */
  override def generateParts(): Array[(Expression, Expression)] = {
    cpg
      .call(Operators.assignment)
      .whereNot(_.argument(2).isCall.methodFullName(ANY_VALUE))
      .where(_.argument(1).isIdentifier)
      .map(assignmentCall => { (assignmentCall.argument(1), assignmentCall.argument(2)) })
      .toArray
  }

  override def runOnPart(builder: DiffGraphBuilder, argumentNodes: (Expression, Expression)): Unit = {

    // Identifier node from generateParts
    val identifierNode = Traversal(argumentNodes._1).isIdentifier.l
    // CallNodeMethodFullName of call node from generateParts
    val callNodeMethodFullName = Traversal(argumentNodes._2).isCall.headOption match {
      case Some(callNode) if callNode.methodFullName != ANY_VALUE || callNode.methodFullName.nonEmpty =>
        callNode.methodFullName
      case _ => ANY_VALUE
    }

    val identifierName     = identifierNode.name.head
    val identifierFileName = identifierNode.file.name.head

    // Query to tag all call node which match with identifier - `logger` in the same file
    cpg.identifier
      .nameExact(identifierName)
      .filter(_.file.name.head.equals(identifierFileName))
      .astParent
      .isCall
      .nameNot(Operators.ALL.asScala.toSeq: _*)
      .or(_.filter(_.methodFullName.isEmpty), _.where(_.methodFullName(ANY_VALUE)))
      .foreach(callNode => updateCallNode(builder, callNode, callNodeMethodFullName))

  }

  private def updateCallNode(builder: DiffGraphBuilder, callNode: Call, methodFullname: String) = {
    builder.setNodeProperty(callNode, PropertyNames.MethodFullName, methodFullname + "." + callNode.name)
  }
}
