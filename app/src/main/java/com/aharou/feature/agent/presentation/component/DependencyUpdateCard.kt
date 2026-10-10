package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Package
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ParsedDependencyUpdate(
    val label: String,
    val current: String,
    val latest: String,
    val file: String,
    val line: Int,
    val oldString: String?,
    val newString: String?
)

internal data class ParsedDependencyReport(
    val updates: List<ParsedDependencyUpdate>,
    val networkOk: Boolean,
    val upToDateCount: Int,
    val unparsedCount: Int
)

/** 解析 `check_dependencies` 工具结果 JSON；不是该工具的结果时返回 null。 */
internal fun parseDependencyReport(content: String): ParsedDependencyReport? {
    return runCatching {
        val data = extractDependencyReportData(content) ?: return null
        if (!data.containsKey("updates")) return null
        val updates = data["updates"]?.jsonArray?.mapNotNull { parseDependencyUpdate(it) }.orEmpty()
        ParsedDependencyReport(
            updates = updates,
            networkOk = data["network_ok"]?.jsonPrimitive?.booleanOrNull ?: true,
            upToDateCount = data["up_to_date"]?.jsonPrimitive?.intOrNull ?: 0,
            unparsedCount = data["unparsed_count"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }.getOrNull()
}

private fun extractDependencyReportData(content: String): JsonObject? {
    val s = content.withoutToolStatusPrefix()
    val outer = Json.parseToJsonElement(s).jsonObject
    if (outer["status"]?.jsonPrimitive?.contentOrNull !in setOf("success", "partial")) return null
    return when (val data = outer["data"]) {
        is JsonObject -> data
        is JsonPrimitive -> data.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?.let { Json.parseToJsonElement(it).jsonObject }
        else -> null
    }
}

private fun parseDependencyUpdate(element: JsonElement): ParsedDependencyUpdate? {
    val obj = element as? JsonObject ?: return null
    val current = obj["current"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val latest = obj["latest"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val label = obj["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(
            obj["group"]?.jsonPrimitive?.contentOrNull,
            obj["artifact"]?.jsonPrimitive?.contentOrNull
        ).joinToString(":")
    if (label.isBlank()) return null
    return ParsedDependencyUpdate(
        label = label,
        current = current,
        latest = latest,
        file = obj["file"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        line = obj["line"]?.jsonPrimitive?.intOrNull ?: 0,
        oldString = obj["old_string"]?.jsonPrimitive?.contentOrNull,
        newString = obj["new_string"]?.jsonPrimitive?.contentOrNull
    )
}

/**
 * 依赖版本检查结果卡：列出可升级项「坐标  current → latest」，附一键复制全部建议编辑。
 */
@Composable
internal fun DependencyUpdateCard(report: ParsedDependencyReport) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        DependencyUpdateSummary(report)
        if (!report.networkOk) {
            Text(
                text = stringResource(R.string.dependency_check_network_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (report.updates.isEmpty()) {
            Text(
                text = stringResource(R.string.dependency_check_all_up_to_date),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        report.updates.forEach { item -> DependencyUpdateRow(item) }
        if (report.unparsedCount > 0) {
            Text(
                text = stringResource(R.string.dependency_check_unparsed, report.unparsedCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DependencyUpdateSummary(report: ParsedDependencyReport) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.Package,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dependency_check_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.dependency_check_updates_count, report.updates.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        report.copyText()?.let { ChatCopyAction(copyText = it) }
    }
}

@Composable
private fun DependencyUpdateRow(item: ParsedDependencyUpdate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = item.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.current,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "→",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = item.latest,
                    color = MaterialTheme.semanticColors.success,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (item.file.isNotBlank()) {
                Text(
                    text = if (item.line > 0) "${item.file}:${item.line}" else item.file,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 把全部建议编辑拼成可复制文本：`坐标  - old_string  + new_string`。 */
private fun ParsedDependencyReport.copyText(): String? {
    val blocks = updates.mapNotNull { item ->
        val oldText = item.oldString
        val newText = item.newString
        if (oldText.isNullOrBlank() || newText.isNullOrBlank()) null
        else "${item.label}\n- $oldText\n+ $newText"
    }
    return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}
