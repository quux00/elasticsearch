Add in fields to the _clusters object to support cluster aliases, status of each cluster (by name) 
and failure/error messages. Update those fields during MRT=true queries

See notes in the annotation log file primary-bigger-gap-run3.log.txt

The problem is that the Clusters object is created many times. We need to share one between
MutableSearchResponse, CCSActionListener and the local Searcher. That may turn out to be 
quite difficult.

We could avoid doing it if we are OK with no "details" section when a skip_unavailable=false
error happens, bcs in that case you get:

```
{
  "id": "FmJsLUJQV3MyU0JHQWxBT2JIdkwyRVEaVFdQekZRN0hTeXFzc2dtYlJOVXdzUTozMzM=",
  "is_partial": true,
  "is_running": false,
  "start_time_in_millis": 1689180496715,
  "expiration_time_in_millis": 1689612496715,
  "response": {
    "took": 135280,
    "timed_out": false,
    "terminated_early": false,
    "_shards": {
      "total": 3,
      "successful": 3,
      "skipped": 0,
      "failed": 0
    },
    "_clusters": {
      "total": 3,
      "successful": 1,
      "skipped": 0
    },
    "hits": {
      "total": {
        "value": 1200,
        "relation": "eq"
      },
      "max_score": null,
      "hits": []
    },
    "aggregations": {
      "indexgroup": {
        "doc_count_error_upper_bound": 0,
        "sum_other_doc_count": 0,
        "buckets": [
          {
            "key": "blogs",
            "doc_count": 1200
          }
        ]
      }
    }
  },
  "error": {
    "type": "status_exception",
    "reason": "error while executing search",
    "caused_by": {
      "type": "node_disconnected_exception",
      "reason": "[michaels-mbp.lan][127.0.0.1:9301][indices:data/read/search] disconnected"
    }
  }
}
```



Request from Tyler, when skip_unavailable=false, the _shards section should also be modified:

```
    "_shards": {
      "total": 9,
      "successful": 6,
      "skipped": 3,
      "failed": 0
```

rather than what I have now:

```
  "response": {
    "took": 14615,
    "timed_out": false,
    "num_reduce_phases": 3,
    "_shards": {
      "total": 6,
      "successful": 6,
      "skipped": 0,
      "failed": 0
    },
    "_clusters": {
      "total": 3,
      "successful": 2,
      "skipped": 1,
      "details": {
        "(local)": {
          "completion_status": "success",
          "percent_shards_successful": "100.00",
          "completion_time": "11s"
        },
        "remote2": {
          "completion_status": "skipped",
          "percent_shards_successful": "0.00",
          "errors": [
            "[michaels-mbp.lan][127.0.0.1:9302][indices:data/read/search] disconnected"
          ]
        },
        "remote1": {
          "completion_status": "success",
          "percent_shards_successful": "100.00",
          "completion_time": "14s"
        }
      }
    },
```

Slack discussion: https://elastic.slack.com/archives/C05AVUN5QBA/p1689186536713429?thread_ts=1689184807.317429&cid=C05AVUN5QBA
