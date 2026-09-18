package dev.shadow.booxbacklight

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Boox-style circular Quick Settings tile:
 *   disabled → white circle, black glyph
 *   enabled  → black circle, white glyph (inverted)
 * E-ink friendly: pure black/white, no anti-alias fuzz on the glyph.
 */
class AutoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        LearnService.start(applicationContext)
        applicationContext.startService(
            Intent(applicationContext, LearnService::class.java)
                .setAction(LearnService.ACTION_TOGGLE))
        TileSync.refresh(applicationContext)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = getSharedPreferences("light", MODE_PRIVATE).getBoolean("auto", true)
        tile.icon = android.graphics.drawable.Icon.createWithBitmap(
            TileGlyphs.circleGlyph(this, on))
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Adaptive light"
        tile.subtitle = if (on) "on" else "off"
        tile.updateTile()
    }
}

object TileSync {
    fun refresh(ctx: Context) {
        // Ask the tile service to re-render (covers notification-line + shade)
        ctx.sendBroadcast(Intent().setComponent(
            ComponentName(ctx, AutoTileService::class.java)))
    }
}

object TileGlyphs {
    /** Circular tile icon, inverted when enabled. Pure B/W for e-ink. */
    fun circleGlyph(ctx: Context, enabled: Boolean): Bitmap {
        val s = 128
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = if (enabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val fg = if (enabled) android.graphics.Color.WHITE else android.graphics.Color.BLACK

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bg; style = Paint.Style.FILL
        }
        // White background ring so the circle is visible on both shades
        c.drawCircle(s / 2f, s / 2f, s / 2f - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = Paint.Style.FILL
        })
        c.drawCircle(s / 2f, s / 2f, s / 2f - 6f, ring)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textAlign = Paint.Align.CENTER; textSize = s * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val y = s / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText("A", s / 2f, y, text)
        return bmp
    }
}
