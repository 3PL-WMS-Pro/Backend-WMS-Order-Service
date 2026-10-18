package com.wmspro.order.service

import com.wmspro.order.dto.ItemPickedLocationBasedDto
import com.wmspro.order.dto.SourceLocationDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * PME's lot QBI-00082 lists WH1-B-2-1-B and WH1-B-3-2-C twice each, so the pick screen sent four
 * rows for two bins. Each bin is now one reduction carrying the sum.
 */
class RepeatedBinPickTest {

    @Test
    fun `rows for the same bin become one row with the quantities added`() {
        val picked = ItemPickedLocationBasedDto(
            quantityInventoryId = "QBI-00082",
            itemType = "PALLET",
            totalQuantityPicked = 4,
            sourceLocations = listOf(
                SourceLocationDto("WH1-B-2-1-B", 1),
                SourceLocationDto("WH1-B-2-1-B", 1),
                SourceLocationDto("WH1-B-3-2-C", 1),
                SourceLocationDto("WH1-B-3-2-C", 1)
            )
        ).withRepeatedBinsMerged()

        assertEquals(
            listOf(SourceLocationDto("WH1-B-2-1-B", 2), SourceLocationDto("WH1-B-3-2-C", 2)),
            picked.sourceLocations
        )
        assertEquals(4, picked.totalQuantityPicked)
    }
}
