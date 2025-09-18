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
UDFs cannot easily be shared across engines. This specification defines a standardized metadata format for UDFs in Iceberg,
enabling interoperability and consistent versioning across engines.

## Goals

* Define a portable metadata format for scalar and table SQL UDFs.

## Overview

UDF metadata storage mirrors how Iceberg table and view metadata is stored. UDF metadata is maintained in metadata files.

* Every UDF update creates a new metadata file, old metadata is replaced atomically.
* Metadata tracks function definitions, parameters, return types, properties, versions, and representations.

Each metadata file is self-sufficient and contains recent version history, enabling rollbacks to prior UDF definitions.

## Specification

### Root Metadata

| Requirement | Field name         | Description                                                        |
|-------------|--------------------|--------------------------------------------------------------------|
| *required*  | `function-uuid`    | A UUID that identifies the UDF, generated when the UDF is created. |
| *required*  | `format-version`   | Metadata format version (must be `1`).                             |
| *required*  | `signatures`       | List of function signatures.                                       |
| *required*  | `versions`         | List of version entries.                                           |
| *required*  | `current-version`  | The current version.                                               |
| *optional*  | `location`         | Base location used to store UDF metadata files.                    |
| *optional*  | `properties`       | Arbitrary key-value properties.                                    |
| *optional*  | `secure`           | Security/privilege enforcement metadata.                           |

### Signature Definitions

Each entry defines a logical function shape, reusable across versions and overloads.

| Requirement | Field name      | Description                                |
|-------------|-----------------|--------------------------------------------|
| *required*  | `signature-id`  | Unique ID for the signature.               |
| *required*  | `parameters`    | List of parameter specs (name, type, doc). |
| *required*  | `return-type`   | Scalar or struct type.                     |
| *optional*  | `doc`           | Documentation string.                      |

### Versions

Each `version` groups one or more overloads that share the same signature or extend it.

| Requirement | Field name      | Description                              |
|-------------|-----------------|------------------------------------------|
| *required*  | `version-id`    | Identifier for the version.              |
| *required*  | `overloads`     | List of overload entries.                |
| *optional*  | `summary`       | Key-value metadata about the version.    |
| *optional*  | `update-log`    | Tracks when overloads were updated.      |

### Overloads

Overloads allow multiple implementations of the same signature.

| Requirement | Field name         | Description                                                                 |
|-------------|--------------------|-----------------------------------------------------------------------------|
| *required*  | `overload-id`      | Unique ID within the version.                                               |
| *required*  | `signature-id`     | Reference to the signature definition.                                      |
| *optional*  | `deterministic`    | Boolean flag indicating deterministic behavior.                             |
| *required*  | `representations`  | List of representations (see below).                                        |
| *optional*  | `update-at-version`| Version number where this overload was last updated.                        |

### Representations

Each overload can have multiple dialect-specific representations, but at most one per dialect.

| Requirement | Field name     | Description                                      |
|-------------|----------------|--------------------------------------------------|
| *required*  | `rep-id`       | Identifier for the representation.               |
| *required*  | `dialect-type` | SQL dialect identifier (e.g., `spark`, `trino`). |
| *required*  | `body`         | SQL expression or query.                         |


### Version log (like metadata.json log in table format, do we need it?)

Tracks transitions of `current-version-id`.

| Requirement | Field name     | Description                  |
| ----------- | -------------- | ---------------------------- |
| *required*  | `timestamp-ms` | Timestamp of update.         |
| *required*  | `version-id`   | Version that became current. |

## Appendix A: Example

For the operation:

```sql
CREATE FUNCTION fruits_by_color(c VARCHAR COMMENT 'color of fruits')
COMMENT 'Return fruits of specific color from fruits table'
RETURNS TABLE (name VARCHAR, color VARCHAR)
RETURN SELECT name, color FROM fruits WHERE color = c;
```

## Example

```json
{
  "function-uuid": "018ec9ac-7680-7d39-b8a3-6c726bafd1aa",
  "format-version": 1,
  "signature-defs": [
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
      },
      "doc": "Return fruits of specific color from fruits table"
    }
  ],
  "versions": [
    {
      "version-id": "v1",
      "overloads": [
        {
          "overload-id": 1,
          "signature-id": 1,
          "deterministic": true,
          "representations": [
            {
              "rep-id": "rep1",
              "dialect-type": "dremio",
              "body": "SELECT name, color FROM fruits WHERE color = c"
            }
          ],
          "update-at-version": "v1"
        }
      ]
    }
  ],
  "open-properties": { "comment": "UDF for filtering fruits by color" },
  "secure": { "owner": "orchard-team" }
}
