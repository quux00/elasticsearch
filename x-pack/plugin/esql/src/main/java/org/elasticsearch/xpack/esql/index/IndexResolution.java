/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.index;

import org.elasticsearch.core.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class IndexResolution {
    public static IndexResolution validWithSkippedRemotes(EsIndex index, List<String> clusterAliasesWithErrors) {
        Objects.requireNonNull(index, "index must not be null if it was found");
        return new IndexResolution(index, clusterAliasesWithErrors);  // MP TODO: FIXME
    }

    public static IndexResolution valid(EsIndex index) {
        Objects.requireNonNull(index, "index must not be null if it was found");
        return new IndexResolution(index);
    }

    public static IndexResolution invalid(String invalid) {
        Objects.requireNonNull(invalid, "invalid must not be null to signal that the index is invalid");
        return new IndexResolution(invalid);
    }

    public static IndexResolution notFound(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return invalid("Unknown index [" + name + "]");
    }

    private final EsIndex index;
    @Nullable
    private final String invalid;
    private final List<String> clusterAliasesWithErrors;  // TODO: do we also need to include the Exception/errors

    private IndexResolution(EsIndex index) {
        this.index = index;
        this.invalid = null;
        this.clusterAliasesWithErrors = Collections.emptyList();
    }

    private IndexResolution(String invalid) {
        this.index = null;
        this.invalid = invalid;
        this.clusterAliasesWithErrors = Collections.emptyList();
    }

    private IndexResolution(EsIndex index, List<String> clusterAliasesWithErrors) {
        this.index = index;
        this.invalid = null;
        this.clusterAliasesWithErrors = clusterAliasesWithErrors;
        System.err.println(">>> DEBUG 100: IndexResolution: clusterAliasesWithErrors: " + clusterAliasesWithErrors);
    }

    public List<String> getClusterAliasesWithErrors() {
        return clusterAliasesWithErrors;
    }

    public boolean matches(String indexName) {
        return isValid() && this.index.name().equals(indexName);
    }

    /**
     * Get the {@linkplain EsIndex}
     * @throws MappingException if the index is invalid for use with ql
     */
    public EsIndex get() {
        if (invalid != null) {
            throw new MappingException(invalid);
        }
        return index;
    }

    /**
     * Is the index valid for use with ql? Returns {@code false} if the
     * index wasn't found.
     */
    public boolean isValid() {
        return invalid == null;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        IndexResolution other = (IndexResolution) obj;
        return Objects.equals(index, other.index) && Objects.equals(invalid, other.invalid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, invalid);
    }

    @Override
    public String toString() {
        return invalid != null ? invalid : index.name();
    }
}
