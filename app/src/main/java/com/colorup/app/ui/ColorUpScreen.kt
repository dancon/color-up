package com.colorup.app.ui

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.colorup.app.ColoringUiState
import com.colorup.app.GameScreen
import com.colorup.core.Crayon
import com.colorup.core.CrayonPalette
import com.colorup.core.LineArt
import com.colorup.core.Point
import com.colorup.core.PageProgress
import com.colorup.core.Stroke
import com.colorup.core.Tool
import kotlinx.coroutines.delay

private val Cream = Color(0xFFFFF8EC)
private val Paper = Color(0xFFFFFDF8)
private val Ink = Color(0xFF3D3A45)
private val Muted = Color(0xFF726C7C)
private val Coral = Color(0xFFFF5C5C)
private val Lemon = Color(0xFFFFD93D)
private val Sky = Color(0xFF4D96FF)
private val Pink = Color(0xFFFF8FAB)
private val Rainbow = listOf(Coral, Color(0xFFFF9F45), Lemon, Color(0xFF6BCB77), Sky, Color(0xFF9B7EDE))

@Composable
fun ColorUpScreen(
    state: ColoringUiState,
    onPageSelected: (String) -> Unit,
    onCrayonSelected: (Crayon) -> Unit,
    onToolSelected: (Tool) -> Unit,
    onWidthSelected: (Float) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onStrokeStart: (Point) -> Unit,
    onStrokeMove: (Point) -> Unit,
    onStrokeEnd: () -> Unit,
    onStrokeCancel: () -> Unit,
    onNavigate: (GameScreen) -> Unit = {},
    onExport: () -> Unit = {},
    onSoundToggle: () -> Unit = {},
    onFinish: () -> Unit = {},
    onCloseCelebration: () -> Unit = {},
    onNextPicture: () -> Unit = {},
    onPalettePageChange: () -> Unit = {},
) {
    var showParents by remember { mutableStateOf(false) }
    var galleryTheme by rememberSaveable { mutableStateOf("all") }
    Box(Modifier.fillMaxSize().testTag("screen_${state.screen.name.lowercase()}")) {
        if (state.screen == GameScreen.PAINT) PaintSceneBackground() else BrandBackground()
        val painting = state.screen == GameScreen.PAINT
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = if (painting) 12.dp else 24.dp, vertical = if (painting) 6.dp else 18.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = if (painting) 1600.dp else 1120.dp).fillMaxWidth().fillMaxHeight()) {
                when (state.screen) {
                    GameScreen.HOME -> HomeScreen(state, onPageSelected, { screen ->
                        if (screen == GameScreen.GALLERY) galleryTheme = "all"
                        onNavigate(screen)
                    }) { showParents = true }
                    GameScreen.GALLERY -> GalleryScreen(state, onPageSelected, onNavigate, galleryTheme, { galleryTheme = it }) { showParents = true }
                    GameScreen.PAINT -> PaintScreen(
                        state, onNavigate, onCrayonSelected, onToolSelected,
                        onUndo, onClear, onExport, onSoundToggle, onPalettePageChange,
                        onStrokeStart, onStrokeMove, onStrokeEnd, onStrokeCancel, { showParents = true },
                    )
                }
            }
        }
    }
    if (showParents) ParentsDialog { showParents = false }
    if (state.showCelebration) CelebrationDialog(state, onExport, onNextPicture, onCloseCelebration)
}

@Composable
private fun BrandBackground() {
    Canvas(Modifier.fillMaxSize().background(Cream)) {
        drawRect(Brush.radialGradient(listOf(Lemon.copy(alpha = .24f), Color.Transparent), center = Offset(size.width * .08f, size.height * .06f), radius = size.width * .45f))
        drawRect(Brush.radialGradient(listOf(Pink.copy(alpha = .19f), Color.Transparent), center = Offset(size.width * .96f, size.height * .12f), radius = size.width * .5f))
        drawRect(Brush.radialGradient(listOf(Sky.copy(alpha = .10f), Color.Transparent), center = Offset(size.width * .46f, size.height * 1.05f), radius = size.width * .55f))
        val step = 28.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            var y = step / 2
            while (y < size.height) {
                drawCircle(Ink.copy(alpha = .045f), .7.dp.toPx(), Offset(x, y))
                y += step
            }
            x += step
        }
    }
}

@Composable
private fun BrandHeader(onHome: () -> Unit, onParents: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onHome).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrayonDrawing(Coral, Modifier.size(22.dp, 44.dp))
            Spacer(Modifier.width(10.dp))
            Text("涂个彩", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.weight(1f))
        SoftChip("♡  家长小贴士", onParents, Modifier.testTag("parent_tips"))
    }
}

@Composable
private fun HomeScreen(state: ColoringUiState, onPageSelected: (String) -> Unit, onNavigate: (GameScreen) -> Unit, onParents: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        BrandHeader({ onNavigate(GameScreen.HOME) }, onParents)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    HeroCopy(state, onNavigate, Modifier.weight(1f))
                    MascotHero(Modifier.weight(1f).height(370.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroCopy(state, onNavigate, Modifier.fillMaxWidth())
                    MascotHero(Modifier.fillMaxWidth().height(280.dp))
                }
            }
        }
        DottedDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的画册", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text("完成了 ${state.pages.count { it.id in state.completedPages }} / ${state.pages.size} 张", Modifier.testTag("home_completion_count"), color = Muted, fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            TextButton({ onNavigate(GameScreen.GALLERY) }, Modifier.heightIn(min = 48.dp).testTag("view_all_artwork")) {
                Text("查看全部 ${state.pages.size} 张 →", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            state.pages.filter { it.featured }.ifEmpty { state.pages.take(4) }.take(4).forEach { page ->
                HomeArtworkCard(state, page, { onPageSelected(page.id) }, Modifier.testTag("home_page_${page.id}"))
            }
        }
        DottedDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("涂个彩 · Color Up!\n没有广告，不用账号，画作保存在本机。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            SoftChip("♡  给家长", onParents)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun HomeArtworkCard(state: ColoringUiState, page: LineArt, onSelect: () -> Unit, modifier: Modifier) {
    Row(
        modifier.width(242.dp).height(82.dp).clip(RoundedCornerShape(17.dp)).background(Color.White.copy(alpha = .75f))
            .border(2.dp, Ink.copy(alpha = .16f), RoundedCornerShape(17.dp)).clickable(onClick = onSelect).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColoringCanvas(page, state.drawings[page.id].orEmpty(), null, Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), interactive = false)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(page.title, color = Ink, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (page.id in state.completedPages) "★  我的作品" else "${pageProgress(state, page).completedRegions} / ${page.regions.size} 个区域涂满", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HeroCopy(state: ColoringUiState, onNavigate: (GameScreen) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("●  为 5–12 岁孩子设计的涂色游戏", Modifier.background(Color.White.copy(alpha = .75f), CircleShape).border(2.dp, Ink.copy(alpha = .12f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("涂个", color = Ink, fontSize = 78.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text("彩", style = TextStyle(brush = Brush.linearGradient(Rainbow), fontSize = 78.sp, fontWeight = FontWeight.Black))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp, 7.dp).background(Brush.horizontalGradient(Rainbow), CircleShape))
            Text("COLOR UP!", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        }
        Row {
            Text("一笔一笔，", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text("涂个彩。", color = Coral, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        Text("挑一张线稿，选一支蜡笔，按住画板慢慢涂。\n颜色只会留在这一笔开始的区域里，怎么涂都不出界。", color = Muted, fontSize = 14.sp, lineHeight = 25.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("▱  开始涂色", { onNavigate(GameScreen.GALLERY) }, background = Lemon, modifier = Modifier.testTag("start_coloring"))
            if (state.hasVisitedPage) {
                InkButton("继续上次 · ${state.page.title} →", { onNavigate(GameScreen.PAINT) }, modifier = Modifier.testTag("continue_coloring"))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Android 平板", "${state.pages.size} 张原创创意线稿", "${CrayonPalette.colors.size} 支蜡笔", "怎么涂都不出界").forEach {
                Text(it, Modifier.border(1.dp, Ink.copy(alpha = .18f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MascotHero(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = minOf(size.width, size.height) * .43f
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF2C5), Color(0xFFFFDF8A)), radius = radius), radius)
            drawCircle(Ink.copy(alpha = .10f), radius, style = DrawStroke(3.dp.toPx()))
            drawCircle(Ink.copy(alpha = .14f), radius * 1.15f, style = DrawStroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            listOf(Offset(.08f, .28f) to Sky, Offset(.88f, .80f) to Color(0xFF6BCB77), Offset(.15f, .88f) to Pink).forEach { (point, color) ->
                val center = Offset(point.x * size.width, point.y * size.height)
                drawCircle(color, 6.dp.toPx(), center)
                drawCircle(Ink, 6.dp.toPx(), center, style = DrawStroke(2.dp.toPx()))
            }
        }
        Bobo(Modifier.fillMaxHeight(.72f).aspectRatio(1f))
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp).background(Paper, RoundedCornerShape(16.dp)).border(3.dp, Ink, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("啵！", color = Coral, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
    }
}

@Composable
private fun GalleryScreen(
    state: ColoringUiState,
    onPageSelected: (String) -> Unit,
    onNavigate: (GameScreen) -> Unit,
    selectedTheme: String,
    onThemeSelected: (String) -> Unit,
    onParents: () -> Unit,
) {
    val themes = remember(state.pages) {
        listOf("all" to "全部") + state.pages.distinctBy { it.theme }.map { it.theme to it.themeLabel }
    }
    val visiblePages = remember(state.pages, selectedTheme) {
        state.pages.filter { selectedTheme == "all" || it.theme == selectedTheme }
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(selectedTheme) { gridState.scrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 900.dp) 4 else 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns), state = gridState,
            modifier = Modifier.fillMaxSize().testTag("gallery_grid").semantics { contentDescription = "${visiblePages.size} 张线稿" },
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "gallery_header", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    BrandHeader({ onNavigate(GameScreen.HOME) }, onParents)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        InkButton("‹", { onNavigate(GameScreen.HOME) }, Modifier.width(52.dp).testTag("gallery_back"))
                        Column {
                            Text("选一张来涂", color = Ink, fontWeight = FontWeight.Black, fontSize = 32.sp)
                            Text("${state.pages.size} 张原创创意线稿 · ${themes.size - 1} 个主题系列 · ${CrayonPalette.colors.size} 支蜡笔", color = Muted, fontSize = 13.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        themes.forEach { (theme, label) ->
                            val selected = selectedTheme == theme
                            val count = if (theme == "all") state.pages.size else state.pages.count { it.theme == theme }
                            Row(
                                Modifier.heightIn(min = 48.dp).clip(CircleShape)
                                    .background(if (selected) Lemon else Color.White.copy(alpha = .7f))
                                    .border(2.dp, if (selected) Ink else Ink.copy(alpha = .18f), CircleShape)
                                    .selectable(selected, onClick = { onThemeSelected(theme) }, role = Role.Tab)
                                    .testTag("theme_$theme").padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(label, color = if (selected) Ink else Muted, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                Text("$count", Modifier.background(Ink.copy(alpha = .08f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text("全部为原创创意线稿 · 适合 5–12 岁 · 没有广告", color = Muted, fontSize = 11.sp)
                }
            }
            items(visiblePages, key = { it.id }) { page ->
                GalleryCard(page, state.drawings[page.id].orEmpty(), pageProgress(state, page), page.id in state.completedPages, { onPageSelected(page.id) }, Modifier.fillMaxWidth())
            }
            item(key = "gallery_tip", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).border(2.dp, Ink.copy(alpha = .15f), RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = .45f), RoundedCornerShape(24.dp)).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Bobo(Modifier.size(70.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("笔尖走到哪里，颜色就留在那一块里。", color = Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text("按住画板慢慢涂，颜色不会串到别的区域；橡皮擦也能一点一点擦掉。画到一半也没关系，下次接着涂。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(page: LineArt, strokes: List<Stroke>, progress: PageProgress, completed: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    InkCard(modifier.testTag("page_${page.id}").clickable(onClick = onSelect), shadow = 7.dp) {
        Box {
            ColoringCanvas(page, strokes, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Ink.copy(alpha = .12f), RoundedCornerShape(16.dp)), interactive = false)
            if (completed) Text("★ 已经完成", Modifier.align(Alignment.TopEnd).padding(5.dp).background(Lemon, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text(page.title, color = Ink, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("${page.themeLabel} · " + if (completed) "已完成 · 还能再涂" else "${progress.completedRegions} / ${page.regions.size} 个区域涂满", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        CoverageProgress(progress.averageCoverage, Modifier.fillMaxWidth().height(7.dp))
    }
}

@Composable
private fun PaintSceneBackground() {
    Canvas(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF8F5FF), Color(0xFFE9E2FF))))) {
        drawRect(Brush.radialGradient(listOf(Color(0xFFFFEDBC).copy(alpha = .55f), Color.Transparent), center = Offset(size.width * .96f, 0f), radius = size.width * .55f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFCDBBFF).copy(alpha = .45f), Color.Transparent), center = Offset(size.width * .78f, size.height), radius = size.width * .6f))
        val step = 28.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            var y = step / 2
            while (y < size.height) {
                drawCircle(Color(0xFF9B84D8).copy(alpha = .07f), .7.dp.toPx(), Offset(x, y))
                y += step
            }
            x += step
        }
    }
}

@Composable
private fun PaintScreen(
    state: ColoringUiState,
    onNavigate: (GameScreen) -> Unit,
    onCrayonSelected: (Crayon) -> Unit,
    onToolSelected: (Tool) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onSoundToggle: () -> Unit,
    onPalettePageChange: () -> Unit,
    onStrokeStart: (Point) -> Unit,
    onStrokeMove: (Point) -> Unit,
    onStrokeEnd: () -> Unit,
    onStrokeCancel: () -> Unit,
    onParents: () -> Unit,
) {
    var showHint by rememberSaveable(state.page.id) { mutableStateOf(state.showBrushHint && state.strokes.isEmpty()) }
    LaunchedEffect(state.page.id) {
        delay(9000)
        showHint = false
    }
    LaunchedEffect(state.strokes.isNotEmpty(), state.activeStroke != null) {
        if (state.strokes.isNotEmpty() || state.activeStroke != null) showHint = false
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val narrow = maxWidth < 700.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (portrait || narrow) {
                Row(Modifier.fillMaxWidth().heightIn(min = if (narrow) 48.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (portrait) "把平板横过来，画板更大哦～" else "涂个彩 · Color Up!", Modifier.weight(1f), color = Color(0xFF8874BC), fontSize = 12.sp, textAlign = TextAlign.Center)
                    if (narrow) ToyIconButton(PaintIcon.PARENTS, "家长小贴士", Color(0xFF9F84E8), onParents, Modifier.size(48.dp).testTag("parent_tips"))
                }
                Spacer(Modifier.height(6.dp))
            }
            PaintToolbar(state, onNavigate, onToolSelected, onUndo, onClear, onExport, onSoundToggle, narrow)
            Spacer(Modifier.height(14.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val side = minOf(maxWidth, maxHeight)
                PaintPaper(
                    state,
                    { point -> showHint = false; onStrokeStart(point) },
                    onStrokeMove, onStrokeEnd, onStrokeCancel,
                    Modifier.size(side).align(Alignment.Center),
                )
                val margin = (maxWidth - side) / 2
                if (showHint && margin >= 180.dp) {
                    Row(
                        Modifier.width(180.dp).align(Alignment.CenterStart).padding(start = 10.dp)
                            .background(Color.White.copy(alpha = .96f), RoundedCornerShape(22.dp))
                            .padding(horizontal = 12.dp, vertical = 14.dp).testTag("brush_hint"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Bobo(Modifier.size(38.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("选一支蜡笔，\n按住画板慢慢涂～", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PaintCrayonBox(state, onCrayonSelected, onPalettePageChange, Modifier.height(168.dp))
        }
        if (!narrow) ToyIconButton(PaintIcon.PARENTS, "家长小贴士", Color(0xFFAE97E9), onParents, Modifier.size(48.dp).align(Alignment.TopEnd).testTag("parent_tips"))
    }
}

@Composable
private fun PaintToolbar(
    state: ColoringUiState,
    onNavigate: (GameScreen) -> Unit,
    onToolSelected: (Tool) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onSoundToggle: () -> Unit,
    narrow: Boolean,
) {
    val iconSize = if (narrow) 48.dp else 52.dp
    val progress = pageProgress(state, state.page)
    Row(
        Modifier.height(60.dp).testTag("paint_toolbar"),
        horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToyIconButton(PaintIcon.BACK, "返回画册", Color(0xFFA084ED), { onNavigate(GameScreen.GALLERY) }, Modifier.size(iconSize).testTag("paint_back"))
        ToyIconButton(PaintIcon.ERASER, "橡皮擦", Pink, { onToolSelected(if (state.tool == Tool.ERASER) Tool.CRAYON else Tool.ERASER) }, Modifier.size(iconSize).testTag("tool_eraser"), selected = state.tool == Tool.ERASER)
        ToyIconButton(PaintIcon.UNDO, "撤销一笔", Color(0xFF9C7AE9), onUndo, Modifier.size(iconSize).testTag("undo"), enabled = state.canUndo)
        ToyIconButton(PaintIcon.RESET, "重新画，可以撤销", Color(0xFF9771E6), onClear, Modifier.size(iconSize).testTag("clear"), enabled = state.strokes.isNotEmpty())
        Column(
            Modifier.width(if (narrow) 174.dp else 220.dp).height(60.dp).background(Color.White.copy(alpha = .95f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.page.title, color = Ink, fontSize = if (narrow) 16.sp else 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoverageProgress(progress.averageCoverage, Modifier.width(if (narrow) 50.dp else 80.dp).height(6.dp), Color(0xFFE9E1FF))
                Text("已涂满 ${progress.completedRegions} / ${state.page.regions.size} 个区域", color = Color(0xFF8874BC), fontSize = if (narrow) 9.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        ToyIconButton(PaintIcon.SAVE, "保存作品", Color(0xFF65CD6A), onExport, Modifier.size(iconSize).testTag("export"))
        ToyIconButton(PaintIcon.SOUND, if (state.soundEnabled) "关闭声音" else "打开声音", Color(0xFF60ACF8), onSoundToggle, Modifier.size(iconSize).testTag("sound_toggle"), muted = !state.soundEnabled)
    }
}

@Composable
private fun PaintPaper(state: ColoringUiState, onStart: (Point) -> Unit, onMove: (Point) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val sparkPhase = remember(state.page.id) { Animatable(1f) }
    var sparkPoint by remember(state.page.id) { mutableStateOf<Point?>(null) }
    var sparkColor by remember(state.page.id) { mutableStateOf(Color.Transparent) }
    var sparkGeneration by remember(state.page.id) { mutableIntStateOf(0) }
    LaunchedEffect(state.page.id, sparkGeneration) {
        if (sparkPoint != null) {
            sparkPhase.snapTo(0f)
            sparkPhase.animateTo(1f, tween(durationMillis = 550))
            sparkPoint = null
        }
    }
    Box(modifier.drawBehind {
        drawRoundRect(Color(0xFF9D83E0).copy(alpha = .25f), topLeft = Offset(0f, 10.dp.toPx()), cornerRadius = CornerRadius(23.dp.toPx()))
    }.clip(RoundedCornerShape(23.dp)).background(Color.White).border(4.dp, Color(0xFF9579EC), RoundedCornerShape(23.dp)).testTag("paint_board_frame")) {
        ColoringCanvas(
            state.page, state.strokes, state.activeStroke, Modifier.fillMaxSize().padding(4.dp),
            onStrokeStart = { point ->
                if (ValueAnimator.areAnimatorsEnabled()) {
                    sparkPoint = point
                    sparkColor = if (state.tool == Tool.ERASER) Color(0xFFB9B4C2) else Color(state.selectedColor.toInt())
                    sparkGeneration++
                } else {
                    sparkPoint = null
                }
                onStart(point)
            },
            onStrokeMove = onMove, onStrokeEnd = onEnd, onStrokeCancel = onCancel,
        )
        sparkPoint?.let { point ->
            // This feedback lives above the paper UI only; stroke replay and exported PNGs omit it.
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val phase = sparkPhase.value
                val side = minOf(size.width, size.height)
                val center = Offset(point.x / 1000f * side, point.y / 1000f * side)
                listOf(Offset(-16f, -11f), Offset(1f, -25f), Offset(19f, -8f)).forEach { direction ->
                    val travel = .2f + phase * .8f
                    val position = center + Offset(direction.x.dp.toPx() * travel, direction.y.dp.toPx() * travel)
                    drawCircle(sparkColor.copy(alpha = (1f - phase) * .8f), (3.5f - phase * 1.8f).dp.toPx(), position)
                }
            }
        }
        if (state.page.id in state.completedPages) {
            Text("★ 涂满啦", Modifier.align(Alignment.TopEnd).padding(13.dp).background(Lemon, CircleShape).border(3.dp, Color.White, CircleShape).padding(horizontal = 14.dp, vertical = 7.dp), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun PaintCrayonBox(state: ColoringUiState, onCrayonSelected: (Crayon) -> Unit, onPalettePageChange: () -> Unit, modifier: Modifier) {
    val currentCrayon = selectedCrayon(state)
    val selectedIndex = CrayonPalette.colors.indexOf(currentCrayon).coerceAtLeast(0)
    val pageCount = (CrayonPalette.colors.size + 5) / 6
    var palettePage by rememberSaveable { mutableIntStateOf(selectedIndex / 6) }
    Column(modifier.widthIn(max = 620.dp).fillMaxWidth().testTag("crayon_tray"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.height(26.dp).background(Color.White.copy(alpha = .96f), CircleShape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).background(if (state.tool == Tool.ERASER) Brush.linearGradient(listOf(Color.White, Color.White)) else crayonBrush(currentCrayon), CircleShape).border(2.dp, Color.White, CircleShape))
            Text(if (state.tool == Tool.ERASER) "橡皮擦" else (if (currentCrayon.gradientStops.isNotEmpty()) "✦ " else "") + currentCrayon.name, color = Color(0xFF64557F), fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ToyIconButton(PaintIcon.BACK, "上一组蜡笔", Color(0xFF9F7DEB), {
                palettePage = (palettePage + pageCount - 1) % pageCount
                onPalettePageChange()
            }, Modifier.size(52.dp).testTag("palette_previous"), round = true)
            Spacer(Modifier.width(14.dp))
            Row(
                Modifier.widthIn(max = 424.dp).weight(1f, fill = false).height(116.dp)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .38f), Color(0xFFE1D8F2).copy(alpha = .55f))), RoundedCornerShape(28.dp))
                    .border(2.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(28.dp)).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                CrayonPalette.colors.drop(palettePage * 6).take(6).forEachIndexed { index, crayon ->
                    ToyCrayonButton(
                        crayon, isCrayonSelected(state, crayon),
                        { onCrayonSelected(crayon) }, Modifier.weight(1f).height(112.dp).testTag("crayon_${palettePage * 6 + index}"),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            ToyIconButton(PaintIcon.NEXT, "下一组蜡笔", Color(0xFF9F7DEB), {
                palettePage = (palettePage + 1) % pageCount
                onPalettePageChange()
            }, Modifier.size(52.dp).testTag("palette_next"), round = true)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(pageCount) { index ->
                Box(Modifier.size(if (index == palettePage) 20.dp else 6.dp, 6.dp).background(if (index == palettePage) Color(0xFF785AC5) else Color(0xFFBAA8DD), CircleShape)
                    .testTag("page_dot_$index").semantics { selected = index == palettePage; contentDescription = "蜡笔颜色组" })
            }
        }
    }
}

@Composable
private fun ToyCrayonButton(crayon: Crayon, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.selectable(selected, onClick = onClick, role = Role.RadioButton).semantics { contentDescription = if (crayon.gradientStops.isEmpty()) "${crayon.name}蜡笔" else "炫彩蜡笔，${crayon.name}" }, contentAlignment = Alignment.BottomCenter) {
        if (selected) Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .95f), Color.Transparent), radius = size.height * .58f), size.height * .58f, Offset(size.width / 2f, size.height * .5f))
        }
        Canvas(Modifier.width(44.dp).height(100.dp).offset(y = if (selected) (-6).dp else 0.dp)) {
            val w = size.width
            val h = size.height
            val color = Color(crayon.argb.toInt())
            val path = Path().apply {
                moveTo(w * .5f, h * .02f)
                cubicTo(w * .39f, h * .05f, w * .08f, h * .28f, w * .07f, h * .37f)
                lineTo(w * .07f, h * .86f)
                cubicTo(w * .07f, h * 1.01f, w * .93f, h * 1.01f, w * .93f, h * .86f)
                lineTo(w * .93f, h * .37f)
                cubicTo(w * .92f, h * .28f, w * .61f, h * .05f, w * .5f, h * .02f)
                close()
            }
            drawPath(path, if (crayon.gradientStops.isEmpty()) Brush.horizontalGradient(listOf(mixWithWhite(color, .27f), color, darken(color, .08f))) else crayonBrush(crayon))
            drawPath(path, Color.White, style = DrawStroke(3.dp.toPx(), join = StrokeJoin.Round))
            drawRect(Color.White.copy(alpha = .88f), Offset(w * .08f, h * .42f), Size(w * .84f, h * .12f))
            drawLine(Color.White.copy(alpha = .58f), Offset(w * .25f, h * .59f), Offset(w * .25f, h * .89f), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White.copy(alpha = .56f), Offset(w * .25f, h * .27f), Offset(w * .38f, h * .12f), 4.dp.toPx(), StrokeCap.Round)
            drawArc(Color.White.copy(alpha = .38f), 30f, 85f, false, topLeft = Offset(w * .16f, h * .81f), size = Size(w * .7f, h * .12f), style = DrawStroke(3.dp.toPx(), cap = StrokeCap.Round))
            if (crayon.gradientStops.isNotEmpty()) clipPath(path) {
                listOf(Offset(.30f, .61f), Offset(.64f, .73f), Offset(.43f, .84f), Offset(.71f, .59f), Offset(.25f, .77f)).forEachIndexed { index, position ->
                    val center = Offset(position.x * w, position.y * h)
                    val r = (if (index % 2 == 0) 2.6f else 2f).dp.toPx()
                    val sparkle = Path().apply {
                        moveTo(center.x, center.y - r)
                        lineTo(center.x + r * .30f, center.y - r * .28f)
                        lineTo(center.x + r, center.y)
                        lineTo(center.x + r * .30f, center.y + r * .28f)
                        lineTo(center.x, center.y + r)
                        lineTo(center.x - r * .30f, center.y + r * .28f)
                        lineTo(center.x - r, center.y)
                        lineTo(center.x - r * .30f, center.y - r * .28f)
                        close()
                    }
                    drawPath(sparkle, if (index % 3 == 1) Color(0xFFFFF6C9) else Color.White)
                }
                drawCircle(Color.White.copy(alpha = .9f), 1.dp.toPx(), Offset(w * .51f, h * .67f))
                drawCircle(Color(0xFFFFF6C9).copy(alpha = .9f), .8.dp.toPx(), Offset(w * .57f, h * .90f))
            }
        }
        if (selected) {
            Box(Modifier.size(25.dp).align(Alignment.TopCenter).background(Color.White, CircleShape).border(3.dp, Color(0xFF7959C8), CircleShape), contentAlignment = Alignment.Center) {
                Text("✓", color = Color(0xFF7959C8), fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
    }
}

private enum class PaintIcon { BACK, NEXT, ERASER, UNDO, RESET, SAVE, SOUND, PARENTS }

@Composable
private fun ToyIconButton(
    icon: PaintIcon,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    muted: Boolean = false,
    round: Boolean = false,
) {
    val radius = if (round) 50.dp else 17.dp
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.alpha(if (enabled) 1f else .3f).drawBehind {
            if (selected) drawRoundRect(Lemon, topLeft = Offset((-3).dp.toPx(), (-3).dp.toPx()), size = Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), cornerRadius = CornerRadius(radius.toPx()))
            drawRoundRect(darken(color, .19f), topLeft = Offset(0f, 5.dp.toPx()), cornerRadius = CornerRadius(radius.toPx()))
        }.background(Brush.verticalGradient(listOf(mixWithWhite(color, .32f), color)), shape)
            .border(4.dp, Color.White, shape).clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .32f), Color.Transparent)), topLeft = Offset(9.dp.toPx(), 7.dp.toPx()), size = Size(size.width - 18.dp.toPx(), size.height * .38f), cornerRadius = CornerRadius(13.dp.toPx()))
        }
        Canvas(Modifier.size(26.dp)) { drawToyIcon(icon, muted) }
    }
}

private fun DrawScope.drawToyIcon(icon: PaintIcon, muted: Boolean) {
    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
        fun path(points: List<Offset>) = Path().apply { points.firstOrNull()?.let { moveTo(it.x, it.y) }; points.drop(1).forEach { lineTo(it.x, it.y) } }
        fun line(path: Path) = drawPath(path, Color.White, style = DrawStroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        when (icon) {
            PaintIcon.BACK -> line(path(listOf(Offset(15f, 4f), Offset(7f, 12f), Offset(15f, 20f))))
            PaintIcon.NEXT -> line(path(listOf(Offset(9f, 4f), Offset(17f, 12f), Offset(9f, 20f))))
            PaintIcon.ERASER -> {
                line(path(listOf(Offset(4f, 14f), Offset(12f, 6f), Offset(20f, 14f), Offset(14f, 20f), Offset(10f, 20f), Offset(4f, 14f))))
                line(path(listOf(Offset(8f, 10f), Offset(16f, 18f))))
                line(path(listOf(Offset(11f, 21f), Offset(22f, 21f))))
            }
            PaintIcon.UNDO -> {
                line(Path().apply { moveTo(6f, 8f); cubicTo(18f, 1f, 25f, 18f, 13f, 21f); cubicTo(8f, 22f, 5f, 19f, 5f, 18f) })
                line(path(listOf(Offset(6f, 3f), Offset(5f, 9f), Offset(11f, 9f))))
            }
            PaintIcon.RESET -> {
                line(path(listOf(Offset(6f, 8f), Offset(8f, 21f), Offset(18f, 21f), Offset(20f, 8f))))
                line(path(listOf(Offset(4f, 7f), Offset(22f, 7f))))
                line(path(listOf(Offset(9f, 6f), Offset(10f, 3f), Offset(16f, 3f), Offset(17f, 6f))))
                line(path(listOf(Offset(11f, 11f), Offset(11f, 17f))))
                line(path(listOf(Offset(15f, 11f), Offset(15f, 17f))))
            }
            PaintIcon.SAVE -> line(path(listOf(Offset(4f, 12f), Offset(10f, 18f), Offset(21f, 6f))))
            PaintIcon.SOUND -> {
                val speaker = path(listOf(Offset(3f, 9f), Offset(7f, 9f), Offset(12f, 5f), Offset(12f, 19f), Offset(7f, 15f), Offset(3f, 15f))).apply { close() }
                drawPath(speaker, Color.White)
                if (muted) {
                    line(path(listOf(Offset(17f, 9f), Offset(22f, 15f))))
                    line(path(listOf(Offset(22f, 9f), Offset(17f, 15f))))
                } else {
                    drawArc(Color.White, -50f, 100f, false, Offset(11f, 5f), Size(11f, 14f), style = DrawStroke(2.3f, cap = StrokeCap.Round))
                    drawArc(Color.White, -45f, 90f, false, Offset(12f, 8f), Size(5f, 8f), style = DrawStroke(2f, cap = StrokeCap.Round))
                }
            }
            PaintIcon.PARENTS -> line(Path().apply { moveTo(12f, 21f); cubicTo(-3f, 11f, 5f, 1f, 12f, 8f); cubicTo(19f, 1f, 27f, 11f, 12f, 21f) })
        }
    }
}

private fun mixWithWhite(color: Color, amount: Float) = Color(color.red + (1f - color.red) * amount, color.green + (1f - color.green) * amount, color.blue + (1f - color.blue) * amount, color.alpha)
private fun darken(color: Color, amount: Float) = Color(color.red * (1f - amount), color.green * (1f - amount), color.blue * (1f - amount), color.alpha)

@Composable
private fun CrayonDrawing(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .5f, h * .02f); lineTo(w * .84f, h * .27f)
            cubicTo(w * .91f, h * .29f, w * .91f, h * .32f, w * .91f, h * .39f)
            lineTo(w * .91f, h * .89f)
            cubicTo(w * .91f, h * .97f, w * .81f, h * .98f, w * .5f, h * .98f)
            cubicTo(w * .19f, h * .98f, w * .09f, h * .97f, w * .09f, h * .89f)
            lineTo(w * .09f, h * .39f)
            cubicTo(w * .09f, h * .32f, w * .09f, h * .29f, w * .16f, h * .27f)
            close()
        }
        drawPath(path, color)
        drawPath(path, Ink, style = DrawStroke(width = 3.dp.toPx()))
        drawLine(Ink, Offset(w * .16f, h * .27f), Offset(w * .84f, h * .27f), strokeWidth = 2.dp.toPx())
        drawRect(Cream, Offset(w * .1f, h * .52f), Size(w * .8f, h * .20f))
        drawLine(Ink, Offset(w * .09f, h * .52f), Offset(w * .91f, h * .52f), strokeWidth = 2.dp.toPx())
        drawLine(Ink, Offset(w * .09f, h * .72f), Offset(w * .91f, h * .72f), strokeWidth = 2.dp.toPx())
        drawLine(Color.White.copy(alpha = .6f), Offset(w * .24f, h * .37f), Offset(w * .24f, h * .46f), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun CelebrationDialog(state: ColoringUiState, onExport: () -> Unit, onAnother: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Box(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(top = 44.dp, bottom = 12.dp)) {
            InkCard(Modifier.padding(top = 32.dp), padding = 26.dp, shadow = 10.dp) {
                Spacer(Modifier.height(46.dp))
                Text("啵啵！整张都涂满啦", Modifier.fillMaxWidth(), color = Coral, fontWeight = FontWeight.Black, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("哇，涂满啦！", Modifier.fillMaxWidth(), color = Ink, fontWeight = FontWeight.Black, fontSize = 30.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("你涂满了《${state.page.title}》的 ${state.page.regions.size} 个区域，\n一共用了 ${state.celebrationColorCount} 种颜色。", Modifier.fillMaxWidth(), color = Muted, fontSize = 13.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().semantics { contentDescription = "${state.completionStars} 颗星" }, horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally)) {
                    repeat(3) { index -> Text("★", color = if (index < state.completionStars) Lemon else Color(0xFFE6DCC9), fontSize = 38.sp) }
                }
                Spacer(Modifier.height(20.dp))
                InkButton("↓  保存我的作品", onExport, Modifier.fillMaxWidth().testTag("celebration_export"), background = Lemon)
                Spacer(Modifier.height(15.dp))
                InkButton("再涂一张  →", onAnother, Modifier.fillMaxWidth().testTag("celebration_another"))
                Spacer(Modifier.height(16.dp))
                Text("也可以先休息一下，再来接着玩。", Modifier.fillMaxWidth(), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            Bobo(Modifier.size(120.dp).align(Alignment.TopCenter).offset(y = (-24).dp))
            InkButton("×", onClose, Modifier.size(48.dp).align(Alignment.TopEnd).testTag("celebration_close"))
        }
    }
}

@Composable
private fun ParentsDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("给家长的小贴士", color = Ink, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("✓  没有广告、不用账号，孩子打开就能画。", color = Muted, lineHeight = 23.sp)
                Text("✓  画作只保存在这台设备，随时可以接着涂。", color = Muted, lineHeight = 23.sp)
                Text("✓  喜欢什么颜色都可以；颜色只会留在起笔的那一块里。", color = Muted, lineHeight = 23.sp)
                Text("✓  玩一会儿，也别忘了看看远处、活动小手。", color = Muted, lineHeight = 23.sp)
            }
        },
        confirmButton = { TextButton(onClose) { Text("知道啦", color = Ink, fontWeight = FontWeight.Bold) } },
        containerColor = Paper,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun Bobo(modifier: Modifier) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        runCatching { context.assets.open("brand/mascot.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, "啵啵，一滴拿着蜡笔的小颜料", modifier, contentScale = ContentScale.Fit)
    else Box(modifier, contentAlignment = Alignment.Center) { Text("✦", color = Coral, fontSize = 45.sp) }
}

@Composable
private fun InkCard(modifier: Modifier = Modifier, padding: Dp = 12.dp, shadow: Dp = 6.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.drawBehind {
        drawRoundRect(Ink.copy(alpha = .15f), topLeft = Offset(0f, shadow.toPx()), cornerRadius = CornerRadius(26.dp.toPx()))
    }.background(Paper, shape).border(3.dp, Ink, shape).clip(shape).padding(padding), content = content)
}

@Composable
private fun InkButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, background: Color = Paper, enabled: Boolean = true) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else .35f).drawBehind {
        drawRoundRect(Ink, topLeft = Offset(0f, 4.dp.toPx()), cornerRadius = CornerRadius(18.dp.toPx()))
    }.background(background, shape).border(3.dp, Ink, shape).clip(shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 15.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Ink, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun SoftChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(label, modifier.heightIn(min = 48.dp).clip(CircleShape).background(Color.White.copy(alpha = .7f))
        .border(2.dp, Ink.copy(alpha = .18f), CircleShape).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 14.dp), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun CoverageProgress(progress: Float, modifier: Modifier, background: Color = Color(0xFFEFE4D3)) {
    Box(modifier.clip(CircleShape).background(background)) {
        if (progress > 0f) Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Brush.horizontalGradient(Rainbow), CircleShape))
    }
}

@Composable
private fun DottedDivider() {
    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
        drawLine(Ink.copy(alpha = .15f), Offset.Zero, Offset(size.width, 0f), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
    }
}

private fun pageProgress(state: ColoringUiState, page: LineArt): PageProgress =
    state.progressByPage[page.id] ?: PageProgress.from(page, emptyMap(), if (page.id == state.page.id) state.strokes else state.drawings[page.id].orEmpty())

private fun selectedCrayon(state: ColoringUiState): Crayon = CrayonPalette.colors.firstOrNull { crayon ->
    if (state.selectedCrayonId != null) crayon.id == state.selectedCrayonId
    else crayon.gradientStops.isEmpty() && crayon.argb == state.selectedColor
} ?: CrayonPalette.colors.first()

private fun isCrayonSelected(state: ColoringUiState, crayon: Crayon): Boolean = state.tool == Tool.CRAYON &&
    if (state.selectedCrayonId != null) state.selectedCrayonId == crayon.id
    else crayon.gradientStops.isEmpty() && state.selectedColor == crayon.argb

private fun crayonBrush(crayon: Crayon): Brush = if (crayon.gradientStops.size >= 2)
    Brush.linearGradient(crayon.gradientStops.map { Color(it.toInt()) })
else Brush.linearGradient(listOf(Color(crayon.argb.toInt()), Color(crayon.argb.toInt())))
