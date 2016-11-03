/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies

import org.apache.commons.codec.binary.Base64
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.{IRepositoryIdProvider, RepositoryContents}
import uk.gov.hmrc.githubclient.GithubApiClient

abstract class Github(artifact: String, buildFilePaths: Seq[String]) {
  def gh: GithubApiClient
  def resolveTag(version: String): String

  def findArtifactVersion(serviceName: String, versionOption: Option[String]): Option[Version] = {
    versionOption match {
      case Some(version) =>
        //println(s"Searching $serviceName $version")
        val result = searchPluginSbtFile(serviceName, version)
        if (result.nonEmpty) result
        else searchBuildFiles(serviceName, version)
      case None => None
    }
  }

  private def searchBuildFiles(serviceName: String, version: String): Option[Version] = {
    buildFilePaths.foreach(filePath => performSearch(serviceName, version, filePath)(parseBuildFile) match {
      case Some(v) => return Some(v)
      case _ =>
    })
    None
  }

  private def searchPluginSbtFile(serviceName: String, version: String) =
      performSearch(serviceName, version, "project/plugins.sbt")(parsePluginsSbtFile)

  private def parseBuildFile(response: RepositoryContents): Option[Version] =
    BuildFileVersionParser.parse(parseFileContents(response), artifact)

  private def parsePluginsSbtFile(response: RepositoryContents): Option[Version] =
    BuildFileVersionParser.parse(parseFileContents(response), artifact)

  private def performSearch(serviceName: String, version: String, filePath: String)
                           (transformF: (RepositoryContents) => Option[Version]): Option[Version] =
    try {
      //println(s"Searching for $filePath")
      val results = gh.contentsService.getContents(repositoryId(serviceName, "HMRC"), filePath, resolveTag(version))
      if (results.isEmpty) None
      else transformF(results.get(0))
    }
    catch {
      case (ex: RequestException) => None
    }

  private def repositoryId(repoName: String, orgName: String): IRepositoryIdProvider =
    new IRepositoryIdProvider { val generateId: String = orgName + "/" + repoName }

  private def parseFileContents(response: RepositoryContents): String =
    new String(Base64.decodeBase64(response.getContent.replaceAll("\n", "")))
}
