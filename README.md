# service-dependencies

[![Build Status](https://travis-ci.org/hmrc/service-dependencies.svg)](https://travis-ci.org/hmrc/service-dependencies) [ ![Download](https://api.bintray.com/packages/hmrc/releases/service-dependencies/images/download.svg) ](https://bintray.com/hmrc/releases/service-dependencies/_latestVersion)

This service provides information about scala library dependencies across the platform.

Used by the catalogue-frontend service deployments page.

#### How it works
The service gathers the following information:

* Dependencies from SBT build files read from github
  * Covers all HMRC repos, including services, libraries, prototypes
  * Limited to a whitelist of dependencies, doesn't cover transitive
  * Only shows the dependencies from master branch

* Dependencies included in a Slug
  * Covers all services that generate a slug
  * Includes all runtime dependencies used by service, including transitive
  * Shows dependencies across different releases of slug, not just latest

* The latest version for a whitelist of dependencies
  * This is looked up periodically from Artifactory
  * It will be included in the dependencies returned for a Slug or Github repository. Identifying if the repositories dependencies are out of date.

#### Configuration

The slug metadata updater is configured:

````
repositoryDependencies.slugJob.enabled           # enable polling of service deployments and updating of slug metadata
repositoryDependencies.slugJob.interval          # delay between polling service deployments
````

The latest version parser is configured:
````
dependencyVersionsReload.scheduler.enabled      # disable refreshing the latest version for the whitelisted dependencies
dependencyVersionsReload.scheduler.interval     # delay between refresh
````

The github/sbt dependency parser is configured:
````
dependencyReload.scheduler.enabled   # disable the refresh of dependencies from the github projects
dependencyReload.scheduler.interval  # delay between refresh
````

The metrics reporter is configured:

````
repositoryDependencies.metricsGauges.enabled   # enable the metrics reporter
repositoryDependencies.metricsGauges.interval  # how often stats are uploaded
````

#### Admin endpoints

As well as the configured scheduler, a refresh of the latest version for whitelisted dependencies can be initiated with:
  `POST    /reload-dependency-versions`

Similarly, a refresh of dependencies from Github, for all modified repositories, can be initiated with:
  `POST    /reload-dependencies`

Note, that this refresh will only include repositories which have been modified in Github since the last run. To force a reload of all repositories, the last modified date can be cleared prior to reloading the dependencies with:
  `POST    /api/admin/dependencies/clear-update-dates`

The last modified date for a single repository can also be cleared with:
  `POST    /api/admin/dependencies/:repository/clear-update-dates`

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")