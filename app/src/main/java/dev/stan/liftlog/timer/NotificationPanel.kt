package dev.stan.liftlog.timer

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import dev.stan.liftlog.R
import dev.stan.liftlog.data.formatClock
import dev.stan.liftlog.data.plural

/**
 * The body both notification surfaces draw for themselves.
 *
 * Asking Android for an accent colour was never going to work: since Android 12 `setColor`
 * tints nothing but the small-icon silhouette in the system header, and HyperOS substitutes
 * the launcher icon for that silhouette — so the accent had no pixels to land on. A layout
 * the app supplies is rendered as given, on the lock screen included, which is where this
 * app is actually read.
 *
 * What stays out of reach is the header and the action row: an app targeting Android 12+
 * cannot draw a fully custom notification, so the system wraps this in its own template.
 * The panel is a band between two rows of system chrome, and the buttons stay system-drawn —
 * deliberately, because those buttons are the whole point of the notification.
 *
 * Deliberately stateless. [RestAlarm.alert] reaches this from a broadcast receiver that can
 * be running in a process spawned only to deliver that broadcast, so nothing here may look
 * at [SessionManager] or the database — every value arrives as an argument.
 */

/** The default clock size, matching `notification_panel_expanded.xml`. */
private const val CLOCK_SP = 26f

/**
 * What a panel says, resolved before any view exists.
 *
 * The `·`-joined strings are composed here rather than split across per-field TextViews on
 * purpose: `listOfNotNull(...).joinToString(" · ")` drops absent fields and takes their
 * separators with them. Bound field-by-field instead, an unresolved set position would leave
 * `Squat · · 100 kg × 5` on the lock screen — and blank fields are reachable, notably on the
 * alarm's cold-start path where the set position and load arrive as 0 and "".
 */
internal class PanelCopy(
    /** The one-line phase-and-clock, e.g. "Resting · 2:31". Also the notification's title. */
    val title: String,
    /** Just the clock, for the expanded view's hero number. */
    val clock: String,
    /** Just the phase, labelling that number. */
    val phase: String,
    /** Movement, set and load — what is in hand. */
    val primary: String,
    /** Rest target and time since the rack; null outside rest. */
    val secondary: String? = null,
    /** Rest target in seconds; 0 hides the bar. */
    val progressMax: Int = 0,
    val progress: Int = 0
) {
    /**
     * What goes into EXTRA_TEXT — not the same string as the panel's own body line.
     *
     * The panel splits primary and secondary across two TextViews; a surface that declines
     * the custom view has one line to work with. That detail line used to reach those
     * surfaces through BigTextStyle's EXTRA_BIG_TEXT, but DecoratedCustomViewStyle occupies
     * the single setStyle slot — so it travels in the content text now, or it stops being
     * read aloud at all. With no secondary this is [primary] unchanged.
     */
    val fallbackText: String get() = listOfNotNull(primary, secondary).joinToString(" · ")
}

/** What the ongoing session notification says. */
internal fun sessionCopy(state: SessionState, now: Long): PanelCopy {
    val overrun = state.restOverrun(now)

    // The phase and its clock read as one line — "Resting · 2:31" — rather than leaning
    // on the platform chronometer in the corner, which would show the same number twice.
    val phase = if (state.phase == Phase.REST && state.restTargetReached) "Rest is up" else state.phaseTitle
    val clock = formatClock(state.displaySeconds(now))

    // What is in hand: movement, which set of the ramp, and the load on the bar.
    val movement = state.currentExercise.ifBlank { "No exercise yet" }
    val load = state.currentSetCore.ifBlank {
        state.currentSetLabel.ifBlank { plural(state.setsThisSession, "set") + " logged" }
    }
    val headline = listOfNotNull(
        movement,
        state.currentSetPosition.takeIf { it > 0 }?.let { "set $it" },
        load
    ).joinToString(" · ")

    // Second line, only where rest is running: the target, and how long since the rack —
    // which is the number that decides whether the next set is going to go.
    val target = state.restTargetSeconds.takeIf { it > 0 }?.let { "target ${formatClock(it)}" }
    val rest = when (state.phase) {
        Phase.REST, Phase.RACKED ->
            if (overrun != null) "${formatClock(overrun)} over target"
            else "${formatClock(state.totalRestSeconds(now))} since rack"
        else -> null
    }
    val detail = listOfNotNull(target, rest).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    // The bar fills to the target and stops. Overrun is the detail line's job — a bar that
    // ran past its own end would say less than "0:12 over target" does.
    val max = if (state.phase == Phase.REST || state.phase == Phase.RACKED) state.restTargetSeconds else 0

    return PanelCopy(
        title = "$phase · $clock",
        clock = clock,
        phase = phase,
        primary = headline,
        secondary = detail,
        progressMax = max,
        progress = if (max > 0) state.totalRestSeconds(now).coerceAtMost(max) else 0
    )
}

/**
 * What the rest-finished alert says.
 *
 * Takes primitives rather than a [SessionState] because its caller may have no session in
 * memory to read — see the note on statelessness above.
 */
internal fun restDoneCopy(
    targetSeconds: Int,
    exercise: String,
    setPosition: Int,
    setCore: String,
    planComplete: Boolean
): PanelCopy {
    // "Next:" is a promise about a specific set, so it is only made when there is a
    // load to name; with the ramp exhausted the alert falls back to a plain prompt.
    val body = if (planComplete) "That was the last set — plan complete"
    else if (setCore.isBlank()) "Back under the bar" else "Next: " + listOfNotNull(
        exercise.ifBlank { null },
        setPosition.takeIf { it > 0 }?.let { "set $it" },
        setCore
    ).joinToString(" · ")

    val clock = formatClock(targetSeconds)
    return PanelCopy(
        title = "$clock rest is up",
        clock = clock,
        phase = "rest is up",
        primary = body
    )
}

private fun compactPanel(context: Context, copy: PanelCopy): RemoteViews =
    RemoteViews(context.packageName, R.layout.notification_panel_compact).apply {
        setTextViewText(R.id.panel_title, copy.title)
        setTextViewText(R.id.panel_primary, copy.primary)
    }

private fun expandedPanel(context: Context, copy: PanelCopy, clockSp: Float): RemoteViews =
    RemoteViews(context.packageName, R.layout.notification_panel_expanded).apply {
        setTextViewText(R.id.panel_clock, copy.clock)
        setTextViewText(R.id.panel_phase, copy.phase)
        setTextViewText(R.id.panel_primary, copy.primary)
        // Set unconditionally rather than only when it differs from the layout's own size:
        // the reapply path reuses the inflated view, so a size left unset would be whatever
        // the last post made it.
        setTextViewTextSize(R.id.panel_clock, TypedValue.COMPLEX_UNIT_SP, clockSp)

        setViewVisibility(R.id.panel_secondary, if (copy.secondary != null) View.VISIBLE else View.GONE)
        copy.secondary?.let { setTextViewText(R.id.panel_secondary, it) }

        setViewVisibility(R.id.panel_progress, if (copy.progressMax > 0) View.VISIBLE else View.GONE)
        if (copy.progressMax > 0) {
            setProgressBar(R.id.panel_progress, copy.progressMax, copy.progress, false)
        }
    }

/**
 * Hangs the panel on a notification.
 *
 * [headsUp] matters for the alert and only the alert: the platform resolves each surface on
 * its own, and a heads-up falls back to the *content* view rather than the big one. An
 * IMPORTANCE_HIGH notification is seen almost exclusively as a heads-up, so without this the
 * expanded panel would be the one view the lifter never sees.
 */
internal fun NotificationCompat.Builder.withPanel(
    context: Context,
    copy: PanelCopy,
    clockSp: Float = CLOCK_SP,
    headsUp: Boolean = false
): NotificationCompat.Builder = apply {
    setStyle(NotificationCompat.DecoratedCustomViewStyle())
    setCustomContentView(compactPanel(context, copy))
    setCustomBigContentView(expandedPanel(context, copy, clockSp))
    // A fresh instance, not the one above: the template parents whatever it is given, and
    // handing the same RemoteViews to two fields is not a thing to bet on across an OEM fork.
    if (headsUp) setCustomHeadsUpContentView(expandedPanel(context, copy, clockSp))
}
