# service-dependencies

[![Build Status](https://travis-ci.org/hmrc/service-dependencies.svg)](https://travis-ci.org/hmrc/service-dependencies) [ ![Download](https://api.bintray.com/packages/hmrc/releases/service-dependencies/images/download.svg) ](https://bintray.com/hmrc/releases/service-dependencies/_latestVersion)

This service provides information about scala library dependencies across the platform.
  
Used by the catalogue-frontend service deployments page.

#### How it works
The service gathers dependency information from two sources:

* SBT build files read from github
  * Covers all HMRC repos, including services, libraries, prototypes
  * Limited to a whitelist of dependencies, doesnt cover transitive 
  * Only shows the latest dependencies from master branch
  * Shows if dependencies are out of date

* Library data from a slug
  * Covers all services that generate a slug
  * Includes all dependencies used by service, including transitive
  * Shows dependencies across different releases of slug, not just latest

#### Configuration

The slug dependency parser is configured in two parts:

````
### Artifactory Polling Service to find new slugs
artifactory.url                                  # url to artifactory api
artifactory.webstore                             # converts artifactory uri to uri to s3 slug mirror
repositoryDependencies.slugJobCreator.enabled    # enable polling of artifactory to find new lsugs
repositoryDependencies.slugJobCreator.interval   # delay between polling artifactory 

### Slug downloader and parser
repositoryDependencies.slugJobProcessor.enabled  # enabled/disable the slugParser
repositoryDependencies.slugJobProcessor.interval # delay between checking for new jobs
````

The github/sbt dependency parser is configured:
````
dependency.reload.intervalminutes  # enables/disables the github repo/sbt parser 
library.reload.intervalminutes     # enables/disables the library version parser
sbtPlugin.reload.intervalminutes1  # enables/disables the sbt plugin version parser
````

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")