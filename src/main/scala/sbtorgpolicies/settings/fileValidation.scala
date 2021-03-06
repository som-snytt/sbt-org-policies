/*
 * Copyright 2017 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbtorgpolicies.settings

import cats.data.Validated.{Invalid, Valid}
import sbt.Keys._
import sbt._
import sbtorgpolicies.exceptions.ValidationException
import sbtorgpolicies.rules._
import sbtorgpolicies.OrgPoliciesKeys._
import sbtorgpolicies.templates.FileType

trait fileValidation extends ValidationFunctions {

  val fileValidation = new FileValidation

  val orgFileValidationTasks = Seq(
    orgValidateFiles := Def.task {
      val baseDirFile: File = (baseDirectory in LocalRootProject).value
      onlyRootUnitTask(baseDirectory.value, baseDirFile, streams.value.log) {
        val files: List[FileType] = orgEnforcedFilesSetting.value
        val validations: List[Validation] = files.flatMap {
          case FileType(true, _, _, _, path, _, _, list) =>
            List(mkValidation((baseDirFile / path).getAbsolutePath, if (list.isEmpty) List(emptyValidation) else list))
          case _ =>
            Nil
        }
        validationFilesTask(validations, streams.value.log)
      }
    }.value
  )

  private[this] def validationFilesTask(list: List[Validation], log: Logger): Unit =
    list foreach (validationFileTask(_, log))

  private[this] def validationFileTask(validation: Validation, log: Logger): Unit = {

    def errorHandler(description: String, errorList: List[ValidationException]): Unit = {
      val errorMessage =
        s"""$description
           |${errorList map (e => s" - ${e.message}") mkString "\n"}
         """.stripMargin
      if (validation.policyLevel == PolicyWarning) log.warn(errorMessage)
      else {
        throw ValidationException(errorMessage)
      }
    }

    fileValidation.validateFile(validation.validationRule.inputPath, validation.validationRule.validationList: _*) match {
      case Valid(_) => log.info(s"File ${validation.validationRule.inputPath} was validated successfully")
      case Invalid(errors) =>
        errorHandler(
          s"Some errors where found while validating ${validation.validationRule.inputPath}:",
          errors.toList)
    }

  }

}
