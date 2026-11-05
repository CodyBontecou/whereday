package com.bontecou.whereday

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

internal val LocalWorkspaceDialogsReady=staticCompositionLocalOf {true}
/** Screen coordinates: each native dialog has a different window origin from its owner. */
internal val LocalWorkspaceDialogRegion=staticCompositionLocalOf<Rect?> {null}
internal val LocalWorkspaceDialogRegions=staticCompositionLocalOf<List<Rect>> {emptyList()}

/** Whereday sheet chrome shared by every workspace dialog: paper card, 20 dp corners, hairline border. */
private val WorkspaceSheetShape=RoundedCornerShape(28.dp)

/** Date picker in ink on paper, matching the iOS graphical calendar tint. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun workspaceDatePickerColors()=DatePickerDefaults.colors(
    containerColor=wd.card,titleContentColor=wd.textMuted,headlineContentColor=wd.textPrimary,weekdayContentColor=wd.textMuted,
    subheadContentColor=wd.textMuted,navigationContentColor=wd.textPrimary,yearContentColor=wd.textPrimary,currentYearContentColor=wd.accent,
    selectedYearContentColor=wd.surface,selectedYearContainerColor=wd.accent,dayContentColor=wd.textPrimary,
    selectedDayContentColor=wd.surface,selectedDayContainerColor=wd.accent,todayContentColor=wd.accent,todayDateBorderColor=wd.accent,
    dividerColor=wd.border,
)

/** Keep the modal window/scrim and Back ownership, but constrain content to the owner's safe pane. */
@Composable internal fun WorkspaceDialog(
    onDismissRequest:()->Unit,
    properties:DialogProperties=DialogProperties(),
    fillRegion:Boolean=false,
    bottomAligned:Boolean=false,
    content:@Composable ()->Unit
) {
    val states=rememberSaveableStateHolder()
    val body=rememberWorkspaceContent {states.SaveableStateProvider("window-body") {content()}}
    if(!LocalWorkspaceDialogsReady.current) return
    val region=LocalWorkspaceDialogRegion.current
    val regions=LocalWorkspaceDialogRegions.current
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val density=LocalDensity.current
    val maxWidth=with(density) {560.dp.roundToPx()}
    val minimumWidth=with(density) {280.dp.roundToPx()}
    val minimumHeight=with(density) {(240.dp*fontScale.coerceAtLeast(1f)).roundToPx()}
    val effectiveProperties=if(region==null) properties else DialogProperties(
        dismissOnBackPress=properties.dismissOnBackPress,dismissOnClickOutside=properties.dismissOnClickOutside,
        securePolicy=properties.securePolicy,usePlatformDefaultWidth=false,decorFitsSystemWindows=false)
    // One native window and one movable body retain framework view focus, mode and pending text.
    Dialog(onDismissRequest,properties=effectiveProperties) {
        if(region==null) body()
        else {
            var origin by remember {mutableStateOf<Offset?>(null)}
            var card by remember {mutableStateOf<Rect?>(null)}
            var layoutCoordinatesOffset by remember {mutableStateOf(Offset.Zero)}
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().semantics {testTagsAsResourceId=true}) {
                Layout(content={Box(Modifier.onGloballyPositioned {card=it.boundsInRoot()}.testTag("workspace-dialog-content")) {body()}},
                    modifier=Modifier.fillMaxSize().onGloballyPositioned {
                        val position=it.localToScreen(Offset.Zero)
                        origin=position.takeIf {p->p.x.isFinite() && p.y.isFinite()}
                        layoutCoordinatesOffset=it.positionInRoot()
                    }.pointerInput(properties.dismissOnClickOutside) {detectTapGestures {point->
                        val rootPoint=point+layoutCoordinatesOffset
                        if(properties.dismissOnClickOutside && card?.contains(rootPoint)==false) latestDismiss()
                    }}) {measurables,constraints->
                    val start=origin
                    fun local(rect:Rect)=WorkspaceRect((rect.left-start!!.x).roundToInt(),(rect.top-start.y).roundToInt(),(rect.right-start.x).roundToInt(),(rect.bottom-start.y).roundToInt())
                    val safe=if(start==null) null else WorkspaceDialogGeometry.choose(local(region),regions.map(::local),
                        WorkspaceRect(0,0,constraints.maxWidth,constraints.maxHeight),minimumWidth,minimumHeight)
                    if(safe==null) layout(constraints.maxWidth,constraints.maxHeight) {}
                    else {
                        // Never measure lazy calendars at zero width/height while awaiting a usable viewport.
                        val child=measurables.single().measure(if(fillRegion) Constraints.fixed(safe.width,safe.height) else Constraints(0,minOf(safe.width,maxWidth),0,safe.height))
                        layout(constraints.maxWidth,constraints.maxHeight) {
                            child.place(safe.left+(safe.width-child.width)/2,safe.top+(if(bottomAligned) safe.height-child.height else (safe.height-child.height)/2))
                        }
                    }
                }
            }
        }
    }
}

/** Standard compact presentation; pane presentation shares the same actions and semantic styles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WorkspaceAlertDialog(
    onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,modifier:Modifier=Modifier,
    dismissButton:(@Composable ()->Unit)?=null,title:(@Composable ()->Unit)?=null,text:(@Composable ()->Unit)?=null
) {
    if(!LocalWorkspaceDialogsReady.current) return
    val states=rememberSaveableStateHolder()
    val body=rememberWorkspaceContent {states.SaveableStateProvider("dialog-body") {text?.invoke()}}
    if(LocalWorkspaceDialogRegion.current==null) {
        AlertDialog(onDismissRequest,confirmButton,modifier,dismissButton=dismissButton,
            title=title?.let {heading->@Composable {ProvideTextStyle(WdType.title2) {heading()}}},text=if(text==null) null else { {body()} },
            shape=WorkspaceSheetShape,containerColor=wd.containerHigh,titleContentColor=wd.textPrimary,textContentColor=wd.textMuted,tonalElevation=0.dp)
    } else WorkspaceDialog(onDismissRequest) {
        Surface(modifier.widthIn(min=280.dp).testTag("workspace-alert"),shape=WorkspaceSheetShape,
            color=wd.containerHigh,contentColor=wd.textPrimary) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                title?.let {Box(Modifier.semantics {heading()}) {CompositionLocalProvider(LocalContentColor provides wd.textPrimary) {ProvideTextStyle(WdType.title2) {it()}}}}
                if(text!=null) Box(Modifier.weight(1f,fill=false)) {CompositionLocalProvider(LocalContentColor provides wd.textMuted) {ProvideTextStyle(MaterialTheme.typography.bodyMedium) {body()}}}
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,androidx.compose.ui.Alignment.End),verticalArrangement=Arrangement.spacedBy(4.dp)) {dismissButton?.invoke();confirmButton()}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun WorkspaceDatePickerDialog(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,dismissButton:@Composable ()->Unit,content:@Composable ColumnScope.()->Unit) {
    if(!LocalWorkspaceDialogsReady.current) return
    val states=rememberSaveableStateHolder()
    val body=rememberWorkspaceContent {states.SaveableStateProvider("calendar-body") {Column(content=content)}}
    if(LocalWorkspaceDialogRegion.current==null) DatePickerDialog(onDismissRequest,confirmButton,Modifier.border(1.dp,wd.border,WorkspaceSheetShape),dismissButton=dismissButton,
        shape=WorkspaceSheetShape,tonalElevation=0.dp,colors=workspaceDatePickerColors()) {body()}
    else WorkspaceDialog(onDismissRequest) {
        Surface(Modifier.widthIn(max=360.dp),shape=WorkspaceSheetShape,color=wd.card,contentColor=wd.textPrimary,border=BorderStroke(1.dp,wd.border)) {
            Column {
                Column(Modifier.weight(1f,fill=false)) {body()}
                FlowRow(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,androidx.compose.ui.Alignment.End)) {dismissButton();confirmButton()}
            }
        }
    }
}

/** A pane menu cannot use a platform popup that is free to cross a reported hinge. */
@Composable internal fun WorkspaceDropdownMenu(expanded:Boolean,onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    if(!LocalWorkspaceDialogsReady.current) return
    if(LocalWorkspaceDialogRegion.current==null) DropdownMenu(expanded,onDismissRequest,shape=RoundedCornerShape(12.dp),containerColor=wd.card,
        tonalElevation=0.dp,border=BorderStroke(1.dp,wd.border),content=content)
    else if(expanded) WorkspaceDialog(onDismissRequest) {
        Surface(shape=RoundedCornerShape(12.dp),color=wd.card,contentColor=wd.textPrimary,border=BorderStroke(1.dp,wd.border)) {
            Column(Modifier.widthIn(min=112.dp).verticalScroll(rememberScrollState()).padding(vertical=8.dp),content=content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkspaceModalBottomSheet(onDismissRequest:()->Unit,fullHeight:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    if(!LocalWorkspaceDialogsReady.current) return
    val states=rememberSaveableStateHolder()
    val body=rememberWorkspaceContent {states.SaveableStateProvider("sheet-body") {Column(content=content)}}
    // A full-height sheet opens expanded, like an iOS .large detent.
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=fullHeight)
    if(LocalWorkspaceDialogRegion.current==null) ModalBottomSheet(onDismissRequest,sheetState=sheetState,containerColor=wd.card,contentColor=wd.textPrimary,tonalElevation=0.dp) {body()}
    else WorkspaceDialog(onDismissRequest,bottomAligned=true) {
        Surface(shape=BottomSheetDefaults.ExpandedShape,color=wd.card,contentColor=wd.textPrimary,border=BorderStroke(1.dp,wd.border)) {body()}
    }
}

/** The framework clock widget has one owned window in compact and pane presentations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WorkspaceClockDialog(initialHour:Int,initialMinute:Int,clock24:Boolean,tagPrefix:String,title:@Composable (Int,Int)->Unit,dismiss:()->Unit,confirmEnabled:Boolean=true,confirm:(Int,Int)->Unit) {
    var draftHour by androidx.compose.runtime.saveable.rememberSaveable {mutableIntStateOf(initialHour)}
    var draftMinute by androidx.compose.runtime.saveable.rememberSaveable {mutableIntStateOf(initialMinute)}
    val nativeClock=remember {java.util.concurrent.atomic.AtomicReference<android.widget.TimePicker?>()}
    val display24=androidx.compose.runtime.saveable.rememberSaveable {clock24}
    val inputSaver=remember {androidx.compose.runtime.saveable.listSaver<FrameworkClockInput?,String>(
        save={(nativeClock.get()?.pendingInput() ?: it)?.saved().orEmpty()},restore=FrameworkClockInput::restored)}
    val pendingInput by androidx.compose.runtime.saveable.rememberSaveable(stateSaver=inputSaver) {mutableStateOf<FrameworkClockInput?>(null)}
    WorkspaceDialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.safeDrawingPadding().imePadding().padding(16.dp)) {
            Surface(Modifier.widthIn(max=360.dp),shape=WorkspaceSheetShape,color=wd.card,contentColor=wd.textPrimary,border=BorderStroke(1.dp,wd.border)) {
                Column {
                    Box(Modifier.padding(24.dp).semantics {heading()}) {ProvideTextStyle(WdType.title2) {title(draftHour,draftMinute)}}
                    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())) {
                        androidx.compose.ui.viewinterop.AndroidView(factory={context->
                            // The host is a narrow modal even in a landscape activity. Inflate the
                            // framework's vertical clock layout instead of clipping its two-column layout.
                            val themed=android.view.ContextThemeWrapper(context,context.theme).apply {
                                applyOverrideConfiguration(android.content.res.Configuration(context.resources.configuration).apply {
                                    orientation=android.content.res.Configuration.ORIENTATION_PORTRAIT
                                })
                            }
                            android.widget.TimePicker(themed).apply {
                                // One save owner avoids framework restoration overwriting raw invalid fields.
                                isSaveEnabled=false;isSaveFromParentEnabled=false
                                setIs24HourView(display24);hour=draftHour;minute=draftMinute
                                restorePendingInput(pendingInput)
                                setOnTimeChangedListener {_,h,m->draftHour=h;draftMinute=m}
                            }.also {nativeClock.set(it)}
                        },modifier=Modifier.testTag("$tagPrefix-clock"))
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,androidx.compose.ui.Alignment.End),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick=dismiss,modifier=Modifier.testTag("$tagPrefix-cancel")) {Text(stringResource(R.string.automatic_export_cancel))}
                        TextButton(enabled=confirmEnabled,onClick={nativeClock.get()?.takeIf {it.isAttachedToWindow}?.let {clock->
                            if(clock.validateInput()) {clock.clearFocus();confirm(clock.hour,clock.minute)}
                        }},modifier=Modifier.testTag("$tagPrefix-confirm")) {Text(stringResource(R.string.visit_apply_time))}
                    }
                }
            }
        }
    }
}
