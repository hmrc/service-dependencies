# Migration to 2.78.0
```javascript
db.getCollection('DERIVED-slug-dependencies').updateMany(
  { "scope_it": {$exists: false }},
  { $set: {"scope_it": false }}
);
```

# Migration to 2.42.0

```javascript
db.getCollection('slugInfos').aggregate([
  {$project:
    {
      _id: 1,
      uri: 1,
      created: { $toDate: "$created" },
      name: 1,
      version: 1,
      runnerVersion: 1,
      classpath: 1,
      java: 1,
      sbtVersion: 1,
      repoUrl: 1,
      dependencies: 1,
      dependencyDot: 1,
      applicationConfig: 1,
      slugConfig: 1
    }
  },
  { $out: "slugInfos-new" }
], {allowDiskUse:true})
```

Switch collections over
```javascript
db.getCollection("slugInfos").renameCollection("slugInfos-bak")
db.getCollection("slugInfos-new").renameCollection("slugInfos")
```

## Rollback

Switch collections over
```javascript
db.getCollection("slugInfos").renameCollection("slugInfos-new")
db.getCollection("slugInfos-bak").renameCollection("slugInfos")
```

# Migration to 2.0.0

## Backup

```javascript
db.getCollection("slugInfos").aggregate([{$match: {}}, {$out: "slugInfos-bak"}])
```

## Upgrade collections

* Need to drop updated indices:
```javascript
db.getCollection("slugInfos").dropIndexes()
db.getCollection("DERIVED-slug-dependencies").dropIndexes()
```

* Need to drop updated schemas (before removing unnecessary fields):
```javascript
db.runCommand({"collMod": "slugInfos", "validator": {}, "validationLevel": "off"})
```

* Need to duplicate the slugInfos collection as deployments
```javascript
db.getCollection("slugInfos").aggregate([{$match: {}}, {$out: "deployments"}])
```

(for newer mongo - i.e. development:
```javascript
db.getCollection("slugInfos").aggregate([
  { $out: "deployments" }
])
```

* Need to remove the environment fields from slugInfos collection
```javascript
db.getCollection("slugInfos").update({}, { $unset: { latest: "", production: "", qa: "", staging: "", development: "", "external test": "", integration: "" } }, {multi: true})
```

* Need to remove the unrequired fields from deployments collection
```javascript
db.getCollection("deployments").update({}, { $unset: { uri: "", created: "", runnerVersion: "", classpath: "", java: "", dependencies: "", dependencyDot: "", applicationConfig: "", slugConfig: "" } }, {multi: true})
```

* poplate legacy data once, on command line:

```javascript
db.getCollection("slugInfos").aggregate([
  {$match: {"dependencyDot": {$exists: false}}},
  {$project: {
    _id: 0,
    "dependencies.slugName": "$name",
    "dependencies.slugVersion": "$version",
    "dependencies.group": 1,
    "dependencies.artifact": 1,
    "dependencies.version": 1
  }},
  {$unwind: "$dependencies"},
  {$group: { "_id": {
    "slugName"    : "$dependencies.slugName",
    "slugVersion" : "$dependencies.slugVersion",
    "group"       : "$dependencies.group",
    "artefact"    : "$dependencies.artifact",
    "version"     : "$dependencies.version"
  }}},
  {$replaceRoot: { "newRoot": "$_id"}},
  {$addFields: {
    "scope_compile": true,
    "scope_test"   : false,
    "scope_build"  : false
  }},
  {$out: "DERIVED-slug-dependencies"}
], {allowDiskUse:true})
```

```javascript
db.getCollection('DERIVED-slug-dependencies').deleteMany({"version": { $regex: "^.*(-assets)|(-sans-externalized)$"}})
```


## Rollback (to 1.x.x)

* Rollback

```javascript
db.getCollection("slugInfos").drop()
db.getCollection("deployments").drop()
db.getCollection("DERIVED-slug-dependencies").drop()
db.getCollection("DERIVED-artefact-lookup").drop()

db.getCollection("slugInfos-bak").aggregate([{$match: {}}, {$out: "slugInfos"}])
```
