---
title: "SQL UDF Spec"
---
<!--
 - Licensed to the Apache Software Foundation (ASF) under one or more
 - contributor license agreements.  See the NOTICE file distributed with
 - this work for additional information regarding copyright ownership.
 - The ASF licenses this file to You under the Apache License, Version 2.0
 - (the "License"); you may not use this file except in compliance with
 - the License.  You may obtain a copy of the License at
 -
 -   http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
 -->

# Iceberg SQL UDF Spec

## Background and Motivation

A SQL user-defined function (UDF/UDTF) is a callable routine that accepts input parameters, executes a function body,
and returns either a single scalar value or a table depending on the UDF type.

* **Scalar functions (UDFs)**: accept one or more arguments and return a single scalar value.
* **Table functions (UDTFs)**: return a table with one or more rows and columns.

Most compute engines (e.g., Spark, Trino) support SQL UDFs. However, without a common standard,
UDFs cannot easily be shared across engines. This specification defines a standardized metadata format for UDFs in Iceberg,
enabling interoperability, reproducibility, and consistent versioning.

## Goals

* Define a portable metadata format for scalar and table SQL UDFs.

## Overview

UDF metadata storage mirrors Iceberg table and view metadata. Each UDF is tracked in metadata files.

* Every UDF update creates a new metadata file.
* Metadata tracks definitions, parameters, return types, documentation, security, properties, and engine-specific representations.
* Each metadata file is self-sufficient and contains recent version history.

## Specification

### Root Metadata

| Requirement | Field name                   | Description                                                 |
|-------------|------------------------------|-------------------------------------------------------------|
| *required*  | `function-uuid`              | A UUID that identifies the UDF, generated once at creation. |
| *required*  | `format-version`             | Metadata format version (must be `1`).                      |
| *required*  | `definition`                 | List of function overloads.                                 |
| *required*  | `definition-versions`        | List of versioned function definitions.                     |
| *required*  | `current-definition-version` | Identifier of the current active definition version.        |
| *optional*  | `location`                   | Base storage location of UDF metadata files.                | 
| *optional*  | `properties`                 | Arbitrary key-value metadata.                               | 
| *optional*  | `secure`                     | Security/privilege enforcement metadata. Default: `false`.  |

### Overload

Overloads allow multiple implementations of the same function name with different signatures.

| Requirement | Field name      | Description                                                            |
|-------------|-----------------|------------------------------------------------------------------------|
| *required*  | `overload-uuid` | A UUID that identifies a overload.                                     |
| *required*  | `parameters`    | List of parameters (name, type, optional doc).                         |
| *required*  | `return-type`   | Return type (scalar or struct). Example: `"string"` or `"struct<...>"` |
| *required*  | `versions`      | List of overload versions.                                             |
| *optional*  | `doc`           | Documentation string.                                                  |

### Overload-Version

| Requirement | Field name            | Description                                                                  |
|-------------|-----------------------|------------------------------------------------------------------------------|
| *required*  | `overload-version-id` | Identifier of this overload version. Example: `1`                            |
| *required*  | `representations`     | Dialect-specific implementations of this overload.                           |
| *optional*  | `deterministic`       | Whether the function is deterministic. Default: `false`.                     |
| *required*  | `timestamp-ms`        | Time when the overload version was created/updated. Example: `1734506000123` |

### Representation

| Requirement | Field name | Description                                            |
|-------------|------------|--------------------------------------------------------|
| *required*  | `type`     | Representation type. Must be `"sql"`.                  |
| *required*  | `dialect`  | SQL dialect identifier. Example: `"spark"`, `"trino"`. |
| *required*  | `body`     | SQL expression or body of the function.                |

### Definition-Version

| Requirement | Field name              | Description                                                                    |
|-------------|-------------------------|--------------------------------------------------------------------------------|
| *required*  | `definition-version-id` | Unique identifier of the definition version. Example: `2`                      |
| *required*  | `timestamp-ms`          | Timestamp when the definition was created or updated. Example: `1734506000456` |
| *required*  | `body`                  | List of mapping of overload uuids to their current version ids.                |

## Appendix A: Example

SQL statement:

```sql
CREATE FUNCTION add_one(x INT COMMENT 'Input integer')
COMMENT 'Add one to the input value'
RETURNS INT
RETURN x + 1;

CREATE FUNCTION add_one(x FLOAT COMMENT 'Input float')
COMMENT 'Add one to the input value'
RETURNS FLOAT
RETURN x + 1.0;
```

```json
{
  "function-uuid": "42fd3f91-bc10-41c1-8a52-92b57dd0a9b2",
  "format-version": 1,
  "definition": [
    {
      "overload-uuid": "d2c7dfe0-54a3-4d5f-a34d-2e8cfbc34111",
      "parameters": [
        { "name": "x", "type": "int", "doc": "Input integer" }
      ],
      "return-type": "int",
      "doc": "Add one to the input integer",
      "versions": [
        {
          "overload-version-id": 1,
          "deterministic": true,
          "representations": [
            {
              "type": "sql",
              "dialect": "trino",
              "body": "x + 1"
            }
          ],
          "timestamp-ms": 1734507000123
        }
      ]
    },
    {
      "overload-uuid": "7c9f93b1-28b4-4ef5-90f5-70c73cda2222",
      "parameters": [
        { "name": "x", "type": "float", "doc": "Input float" }
      ],
      "return-type": "float",
      "doc": "Add one to the input float",
      "versions": [
        {
          "overload-version-id": 1,
          "deterministic": true,
          "representations": [
            {
              "type": "sql",
              "dialect": "trino",
              "body": "x + 1.0"
            }
          ],
          "timestamp-ms": 1734507001123
        }
      ]
    }
  ],
  "definition-versions": [
    {
      "definition-version-id": 1,
      "timestamp-ms": 1734507001456,
      "body": [
        ["d2c7dfe0-54a3-4d5f-a34d-2e8cfbc34111", 1],
        ["7c9f93b1-28b4-4ef5-90f5-70c73cda2222", 1]
      ]
    }
  ],
  "current-definition-version": 1,
  "properties": { "comment": "Overloaded scalar UDF for integer and float inputs" },
  "secure": false
}
```

