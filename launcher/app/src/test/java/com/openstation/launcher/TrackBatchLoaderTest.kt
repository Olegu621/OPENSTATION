package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackBatchLoaderTest {
    @Test fun batchesAndPreservesLibraryOrder() {
        val ids = (1..1001).map { it.toString() }
        val sizes = mutableListOf<Int>()
        val result = TrackBatchLoader.load(ids, 100, { batch ->
            sizes.add(batch.size)
            batch.reversed().filterNot { it == "77" }
        }, { it })
        assertEquals(11, sizes.size)
        assertEquals(1, sizes.last())
        assertEquals(ids.filterNot { it == "77" }, result)
    }
}
