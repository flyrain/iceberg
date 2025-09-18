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

* **Scalar functions**: accept one or more arguments and return a single value.
* **Table functions (UDTFs)**: return a table with multiple rows and columns.

Most compute engines (e.g., Spark, Trino) support SQL UDFs. However, without a common standard,
UDFs cannot easily be shared across engines. This specification establishes a standardized metadata format for UDFs in Iceberg,
enabling interoperability, versioning across engines.

## Goals

* A common metadata format for scalar and table SQL UDFs.

## Overview

UDF metadata storage mirrors how Iceberg table and view metadata is stored. UDF metadata is maintained in metadata files.

* Every UDF update creates a new metadata file, old metadata is replaced atomically.
* Metadata tracks function definitions, parameters, return types, properties, versions, and representations.

Each metadata file is self-sufficient and contains recent version history, enabling rollbacks to prior UDF definitions.

## Specification

### Terms

* **UDF Definition** — Parameters, return type, and function body.
* **Signature** — A unique combination of parameter types and return type.
* **Version** — The state of a UDF definition at a point in time.

### UDF Metadata

| Requirement | Field name           | Description                                                                  |
| ----------- |----------------------|------------------------------------------------------------------------------|
| *required*  | `function-uuid`      | UUID for the UDF, set at creation. Must remain stable.                       |
| *required*  | `format-version`     | Integer version of the function metadata format; must be `1`.                |
| *optional*  | `location`           | Base location used to store UDF metadata files.                              |
| *required*  | `signatures`         | List of function signatures (parameters and return type).                    |
| *required*  | `return-type`        | Return type (scalar type or struct for TABLE UDF).                           |
| *required*  | `current-version-id` | ID of the currently active UDF version.                                      |
| *required*  | `versions`           | List of known [versions](#versions) of the function.                         |
| *optional*  | `version-log`        | List of version change entries.                                              |
| *optional*  | `properties`         | Key-value map for metadata (e.g., `comment`, `version.history.num-entries`). |
| *required*  | `function-type`      | One of `SCALAR` or `TABLE`. Immutable after creation.                        |

### Function Signature

Each entry in `signatures` includes:

| Requirement | Field name     | Description                                        |
| ----------- | -------------- | -------------------------------------------------- |
| *required*  | `signature-id` | Unique ID for the function signature.              |
| *required*  | `parameters`   | List of parameter fields (name, type, doc).        |
| *required*  | `return-type`  | Return type (scalar type or struct for TABLE UDF). |

### Versions

Each version in `versions` includes:

| Requirement | Field name          | Description                                                              |
| ----------- | ------------------- | ------------------------------------------------------------------------ |
| *required*  | `version-id`        | ID for the version.                                                      |
| *required*  | `signature-id`      | ID of the function signature used.                                       |
| *required*  | `timestamp-ms`      | Time of version creation (ms since epoch).                               |
| *optional*  | `summary`           | Key-value summary (e.g., `engine-name`, `engine-version`).               |
| *required*  | `representations`   | List of [representations](#representations) for the function definition. |
| *optional*  | `default-catalog`   | Catalog to use when references omit catalog.                             |
| *required*  | `default-namespace` | Namespace to use when references omit namespace.                         |
| *optional*  | `comment`           | User-provided comment for the function.                                  |

### Summary

Metadata map for each version. Common keys:

* `engine-name` — Engine that created the version.
* `engine-version` — Version of the engine.

### Representations

Function bodies can be expressed in different formats. In this spec:

* **SQL representation** is supported.
* A function version can include multiple SQL representations (different dialects), but at most one per dialect.

| Requirement | Field name | Description                                                  |
| ----------- | ---------- | ------------------------------------------------------------ |
| *required*  | `type`     | Must be `"sql"`.                                             |
| *required*  | `dialect`  | SQL dialect (`trino`, `snowflake`, `spark`, `dremio`, etc.). |
| *required*  | `body`     | SQL expression or query implementing the function.           |

### Version log

Tracks transitions of `current-version-id`.

| Requirement | Field name     | Description                  |
| ----------- | -------------- | ---------------------------- |
| *required*  | `timestamp-ms` | Timestamp of update.         |
| *required*  | `version-id`   | Version that became current. |

### (De-)serialization & Compatibility

* Parsing begins with `format-version`.
* JSON structure is validated per this spec.
* Compatible evolution: new format versions must preserve backward compatibility.

## Appendix A: Example

For the operation:

```sql
CREATE FUNCTION fruits_by_color(c VARCHAR COMMENT 'color of fruits')
COMMENT 'Return fruits of specific color from fruits table'
RETURNS TABLE (name VARCHAR, color VARCHAR)
RETURN SELECT name, color FROM fruits WHERE color = c;
```

The serialized metadata:

```json
{
  "function-uuid": "018ec9ac-7680-7d39-b8a3-6c726bafd1aa",
  "format-version": 1,
  "location": "file:/var/orchard/fruits_by_color",
  "signatures": [
    {
      "signature-id": 1,
      "parameters": [
        { "name": "c", "type": "string", "doc": "color of fruits" }
      ],
      "return-type": {
        "type": "struct",
        "fields": [
          { "id": 1, "name": "name", "type": "string" },
          { "id": 2, "name": "color", "type": "string" }
        ]
      }
    }
  ],
  "current-version-id": 1,
  "versions": [
    {
      "version-id": 1,
      "signature-id": 1,
      "timestamp-ms": 1712780589806,
      "summary": { "engine-name": "Dremio", "engine-version": "25.0.0" },
      "representations": [
        {
          "type": "sql",
          "dialect": "dremio",
          "body": "SELECT name, color FROM fruits WHERE color = c"
        }
      ],
      "default-catalog": "prod",
      "default-namespace": ["orchard"],
      "comment": "Return fruits of specific color from fruits table"
    }
  ],
  "version-log": [
    { "timestamp-ms": 1712780589806, "version-id": 1 }
  ],
  "function-type": "TABLE"
}
```