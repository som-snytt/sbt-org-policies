import sbtorgpolicies.runnable.syntax._

name := "sbt-org-policies"

version := "1.0"

scalaVersion := sbtorgpolicies.model.scalac.`2.12`

orgGithubTokenSetting := "GITHUB_TOKEN_REPO"

orgAfterCISuccessCheckSetting := true

orgAfterCISuccessTaskListSetting := List(orgCreateFiles.asRunnableItem)
