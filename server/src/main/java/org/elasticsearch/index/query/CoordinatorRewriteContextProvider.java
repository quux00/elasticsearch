/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.query;

import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.shard.IndexLongFieldRange;
import org.elasticsearch.xcontent.XContentParserConfiguration;

import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class CoordinatorRewriteContextProvider {
    private final XContentParserConfiguration parserConfig;
    private final Client client;
    private final LongSupplier nowInMillis;
    private final Supplier<ClusterState> clusterStateSupplier;
    private final Function<Index, DateFieldMapper.DateFieldType> mappingSupplier;
    /// MP TOOD: this mappingSupplier comes from the IndicesService and is:
    /*
        public DateFieldMapper.DateFieldType getTimestampFieldType(Index index) {
          return timestampFieldMapperService.getTimestampFieldType(index);
        }
     */

    public CoordinatorRewriteContextProvider(
        XContentParserConfiguration parserConfig,
        Client client,
        LongSupplier nowInMillis,
        Supplier<ClusterState> clusterStateSupplier,
        Function<Index, DateFieldMapper.DateFieldType> mappingSupplier  // MP TODO: answers does this index have a DateFieldType (I guess?)
    ) {
        this.parserConfig = parserConfig;
        this.client = client;
        this.nowInMillis = nowInMillis;
        this.clusterStateSupplier = clusterStateSupplier;
        this.mappingSupplier = mappingSupplier;
    }

    /// MP TODO: called from runCoordinatorRewritePhase
    @Nullable
    public CoordinatorRewriteContext getCoordinatorRewriteContext(Index index) {
        var clusterState = clusterStateSupplier.get();
        var indexMetadata = clusterState.metadata().index(index); /// MPQ TODO: will this return null for any index without @timestamp?

        if (indexMetadata == null) {
            return null;
        }
        // MP TODO: ask the mappingSupplier if there is a dateFieldType in the TimestampFieldMapperService for the given index
        // MP TODO:   if NO (null), return a NULL CoordinatorRewriteContext, since there's no rewrite action to take
        DateFieldMapper.DateFieldType dateFieldTypex = mappingSupplier.apply(index);
        if (dateFieldType == null) {
            return null;
        }
        // MP TODO: if YES, get the timestamp range on that index; then
        IndexLongFieldRange timestampRange = indexMetadata.getTimestampRange(); // MP TODO: what is this range of?
        /// MPQ TODO I don't understand this containsAllShardRanges() == false logic - explain?
        if (timestampRange.containsAllShardRanges() == false) {
            /// MP TODO: I think we may need to pass in field name: @timestamp vs. event.ingested
            timestampRange = indexMetadata.getTimeSeriesTimestampRange(dateFieldType); // MP TODO: why do you need to pass in type here?
            if (timestampRange == null) {
                return null;
            }
        }

        // MP TODO: does this CoordinatorRewriteContext need to know whether we are searching on @timestamp or event.ingested?
        return new CoordinatorRewriteContext(parserConfig, client, nowInMillis, timestampRange, dateFieldType);
    }
}
