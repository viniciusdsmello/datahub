from dataclasses import dataclass, field
from typing import Dict, MutableSet, Optional

from datahub.ingestion.glossary.classification_mixin import ClassificationReportMixin
from datahub.ingestion.source.snowflake.constants import SnowflakeEdition
from datahub.ingestion.source.sql.sql_generic_profiler import ProfilingSqlReport
from datahub.ingestion.source_report.sql.snowflake import SnowflakeReport
from datahub.ingestion.source_report.usage.snowflake_usage import SnowflakeUsageReport


@dataclass
class SnowflakeV2Report(
    SnowflakeReport, SnowflakeUsageReport, ProfilingSqlReport, ClassificationReportMixin
):
    account_locator: Optional[str] = None
    region: Optional[str] = None

    schemas_scanned: int = 0
    databases_scanned: int = 0
    tags_scanned: int = 0

    include_usage_stats: bool = False
    include_operational_stats: bool = False
    include_technical_schema: bool = False
    include_column_lineage: bool = False

    usage_aggregation_query_secs: float = -1
    table_lineage_query_secs: float = -1
    view_lineage_parse_secs: float = -1
    view_upstream_lineage_query_secs: float = -1
    view_downstream_lineage_query_secs: float = -1
    external_lineage_queries_secs: float = -1

    # Reports how many times we reset in-memory `functools.lru_cache` caches of data,
    # which occurs when we occur a different database / schema.
    # Should not be more than the number of databases / schemas scanned.
    # Maps (function name) -> (stat_name) -> (stat_value)
    lru_cache_info: Dict[str, Dict[str, int]] = field(default_factory=dict)

    # These will be non-zero if snowflake information_schema queries fail with error -
    # "Information schema query returned too much data. Please repeat query with more selective predicates.""
    # This will result in overall increase in time complexity
    num_get_tables_for_schema_queries: int = 0
    num_get_views_for_schema_queries: int = 0
    num_get_columns_for_table_queries: int = 0

    # these will be non-zero if the user choses to enable the extract_tags = "with_lineage" option, which requires
    # individual queries per object (database, schema, table) and an extra query per table to get the tags on the columns.
    num_get_tags_for_object_queries: int = 0
    num_get_tags_on_columns_for_table_queries: int = 0

    rows_zero_objects_modified: int = 0

    _processed_tags: MutableSet[str] = field(default_factory=set)
    _scanned_tags: MutableSet[str] = field(default_factory=set)

    edition: Optional[SnowflakeEdition] = None

    num_tables_with_external_upstreams_only: int = 0
    num_tables_with_upstreams: int = 0
    num_views_with_upstreams: int = 0

    num_view_definitions_parsed: int = 0
    num_view_definitions_failed_parsing: int = 0
    num_view_definitions_failed_column_parsing: int = 0

    def report_entity_scanned(self, name: str, ent_type: str = "table") -> None:
        """
        Entity could be a view or a table or a schema or a database
        """
        if ent_type == "table":
            self.tables_scanned += 1
        elif ent_type == "view":
            self.views_scanned += 1
        elif ent_type == "schema":
            self.schemas_scanned += 1
        elif ent_type == "database":
            self.databases_scanned += 1
        elif ent_type == "tag":
            # the same tag can be assigned to multiple objects, so we need
            # some extra logic account for each tag only once.
            if self._is_tag_scanned(name):
                return
            self._scanned_tags.add(name)
            self.tags_scanned += 1
        else:
            raise KeyError(f"Unknown entity {ent_type}.")

    def is_tag_processed(self, tag_name: str) -> bool:
        return tag_name in self._processed_tags

    def _is_tag_scanned(self, tag_name: str) -> bool:
        return tag_name in self._scanned_tags

    def report_tag_processed(self, tag_name: str) -> None:
        self._processed_tags.add(tag_name)
