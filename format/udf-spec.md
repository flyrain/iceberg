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

| Requirement | Field name                   | Description                                                        |
|-------------|------------------------------|--------------------------------------------------------------------|
| *required*  | `function-uuid`              | A UUID that identifies the UDF, generated when the UDF is created. |
| *required*  | `format-version`             | Metadata format version (must be `1`).                             |
| *required*  | `definition`                 | List of overload entries.                                          |
| *required*  | `definition-versions`        | List of versioned definitions.                                     |
| *required*  | `current-definition-version` | The current definition version id.                                 |
| *optional*  | `location`                   | Base location used to store UDF metadata files.                    |
| *optional*  | `properties`                 | Arbitrary key-value properties.                                    |
| *optional*  | `secure`                     | Security/privilege enforcement metadata, default to `false`        |

### Overload

Overloads allow multiple implementations of the same function name with different signatures. One overload is with one
signature with different dialects of representations.

| Requirement | Field name        | Description                                     |
|-------------|-------------------|-------------------------------------------------|
| *required*  | `overload-id`     | Unique ID within the version.                   |
| *required*  | `parameters`      | List of parameter specs (name, type, doc).      |
| *required*  | `return-type`     | Scalar or struct type.                          |
| *required*  | `versions`        | List of version entries.                        |
| *optional*  | `doc`             | Documentation string.                           |

### Overload-version

| Requirement | Field name            | Description                                                        |
|-------------|-----------------------|--------------------------------------------------------------------|
| *required*  | `overload-version-id` | Identifier for the version.                                        |
| *required*  | `representations`     | List of dialect-specific representations                           |
| *optional*  | `deterministic`       | Boolean flag indicating deterministic behavior, default to `false` |
| *required*  | `timestamp-ms`        | Timestamp of update.                                               |

### Representation

| Requirement | Field name | Description                                      |
|-------------|------------|--------------------------------------------------|
| *required*  | `type`     | Must be `sql`                                    |
| *required*  | `dialect`  | SQL dialect identifier (e.g., `spark`, `trino`). |
| *required*  | `body`     | SQL expression.                                  |

### Definition-version

| Requirement | Field name              | Description                                             |
|-------------|-------------------------|---------------------------------------------------------|
| *required*  | `definition-version-id` | The version id of a definition.                         |
| *required*  | `timestamp-ms`          | Timestamp of update.                                    |
| *required*  | `body`                  | List of pairs of (`overload id`, `overload-version-id`) |

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
