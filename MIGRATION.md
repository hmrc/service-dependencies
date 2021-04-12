* Need to drop updated indices:
```
db.getCollection("slugInfos").dropIndexes()
db.getCollection("DERIVED-slug-dependencies").dropIndexes()
```

* Need to drop updated schemas (before removing unnecessary fields):
```
db.runCommand({"collMod": "slugInfos", "validator": {}, "validationLevel": "off"})
```

* Need to duplicate the slugInfos collection as deployments
```
db.getCollection("slugInfos").copyTo("deployments")
```

(for newer mongo - i.e. development:
```
db.getCollection("slugInfos").aggregate([
  { $out: "deployments" }
])
```

* Need to remove the environment fields from slugInfos collection
```
db.getCollection("slugInfos").update({}, { $unset: { latest: "", production: "", qa: "", staging: "", development: "", "external test": "", integration: "" } }, {multi: true})
```

* Need to remove the unrequired fields from deployments collection
```
db.getCollection("deployments").update({}, { $unset: { uri: "", created: "", runnerVersion: "", classpath: "", java: "", dependencies: "", dependencyDot: "", applicationConfig: "", slugConfig: "" } }, {multi: true})
```

* poplate legacy data once, on command line:

```
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

```
db.getCollection('DERIVED-slug-dependencies').deleteMany({"version": { $regex: "^.*(-assets)|(-sans-externalized)$"}})
```
