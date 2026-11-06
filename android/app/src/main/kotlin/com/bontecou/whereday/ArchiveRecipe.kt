package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One underlined word in the Archive sentence; tapping it advances to the next choice. */
internal data class RecipeToken(val text:String,val tag:String,val onClick:()->Unit)

/** The Archive board's "export as a sentence": the localized template's %n$s slots become tappable ink words. */
@Composable internal fun ArchiveRecipeSentence(template:String,tokens:List<RecipeToken>,enabled:Boolean,modifier:Modifier=Modifier) {
    val rust=wd.inkRust
    val muted=wd.textMuted
    val text=buildAnnotatedString {
        val slot=Regex("%(\\d+)\\\$s")
        var cursor=0
        slot.findAll(template).forEach {match->
            append(template.substring(cursor,match.range.first))
            val token=tokens.getOrNull(match.groupValues[1].toInt()-1)
            if(token==null) append(match.value)
            else if(!enabled) withStyleSpan(SpanStyle(color=muted,textDecoration=TextDecoration.Underline)) {append(token.text)}
            else withLink(LinkAnnotation.Clickable(token.tag,TextLinkStyles(SpanStyle(color=rust,textDecoration=TextDecoration.Underline))) {token.onClick()}) {append(token.text)}
            cursor=match.range.last+1
        }
        append(template.substring(cursor))
    }
    val shape=RoundedCornerShape(28.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).padding(start=20.dp,end=20.dp,top=20.dp,bottom=16.dp).testTag("archive-recipe")) {
        Text(text,style=WdType.serifBody.copy(fontSize=27.sp,lineHeight=39.sp),color=wd.textPrimary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.archive_recipe_hint).uppercase(),style=WdType.caption2.copy(fontSize=10.sp),color=wd.textMuted)
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(style:SpanStyle,block:androidx.compose.ui.text.AnnotatedString.Builder.()->Unit) {
    val index=pushStyle(style)
    try {block()} finally {pop(index)}
}

/** Protocol format names as people write them (GPX, GeoJSON, OwnTracks); these are product names, not translated. */
internal fun recipeFormatName(format:com.bontecou.whereday.export.ExportFormat)=when(format) {
    com.bontecou.whereday.export.ExportFormat.markdown->"Markdown"
    com.bontecou.whereday.export.ExportFormat.geojson->"GeoJSON"
    com.bontecou.whereday.export.ExportFormat.owntracks->"OwnTracks"
    com.bontecou.whereday.export.ExportFormat.overland->"Overland"
    else->format.name.uppercase(java.util.Locale.ROOT)
}
