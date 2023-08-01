import logging
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, Iterator, List, Optional

from google.cloud import bigquery
from google.cloud.bigquery.table import (
    RowIterator,
    TableListItem,
    TimePartitioning,
    TimePartitioningType,
)

from datahub.ingestion.source.bigquery_v2.bigquery_audit import BigqueryTableIdentifier
from datahub.ingestion.source.bigquery_v2.bigquery_report import BigQueryV2Report
from datahub.ingestion.source.sql.sql_generic import BaseColumn, BaseTable, BaseView

logger: logging.Logger = logging.getLogger(__name__)


class BigqueryTableType:
    # See https://cloud.google.com/bigquery/docs/information-schema-tables#schema
    BASE_TABLE = "BASE TABLE"
    EXTERNAL = "EXTERNAL"
    VIEW = "VIEW"
    MATERIALIZED_VIEW = "MATERIALIZED VIEW"
    CLONE = "CLONE"
    SNAPSHOT = "SNAPSHOT"


@dataclass
class BigqueryColumn(BaseColumn):
    field_path: str
    is_partition_column: bool


RANGE_PARTITION_NAME: str = "RANGE"


@dataclass
class PartitionInfo:
    field: str
    # Data type is optional as we not have it when we set it from TimePartitioning
    column: Optional[BigqueryColumn] = None
    type: str = TimePartitioningType.DAY
    expiration_ms: Optional[int] = None
    require_partition_filter: bool = False

    # TimePartitioning field doesn't provide data_type so we have to add it afterwards
    @classmethod
    def from_time_partitioning(
        cls, time_partitioning: TimePartitioning
    ) -> "PartitionInfo":
        return cls(
            field=time_partitioning.field
            if time_partitioning.field
            else "_PARTITIONTIME",
            type=time_partitioning.type_,
            expiration_ms=time_partitioning.expiration_ms,
            require_partition_filter=time_partitioning.require_partition_filter,
        )

    @classmethod
    def from_range_partitioning(
        cls, range_partitioning: Dict[str, Any]
    ) -> Optional["PartitionInfo"]:
        field: Optional[str] = range_partitioning.get("field")
        if not field:
            return None

        return cls(
            field=field,
            type="RANGE",
        )

    @classmethod
    def from_table_info(cls, table_info: TableListItem) -> Optional["PartitionInfo"]:
        RANGE_PARTITIONING_KEY: str = "rangePartitioning"

        if table_info.time_partitioning:
            return PartitionInfo.from_time_partitioning(table_info.time_partitioning)
        elif RANGE_PARTITIONING_KEY in table_info._properties:
            return PartitionInfo.from_range_partitioning(
                table_info._properties[RANGE_PARTITIONING_KEY]
            )
        else:
            return None


@dataclass
class BigqueryTable(BaseTable):
    expires: Optional[datetime] = None
    clustering_fields: Optional[List[str]] = None
    labels: Optional[Dict[str, str]] = None
    num_partitions: Optional[int] = None
    max_partition_id: Optional[str] = None
    max_shard_id: Optional[str] = None
    active_billable_bytes: Optional[int] = None
    long_term_billable_bytes: Optional[int] = None
    partition_info: Optional[PartitionInfo] = None
    columns_ignore_from_profiling: List[str] = field(default_factory=list)


@dataclass
class BigqueryView(BaseView):
    columns: List[BigqueryColumn] = field(default_factory=list)
    materialized: bool = False


@dataclass
class BigqueryDataset:
    name: str
    labels: Optional[Dict[str, str]] = None
    created: Optional[datetime] = None
    last_altered: Optional[datetime] = None
    location: Optional[str] = None
    comment: Optional[str] = None
    tables: List[BigqueryTable] = field(default_factory=list)
    views: List[BigqueryView] = field(default_factory=list)
    columns: List[BigqueryColumn] = field(default_factory=list)


@dataclass
class BigqueryProject:
    id: str
    name: str
    datasets: List[BigqueryDataset] = field(default_factory=list)


class BigqueryQuery:
    show_datasets: str = (
        "select schema_name from `{project_id}`.INFORMATION_SCHEMA.SCHEMATA"
    )

    datasets_for_project_id: str = """
select
  s.CATALOG_NAME as catalog_name,
  s.schema_name as table_schema,
  s.location as location,
  s.CREATION_TIME as created,
  s.LAST_MODIFIED_TIME as last_altered,
  o.OPTION_VALUE as comment
from
  `{project_id}`.INFORMATION_SCHEMA.SCHEMATA as s
  left join `{project_id}`.INFORMATION_SCHEMA.SCHEMATA_OPTIONS as o on o.schema_name = s.schema_name
  and o.option_name = "description"
order by
  s.schema_name
"""

    # https://cloud.google.com/bigquery/docs/information-schema-table-storage?hl=en
    tables_for_dataset = f"""
SELECT
  t.table_catalog as table_catalog,
  t.table_schema as table_schema,
  t.table_name as table_name,
  t.table_type as table_type,
  t.creation_time as created,
  ts.last_modified_time as last_altered,
  tos.OPTION_VALUE as comment,
  is_insertable_into,
  ddl,
  row_count,
  size_bytes as bytes,
  num_partitions,
  max_partition_id,
  active_billable_bytes,
  long_term_billable_bytes,
  REGEXP_EXTRACT(t.table_name, r".*_(\\d+)$") as table_suffix,
  REGEXP_REPLACE(t.table_name, r"_(\\d+)$", "") as table_base

FROM
  `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLES t
  join `{{project_id}}`.`{{dataset_name}}`.__TABLES__ as ts on ts.table_id = t.TABLE_NAME
  left join `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLE_OPTIONS as tos on t.table_schema = tos.table_schema
  and t.TABLE_NAME = tos.TABLE_NAME
  and tos.OPTION_NAME = "description"
  left join (
    select
        table_name,
        sum(case when partition_id not in ('__NULL__', '__UNPARTITIONED__', '__STREAMING_UNPARTITIONED__') then 1 else 0 END) as num_partitions,
        max(case when partition_id not in ('__NULL__', '__UNPARTITIONED__', '__STREAMING_UNPARTITIONED__') then partition_id else NULL END) as max_partition_id,
        sum(total_rows) as total_rows,
        sum(case when storage_tier = 'LONG_TERM' then total_billable_bytes else 0 end) as long_term_billable_bytes,
        sum(case when storage_tier = 'ACTIVE' then total_billable_bytes else 0 end) as active_billable_bytes,
    from
        `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.PARTITIONS
    group by
        table_name) as p on
    t.table_name = p.table_name
WHERE
  table_type in ('{BigqueryTableType.BASE_TABLE}', '{BigqueryTableType.EXTERNAL}')
{{table_filter}}
order by
  table_schema ASC,
  table_base ASC,
  table_suffix DESC
"""

    tables_for_dataset_without_partition_data = f"""
SELECT
  t.table_catalog as table_catalog,
  t.table_schema as table_schema,
  t.table_name as table_name,
  t.table_type as table_type,
  t.creation_time as created,
  tos.OPTION_VALUE as comment,
  is_insertable_into,
  ddl,
  REGEXP_EXTRACT(t.table_name, r".*_(\\d+)$") as table_suffix,
  REGEXP_REPLACE(t.table_name, r"_(\\d+)$", "") as table_base

FROM
  `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLES t
  left join `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLE_OPTIONS as tos on t.table_schema = tos.table_schema
  and t.TABLE_NAME = tos.TABLE_NAME
  and tos.OPTION_NAME = "description"
WHERE
  table_type in ('{BigqueryTableType.BASE_TABLE}', '{BigqueryTableType.EXTERNAL}')
{{table_filter}}
order by
  table_schema ASC,
  table_base ASC,
  table_suffix DESC
"""

    views_for_dataset: str = f"""
SELECT
  t.table_catalog as table_catalog,
  t.table_schema as table_schema,
  t.table_name as table_name,
  t.table_type as table_type,
  t.creation_time as created,
  ts.last_modified_time as last_altered,
  tos.OPTION_VALUE as comment,
  is_insertable_into,
  ddl as view_definition,
  row_count,
  size_bytes
FROM
  `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLES t
  join `{{project_id}}`.`{{dataset_name}}`.__TABLES__ as ts on ts.table_id = t.TABLE_NAME
  left join `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLE_OPTIONS as tos on t.table_schema = tos.table_schema
  and t.TABLE_NAME = tos.TABLE_NAME
  and tos.OPTION_NAME = "description"
WHERE
  table_type in ('{BigqueryTableType.VIEW}', '{BigqueryTableType.MATERIALIZED_VIEW}')
order by
  table_schema ASC,
  table_name ASC
"""

    views_for_dataset_without_data_read: str = f"""
SELECT
  t.table_catalog as table_catalog,
  t.table_schema as table_schema,
  t.table_name as table_name,
  t.table_type as table_type,
  t.creation_time as created,
  tos.OPTION_VALUE as comment,
  is_insertable_into,
  ddl as view_definition
FROM
  `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLES t
  left join `{{project_id}}`.`{{dataset_name}}`.INFORMATION_SCHEMA.TABLE_OPTIONS as tos on t.table_schema = tos.table_schema
  and t.TABLE_NAME = tos.TABLE_NAME
  and tos.OPTION_NAME = "description"
WHERE
  table_type in ('{BigqueryTableType.VIEW}', '{BigqueryTableType.MATERIALIZED_VIEW}')
order by
  table_schema ASC,
  table_name ASC
"""

    columns_for_dataset: str = """
select
  c.table_catalog as table_catalog,
  c.table_schema as table_schema,
  c.table_name as table_name,
  c.column_name as column_name,
  c.ordinal_position as ordinal_position,
  cfp.field_path as field_path,
  c.is_nullable as is_nullable,
  CASE WHEN CONTAINS_SUBSTR(field_path, ".") THEN NULL ELSE c.data_type END as data_type,
  description as comment,
  c.is_hidden as is_hidden,
  c.is_partitioning_column as is_partitioning_column
from
  `{project_id}`.`{dataset_name}`.INFORMATION_SCHEMA.COLUMNS c
  join `{project_id}`.`{dataset_name}`.INFORMATION_SCHEMA.COLUMN_FIELD_PATHS as cfp on cfp.table_name = c.table_name
  and cfp.column_name = c.column_name
ORDER BY
  table_catalog, table_schema, table_name, ordinal_position ASC, data_type DESC"""

    optimized_columns_for_dataset: str = """
select * from
(select
  c.table_catalog as table_catalog,
  c.table_schema as table_schema,
  c.table_name as table_name,
  c.column_name as column_name,
  c.ordinal_position as ordinal_position,
  cfp.field_path as field_path,
  c.is_nullable as is_nullable,
  CASE WHEN CONTAINS_SUBSTR(field_path, ".") THEN NULL ELSE c.data_type END as data_type,
  description as comment,
  c.is_hidden as is_hidden,
  c.is_partitioning_column as is_partitioning_column,
  -- We count the columns to be able limit it later
  row_number() over (partition by c.table_catalog, c.table_schema, c.table_name order by c.ordinal_position asc, c.data_type DESC) as column_num,
  -- Getting the maximum shard for each table
  row_number() over (partition by c.table_catalog, c.table_schema, ifnull(REGEXP_EXTRACT(c.table_name, r'(.*)_\\d{{8}}$'), c.table_name), cfp.field_path order by c.table_catalog, c.table_schema asc, c.table_name desc) as shard_num
from
  `{project_id}`.`{dataset_name}`.INFORMATION_SCHEMA.COLUMNS c
  join `{project_id}`.`{dataset_name}`.INFORMATION_SCHEMA.COLUMN_FIELD_PATHS as cfp on cfp.table_name = c.table_name
  and cfp.column_name = c.column_name
  )
-- We filter column limit + 1 to make sure we warn about the limit being reached but not reading too much data
where column_num <= {column_limit} and shard_num = 1
ORDER BY
  table_catalog, table_schema, table_name, ordinal_position, column_num ASC, data_type DESC"""

    columns_for_table: str = """
select
  c.table_catalog as table_catalog,
  c.table_schema as table_schema,
  c.table_name as table_name,
  c.column_name as column_name,
  c.ordinal_position as ordinal_position,
  cfp.field_path as field_path,
  c.is_nullable as is_nullable,
  CASE WHEN CONTAINS_SUBSTR(field_path, ".") THEN NULL ELSE c.data_type END as data_type,
  c.is_hidden as is_hidden,
  c.is_partitioning_column as is_partitioning_column,
  description as comment
from
  `{table_identifier.project_id}`.`{table_identifier.dataset}`.INFORMATION_SCHEMA.COLUMNS as c
  join `{table_identifier.project_id}`.`{table_identifier.dataset}`.INFORMATION_SCHEMA.COLUMN_FIELD_PATHS as cfp on cfp.table_name = c.table_name
  and cfp.column_name = c.column_name
where
  c.table_name = '{table_identifier.table}'
ORDER BY
  table_catalog, table_schema, table_name, ordinal_position ASC, data_type DESC"""


class BigQueryDataDictionary:
    @staticmethod
    def get_query_result(conn: bigquery.Client, query: str) -> RowIterator:
        logger.debug(f"Query : {query}")
        resp = conn.query(query)
        return resp.result()

    @staticmethod
    def get_projects(conn: bigquery.Client) -> List[BigqueryProject]:
        projects = conn.list_projects()

        return [
            BigqueryProject(id=p.project_id, name=p.friendly_name) for p in projects
        ]

    @staticmethod
    def get_datasets_for_project_id(
        conn: bigquery.Client, project_id: str, maxResults: Optional[int] = None
    ) -> List[BigqueryDataset]:
        datasets = conn.list_datasets(project_id, max_results=maxResults)
        return [BigqueryDataset(name=d.dataset_id, labels=d.labels) for d in datasets]

    @staticmethod
    def get_datasets_for_project_id_with_information_schema(
        conn: bigquery.Client, project_id: str
    ) -> List[BigqueryDataset]:
        """
        This method is not used as of now, due to below limitation.
        Current query only fetches datasets in US region
        We'll need Region wise separate queries to fetch all datasets
        https://cloud.google.com/bigquery/docs/information-schema-datasets-schemata
        """
        schemas = BigQueryDataDictionary.get_query_result(
            conn,
            BigqueryQuery.datasets_for_project_id.format(project_id=project_id),
        )
        return [
            BigqueryDataset(
                name=s.table_schema,
                created=s.created,
                location=s.location,
                last_altered=s.last_altered,
                comment=s.comment,
            )
            for s in schemas
        ]

    @staticmethod
    def get_tables_for_dataset(
        conn: bigquery.Client,
        project_id: str,
        dataset_name: str,
        tables: Dict[str, TableListItem],
        with_data_read_permission: bool = False,
        report: Optional[BigQueryV2Report] = None,
    ) -> Iterator[BigqueryTable]:
        filter: str = ", ".join(f"'{table}'" for table in tables.keys())

        if with_data_read_permission:
            # Tables are ordered by name and table suffix to make sure we always process the latest sharded table
            # and skip the others. Sharded tables are tables with suffix _20220102
            cur = BigQueryDataDictionary.get_query_result(
                conn,
                BigqueryQuery.tables_for_dataset.format(
                    project_id=project_id,
                    dataset_name=dataset_name,
                    table_filter=f" and t.table_name in ({filter})" if filter else "",
                ),
            )
        else:
            # Tables are ordered by name and table suffix to make sure we always process the latest sharded table
            # and skip the others. Sharded tables are tables with suffix _20220102
            cur = BigQueryDataDictionary.get_query_result(
                conn,
                BigqueryQuery.tables_for_dataset_without_partition_data.format(
                    project_id=project_id,
                    dataset_name=dataset_name,
                    table_filter=f" and t.table_name in ({filter})" if filter else "",
                ),
            )

        for table in cur:
            try:
                yield BigQueryDataDictionary._make_bigquery_table(
                    table, tables.get(table.table_name)
                )
            except Exception as e:
                table_name = f"{project_id}.{dataset_name}.{table.table_name}"
                logger.warning(
                    f"Error while processing table {table_name}",
                    exc_info=True,
                )
                if report:
                    report.report_warning(
                        "metadata-extraction",
                        f"Failed to get table {table_name}: {e}",
                    )

    @staticmethod
    def _make_bigquery_table(
        table: bigquery.Row, table_basic: Optional[TableListItem]
    ) -> BigqueryTable:
        # Some properties we want to capture are only available from the TableListItem
        # we get from an earlier query of the list of tables.
        try:
            expiration = table_basic.expires if table_basic else None
        except OverflowError:
            logger.info(f"Invalid expiration time for table {table.table_name}.")
            expiration = None

        _, shard = BigqueryTableIdentifier.get_table_and_shard(table.table_name)
        return BigqueryTable(
            name=table.table_name,
            created=table.created,
            last_altered=datetime.fromtimestamp(
                table.get("last_altered") / 1000, tz=timezone.utc
            )
            if table.get("last_altered") is not None
            else table.created,
            size_in_bytes=table.get("bytes"),
            rows_count=table.get("row_count"),
            comment=table.comment,
            ddl=table.ddl,
            expires=expiration,
            labels=table_basic.labels if table_basic else None,
            partition_info=PartitionInfo.from_table_info(table_basic)
            if table_basic
            else None,
            clustering_fields=table_basic.clustering_fields if table_basic else None,
            max_partition_id=table.get("max_partition_id"),
            max_shard_id=shard,
            num_partitions=table.get("num_partitions"),
            active_billable_bytes=table.get("active_billable_bytes"),
            long_term_billable_bytes=table.get("long_term_billable_bytes"),
        )

    @staticmethod
    def get_views_for_dataset(
        conn: bigquery.Client,
        project_id: str,
        dataset_name: str,
        has_data_read: bool,
        report: Optional[BigQueryV2Report] = None,
    ) -> Iterator[BigqueryView]:
        if has_data_read:
            cur = BigQueryDataDictionary.get_query_result(
                conn,
                BigqueryQuery.views_for_dataset.format(
                    project_id=project_id, dataset_name=dataset_name
                ),
            )
        else:
            cur = BigQueryDataDictionary.get_query_result(
                conn,
                BigqueryQuery.views_for_dataset_without_data_read.format(
                    project_id=project_id, dataset_name=dataset_name
                ),
            )

        for table in cur:
            try:
                yield BigQueryDataDictionary._make_bigquery_view(table)
            except Exception as e:
                view_name = f"{project_id}.{dataset_name}.{table.table_name}"
                logger.warning(
                    f"Error while processing view {view_name}",
                    exc_info=True,
                )
                if report:
                    report.report_warning(
                        "metadata-extraction",
                        f"Failed to get view {view_name}: {e}",
                    )

    @staticmethod
    def _make_bigquery_view(view: bigquery.Row) -> BigqueryView:
        return BigqueryView(
            name=view.table_name,
            created=view.created,
            last_altered=datetime.fromtimestamp(
                view.get("last_altered") / 1000, tz=timezone.utc
            )
            if view.get("last_altered") is not None
            else view.created,
            comment=view.comment,
            view_definition=view.view_definition,
            materialized=view.table_type == BigqueryTableType.MATERIALIZED_VIEW,
        )

    @staticmethod
    def get_columns_for_dataset(
        conn: bigquery.Client,
        project_id: str,
        dataset_name: str,
        column_limit: int,
        run_optimized_column_query: bool = False,
    ) -> Optional[Dict[str, List[BigqueryColumn]]]:
        columns: Dict[str, List[BigqueryColumn]] = defaultdict(list)
        try:
            cur = BigQueryDataDictionary.get_query_result(
                conn,
                BigqueryQuery.columns_for_dataset.format(
                    project_id=project_id, dataset_name=dataset_name
                )
                if not run_optimized_column_query
                else BigqueryQuery.optimized_columns_for_dataset.format(
                    project_id=project_id,
                    dataset_name=dataset_name,
                    column_limit=column_limit,
                ),
            )
        except Exception as e:
            logger.warning(f"Columns for dataset query failed with exception: {e}")
            # Error - Information schema query returned too much data.
            # Please repeat query with more selective predicates.
            return None

        last_seen_table: str = ""
        for column in cur:
            if (
                column_limit
                and column.table_name in columns
                and len(columns[column.table_name]) >= column_limit
            ):
                if last_seen_table != column.table_name:
                    logger.warning(
                        f"{project_id}.{dataset_name}.{column.table_name} contains more than {column_limit} columns, only processing {column_limit} columns"
                    )
                    last_seen_table = column.table_name
            else:
                columns[column.table_name].append(
                    BigqueryColumn(
                        name=column.column_name,
                        ordinal_position=column.ordinal_position,
                        field_path=column.field_path,
                        is_nullable=column.is_nullable == "YES",
                        data_type=column.data_type,
                        comment=column.comment,
                        is_partition_column=column.is_partitioning_column == "YES",
                    )
                )

        return columns

    @staticmethod
    def get_columns_for_table(
        conn: bigquery.Client,
        table_identifier: BigqueryTableIdentifier,
        column_limit: Optional[int],
    ) -> List[BigqueryColumn]:
        cur = BigQueryDataDictionary.get_query_result(
            conn,
            BigqueryQuery.columns_for_table.format(table_identifier=table_identifier),
        )

        columns: List[BigqueryColumn] = []
        last_seen_table: str = ""
        for column in cur:
            if (
                column_limit
                and column.table_name in columns
                and len(columns[column.table_name]) >= column_limit
            ):
                if last_seen_table != column.table_name:
                    logger.warning(
                        f"{table_identifier.project_id}.{table_identifier.dataset}.{column.table_name} contains more than {column_limit} columns, only processing {column_limit} columns"
                    )
            else:
                columns.append(
                    BigqueryColumn(
                        name=column.column_name,
                        ordinal_position=column.ordinal_position,
                        is_nullable=column.is_nullable == "YES",
                        field_path=column.field_path,
                        data_type=column.data_type,
                        comment=column.comment,
                        is_partition_column=column.is_partitioning_column == "YES",
                    )
                )
            last_seen_table = column.table_name

        return columns
