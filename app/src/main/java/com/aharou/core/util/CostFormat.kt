package com.aharou.core.util

import java.util.Locale

/** 费用展示：小额保留 4 位小数，极小值折叠为 <$0.0001。 */
fun formatCostUsd(cost: Double): String = when {
    cost <= 0.0 -> "$0.00"
    cost < 0.0001 -> "<$0.0001"
    cost < 0.01 -> String.format(Locale.getDefault(), "$%.4f", cost)
    else -> String.format(Locale.getDefault(), "$%.2f", cost)
}
