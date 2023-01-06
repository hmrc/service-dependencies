how many slug-infos, that are deployed, have no dependency-dot?

```javascript
db.getCollection('deployments').aggregate([
 // deploymentsFilter... ,
 { "$match": { "$or": [
     { "latest": true },  { "production": true }, { "qa": true }, { "staging": true }, { "development": true }, { "external-test": true} , { "integration": true}  ] } },
 { "$lookup": {
     "from": "slugInfos",
     "let": { "sn": "$name", "sv": "$version" },
     "pipeline": [
        { "$match": { "$and": [
            { "$expr": {"$and": [
                {"$eq": ["$name", "$$sn" ] },
                {"$eq": ["$version", "$$sv" ] }
                ] } },
        // domainFilter ...
        { "dependencyDot": { "$exists": 0 } }
        ] } }
     ],
     "as": "res"
 } },
 { "$unwind": "$res" },
 { "$count": "res" },
 //{ "$replaceRoot": { "newRoot": "$res" } }
]);
```

how many slug-infos are missing latest flag?

```javascript
db.getCollection('deployments').aggregate([
 {"$group":{_id: { "name": "$name" }, "latest": {"$addToSet": "$latest" }}},
 {"$match": {"latest": {$nin: [true]}}}
]);
```
