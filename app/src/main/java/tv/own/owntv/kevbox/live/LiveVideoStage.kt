// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collectLatest
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.player.ExoPreviewSurface
import tv.own.owntv.player.LivePreviewEngine
import kotlin.math.roundToInt

/*
 * Live TV's one video window.
 *
 * The ExoPlayer surface sits at the bottom of the shell, under the browse UI, and moves to wherever
 * the video belongs: the grid's top box now, full screen and the mini-player later. The UI above it
 * cuts a see-through patch (a BlendMode.Clear draw) where the video should show. The surface is never
 * handed over between screens, so the stream never gets a new window: some TV chips stall when it does.
 *
 * Who cuts the patch: a site the window has settled on cuts its own, so its overlays draw above the
 * video and anything composed later (the categories sidebar) covers it. While the window moves, or
 * waits out the grace period after its last site left, LiveVideoPatch cuts it at the window's
 * current rectangle instead.
 */

/** Where the video window can sit. */
enum class VideoSite { BOX, PLAYER }

/** How long the window stays after its last site leaves, so a site handing over to the next keeps the surface. */
internal const val GRACE_MS = 600L

/** The player beats the box; neither means no target. */
internal fun pickTarget(box: Boolean, player: Boolean): VideoSite? = when {
    player -> VideoSite.PLAYER
    box -> VideoSite.BOX
    else -> null
}

/** True once [nowMs] is [GRACE_MS] or more past [lastClearMs]. */
internal fun graceOver(lastClearMs: Long, nowMs: Long): Boolean = nowMs - lastClearMs >= GRACE_MS

internal fun lerpCorner(from: Float, to: Float, t: Float): Float = from + (to - from) * t

internal fun lerpRect(from: Rect, to: Rect, t: Float): Rect = Rect(
    left = lerpCorner(from.left, to.left, t),
    top = lerpCorner(from.top, to.top, t),
    right = lerpCorner(from.right, to.right, t),
    bottom = lerpCorner(from.bottom, to.bottom, t),
)

/** The clock reports and frames share: Choreographer frame times are on the System.nanoTime base. */
internal fun stageNowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * Where the video window is and where it is going. Everything is snapshot state, and time only moves
 * through [frame], so a plain test can drive it with no composition.
 */
class LiveVideoStage(durationMs: Int = 320) {

    private class Site(val bounds: Rect, val cornerPx: Float)

    private class Move(val fromRect: Rect, val fromCorner: Float, val startMs: Long)

    /** The length of a move. The shell sets it from the Animations setting each composition. */
    var durationMs: Int by mutableIntStateOf(durationMs)

    private var box by mutableStateOf<Site?>(null)
    private var player by mutableStateOf<Site?>(null)
    private var move by mutableStateOf<Move?>(null)
    private var lastClearMs by mutableStateOf<Long?>(null)

    /** The channel in the grid's box, kept here so it outlives the grid while full screen shows. */
    var boxChannel: ChannelEntity? by mutableStateOf(null)

    /** The window's rectangle now, in window pixels; null while there is no window. */
    var current: Rect? by mutableStateOf(null)
        private set

    var currentCornerPx: Float by mutableFloatStateOf(0f)
        private set

    val target: VideoSite? get() = pickTarget(box != null, player != null)

    /** True while a site wants the video, or within [GRACE_MS] of the last one leaving. */
    val visible: Boolean get() = target != null || current != null

    /** True while [frame] has work to do: a move, or a grace period to time out. */
    internal val needsFrames: Boolean get() = move != null || (target == null && lastClearMs != null)

    private fun siteOf(site: VideoSite): Site? = when (site) {
        VideoSite.BOX -> box
        VideoSite.PLAYER -> player
    }

    /** True when the window has reached [site]'s rectangle and is not moving. */
    fun settledOn(site: VideoSite): Boolean {
        val s = siteOf(site) ?: return false
        return target == site && move == null && current == s.bounds
    }

    /** [site] is composed at [bounds] (window pixels) with [cornerPx] corners. */
    fun report(site: VideoSite, bounds: Rect, cornerPx: Float, nowMs: Long) {
        val old = siteOf(site)
        if (old != null && old.bounds == bounds && old.cornerPx == cornerPx) return
        val before = targetSite()
        when (site) {
            VideoSite.BOX -> box = Site(bounds, cornerPx)
            VideoSite.PLAYER -> player = Site(bounds, cornerPx)
        }
        retarget(before, nowMs)
    }

    /** [site] left the screen. */
    fun clear(site: VideoSite, nowMs: Long) {
        if (siteOf(site) == null) return
        val before = targetSite()
        when (site) {
            VideoSite.BOX -> box = null
            VideoSite.PLAYER -> player = null
        }
        retarget(before, nowMs)
    }

    /** Advance the move and the grace period to [nowMs]. */
    fun frame(nowMs: Long) {
        val to = targetSite()
        if (to == null) {
            val cleared = lastClearMs
            if (cleared != null && graceOver(cleared, nowMs)) {
                current = null
                lastClearMs = null
            }
            return
        }
        val m = move ?: return
        val t = if (durationMs <= 0) 1f else ((nowMs - m.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        if (t >= 1f) {
            current = to.bounds
            currentCornerPx = to.cornerPx
            move = null
        } else {
            val eased = FastOutSlowInEasing.transform(t)
            current = lerpRect(m.fromRect, to.bounds, eased)
            currentCornerPx = lerpCorner(m.fromCorner, to.cornerPx, eased)
        }
    }

    private fun targetSite(): Site? = target?.let(::siteOf)

    /** The target site or its rectangle may have changed: start a move from where the window is now. */
    private fun retarget(before: Site?, nowMs: Long) {
        val to = targetSite()
        if (to == null) {
            move = null
            if (before != null) lastClearMs = nowMs
            return
        }
        lastClearMs = null
        if (to === before) return
        val from = current
        if (from == null) {
            // No window yet (the first site, or one after the grace period ran out): appear in place.
            current = to.bounds
            currentCornerPx = to.cornerPx
            move = null
        } else if (from == to.bounds && currentCornerPx == to.cornerPx) {
            move = null
        } else {
            move = Move(from, currentCornerPx, nowMs)
        }
    }
}

val LocalLiveVideoStage = staticCompositionLocalOf<LiveVideoStage?> { null }

/**
 * The bottom window: one [ExoPreviewSurface] at the stage's current rectangle, composed while the
 * stage is visible. Subtitles are left to the site above it, which knows whether it wants them.
 */
@Composable
fun LiveVideoLayer(stage: LiveVideoStage, engine: LivePreviewEngine, keepAwake: Boolean, autoFrameRate: Boolean) {
    LaunchedEffect(stage) {
        snapshotFlow { stage.needsFrames }.collectLatest { needed ->
            if (needed) while (true) withFrameMillis { stage.frame(it) }
        }
    }
    if (!stage.visible) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        ExoPreviewSurface(
            engine = engine,
            modifier = Modifier.layout { measurable, constraints ->
                // Read here, not in composition, so a move re-lays out the window without recomposing it.
                val r = stage.current ?: Rect.Zero
                val w = r.width.roundToInt().coerceAtLeast(0)
                val h = r.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((r.left - origin.x).roundToInt(), (r.top - origin.y).roundToInt())
                }
            },
            keepAwake = keepAwake,
            autoFrameRate = autoFrameRate,
            subtitles = false,
        )
    }
}

/** The stage-level see-through patch, cut while the window moves or waits out the grace period. */
@Composable
fun LiveVideoPatch(stage: LiveVideoStage) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Spacer(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val r = stage.current ?: return@drawBehind
                val settled = stage.target?.let(stage::settledOn) == true
                if (settled) return@drawBehind
                drawRoundRect(
                    color = Color.Black,
                    topLeft = r.topLeft - origin,
                    size = r.size,
                    cornerRadius = CornerRadius(stage.currentCornerPx),
                    blendMode = BlendMode.Clear,
                )
            },
    )
}

/**
 * Marks this layout as a place the video window can sit: reports its bounds while composed, clears
 * them when it leaves, and cuts the see-through patch, under its own content, once the window has
 * settled here.
 */
fun Modifier.liveVideoSite(stage: LiveVideoStage, site: VideoSite, corner: Dp): Modifier =
    this then LiveVideoSiteElement(stage, site, corner)

private data class LiveVideoSiteElement(val stage: LiveVideoStage, val site: VideoSite, val corner: Dp) :
    ModifierNodeElement<LiveVideoSiteNode>() {
    override fun create() = LiveVideoSiteNode(stage, site, corner)

    override fun update(node: LiveVideoSiteNode) = node.update(stage, site, corner)
}

private class LiveVideoSiteNode(
    var stage: LiveVideoStage,
    var site: VideoSite,
    var corner: Dp,
) : Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode {

    fun update(stage: LiveVideoStage, site: VideoSite, corner: Dp) {
        if (stage !== this.stage || site != this.site) this.stage.clear(this.site, stageNowMs())
        this.stage = stage
        this.site = site
        this.corner = corner
        // A new corner or site with no layout change gets no positioning callback, so report it now.
        if (isAttached) {
            runCatching { requireLayoutCoordinates() }.getOrNull()?.takeIf { it.isAttached }?.let(::reportFrom)
        }
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) = reportFrom(coordinates)

    private fun reportFrom(coordinates: LayoutCoordinates) {
        // Both corners through localToWindow: unclipped, and true to any scale above this layout.
        val size = coordinates.size
        val bounds = Rect(
            coordinates.localToWindow(Offset.Zero),
            coordinates.localToWindow(Offset(size.width.toFloat(), size.height.toFloat())),
        )
        stage.report(site, bounds, with(requireDensity()) { corner.toPx() }, stageNowMs())
    }

    override fun ContentDrawScope.draw() {
        if (stage.settledOn(site)) {
            drawRoundRect(
                color = Color.Black,
                cornerRadius = CornerRadius(with(requireDensity()) { corner.toPx() }),
                blendMode = BlendMode.Clear,
            )
        }
        drawContent()
    }

    override fun onDetach() {
        stage.clear(site, stageNowMs())
    }
}
