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

package sbtorgpolicies

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths.get

import cats.syntax.either._
import sbt.{file, File}
import sbtorgpolicies.exceptions.IOException

import scala.language.implicitConversions

package object io {

  type IOResult[T] = Either[IOException, T]

  object syntax {

    implicit def eitherFilterSyntax[T](either: Either[Throwable, T]): FilteredEitherOps[T] =
      new FilteredEitherOps(either)

    implicit def fileNameSyntax(fileName: String): FileNameOps = new FileNameOps(fileName)

    final class FilteredEitherOps[T](either: Either[Throwable, T]) {

      def withFilter(f: T => Boolean): Either[Throwable, T] = either match {
        case Right(r) if !f(r) =>
          new IllegalStateException("Filter condition has not been satisfied").asLeft[T]
        case _ =>
          either
      }
    }

    final class FileNameOps(filename: String) {

      def toPath: Path = get(filename)

      def toFile: File = file(filename.fixPath)

      def fixPath: String = filename.replaceAll("/", File.separator)

      def ensureFinalSlash: String =
        filename +
          (if (filename.endsWith(File.separator)) ""
           else File.separator)
    }
  }
}
