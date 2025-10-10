package com.wmspro.order.enums

enum class ExecutionApproach {
    SEPARATED_PICKING,          // Separate PICKING and PACK_MOVE tasks
    PICK_PACK_MOVE_TOGETHER     // Combined PICK_PACK_MOVE task
}
