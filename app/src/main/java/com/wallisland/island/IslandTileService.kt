package com.wallisland.island

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile to switch the island on and off without opening the app. */
class IslandTileService : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val prefs = Prefs(this)
        prefs.enabled = !prefs.enabled
        if (prefs.enabled) IslandService.start(this) else IslandService.stop(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (Prefs(this).enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
