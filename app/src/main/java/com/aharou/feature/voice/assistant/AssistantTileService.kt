package com.aharou.feature.voice.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aharou.feature.voice.assistant.presentation.AssistantOverlayActivity

/**
 * 下拉快捷磁贴：任何界面下拉点一下就唤起助手浮层。
 *
 * 为什么需要它：ColorOS 把长按电源独占给了自家小布（系统层直接调，第三方接不进去），
 * 系统助手角色只能接到系统主动发起的 Assist 请求，日常没有一个「随手唤起」的入口。
 * 磁贴是三方应用能拿到的最轻的常驻入口。
 */
class AssistantTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(com.aharou.R.string.assistant_tile_label)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AssistantOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistantOverlayActivity.EXTRA_FROM_TILE, true)
        // API 34 起只接受 PendingIntent，旧版本仍用 Intent。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
