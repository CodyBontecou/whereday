package com.bontecou.whereday

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** null means the posture is not known yet; do not briefly lay controls across an unknown hinge. */
@Composable internal fun rememberJournalWindowFolds():List<WorkspaceFold>? {
    val activity=LocalActivity.current ?: return emptyList()
    val source=remember(activity) {WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map {info->
        info.displayFeatures.filterIsInstance<FoldingFeature>().map {feature->
            val b=feature.bounds
            WorkspaceFold(WorkspaceRect(b.left,b.top,b.right,b.bottom),
                if(feature.orientation==FoldingFeature.Orientation.VERTICAL) WorkspaceFoldAxis.vertical else WorkspaceFoldAxis.horizontal,
                feature.isSeparating,feature.occlusionType==FoldingFeature.OcclusionType.FULL,feature.state==FoldingFeature.State.HALF_OPENED)
        }
    }}
    return source.collectAsStateWithLifecycle(initialValue=null).value
}

/** One movable composition per screen, even when its primary/companion role changes. */
@Composable internal fun rememberWorkspaceContent(content:@Composable ()->Unit):@Composable ()->Unit {
    val latest=rememberUpdatedState(content)
    return remember {movableContentOf {latest.value()}}
}

/** The full primary Scaffold and companion chrome are measured inside fold-safe window rectangles. */
@Composable internal fun JournalWorkspaceFrame(allowCompanion:Boolean,modifier:Modifier=Modifier,semanticsTags:Boolean=true,folds:List<WorkspaceFold>?=rememberJournalWindowFolds(),inspectionActive:Boolean=false,inspectionInPrimary:Boolean=false,companionFirst:Boolean=false,inspection:@Composable (Boolean)->Unit={},modal:(@Composable ()->Unit)?=null,primary:@Composable (Boolean)->Unit,companion:@Composable ()->Unit) {
    val density=LocalDensity.current.density
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    var origin by remember {mutableStateOf<IntOffset?>(null)}
    var screenOrigin by remember {mutableStateOf<Offset?>(null)}
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned {position->
        screenOrigin=position.localToScreen(Offset.Zero)
        val p=position.positionInWindow();origin=IntOffset(p.x.roundToInt(),p.y.roundToInt())
    }.then(if(semanticsTags) Modifier.testTag("journal-workspace") else Modifier)) {
        val width=constraints.maxWidth;val height=constraints.maxHeight
        val offset=origin
        val local=if(offset==null || folds==null) null else folds.map {f->
            val b=f.bounds
            f.copy(bounds=WorkspaceRect(b.left-offset.x,b.top-offset.y,b.right-offset.x,b.bottom-offset.y))
        }
        val arranged=if(local==null) WorkspaceArrangement(null) else JournalWorkspaceGeometry.arrange(width,height,density,local,rtl,allowCompanion)
        // Posture-aware: the companion can take the first (top) pane, e.g. the map above a half-open tabletop hinge.
        val arrangement=if(companionFirst && arranged.paired) arranged.copy(primary=arranged.companion,companion=arranged.primary) else arranged
        fun screenRegion(rect:WorkspaceRect?):Rect? {
            val start=screenOrigin ?: return null
            return rect?.let {Rect(it.left+start.x,it.top+start.y,it.right+start.x,it.bottom+start.y)}
        }
        val bounded=arrangement.paired || local?.any {it.separating || it.occluding}==true
        val inspectionRegion=if(arrangement.paired && !inspectionInPrimary) arrangement.companion else arrangement.primary
        val dialogRegions=if(bounded) arrangement.safeRegions.mapNotNull(::screenRegion) else emptyList()
        CompositionLocalProvider(LocalWorkspaceDialogsReady provides (arrangement.primary!=null && screenOrigin!=null),LocalWorkspaceDialogRegions provides dialogRegions) {
        Layout(content={
            if(arrangement.primary!=null) Box(Modifier.fillMaxSize().then(if(semanticsTags) Modifier.testTag("workspace-primary") else Modifier)) {CompositionLocalProvider(LocalWorkspaceDialogRegion provides (if(bounded) screenRegion(arrangement.primary) else null)) {primary(arrangement.paired)}}
            if(arrangement.companion!=null) Box(Modifier.fillMaxSize().then(if(semanticsTags) Modifier.testTag("workspace-companion") else Modifier)) {CompositionLocalProvider(LocalWorkspaceDialogRegion provides (if(bounded) screenRegion(arrangement.companion) else null)) {companion()}}
            key("workspace-inspection") {if(inspectionActive) Box(Modifier.fillMaxSize().then(if(semanticsTags) Modifier.testTag("workspace-inspection-host") else Modifier)) {CompositionLocalProvider(LocalWorkspaceDialogRegion provides (if(bounded) screenRegion(inspectionRegion) else null)) {inspection(arrangement.paired)}}}
            key("workspace-modal") {if(modal!=null) Box(Modifier.fillMaxSize()) {CompositionLocalProvider(LocalWorkspaceDialogRegion provides (if(bounded) screenRegion(arrangement.primary) else null)) {modal()}}}
        },modifier=Modifier.fillMaxSize()) {measurables,_->
            val target=if(arrangement.paired) (if(inspectionInPrimary) arrangement.primary else arrangement.companion) else null
            val rectangles=listOfNotNull(arrangement.primary,arrangement.companion)+(if(inspectionActive) listOf(target ?: WorkspaceRect(0,0,0,0)) else emptyList())+(if(modal!=null) listOf(arrangement.primary ?: WorkspaceRect(0,0,0,0)) else emptyList())
            val panes=measurables.zip(rectangles).map {(measurable,rect)->measurable.measure(Constraints.fixed(rect.width,rect.height)) to rect}
            layout(width,height) {panes.forEach {(placeable,rect)->placeable.place(rect.left,rect.top)}}
        }
        }
    }
}

/** Root permission reviews are outside journal navigation, but still need a known single safe region.
 * This frame owns only native modal content; its empty pane has no drawing, input or semantics. */
@Composable internal fun WorkspaceRootDialogScope(content:@Composable ()->Unit) {
    JournalWorkspaceFrame(false,semanticsTags=false,modal=content,primary={},companion={})
}
