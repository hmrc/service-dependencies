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

The slug metadata updater is configured:

````
repositoryDependencies.slugJob.enabled           # enable polling of service deployments and updating of slug metadata
repositoryDependencies.slugJob.interval          # delay between polling service deployments
````

The github/sbt dependency parser is configured:
````
scheduler.enabled                  # disable all github/sbt parsers
dependency.reload.intervalminutes  # how often the github repo/sbt parser will run
library.reload.intervalminutes     # how often the library version parser will run
sbtPlugin.reload.intervalminutes   # how often the sbt plugin version parser will run
````

The metrics reporter is configured:

````
repositoryDependencies.metricsGauges.enabled   # enable the metrics reporter
repositoryDependencies.metricsGauges.interval  # how often stats are uploaded
````

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")