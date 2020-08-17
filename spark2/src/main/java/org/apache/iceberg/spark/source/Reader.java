/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.spark.source;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.CombinedScanTask;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SerializableTable;
import org.apache.iceberg.SnapshotSummary;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.hadoop.Util;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.spark.SparkFilters;
import org.apache.iceberg.spark.SparkReadOptions;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.SparkUtil;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.TableScanUtil;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.RuntimeConfig;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.sources.v2.DataSourceOptions;
import org.apache.spark.sql.sources.v2.reader.DataSourceReader;
import org.apache.spark.sql.sources.v2.reader.InputPartition;
import org.apache.spark.sql.sources.v2.reader.InputPartitionReader;
import org.apache.spark.sql.sources.v2.reader.Statistics;
import org.apache.spark.sql.sources.v2.reader.SupportsPushDownFilters;
import org.apache.spark.sql.sources.v2.reader.SupportsPushDownRequiredColumns;
import org.apache.spark.sql.sources.v2.reader.SupportsReportStatistics;
import org.apache.spark.sql.sources.v2.reader.SupportsScanColumnarBatch;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.vectorized.ColumnarBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Reader implements DataSourceReader, SupportsScanColumnarBatch, SupportsPushDownFilters,
    SupportsPushDownRequiredColumns, SupportsReportStatistics {
  private static final Logger LOG = LoggerFactory.getLogger(Reader.class);

  private static final Filter[] NO_FILTERS = new Filter[0];
  private static final ImmutableSet<String> LOCALITY_WHITELIST_FS = ImmutableSet.of("hdfs");

  private final JavaSparkContext sparkContext;
  private final Table table;
  private final DataSourceOptions options;
  private final Long snapshotId;
  private final Long startSnapshotId;
  private final Long endSnapshotId;
  private final Long asOfTimestamp;
  private final Long splitSize;
  private final Integer splitLookback;
  private final Long splitOpenFileCost;
  private final boolean caseSensitive;
  private StructType requestedSchema = null;
  private List<Expression> filterExpressions = null;
  private Filter[] pushedFilters = NO_FILTERS;
  private final boolean localityPreferred;
  private final boolean readTimestampWithoutZone;

  // lazy variables
  private Schema schema = null;
  private StructType type = null; // cached because Spark accesses it multiple times
  private List<CombinedScanTask> tasks = null; // lazy cache of tasks
  private Boolean readUsingBatch = null;
  private int batchSize = 0;

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  Reader(SparkSession spark, Table table, boolean caseSensitive, DataSourceOptions options) {
    this.sparkContext = JavaSparkContext.fromSparkContext(spark.sparkContext());
    this.table = table;
    this.options = options;
    this.snapshotId = options.get(SparkReadOptions.SNAPSHOT_ID).map(Long::parseLong).orElse(null);
    this.asOfTimestamp = options.get(SparkReadOptions.AS_OF_TIMESTAMP).map(Long::parseLong).orElse(null);
    if (snapshotId != null && asOfTimestamp != null) {
      throw new IllegalArgumentException(
          "Cannot scan using both snapshot-id and as-of-timestamp to select the table snapshot");
    }

    this.startSnapshotId = options.get("start-snapshot-id").map(Long::parseLong).orElse(null);
    this.endSnapshotId = options.get("end-snapshot-id").map(Long::parseLong).orElse(null);
    if (snapshotId != null || asOfTimestamp != null) {
      if (startSnapshotId != null || endSnapshotId != null) {
        throw new IllegalArgumentException(
            "Cannot specify start-snapshot-id and end-snapshot-id to do incremental scan when either snapshot-id or " +
                "as-of-timestamp is specified");
      }
    } else {
      if (startSnapshotId == null && endSnapshotId != null) {
        throw new IllegalArgumentException("Cannot only specify option end-snapshot-id to do incremental scan");
      }
    }

    // look for split behavior overrides in options
    this.splitSize = options.get(SparkReadOptions.SPLIT_SIZE).map(Long::parseLong).orElse(null);
    this.splitLookback = options.get(SparkReadOptions.LOOKBACK).map(Integer::parseInt).orElse(null);
    this.splitOpenFileCost = options.get(SparkReadOptions.FILE_OPEN_COST).map(Long::parseLong).orElse(null);

    boolean localityOption = options.get("locality").map(Boolean::parseBoolean).orElse(false);
    if (localityOption && table.io() instanceof HadoopFileIO) {
      String scheme = "no_exist";
      try {
        Configuration conf = SparkSession.active().sessionState().newHadoopConf();
        // merge hadoop config set on table
        mergeIcebergHadoopConfs(conf, table.properties());
        // merge hadoop config passed as options and overwrite the one on table
        mergeIcebergHadoopConfs(conf, options.asMap());
        FileSystem fs = new Path(table.location()).getFileSystem(conf);
        scheme = fs.getScheme().toLowerCase(Locale.ENGLISH);
      } catch (IOException ioe) {
        LOG.warn("Failed to get Hadoop Filesystem", ioe);
      }
      this.localityPreferred = LOCALITY_WHITELIST_FS.contains(scheme);
    } else {
      this.localityPreferred = false;
    }

    this.schema = table.schema();
    this.caseSensitive = caseSensitive;
    this.batchSize = options.get(SparkReadOptions.VECTORIZATION_BATCH_SIZE).map(Integer::parseInt).orElseGet(() ->
        PropertyUtil.propertyAsInt(table.properties(),
          TableProperties.PARQUET_BATCH_SIZE, TableProperties.PARQUET_BATCH_SIZE_DEFAULT));
    RuntimeConfig sessionConf = SparkSession.active().conf();
    this.readTimestampWithoutZone = SparkUtil.canHandleTimestampWithoutZone(options.asMap(), sessionConf);
  }

  private Schema lazySchema() {
    if (schema == null) {
      if (requestedSchema != null) {
        // the projection should include all columns that will be returned, including those only used in filters
        this.schema = SparkSchemaUtil.prune(table.schema(), requestedSchema, filterExpression(), caseSensitive);
      } else {
        this.schema = table.schema();
      }
    }
    return schema;
  }

  private Expression filterExpression() {
    if (filterExpressions != null) {
      return filterExpressions.stream().reduce(Expressions.alwaysTrue(), Expressions::and);
    }
    return Expressions.alwaysTrue();
  }

  private StructType lazyType() {
    if (type == null) {
      Preconditions.checkArgument(readTimestampWithoutZone || !SparkUtil.hasTimestampWithoutZone(lazySchema()),
              SparkUtil.TIMESTAMP_WITHOUT_TIMEZONE_ERROR);
      this.type = SparkSchemaUtil.convert(lazySchema());
    }
    return type;
  }

  @Override
  public StructType readSchema() {
    return lazyType();
  }

  /**
   * This is called in the Spark Driver when data is to be materialized into {@link ColumnarBatch}
   */
  @Override
  public List<InputPartition<ColumnarBatch>> planBatchInputPartitions() {
    Preconditions.checkState(enableBatchRead(), "Batched reads not enabled");
    Preconditions.checkState(batchSize > 0, "Invalid batch size");
    String expectedSchemaString = SchemaParser.toJson(lazySchema());

    ValidationException.check(tasks().stream().noneMatch(TableScanUtil::hasDeletes),
        "Cannot scan table %s: cannot apply required delete files", table);

    // broadcast the table metadata as input partitions will be sent to executors
    Broadcast<Table> tableBroadcast = sparkContext.broadcast(SerializableTable.copyOf(table));

    List<CombinedScanTask> scanTasks = tasks();
    InputPartition<ColumnarBatch>[] readTasks = new InputPartition[scanTasks.size()];

    Tasks.range(readTasks.length)
        .stopOnFailure()
        .executeWith(localityPreferred ? ThreadPools.getWorkerPool() : null)
        .run(index -> readTasks[index] = new ReadTask<>(
            scanTasks.get(index), tableBroadcast, expectedSchemaString, caseSensitive,
            localityPreferred, new BatchReaderFactory(batchSize)));
    LOG.info("Batching input partitions with {} tasks.", readTasks.length);

    return Arrays.asList(readTasks);
  }

  /**
   * This is called in the Spark Driver when data is to be materialized into {@link InternalRow}
   */
  @Override
  public List<InputPartition<InternalRow>> planInputPartitions() {
    String expectedSchemaString = SchemaParser.toJson(lazySchema());

    // broadcast the table metadata as input partitions will be sent to executors
    Broadcast<Table> tableBroadcast = sparkContext.broadcast(SerializableTable.copyOf(table));

    List<CombinedScanTask> scanTasks = tasks();
    InputPartition<InternalRow>[] readTasks = new InputPartition[scanTasks.size()];

    Tasks.range(readTasks.length)
        .stopOnFailure()
        .executeWith(localityPreferred ? ThreadPools.getWorkerPool() : null)
        .run(index -> readTasks[index] = new ReadTask<>(
            scanTasks.get(index), tableBroadcast, expectedSchemaString, caseSensitive,
            localityPreferred, InternalRowReaderFactory.INSTANCE));

    return Arrays.asList(readTasks);
  }

  @Override
  public Filter[] pushFilters(Filter[] filters) {
    this.tasks = null; // invalidate cached tasks, if present

    List<Expression> expressions = Lists.newArrayListWithExpectedSize(filters.length);
    List<Filter> pushed = Lists.newArrayListWithExpectedSize(filters.length);

    for (Filter filter : filters) {
      Expression expr = SparkFilters.convert(filter);
      if (expr != null) {
        expressions.add(expr);
        pushed.add(filter);
      }
    }

    this.filterExpressions = expressions;
    this.pushedFilters = pushed.toArray(new Filter[0]);

    // invalidate the schema that will be projected
    this.schema = null;
    this.type = null;

    // Spark doesn't support residuals per task, so return all filters
    // to get Spark to handle record-level filtering
    return filters;
  }

  @Override
  public Filter[] pushedFilters() {
    return pushedFilters;
  }

  @Override
  public void pruneColumns(StructType newRequestedSchema) {
    this.requestedSchema = newRequestedSchema;

    // invalidate the schema that will be projected
    this.schema = null;
    this.type = null;
  }

  @Override
  public Statistics estimateStatistics() {
    // its a fresh table, no data
    if (table.currentSnapshot() == null) {
      return new Stats(0L, 0L);
    }

    // estimate stats using snapshot summary only for partitioned tables (metadata tables are unpartitioned)
    if (!table.spec().isUnpartitioned() && filterExpression() == Expressions.alwaysTrue()) {
      long totalRecords = PropertyUtil.propertyAsLong(table.currentSnapshot().summary(),
          SnapshotSummary.TOTAL_RECORDS_PROP, Long.MAX_VALUE);
      return new Stats(SparkSchemaUtil.estimateSize(lazyType(), totalRecords), totalRecords);
    }

    long numRows = 0L;

    for (CombinedScanTask task : tasks()) {
      for (FileScanTask file : task.files()) {
        numRows += file.file().recordCount();
      }
    }

    long sizeInBytes = SparkSchemaUtil.estimateSize(lazyType(), numRows);
    return new Stats(sizeInBytes, numRows);
  }

  @Override
  public boolean enableBatchRead() {
    if (readUsingBatch == null) {
      boolean allParquetFileScanTasks =
          tasks().stream()
              .allMatch(combinedScanTask -> !combinedScanTask.isDataTask() && combinedScanTask.files()
                  .stream()
                  .allMatch(fileScanTask -> fileScanTask.file().format().equals(
                      FileFormat.PARQUET)));

      boolean allOrcFileScanTasks =
          tasks().stream()
              .allMatch(combinedScanTask -> !combinedScanTask.isDataTask() && combinedScanTask.files()
                  .stream()
                  .allMatch(fileScanTask -> fileScanTask.file().format().equals(
                      FileFormat.ORC)));

      boolean atLeastOneColumn = lazySchema().columns().size() > 0;

      boolean onlyPrimitives = lazySchema().columns().stream().allMatch(c -> c.type().isPrimitiveType());

      boolean hasNoDeleteFiles = tasks().stream().noneMatch(TableScanUtil::hasDeletes);

      boolean batchReadsEnabled = batchReadsEnabled(allParquetFileScanTasks, allOrcFileScanTasks);

      this.readUsingBatch = batchReadsEnabled && hasNoDeleteFiles && (allOrcFileScanTasks ||
          (allParquetFileScanTasks && atLeastOneColumn && onlyPrimitives));

      if (readUsingBatch) {
        this.batchSize = batchSize(allParquetFileScanTasks, allOrcFileScanTasks);
      }
    }
    return readUsingBatch;
  }

  private boolean batchReadsEnabled(boolean isParquetOnly, boolean isOrcOnly) {
    if (isParquetOnly) {
      return isVectorizationEnabled(FileFormat.PARQUET);
    } else if (isOrcOnly) {
      return isVectorizationEnabled(FileFormat.ORC);
    } else {
      return false;
    }
  }

  public boolean isVectorizationEnabled(FileFormat fileFormat) {
    String readOptionValue = options.get(SparkReadOptions.VECTORIZATION_ENABLED).orElse(null);
    if (readOptionValue != null) {
      return Boolean.parseBoolean(readOptionValue);
    }

    RuntimeConfig sessionConf = SparkSession.active().conf();
    String sessionConfValue = sessionConf.get("spark.sql.iceberg.vectorization.enabled", null);
    if (sessionConfValue != null) {
      return Boolean.parseBoolean(sessionConfValue);
    }

    switch (fileFormat) {
      case PARQUET:
        return PropertyUtil.propertyAsBoolean(
            table.properties(),
            TableProperties.PARQUET_VECTORIZATION_ENABLED,
            TableProperties.PARQUET_VECTORIZATION_ENABLED_DEFAULT);
      case ORC:
        return PropertyUtil.propertyAsBoolean(
            table.properties(),
            TableProperties.ORC_VECTORIZATION_ENABLED,
            TableProperties.ORC_VECTORIZATION_ENABLED_DEFAULT);
      default:
        return false;
    }
  }

  private int batchSize(boolean isParquetOnly, boolean isOrcOnly) {
    if (isParquetOnly) {
      return batchSize(FileFormat.PARQUET);
    } else if (isOrcOnly) {
      return batchSize(FileFormat.ORC);
    } else {
      return 0;
    }
  }

  private int batchSize(FileFormat fileFormat) {
    String readOptionValue = options.asMap().get(SparkReadOptions.VECTORIZATION_BATCH_SIZE);
    if (readOptionValue != null) {
      return Integer.parseInt(readOptionValue);
    }

    switch (fileFormat) {
      case PARQUET:
        return PropertyUtil.propertyAsInt(
            table.properties(),
            TableProperties.PARQUET_BATCH_SIZE,
            TableProperties.PARQUET_BATCH_SIZE_DEFAULT);

      case ORC:
        return PropertyUtil.propertyAsInt(
            table.properties(),
            TableProperties.ORC_BATCH_SIZE,
            TableProperties.ORC_BATCH_SIZE_DEFAULT);

      default:
        throw new IllegalArgumentException("File format does not support batch reads: " + fileFormat);
    }
  }

  private static void mergeIcebergHadoopConfs(
      Configuration baseConf, Map<String, String> options) {
    options.keySet().stream()
        .filter(key -> key.startsWith("hadoop."))
        .forEach(key -> baseConf.set(key.replaceFirst("hadoop.", ""), options.get(key)));
  }

  private List<CombinedScanTask> tasks() {
    if (tasks == null) {
      TableScan scan = table
          .newScan()
          .caseSensitive(caseSensitive)
          .project(lazySchema());

      if (snapshotId != null) {
        scan = scan.useSnapshot(snapshotId);
      }

      if (asOfTimestamp != null) {
        scan = scan.asOfTime(asOfTimestamp);
      }

      if (startSnapshotId != null) {
        if (endSnapshotId != null) {
          scan = scan.appendsBetween(startSnapshotId, endSnapshotId);
        } else {
          scan = scan.appendsAfter(startSnapshotId);
        }
      }

      if (splitSize != null) {
        scan = scan.option(TableProperties.SPLIT_SIZE, splitSize.toString());
      }

      if (splitLookback != null) {
        scan = scan.option(TableProperties.SPLIT_LOOKBACK, splitLookback.toString());
      }

      if (splitOpenFileCost != null) {
        scan = scan.option(TableProperties.SPLIT_OPEN_FILE_COST, splitOpenFileCost.toString());
      }

      if (filterExpressions != null) {
        for (Expression filter : filterExpressions) {
          scan = scan.filter(filter);
        }
      }

      try (CloseableIterable<CombinedScanTask> tasksIterable = scan.planTasks()) {
        this.tasks = Lists.newArrayList(tasksIterable);
      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to close table scan: %s", scan);
      }
    }

    return tasks;
  }

  @Override
  public String toString() {
    return String.format(
        "IcebergScan(table=%s, type=%s, filters=%s, caseSensitive=%s, batchedReads=%s)",
        table, lazySchema().asStruct(), filterExpressions, caseSensitive, enableBatchRead());
  }

  private static class ReadTask<T> implements Serializable, InputPartition<T> {
    private final CombinedScanTask task;
    private final Broadcast<Table> tableBroadcast;
    private final String expectedSchemaString;
    private final boolean caseSensitive;
    private final boolean localityPreferred;
    private final ReaderFactory<T> readerFactory;

    private transient Schema expectedSchema = null;
    private transient String[] preferredLocations = null;

    private ReadTask(CombinedScanTask task, Broadcast<Table> tableBroadcast, String expectedSchemaString,
                     boolean caseSensitive, boolean localityPreferred, ReaderFactory<T> readerFactory) {
      this.task = task;
      this.tableBroadcast = tableBroadcast;
      this.expectedSchemaString = expectedSchemaString;
      this.caseSensitive = caseSensitive;
      this.localityPreferred = localityPreferred;
      this.preferredLocations = getPreferredLocations();
      this.readerFactory = readerFactory;
    }

    @Override
    public InputPartitionReader<T> createPartitionReader() {
      Table table = tableBroadcast.value();
      return readerFactory.create(task, table, lazyExpectedSchema(), caseSensitive);
    }

    @Override
    public String[] preferredLocations() {
      return preferredLocations;
    }

    private Schema lazyExpectedSchema() {
      if (expectedSchema == null) {
        this.expectedSchema = SchemaParser.fromJson(expectedSchemaString);
      }
      return expectedSchema;
    }

    @SuppressWarnings("checkstyle:RegexpSingleline")
    private String[] getPreferredLocations() {
      if (!localityPreferred) {
        return new String[0];
      }

      Configuration conf = SparkSession.active().sparkContext().hadoopConfiguration();
      return Util.blockLocations(task, conf);
    }
  }

  private interface ReaderFactory<T> extends Serializable {
    InputPartitionReader<T> create(CombinedScanTask task, Table table, Schema expectedSchema, boolean caseSensitive);
  }

  private static class InternalRowReaderFactory implements ReaderFactory<InternalRow> {
    private static final InternalRowReaderFactory INSTANCE = new InternalRowReaderFactory();

    private InternalRowReaderFactory() {
    }

    @Override
    public InputPartitionReader<InternalRow> create(CombinedScanTask task, Table table,
                                                    Schema expectedSchema, boolean caseSensitive) {
      return new RowReader(task, table, expectedSchema, caseSensitive);
    }
  }

  private static class BatchReaderFactory implements ReaderFactory<ColumnarBatch> {
    private final int batchSize;

    BatchReaderFactory(int batchSize) {
      this.batchSize = batchSize;
    }

    @Override
    public InputPartitionReader<ColumnarBatch> create(CombinedScanTask task, Table table,
                                                      Schema expectedSchema, boolean caseSensitive) {
      return new BatchReader(task, table, expectedSchema, caseSensitive, batchSize);
    }
  }

  private static class RowReader extends RowDataReader implements InputPartitionReader<InternalRow> {
    RowReader(CombinedScanTask task, Table table, Schema expectedSchema, boolean caseSensitive) {
      super(task, table, expectedSchema, caseSensitive);
    }
  }

  private static class BatchReader extends BatchDataReader implements InputPartitionReader<ColumnarBatch> {
    BatchReader(CombinedScanTask task, Table table, Schema expectedSchema, boolean caseSensitive, int size) {
      super(task, table, expectedSchema, caseSensitive, size);
    }
  }
}
