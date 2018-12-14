/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.BackOff;
import com.google.api.client.util.BackOffUtils;
import com.google.api.client.util.ClassInfo;
import com.google.api.client.util.Data;
import com.google.api.client.util.Sleeper;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.BigqueryScopes;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.DatasetReference;
import com.google.api.services.bigquery.model.GetQueryResultsResponse;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationQuery;
import com.google.api.services.bigquery.model.QueryRequest;
import com.google.api.services.bigquery.model.QueryResponse;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableDataInsertAllRequest;
import com.google.api.services.bigquery.model.TableDataInsertAllRequest.Rows;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableList;
import com.google.api.services.bigquery.model.TableList.Tables;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.beam.sdk.util.BackOffAdapter;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.Transport;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper class to call Bigquery API calls.
 *
 * <p>Example:
 *
 * <p>Get a new Bigquery client:
 *
 * <pre>{@code [
 *    BigqueryClient client = BigqueryClient.getNewBigquerryClient(applicationName);
 * ]}</pre>
 *
 * <p>Execute a query with retries:
 *
 * <pre>{@code [
 *    QueryResponse response = client.queryWithRetries(queryString, projectId);
 * ]}</pre>
 *
 * <p>Create a new dataset in one project:
 *
 * <pre>{@code [
 *    client.createNewDataset(projectId, datasetId);
 * ]}</pre>
 *
 * <p>Delete a dataset in one project, included its all tables:
 *
 * <pre>{@code [
 *    client.deleteDataset(projectId, datasetId);
 * ]}</pre>
 *
 * <p>Create a new table
 *
 * <pre>{@code [
 *    client.createNewTable(projectId, datasetId, newTable)
 * ]}</pre>
 *
 * <p>Insert data into table
 *
 * <pre>{@code [
 *    client.insertDataToTable(projectId, datasetId, tableName, rows)
 * ]}</pre>
 */
public class BigqueryClient {
  private static final Logger LOG = LoggerFactory.getLogger(BigqueryClient.class);
  // The maximum number of retries to execute a BigQuery RPC
  static final int MAX_QUERY_RETRIES = 4;

  // The initial backoff for executing a BigQuery RPC
  private static final Duration INITIAL_BACKOFF = Duration.standardSeconds(1L);

  // The backoff factory with initial configs
  static final FluentBackoff BACKOFF_FACTORY =
      FluentBackoff.DEFAULT.withMaxRetries(MAX_QUERY_RETRIES).withInitialBackoff(INITIAL_BACKOFF);

  private static final Collection<String> RESERVED_FIELD_NAMES =
      ClassInfo.of(TableRow.class).getNames();

  private Bigquery bqClient;

  private static Credentials getDefaultCredential() {
    GoogleCredentials credential;
    try {
      credential = GoogleCredentials.getApplicationDefault();
    } catch (IOException e) {
      throw new RuntimeException("Failed to get application default credential.", e);
    }

    if (credential.createScopedRequired()) {
      Collection<String> bigqueryScope = Lists.newArrayList(BigqueryScopes.all());
      credential = credential.createScoped(bigqueryScope);
    }
    return credential;
  }

  public static Bigquery getNewBigquerryClient(String applicationName) {
    HttpTransport transport = Transport.getTransport();
    JsonFactory jsonFactory = Transport.getJsonFactory();
    Credentials credential = getDefaultCredential();
    return new Bigquery.Builder(transport, jsonFactory, new HttpCredentialsAdapter(credential))
        .setApplicationName(applicationName)
        .build();
  }

  public static BigqueryClient getClient(String applicationName) {
    return new BigqueryClient(applicationName);
  }

  public BigqueryClient(String applicationName) {
    bqClient = BigqueryClient.getNewBigquerryClient(applicationName);
  }

  @Nonnull
  public QueryResponse queryWithRetries(String query, String projectId)
      throws IOException, InterruptedException {
    return queryWithRetries(query, projectId, false);
  }

  @Nullable
  private Object getTypedCellValue(TableFieldSchema fieldSchema, Object v) {
    if (Data.isNull(v)) {
      return null;
    }

    if (Objects.equals(fieldSchema.getMode(), "REPEATED")) {
      TableFieldSchema elementSchema = fieldSchema.clone().setMode("REQUIRED");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rawCells = (List<Map<String, Object>>) v;
      ImmutableList.Builder<Object> values = ImmutableList.builder();
      for (Map<String, Object> element : rawCells) {
        values.add(getTypedCellValue(elementSchema, element.get("v")));
      }
      return values.build();
    }

    if ("RECORD".equals(fieldSchema.getType())) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typedV = (Map<String, Object>) v;
      return getTypedTableRow(fieldSchema.getFields(), typedV);
    }

    if ("FLOAT".equals(fieldSchema.getType())) {
      return Double.parseDouble((String) v);
    }

    if ("BOOLEAN".equals(fieldSchema.getType())) {
      return Boolean.parseBoolean((String) v);
    }

    if ("TIMESTAMP".equals(fieldSchema.getType())) {
      return (String) v;
    }

    // Returns the original value for:
    // 1. String, 2. base64 encoded BYTES, 3. DATE, DATETIME, TIME strings.
    return v;
  }

  private TableRow getTypedTableRow(List<TableFieldSchema> fields, Map<String, Object> rawRow) {
    TableRow row;
    List<? extends Map<String, Object>> cells;
    if (rawRow instanceof TableRow) {
      // Since rawRow is a TableRow it already has TableCell objects in setF. We do not need to do
      // any type conversion, but extract the cells for cell-wise processing below.
      row = (TableRow) rawRow;
      cells = row.getF();
      // Clear the cells from the row, so that row.getF() will return null. This matches the
      // behavior of rows produced by the BigQuery export API used on the service.
      row.setF(null);
    } else {
      row = new TableRow();

      // Since rawRow is a Map<String, Object> we use Map.get("f") instead of TableRow.getF() to
      // get its cells. Similarly, when rawCell is a Map<String, Object> instead of a TableCell,
      // we will use Map.get("v") instead of TableCell.getV() get its value.
      @SuppressWarnings("unchecked")
      List<? extends Map<String, Object>> rawCells =
          (List<? extends Map<String, Object>>) rawRow.get("f");
      cells = rawCells;
    }

    checkState(
        cells.size() == fields.size(),
        "Expected that the row has the same number of cells %s as fields in the schema %s",
        cells.size(),
        fields.size());

    // Loop through all the fields in the row, normalizing their types with the TableFieldSchema
    // and storing the normalized values by field name in the Map<String, Object> that
    // underlies the TableRow.
    Iterator<? extends Map<String, Object>> cellIt = cells.iterator();
    Iterator<TableFieldSchema> fieldIt = fields.iterator();
    while (cellIt.hasNext()) {
      Map<String, Object> cell = cellIt.next();
      TableFieldSchema fieldSchema = fieldIt.next();

      // Convert the object in this cell to the Java type corresponding to its type in the schema.
      Object convertedValue = getTypedCellValue(fieldSchema, cell.get("v"));

      String fieldName = fieldSchema.getName();
      checkArgument(
          !RESERVED_FIELD_NAMES.contains(fieldName),
          "BigQueryIO does not support records with columns named %s",
          fieldName);

      if (convertedValue == null) {
        // BigQuery does not include null values when the export operation (to JSON) is used.
        // To match that behavior, BigQueryTableRowiterator, and the DirectPipelineRunner,
        // intentionally omits columns with null values.
        continue;
      }

      row.set(fieldName, convertedValue);
    }
    return row;
  }

  private QueryResponse getTypedTableRows(QueryResponse response) {
    List<TableRow> rows = response.getRows();
    TableSchema schema = response.getSchema();
    response.setRows(
        rows.stream()
            .map(r -> getTypedTableRow(schema.getFields(), r))
            .collect(Collectors.toList()));
    return response;
  }

  @Nonnull
  public List<TableRow> queryUnflattened(String query, String projectId, boolean typed)
      throws IOException, InterruptedException {
    Random rnd = new Random(System.currentTimeMillis());
    String temporaryDatasetId = "_dataflow_temporary_dataset_" + rnd.nextInt(1000000);
    String temporaryTableId = "dataflow_temporary_table_" + rnd.nextInt(1000000);
    TableReference tempTableReference =
        new TableReference()
            .setProjectId(projectId)
            .setDatasetId(temporaryDatasetId)
            .setTableId(temporaryTableId);

    createNewDataset(projectId, temporaryDatasetId);
    createNewTable(
        projectId, temporaryDatasetId, new Table().setTableReference(tempTableReference));

    JobConfigurationQuery jcQuery =
        new JobConfigurationQuery()
            .setFlattenResults(false)
            .setAllowLargeResults(true)
            .setDestinationTable(tempTableReference)
            .setQuery(query);
    JobConfiguration jc = new JobConfiguration().setQuery(jcQuery);

    Job job = new Job().setConfiguration(jc);

    Job insertedJob = bqClient.jobs().insert(projectId, job).execute();

    GetQueryResultsResponse qResponse;
    do {
      qResponse =
          bqClient
              .jobs()
              .getQueryResults(projectId, insertedJob.getJobReference().getJobId())
              .execute();

    } while (!qResponse.getJobComplete());

    final TableSchema schema = qResponse.getSchema();
    final List<TableRow> rows = qResponse.getRows();
    deleteDataset(projectId, temporaryDatasetId);
    return !typed
        ? rows
        : rows.stream()
            .map(r -> getTypedTableRow(schema.getFields(), r))
            .collect(Collectors.toList());
  }

  @Nonnull
  public QueryResponse queryWithRetries(String query, String projectId, boolean typed)
      throws IOException, InterruptedException {
    Sleeper sleeper = Sleeper.DEFAULT;
    BackOff backoff = BackOffAdapter.toGcpBackOff(BACKOFF_FACTORY.backoff());
    IOException lastException = null;
    QueryRequest bqQueryRequest = new QueryRequest().setQuery(query);
    do {
      if (lastException != null) {
        LOG.warn("Retrying query ({}) after exception", bqQueryRequest.getQuery(), lastException);
      }
      try {
        QueryResponse response = bqClient.jobs().query(projectId, bqQueryRequest).execute();
        if (response != null) {
          return typed ? getTypedTableRows(response) : response;
        } else {
          lastException =
              new IOException("Expected valid response from query job, but received null.");
        }
      } catch (IOException e) {
        // ignore and retry
        lastException = e;
      }
    } while (BackOffUtils.next(sleeper, backoff));

    throw new RuntimeException(
        String.format(
            "Unable to get BigQuery response after retrying %d times using query (%s)",
            MAX_QUERY_RETRIES, bqQueryRequest.getQuery()),
        lastException);
  }

  public void createNewDataset(String projectId, String datasetId) {
    try {
      bqClient
          .datasets()
          .insert(
              projectId,
              new Dataset().setDatasetReference(new DatasetReference().setDatasetId(datasetId)))
          .execute();
      LOG.info("Successfully created new dataset : " + datasetId);
    } catch (Exception e) {
      LOG.debug("Exceptions caught when creating new dataset: " + e.getMessage());
    }
  }

  public void deleteTable(String projectId, String datasetId, String tableName) {
    try {
      bqClient.tables().delete(projectId, datasetId, tableName).execute();
      LOG.info("Successfully deleted table: " + tableName);
    } catch (Exception e) {
      LOG.debug("Exception caught when deleting table: " + e.getMessage());
    }
  }

  public void deleteDataset(String projectId, String datasetId) {
    try {
      TableList tables = bqClient.tables().list(projectId, datasetId).execute();
      for (Tables table : tables.getTables()) {
        this.deleteTable(projectId, datasetId, table.getTableReference().getTableId());
      }
    } catch (Exception e) {
      LOG.debug("Exceptions caught when listing all tables: " + e.getMessage());
    }

    try {
      bqClient.datasets().delete(projectId, datasetId).execute();
      LOG.info("Successfully deleted dataset: " + datasetId);
    } catch (Exception e) {
      LOG.debug("Exceptions caught when deleting dataset: " + e.getMessage());
    }
  }

  public void createNewTable(String projectId, String datasetId, Table newTable) {
    try {
      this.bqClient.tables().insert(projectId, datasetId, newTable).execute();
      LOG.info("Successfully created new table: " + newTable.getId());
    } catch (Exception e) {
      LOG.debug("Exceptions caught when creating new table: " + e.getMessage());
    }
  }

  public void insertDataToTable(
      String projectId, String datasetId, String tableName, List<Map<String, Object>> rows) {
    try {
      List<Rows> dataRows =
          rows.stream().map(row -> new Rows().setJson(row)).collect(Collectors.toList());
      this.bqClient
          .tabledata()
          .insertAll(
              projectId, datasetId, tableName, new TableDataInsertAllRequest().setRows(dataRows))
          .execute();
      LOG.info("Successfully inserted data into table : " + tableName);
    } catch (Exception e) {
      LOG.debug("Exceptions caught when inserting data: " + e.getMessage());
    }
  }
}