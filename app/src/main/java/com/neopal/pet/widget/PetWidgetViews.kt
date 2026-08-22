package com.neopal.pet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.neopal.pet.MainActivity
import com.neopal.pet.R
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.WidgetFace
import com.neopal.pet.domain.WidgetSnapshot
import com.neopal.pet.ui.art.Palettes
import java.util.Date

/**
 * Turns one [WidgetSnapshot] into the view tree the launcher will hold.
 *
 * The picture is a bitmap and the words are real views. That split is on purpose: text baked
 * into the bitmap would ignore the reader's font size, would be invisible to a screen reader,
 * and would need re-rendering to change a single character. Everything a person reads is
 * therefore a `TextView`, and everything that is *the creature* is the bitmap.
 *
 * The one thing worth defending here is the line that says **when**. A widget is drawn once and
 * then left alone; a satiety figure two hours old is a lie told confidently. It cannot be fixed
 * by refreshing harder — the system rate-limits widgets and batteries are finite — so instead
 * the widget states the moment it was drawn and lets the reader judge. A widget that has not
 * been refreshed since breakfast says so, in the reader's own clock format.
 */
internal object PetWidgetViews {

    /** Cell size to assume when the host has not told us one yet, in dp. */
    private const val FALLBACK_WIDTH_DP = 250
    private const val FALLBACK_HEIGHT_DP = 110

    /**
     * How tall the text plate is at the default font scale, in dp: its two rows, its padding and
     * its margin. Kept next to the layout it describes, and the one number that has to change if
     * `widget_pet.xml` ever grows a third line.
     */
    private const val PLATE_HEIGHT_DP = 52f

    fun build(
        context: Context,
        snapshot: WidgetSnapshot,
        config: GameConfig,
        options: Bundle?,
        appWidgetId: Int,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_pet)
        val room = Palettes.applyNight(
            Palettes.room(snapshot.scene.roomTheme),
            if (snapshot.scene.night) 1f else 0f,
        )

        val density = context.resources.displayMetrics.density
        // Portrait is the width the host guarantees and the height it allows; the pair of them
        // is the cell as the reader actually sees it. Both are absent until the host has laid
        // the widget out once, which is exactly when the first update arrives.
        val widthDp = cellDp(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, FALLBACK_WIDTH_DP)
        val heightDp = cellDp(options, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, FALLBACK_HEIGHT_DP)
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()

        val bitmap = PetWidgetBitmap.render(
            snapshot = snapshot,
            widthPx = widthPx,
            heightPx = heightPx,
            cornerRadiusPx = cornerRadius(context, density),
            softness = config.softFinish,
            // What the plate will take, measured rather than guessed: two lines of text at the
            // size this reader actually reads at. Clamped, because a very large font on a very
            // short widget would otherwise leave the creature no room at all — better a plate
            // that covers part of a visible creature than a creature that is not drawn.
            bottomInset = (PLATE_HEIGHT_DP * fontScale(context) / heightDp).coerceIn(0.18f, 0.55f),
            retroMode = config.retroMode,
        )
        // A widget with no picture still has honest words in it, which is a great deal better
        // than a widget that refuses to draw at all.
        if (bitmap != null) views.setImageViewBitmap(R.id.widget_art, bitmap)

        // The bar belongs to the room it sits under, so a beach pet and a space pet do not share
        // one grey strip. Deep enough to read against a lit floor, short of opaque so the widget
        // still looks like a window rather than a card.
        views.setInt(R.id.widget_bar, "setBackgroundColor", room.shadow.copy(alpha = 0.86f).toArgb())
        views.setTextColor(R.id.widget_title, room.highlight.toArgb())
        views.setTextColor(R.id.widget_detail, room.highlight.copy(alpha = 0.78f).toArgb())
        views.setTextColor(R.id.widget_time, room.highlight.copy(alpha = 0.55f).toArgb())

        views.setTextViewText(R.id.widget_title, snapshot.headline)
        views.setTextViewText(R.id.widget_detail, snapshot.detail)
        views.setTextViewText(R.id.widget_time, asOf(context, snapshot.atMillis))

        val offer = snapshot.offer
        if (offer == null) {
            views.setViewVisibility(R.id.widget_chip, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_chip, View.VISIBLE)
            views.setTextViewText(R.id.widget_chip, offer.label)
            val chip = room.propAccent
            views.setInt(R.id.widget_chip, "setBackgroundColor", chip.toArgb())
            // The accent is whatever the room's lamp or neon happens to be, and rooms disagree
            // about how bright that is. Picking the ink from the paint is the only way one rule
            // reads on all five.
            views.setTextColor(R.id.widget_chip, inkOn(chip).toArgb())
            views.setOnClickPendingIntent(R.id.widget_chip, carePendingIntent(context, snapshot, appWidgetId))
        }

        views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent(context))
        views.setContentDescription(R.id.widget_root, spoken(context, snapshot))
        return views
    }

    /**
     * What a screen reader says. The same three facts as the visible widget, in a sentence, with
     * the time of the reading included for the same reason it is on screen.
     */
    private fun spoken(context: Context, snapshot: WidgetSnapshot): String {
        val head = when (snapshot.face) {
            WidgetFace.EMPTY -> "NeoPal. No pet yet."
            WidgetFace.EGG -> "${snapshot.name}, still an egg. ${snapshot.detail}."
            WidgetFace.GONE -> "${snapshot.name}. ${snapshot.detail}."
            WidgetFace.ALIVE -> "${snapshot.headline}. ${snapshot.detail}."
        }
        val tap = snapshot.offer?.let { " Button: ${it.label}." } ?: ""
        return "$head$tap Shown as of ${asOf(context, snapshot.atMillis)}."
    }

    /** One side of the cell in dp, or [fallback] until the host has laid the widget out once. */
    private fun cellDp(options: Bundle?, key: String, fallback: Int): Int {
        if (options == null) return fallback
        val dp: Int = options.getInt(key)
        return if (dp > 0) dp else fallback
    }

    /** The reader's own text size. A phone that will not say returns 1, which is the default. */
    private fun fontScale(context: Context): Float {
        val scale: Float = context.resources.configuration.fontScale
        return if (scale > 0f && scale < 10f) scale else 1f
    }

    private fun asOf(context: Context, millis: Long): String =
        "as of " + DateFormat.getTimeFormat(context).format(Date(millis))

    /**
     * Tapping the widget itself opens the app on the creature, which is where the app already
     * starts. `SINGLE_TOP` matches the activity's own launch mode, so a second tap brings the
     * running game forward instead of building a second copy of it.
     */
    private fun openPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, IMMUTABLE_UPDATE)
    }

    /**
     * The chip.
     *
     * Only the *verb* travels. The item, and the whole question of whether this is still worth
     * doing, are worked out again on the other side against a fresh read of the save — see
     * [WidgetSnapshot.apply]. A picture drawn at nine o'clock has no business feeding a pet at
     * one, and this is what stops it.
     *
     * The request code is the widget's own id: two widgets on the same screen would otherwise
     * share one `PendingIntent` and the second would silently inherit the first one's verb.
     */
    private fun carePendingIntent(context: Context, snapshot: WidgetSnapshot, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, PetWidget::class.java)
            .setAction(PetWidget.ACTION_CARE)
            .putExtra(PetWidget.EXTRA_ACTION, snapshot.offer?.action?.name)
        return PendingIntent.getBroadcast(context, appWidgetId, intent, IMMUTABLE_UPDATE)
    }

    /**
     * The corner the picture is cut to.
     *
     * From Android 12 the platform publishes the radius its widget frames use, and matching it
     * is the difference between a picture that sits in the frame and one that pokes out of it.
     * Older hosts get a plain rectangle, which is what they draw around anyway.
     */
    private fun cornerRadius(context: Context, density: Float): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                context.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
            }.getOrDefault(16f * density)
        } else {
            0f
        }

    /** Black or white, whichever the paint underneath can carry. */
    private fun inkOn(background: Color): Color =
        if (background.luminance() > 0.45f) Color(0xFF14161C) else Color(0xFFF6F3EA)

    /**
     * Immutable, because nothing outside this app has any business editing what the chip does;
     * update-current, because the chip's verb changes with the pet and the launcher is holding
     * the last one it was given.
     *
     * A plain `val`: these are Java compile-time constants and `const` would very likely work,
     * but nothing here needs one and a build is a poor place to find out.
     */
    private val IMMUTABLE_UPDATE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
