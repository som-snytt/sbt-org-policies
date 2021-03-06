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

package sbtorgpolicies.github

import java.io.File

import cats.data.{EitherT, NonEmptyList}
import cats.free.Free
import cats.implicits._
import cats.syntax.either._
import com.github.marklister.base64.Base64.Encoder
import github4s.Github
import github4s.GithubResponses._
import github4s.free.domain._
import sbt.IO
import sbtorgpolicies.exceptions.{GitHubException, OrgPolicyException}
import sbtorgpolicies.github.instances._
import sbtorgpolicies.github.syntax._
import sbtorgpolicies.io.syntax._
import sbtorgpolicies.io.{FileReader, IOResult}
import com.github.marklister.base64.Base64._

class GitHubOps(owner: String, repo: String, accessToken: Option[String]) {

  val fileReader: FileReader = new FileReader

  val gh = Github(accessToken)

  def fetchContributors: Either[GitHubException, List[User]] = {

    def fetchUserList: Github4sResponse[List[User]] =
      EitherT(gh.repos.listContributors(owner, repo))

    def fetchUserDetail(user: User): Github4sResponse[User] =
      EitherT(gh.users.get(user.login))

    val op: EitherT[GHIO, GHException, GHResult[List[User]]] = for {
      response         <- fetchUserList
      detailedResponse <- response.result.traverse(fetchUserDetail)
    } yield detailedResponse

    op.execE
  }

  def commitFiles(
      baseDir: File,
      branch: String,
      message: String,
      files: List[File]): Either[OrgPolicyException, Option[Ref]] = {

    def relativePath(file: File): String = IO.relativize(baseDir, file).getOrElse(file.getName)

    def readFileContents: IOResult[List[(String, String)]] = {
      files.foldLeft[IOResult[List[(String, String)]]](Right(Nil)) {
        case (Right(partialResult), file) =>
          fileReader.getFileContent(file.getAbsolutePath) map { content =>
            (relativePath(file), content) :: partialResult
          }
        case (Left(e), _) => Left(e)
      }
    }

    readFileContents match {
      case Right(filesAndContents) =>
        commitFilesAndContents(branch, message, filesAndContents)
      case Left(e) => Left(e)
    }

  }

  def commitFilesAndContents(
      branch: String,
      message: String,
      filesAndContents: List[(String, String)]): Either[OrgPolicyException, Option[Ref]] = {

    def fetchBaseTreeSha(commitSha: String): Github4sResponse[RefCommit] =
      EitherT(gh.gitData.getCommit(owner, repo, commitSha))

    def fetchFilesContents(commitSha: String): Github4sResponse[List[(String, Option[String])]] = {

      def fetchFileContents(path: String, commitSha: String): Github4sResponse[(String, Option[String])] = {
        val result: GHIO[GHResponse[(String, Option[String])]] = gh.repos.getContents(
          owner = owner,
          repo = repo,
          path = path,
          ref = Some(commitSha)) map {
          case Right(ghResult) => Right(ghResult.map(list => (path, list.head.content)))
          case Left(_)         => Right(newGHResult((path, None)))
        }
        EitherT(result)
      }

      filesAndContents.map(_._1).traverse(fetchFileContents(_, commitSha))
    }

    def filterNonChangedFiles(remote: List[(String, Option[String])]): List[(String, String)] = {
      val remoteMap = remote.collect {
        case (path, Some(c)) => path -> c
      }.toMap
      filesAndContents.filterNot {
        case (path, content) =>
          remoteMap.get(path).exists { remoteContent =>
            remoteContent.trim.replaceAll("\n", "") == content.getBytes.toBase64.trim
          }
      }
    }

    def createTree(baseTreeSha: String, filteredFilesContent: List[(String, String)]): Github4sResponse[TreeResult] = {

      def treeData: List[TreeDataBlob] = filteredFilesContent.map {
        case (path, content) => TreeDataBlob(path, "100644", "blob", content)
      }

      EitherT(gh.gitData.createTree(owner, repo, Some(baseTreeSha), treeData))
    }

    def createCommit(treeSha: String, baseCommitSha: String): Github4sResponse[RefCommit] =
      EitherT(gh.gitData.createCommit(owner, repo, message, treeSha, List(baseCommitSha)))

    def updateHead(commitSha: String): Github4sResponse[Ref] =
      EitherT(gh.gitData.updateReference(owner, repo, s"heads/$branch", commitSha))

    def commitFilesIfChanged(
        baseTreeSha: String,
        parentCommitSha: String,
        filteredFilesContent: List[(String, String)]): Github4sResponse[Option[Ref]] =
      filteredFilesContent match {
        case Nil =>
          val result: GHIO[GHResponse[Option[Ref]]] = Free.pure(Right(newGHResult(None)))
          EitherT(result)
        case list =>
          for {
            ghResultTree   <- createTree(baseTreeSha, list)
            ghResultCommit <- createCommit(ghResultTree.result.sha, parentCommitSha)
            ghResultUpdate <- updateHead(ghResultCommit.result.sha)
          } yield ghResultUpdate.map(Option(_))
      }

    val op = for {
      gHResultParentCommit <- fetchHeadCommit(branch)
      parentCommitSha = gHResultParentCommit.result.`object`.sha
      gHResultBaseTree <- fetchBaseTreeSha(parentCommitSha)
      baseTreeSha = gHResultBaseTree.result.tree.sha
      ghResultFilesContent <- fetchFilesContents(parentCommitSha)
      ghResultUpdate <- commitFilesIfChanged(
        baseTreeSha,
        parentCommitSha,
        filterNonChangedFiles(ghResultFilesContent.result))
    } yield ghResultUpdate

    op.execE
  }

  def commitDir(branch: String, message: String, dir: File): Either[OrgPolicyException, Ref] = {

    def getAllFilesAsGithub4sResponse(dir: File): Github4sResponse[List[File]] = {
      val ghio: GHIO[GHResponse[List[File]]] = Free.pure {
        fileReader
          .fetchFilesRecursively(List(dir))
          .bimap(
            e => UnexpectedException(e.getMessage),
            v => newGHResult(v)
          )
      }
      EitherT(ghio)
    }

    def createBlobs(files: List[File]): Github4sResponse[List[(String, RefInfo)]] = {

      def readFileAsGithub4sResponse(file: File): Github4sResponse[Array[Byte]] = {
        val ghio: GHIO[GHResponse[Array[Byte]]] = Free.pure {
          fileReader
            .getFileBytes(file)
            .bimap(e => UnexpectedException(e.getMessage), newGHResult)
        }
        EitherT(ghio)
      }

      def path(file: File): Github4sResponse[String] = {
        val ghio: GHIO[GHResponse[String]] = Free.pure {
          IO.relativize(dir, file) match {
            case Some(p) => Right(newGHResult(p))
            case None    => Left(UnexpectedException(s"Can't determine path for ${file.getAbsolutePath}"))
          }
        }
        EitherT(ghio)
      }

      def createBlob(file: File): Github4sResponse[(String, RefInfo)] =
        for {
          filePath <- path(file)
          array    <- readFileAsGithub4sResponse(file)
          refInfo  <- EitherT(gh.gitData.createBlob(owner, repo, array.result.toBase64, Some("base64")))
        } yield refInfo.map(v => (filePath.result, v))

      files.traverse(createBlob)
    }

    def createTree(filesSha: List[(String, String)]): Github4sResponse[TreeResult] = {

      def treeData: List[TreeDataSha] = filesSha.map {
        case (path, sha) => TreeDataSha(path, "100644", "blob", sha)
      }

      EitherT(gh.gitData.createTree(owner, repo, baseTree = None, treeData))
    }

    def createCommit(treeSha: String, parentCommit: String): Github4sResponse[RefCommit] =
      EitherT(gh.gitData.createCommit(owner, repo, message, treeSha, List(parentCommit)))

    def updateHead(commitSha: String): Github4sResponse[Ref] =
      EitherT(gh.gitData.updateReference(owner, repo, s"heads/$branch", commitSha))

    val op = for {
      allFiles     <- getAllFilesAsGithub4sResponse(dir)
      parentCommit <- fetchHeadCommit(branch)
      blobs        <- createBlobs(allFiles.result)
      treeResult   <- createTree(blobs.result.map(t => (t._1, t._2.sha)))
      refCommit    <- createCommit(treeResult.result.sha, parentCommit.result.`object`.sha)
      headRef      <- updateHead(refCommit.result.sha)
    } yield headRef

    op.execE
  }

  def fetchReference(ref: String): Either[GitHubException, NonEmptyList[Ref]] =
    gh.gitData
      .getReference(owner, repo, ref)
      .execE

  def createTagRelease(
      branch: String,
      tag: String,
      message: String,
      releaseDescription: String): Either[GitHubException, Release] = {

    def createTag(obj: RefObject): Github4sResponse[Tag] =
      EitherT(gh.gitData.createTag(owner, repo, tag, message, obj.sha, obj.`type`))

    def createTagReference(commitSha: String): Github4sResponse[Ref] =
      EitherT(gh.gitData.createReference(owner, repo, s"refs/tags/$tag", commitSha))

    def createRelease: Github4sResponse[Release] =
      EitherT(gh.repos.createRelease(owner, repo, tag, tag, releaseDescription))

    val op = for {
      headCommit  <- fetchHeadCommit(branch)
      tagResponse <- createTag(headCommit.result.`object`)
      _           <- createTagReference(tagResponse.result.sha)
      release     <- createRelease
    } yield release

    op.execE
  }

  def latestPullRequests(branch: String, inPath: String, message: String): Either[GitHubException, List[PullRequest]] = {

    def fetchLastCommit: Github4sResponse[Option[Commit]] = {

      def findCommit(list: List[Commit]): Option[Commit] =
        list.sortBy(_.date).reverse.find(_.message.contains(message))

      val result: GHIO[GHResponse[Option[Commit]]] = gh.repos.listCommits(
        owner = owner,
        repo = repo,
        path = Some(inPath)) map {
        case Right(ghResult) => Right(ghResult.map(findCommit))
        case Left(e)         => Left(e)
      }
      EitherT(result)
    }

    def fetchPullRequests(maybeDate: Option[String]): Github4sResponse[List[PullRequest]] = {

      def orderAndFilter(list: List[PullRequest]): List[PullRequest] =
        list.reverse.flatMap { pr =>
          pr.merged_at.map((_, pr))
        } filter {
          case (mergedAt, _) => mergedAt > maybeDate.getOrElse("")
        } map (_._2)

      val result: GHIO[GHResponse[List[PullRequest]]] = gh.pullRequests.list(
        owner,
        repo,
        List(PRFilterClosed, PRFilterBase(branch), PRFilterSortUpdated, PRFilterOrderDesc)) map {
        case Right(gHResult) => Right(gHResult.map(orderAndFilter))
        case Left(e)         => Left(e)
      }
      EitherT(result)
    }

    val op = for {
      maybeCommit <- fetchLastCommit
      list        <- fetchPullRequests(maybeCommit.result.map(_.date))
    } yield list

    op.execE
  }

  def fetchHeadCommit(branch: String): Github4sResponse[Ref] = {

    def findReference(gHResult: GHResult[NonEmptyList[Ref]]): GHResponse[Ref] =
      gHResult.result.toList.find(_.ref == s"refs/heads/$branch") match {
        case Some(ref) => Right(gHResult.map(_ => ref))
        case None      => Left(UnexpectedException(s"Branch $branch not found"))
      }

    val result: GHIO[GHResponse[Ref]] =
      gh.gitData.getReference(owner, repo, s"heads/$branch").map {
        case Right(r) => findReference(r)
        case Left(e)  => Left(e)
      }
    EitherT(result)
  }

}
