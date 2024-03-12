/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.query;

import org.elasticsearch.client.internal.Client;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.shard.IndexLongFieldRange;
import org.elasticsearch.xcontent.XContentParserConfiguration;

import java.util.Collections;
import java.util.function.LongSupplier;

/**
 * Context object used to rewrite {@link QueryBuilder} instances into simplified version in the coordinator.
 * Instances of this object rely on information stored in the {@code IndexMetadata} for certain indices.
 * Right now this context object is able to rewrite range queries that include a known timestamp field
 * (i.e. the timestamp field for DataStreams) into a MatchNoneQueryBuilder and skip the shards that
 * don't hold queried data. See IndexMetadata#getTimestampRange() for more details
 */
public class CoordinatorRewriteContext extends QueryRewriteContext {
    private final IndexLongFieldRange indexLongFieldRange;
    /**
     * Refers to the @timestamp field
     */
    private final DateFieldMapper.DateFieldType timestampFieldType;
    /**
     * Refers to 'event.ingested' field
     */
    private DateFieldMapper.DateFieldType eventIngestedFieldType;  // TODO: make final

    public CoordinatorRewriteContext(
        XContentParserConfiguration parserConfig,
        Client client,
        LongSupplier nowInMillis,
        IndexLongFieldRange indexLongFieldRange,
        DateFieldMapper.DateFieldType timestampFieldType
    ) {
        super(
            parserConfig,
            client,
            nowInMillis,
            null,
            MappingLookup.EMPTY,
            Collections.emptyMap(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        this.indexLongFieldRange = indexLongFieldRange;
        this.timestampFieldType = timestampFieldType;
    }


    /// MP TODO: does this need to take a field name (@timestamp vs. event.ingested) or take union of the two?
    long getMinTimestamp() {  /// MP TODO: this is called only from RangeQueryBuilder.getRelation
        return indexLongFieldRange.getMin();
    }

    /// MPQ TODO: Hmm - this cannot return null. Is that a problem when we have support either @timestamp or event.ingested? One could be null then
    long getMaxTimestamp() {
        return indexLongFieldRange.getMax();
    }

    boolean hasTimestampData() {
        return indexLongFieldRange.isComplete() && indexLongFieldRange != IndexLongFieldRange.EMPTY;
    }

    /// MP TODO: GOOD NEWS - this is where we can add support for event.ingested since field name has to be passed in here
    @Nullable
    public MappedFieldType getFieldType(String fieldName) {
        if (fieldName.equals(timestampFieldType.name()) == false) {
            return null;
        }

        return timestampFieldType;
    }

    @Override
    public CoordinatorRewriteContext convertToCoordinatorRewriteContext() {
        return this;
    }
}
