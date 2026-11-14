package com.openstation.launcher

internal object TrackBatchLoader {
    fun <T> load(ids: List<String>, batchSize: Int = 100,
                 fetch: (List<String>) -> List<T>, idOf: (T) -> String): List<T> {
        require(batchSize > 0)
        val result = ArrayList<T>(ids.size)
        for (batch in ids.chunked(batchSize)) {
            val fetched = fetch(batch).associateBy(idOf)
            for (id in batch) fetched[id]?.let { result.add(it) }
        }
        return result
    }
}
