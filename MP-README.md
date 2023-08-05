Start on ccs/mrt-false-error-message before ccs/mrt-error-message merged

Basing off previous work in `ccs/expt/mrt-false-error-messages` branch:
https://github.com/quux00/elasticsearch/commit/71da7ae6e2f4dfd7aa201df8322509597ecee179

which did *not* have immutable Cluster objects with CAS, so that needs to be added.
Note that I decided to do this ticket before the fail-fast, as that one needs to be
redone carefully to figure out where the race condition between GET _async_search calls
and backend updates is coming from and how serious it is.

### TODO:

* make sure to change CrossClusterAsyncSearchIT to also test MRT=false (it only does MRT=true right now)

### Tests to run:

grs ':qa:multi-cluster-search:v8.10.0#multi-cluster' --tests "org.elasticsearch.search.CCSDuelIT"
grs ':server:internalClusterTest' --tests "org.elasticsearch.search.ccs.CrossClusterSearchIT" -Dtests.iters=3
grs ':x-pack:plugin:async-search:internalClusterTest' --tests "org.elasticsearch.xpack.search.CrossClusterAsyncSearchIT" -Dtests.iters=3
grs -p server test --tests org.elasticsearch.action.search.TransportSearchActionTests -Dtests.iters=6
grs -p x-pack/plugin/async-search test -Dtests.iters=6
gr ':docs:yamlRestTest' --tests "org.elasticsearch.smoketest.DocsClientYamlTestSuiteIT.test {yaml=reference/search/search-your-data/search-across-clusters/*}" -Dtests.iters=10

