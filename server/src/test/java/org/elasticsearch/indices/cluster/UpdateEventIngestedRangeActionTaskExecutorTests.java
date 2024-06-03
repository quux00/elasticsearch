/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices.cluster;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionTestUtils;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.service.ClusterStateTaskExecutorUtils;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardLongFieldRange;
import org.elasticsearch.test.ESTestCase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.elasticsearch.action.support.replication.ClusterStateCreationUtils.stateWithNoShard;

public class UpdateEventIngestedRangeActionTaskExecutorTests extends ESTestCase {

    private EventIngestedRangeClusterStateService.UpdateEventIngestedRangeAction.TaskExecutor executor;

    public void testEmptyTaskListProducesSameClusterState() throws Exception {
        executor = new EventIngestedRangeClusterStateService.UpdateEventIngestedRangeAction.TaskExecutor();
        ClusterState stateBefore = stateWithNoShard();
        assertSame(stateBefore, executeTasks(stateBefore, List.of()));
    }

    public void testTaskWithSingleIndexAndShard() throws Exception {
        executor = new EventIngestedRangeClusterStateService.UpdateEventIngestedRangeAction.TaskExecutor();
        ClusterState clusterState = stateWithNoShard();

        Map<Index, List<EventIngestedRangeClusterStateService.ShardRangeInfo>> eventIngestedRangeMap = new HashMap<>();
        Index blogsIndex = new Index("blogs", UUID.randomUUID().toString());
        EventIngestedRangeClusterStateService.ShardRangeInfo shardRangeInfo = new EventIngestedRangeClusterStateService.ShardRangeInfo(
            new ShardId(blogsIndex, 1),
            ShardLongFieldRange.of(1000, 2000)
        );
        eventIngestedRangeMap.put(blogsIndex, List.of(shardRangeInfo));

        UpdateEventIngestedRangeRequest rangeUpdateRequest = new UpdateEventIngestedRangeRequest(eventIngestedRangeMap);
        var createEventIngestedRangeTask =
            new EventIngestedRangeClusterStateService.UpdateEventIngestedRangeAction.CreateEventIngestedRangeTask(rangeUpdateRequest);

        assertNotSame(clusterState, executeTasks(clusterState, List.of(createEventIngestedRangeTask)));
    }

    private ClusterState executeTasks(
        ClusterState state,
        List<EventIngestedRangeClusterStateService.UpdateEventIngestedRangeAction.EventIngestedRangeTask> tasks
    ) throws Exception {
        return ClusterStateTaskExecutorUtils.executeAndAssertSuccessful(state, executor, tasks);
    }

    private static <T> ActionListener<T> createTestListener() {
        return ActionTestUtils.assertNoFailureListener(t -> {});
    }
}
