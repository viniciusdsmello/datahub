package com.linkedin.metadata.search.cache;

import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.search.SearchResult;
import com.linkedin.metadata.search.aggregator.AllEntitiesSearchAggregator;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.javatuples.Sextet;
import org.springframework.cache.CacheManager;

import static com.datahub.util.RecordUtils.*;


@RequiredArgsConstructor
public class CachingAllEntitiesSearchAggregator {
  private static final String ALL_ENTITIES_SEARCH_AGGREGATOR_CACHE_NAME = "allEntitiesSearchAggregator";

  private final CacheManager cacheManager;
  private final AllEntitiesSearchAggregator aggregator;
  private final int batchSize;
  private final boolean enableCache;

  public SearchResult getSearchResults(List<String> entities, @Nonnull String input, @Nullable Filter postFilters,
      @Nullable SortCriterion sortCriterion, int from, int size, @Nullable SearchFlags searchFlags, @Nullable List<String> facets) {
    return new CacheableSearcher<>(cacheManager.getCache(ALL_ENTITIES_SEARCH_AGGREGATOR_CACHE_NAME), batchSize,
        querySize -> aggregator.search(entities, input, postFilters, sortCriterion, querySize.getFrom(),
            querySize.getSize(), searchFlags, facets),
        querySize -> Sextet.with(entities, input, postFilters != null ? toJsonString(postFilters) : null,
            sortCriterion != null ? toJsonString(sortCriterion) : null, facets, querySize), searchFlags, enableCache)
        .getSearchResults(from, size);
  }
}
