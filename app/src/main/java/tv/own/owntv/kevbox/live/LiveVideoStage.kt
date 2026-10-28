// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.features.shell.PlayerMode
import tv.own.owntv.player.ExoPreviewSurface
import tv.own.owntv.player.LivePreviewEngine

/*
 * Live TV's one video window.
 *
 * The ExoPlayer surface sits at the bottom of the shell, under the browse UI, and moves to wherever
 * the video belongs: the grid's top box, full screen or the mini-player. The UI above it cuts a
 * see-through patch (a BlendMode.Clear draw) where the video should show. The surface is never
 * handed over between screens, so the stream never gets a new window: some TV chips stall when it does.
 *
 * Who cuts the patch: a site the window has settled on cuts its own, so its overlays draw above the
 * video and anything composed later covers it. While the window moves,
 * LiveVideoPatch cuts it at the window's current rectangle instead. Once the last site leaves, the
 * window goes at once, unless the bridge was armed and the player left: then it waits up to the grace
 * period for the box (see patchShows).
 */

/** Where the video window can sit. */
enum class VideoSite { BOX, PLAYER }

/** How long an armed bridge keeps the window after the player leaves, waiting for the box to report. */
internal const val GRACE_MS = 600L

/** The player beats the box; neither means no target. */
internal fun pickTarget(box: Boolean, player: Boolean): VideoSite? = when {
    player -> VideoSite.PLAYER
    box -> VideoSite.BOX
    else -> null
}

/** True once [nowMs] is [GRACE_MS] or more past [lastClearMs]. */
internal fun graceOver(lastClearMs: Long, nowMs: Long): Boolean = nowMs - lastClearMs >= GRACE_MS

/**
 * Whether the stage-level patch shows. With a target, it shows until the window settles there. With
 * none, it shows only when the bridge was armed and the player was the site that left: that carries
 * Back from full screen to the grid until the box reports. Any other leave hides the video at once:
 * the mini-player's Close, Audio Mode, or Live TV closed mid-preview.
 */
internal fun patchShows(target: VideoSite?, settledOnTarget: Boolean, lastCleared: VideoSite?, bridgeArmed: Boolean): Boolean =
    if (target != null) !settledOnTarget else bridgeArmed && lastCleared == VideoSite.PLAYER

/** Closer than this, in px, two edges count as one: float rounding, never a real move. */
private const val SAME_PX = 0.01f

internal fun sameRect(a: Rect, b: Rect): Boolean =
    abs(a.left - b.left) < SAME_PX && abs(a.top - b.top) < SAME_PX &&
        abs(a.right - b.right) < SAME_PX && abs(a.bottom - b.bottom) < SAME_PX

internal fun sameSize(a: Rect, b: Rect): Boolean =
    abs(a.width - b.width) < SAME_PX && abs(a.height - b.height) < SAME_PX

internal fun lerpCorner(from: Float, to: Float, t: Float): Float = from + (to - from) * t

internal fun lerpRect(from: Rect, to: Rect, t: Float): Rect = Rect(
    left = lerpCorner(from.left, to.left, t),
    top = lerpCorner(from.top, to.top, t),
    right = lerpCorner(from.right, to.right, t),
    bottom = lerpCorner(from.bottom, to.bottom, t),
)

/**
 * True when leaving the player hands the stream back to a box: full screen, opened as a live channel
 * with Live TV's grid or Search on screen, the channel on ExoPlayer at the live edge (no catch-up
 * archive, no rewind). Only then does Back keep the stream and shrink the video into the box; every
 * other exit stops it. Search joined on 2026-10-14 (Kevin): OK there previews, OK again goes full
 * screen, Back comes back to the results. Sports joined on 2026-10-28, but only when the box is Sports'
 * ([boxSection]): the hub's "On channels now" rows open full screen with no box drawn, and keeping the
 * stream there would leave it playing behind the hub.
 */
internal fun isBackToGrid(
    playerMode: PlayerMode,
    section: MainSection,
    zapSource: MainSection?,
    liveOnExo: Boolean,
    catchupActive: Boolean,
    rewound: Boolean,
    boxSection: MainSection?,
): Boolean = playerMode == PlayerMode.FULLSCREEN &&
    (section == MainSection.LIVE_TV || section == MainSection.SEARCH ||
        (section == MainSection.SPORTS && boxSection == MainSection.SPORTS)) &&
    zapSource == MainSection.LIVE_TV && liveOnExo && !catchupActive && !rewound

/** What showing a section does to the box and the preview stream, from [sectionShown]. */
data class SectionShown(val clearBox: Boolean, val stopPreview: Boolean)

/**
 * The shell shows [section] with the player [playerIdle] or not, and the box filled by [boxSection]
 * (null for an empty box). The box's own screen keeps it. Any other screen empties it, and stops the
 * stream unless the box was Live TV's and the new screen is one of the two that share its preview
 * engine (Live TV, the TV Guide page): that was the rule before Search had a box. A Search or Sports
 * box always stops it. With no box, every screen but those two stops the preview.
 *
 * While the player is open nothing stops, but a Sports box is emptied on any screen but Sports. A
 * launcher Live tile opens full screen in the same frame as the section change, so the shell never
 * sees the player idle on the new screen. Without this the Sports box stayed filled behind the tile's
 * channel, and its sound kept playing behind the Sports hub.
 */
fun sectionShown(section: MainSection, playerIdle: Boolean, boxSection: MainSection?): SectionShown {
    if (!playerIdle) {
        return SectionShown(
            clearBox = boxSection == MainSection.SPORTS && section != MainSection.SPORTS,
            stopPreview = false,
        )
    }
    if (boxSection != null && boxSection == section) return SectionShown(clearBox = false, stopPreview = false)
    val liveScreen = section == MainSection.LIVE_TV || section == MainSection.EPG
    return SectionShown(
        clearBox = boxSection != null,
        stopPreview = !liveScreen || boxSection == MainSection.SEARCH || boxSection == MainSection.SPORTS,
    )
}

/** The clock reports and frames share: Choreographer frame times are on the System.nanoTime base. */
internal fun stageNowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * Where the video window is and where it is going. Everything is snapshot state, and time only moves
 * through [frame], so a plain test can drive it with no composition.
 */
class LiveVideoStage(durationMs: Int = 320) {

    private class Site(val bounds: Rect, val cornerPx: Float)

    /**
     * A move's clock starts at its first frame ([startMs] null until then), not when it was asked for:
     * on Kevin's TV the frame after OK or Back comes ~350 ms late while the player is built or taken
     * down, and a clock started at the request had the 320 ms move over before it was ever drawn.
     */
    private class Move(val fromRect: Rect, val fromCorner: Float, val startMs: Long? = null)

    /** The length of a move. The shell sets it from the Animations setting each composition. */
    var durationMs: Int by mutableIntStateOf(durationMs)

    private var box by mutableStateOf<Site?>(null)
    private var player by mutableStateOf<Site?>(null)
    private var move by mutableStateOf<Move?>(null)
    private var lastClearMs by mutableStateOf<Long?>(null)

    /** The site whose leaving left no target, until a target returns or the grace period ends. */
    private var lastCleared by mutableStateOf<VideoSite?>(null)

    /** Set by [armBridge]; spent when a target next appears or the grace period ends. */
    private var bridgeArmed by mutableStateOf(false)

    /**
     * The channel in the box, kept here so it outlives the screen while full screen shows. Live TV's
     * grid and Search each write it on OK ([putInBox]); the shell sets it to the playing channel on Back
     * and clears it on any other exit, or when the screen that filled it closes ([sectionShown]).
     */
    var boxChannel: ChannelEntity? by mutableStateOf(null)
        private set

    /** The screen that filled the box: Live TV or Search. Null with an empty box. */
    var boxSection: MainSection? by mutableStateOf(null)
        private set

    fun putInBox(channel: ChannelEntity, section: MainSection) {
        boxChannel = channel
        boxSection = section
    }

    fun clearBox() {
        boxChannel = null
        boxSection = null
    }

    /**
     * True from a held Back opening the box full screen until that Back is released (see HoldBack.kt).
     * The shell sets and clears it; screens' Back handlers skip the release while it is set. Not
     * snapshot state: only Back handlers read it, and they run outside composition.
     */
    var heldBackActed: Boolean = false

    /**
     * Hand the box from screen [from] to screen [to], stream and all, when [from] filled it (and, with
     * [onlyChannelId], only when that is the channel in it). True when it moved. Holding OK on Search's
     * channel takes it to Live TV's grid, and the Back that returns to Search brings the grid's box back.
     */
    fun moveBox(from: MainSection, to: MainSection, onlyChannelId: Long? = null): Boolean {
        val ch = boxChannel ?: return false
        if (boxSection != from) return false
        if (onlyChannelId != null && ch.id != onlyChannelId) return false
        boxSection = to
        return true
    }

    /** The window's rectangle now, in window pixels; null while there is no window. */
    var current: Rect? by mutableStateOf(null)
        private set

    var currentCornerPx: Float by mutableFloatStateOf(0f)
        private set

    val target: VideoSite? get() = pickTarget(box != null, player != null)

    /**
     * How far the box's screen has slid it sideways right now, in px, or null when nothing slides it.
     * Live TV's guide moves the box with a layer translation when its category column opens and closes.
     * The box's bounds are kept without the slide, and the slide is added where the window is drawn
     * ([shown]), which reads it in the same frame as the guide's own layer. Through reports alone the
     * window trailed the box by a frame: a report lands after that frame's layers are updated.
     */
    var boxSlide: (() -> Float)? by mutableStateOf(null)

    /** The box's slide now, in px (0 with nothing sliding it). */
    fun boxSlideNow(): Float = boxSlide?.invoke() ?: 0f

    /**
     * The window's rectangle as drawn now, in window pixels: [current], plus the box's slide while the
     * box is the target. Read it in a draw or layer block, so the slide is read there too.
     */
    val shown: Rect?
        get() = current?.let { r -> if (target == VideoSite.BOX) r.translate(boxSlideNow(), 0f) else r }

    /** The armed player left and no target has appeared yet: the window waits for the box. */
    private val bridging: Boolean get() = bridgeArmed && lastCleared == VideoSite.PLAYER

    /**
     * True while a site wants the video, or while an armed bridge waits (up to [GRACE_MS]) for the box.
     * Any other leave hides the window at once, so nothing shows through a see-through background.
     */
    val visible: Boolean get() = target != null || (current != null && bridging)

    /** True while [LiveVideoPatch] should cut the patch at [current]: see [patchShows]. */
    val patchVisible: Boolean
        get() = current != null && patchShows(target, target?.let(::settledOn) == true, lastCleared, bridgeArmed)

    /**
     * Keep showing the video at the player's rectangle once the player leaves, until the box reports.
     * Call it on an exit that hands the stream back to the grid's box, before the player goes. Does
     * nothing while the player is not on screen, so a stale arm cannot outlive it.
     */
    fun armBridge() {
        if (player != null) bridgeArmed = true
    }

    /**
     * True while the window is moving to [site], or about to: [site] has not reported yet and the window
     * sits somewhere else. With no window at all, [site] appears in place, so it does not count.
     */
    fun arrivingAt(site: VideoSite): Boolean =
        if (siteOf(site) == null) current != null else target == site && !settledOn(site)

    /** True while [frame] has work to do: a move, a hidden window to drop, or a bridge to time out. */
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
        // Nearly equal, not equal: a slid box's bounds come back as (place + slide) - slide in floats.
        if (old != null && sameRect(old.bounds, bounds) && old.cornerPx == cornerPx) return
        // A site the window has settled on that only moved, at the same size, takes the window along at
        // once: the site itself jumped there, and an eased move would leave the video behind it.
        val follow = old != null && settledOn(site) && sameSize(old.bounds, bounds) && old.cornerPx == cornerPx
        val before = targetSite()
        val beforeTarget = target
        when (site) {
            VideoSite.BOX -> box = Site(bounds, cornerPx)
            VideoSite.PLAYER -> player = Site(bounds, cornerPx)
        }
        if (follow) {
            current = bounds
            return
        }
        retarget(before, beforeTarget, nowMs)
    }

    /** [site] left the screen. */
    fun clear(site: VideoSite, nowMs: Long) {
        if (siteOf(site) == null) return
        val before = targetSite()
        val beforeTarget = target
        when (site) {
            VideoSite.BOX -> box = null
            VideoSite.PLAYER -> player = null
        }
        retarget(before, beforeTarget, nowMs)
        if (target == null && before != null) lastCleared = site
        // The bridge only covers a player leaving with no target. Back during the grow leaves the box
        // there, so the arm is spent and a later player exit hides at once.
        if (site == VideoSite.PLAYER && target != null) bridgeArmed = false
    }

    /**
     * Advance the move and the bridge to [nowMs]. A window hidden by an unarmed leave keeps its
     * rectangle until this next frame, so a site whose node is swapped within one frame (a dock)
     * reports again and the window moves on from where it was instead of jumping.
     */
    fun frame(nowMs: Long) {
        val to = targetSite()
        if (to == null) {
            val cleared = lastClearMs
            if (cleared != null && (!bridging || graceOver(cleared, nowMs))) {
                current = null
                lastClearMs = null
                lastCleared = null
                bridgeArmed = false
            }
            return
        }
        var m = move ?: return
        if (m.startMs == null && durationMs > 0) {
            // The move's first frame: it starts now, from where the window is.
            m = Move(m.fromRect, m.fromCorner, nowMs)
            move = m
        }
        val start = m.startMs ?: nowMs
        val t = if (durationMs <= 0) 1f else ((nowMs - start).toFloat() / durationMs).coerceIn(0f, 1f)
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
    private fun retarget(before: Site?, beforeTarget: VideoSite?, nowMs: Long) {
        // The box's rectangles leave out its slide, every other one is plain window pixels: a window
        // passing between the two keeps its place on screen.
        if (beforeTarget != target) {
            val slide = boxSlideNow()
            val dx = (if (beforeTarget == VideoSite.BOX) slide else 0f) - (if (target == VideoSite.BOX) slide else 0f)
            current?.let { if (dx != 0f) current = it.translate(dx, 0f) }
        }
        val to = targetSite()
        if (to == null) {
            move = null
            if (before != null) lastClearMs = nowMs
            return
        }
        lastClearMs = null
        if (before == null) {
            // A target after none: whatever bridged the gap is done.
            lastCleared = null
            bridgeArmed = false
        }
        if (to === before) return
        val from = current
        if (from == null) {
            // No window yet (the first site, or one after the last window went): appear in place.
            current = to.bounds
            currentCornerPx = to.cornerPx
            move = null
        } else if (from == to.bounds && currentCornerPx == to.cornerPx) {
            move = null
        } else {
            move = Move(from, currentCornerPx)
        }
    }
}

val LocalLiveVideoStage = staticCompositionLocalOf<LiveVideoStage?> { null }

/**
 * The bottom window: one [ExoPreviewSurface] at the stage's current rectangle, composed while the
 * stage is visible. Subtitles are left to the site above it, which knows whether it wants them.
 *
 * The window is laid out once, at the full size of this layer, and placed by its graphics layer:
 * moved and scaled down to [LiveVideoStage.shown]. A SurfaceView applies a layout resize off the
 * frame, so resizing it each frame left the video lagging its see-through patch (a black strip along
 * the trailing edges). A layer transform reaches the surface in step with the frame. All stage reads
 * happen in the layer block, so a move neither recomposes nor re-lays out the window.
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
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val r = stage.shown ?: Rect.Zero
                if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
                // The float rectangle, unrounded, so the window lines up with the patch exactly.
                val sx = r.width / size.width
                val sy = r.height / size.height
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = r.left - origin.x
                translationY = r.top - origin.y
                scaleX = sx
                scaleY = sy
                // Round the surface's hole like the site's corners: with Glass on, nothing opaque covers
                // the corner slivers above, so an unclipped surface shows square corners. The shape is
                // in unscaled pixels, so divide by the scale (near uniform: every site is 16:9). No
                // alpha, no offscreen compositing, no render effect, so the hole still reaches the window.
                val scale = (sx + sy) / 2f
                shape = RoundedCornerShape(if (scale > 0f) stage.currentCornerPx / scale else 0f)
                clip = true
            },
            keepAwake = keepAwake,
            autoFrameRate = autoFrameRate,
            subtitles = false,
        )
    }
}

/** The stage-level see-through patch, cut while the window moves or bridges an armed exit. */
@Composable
fun LiveVideoPatch(stage: LiveVideoStage) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Spacer(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                if (!stage.patchVisible) return@drawBehind
                val r = stage.shown ?: return@drawBehind
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
        // The box is kept without its slide (see LiveVideoStage.boxSlide). A layer translation above it
        // calls this on every frame it changes, so a slide reports the same place each frame and moves
        // nothing here.
        val unslid = if (site == VideoSite.BOX) bounds.translate(-stage.boxSlideNow(), 0f) else bounds
        stage.report(site, unslid, with(requireDensity()) { corner.toPx() }, stageNowMs())
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
