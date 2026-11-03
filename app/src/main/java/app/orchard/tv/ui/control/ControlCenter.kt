package app.orchard.tv.ui.control

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.orchard.tv.ui.components.smoothBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.CcPage
import app.orchard.tv.BuildConfig
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.adb.AdbDramaUpgrade
import app.orchard.tv.adb.AdbLocalConnect
import app.orchard.tv.adb.AdbOperationResult
import app.orchard.tv.adb.AdbPrivilege
import app.orchard.tv.adb.AdbService
import app.orchard.tv.adb.AerialFile
import app.orchard.tv.adb.AerialSource
import app.orchard.tv.adb.AerialUpgrade
import app.orchard.tv.adb.ApkAsset
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.BackupStore
import androidx.core.content.ContextCompat
import app.orchard.tv.system.HomeStatus
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.SourceKind
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.home.VpnBadge
import app.orchard.tv.ui.home.dateText
import app.orchard.tv.ui.home.timeText
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.update.ReleaseNotes
import app.orchard.tv.update.SelfCompile
import app.orchard.tv.update.Updates
import app.orchard.tv.wallpaper.PhoneServer
import java.time.LocalDateTime

/**
 * tvOS Control Center: a glass panel that drops in from the top-right corner over a blurred Home.
 * Inputs open as a second page inside the same panel, the way tvOS shows audio routes.
 */
@Composable
fun ControlCenter(
    visible: Boolean,
    page: CcPage,
    vm: HomeViewModel,
    now: LocalDateTime,
    onOpen: (android.content.Intent) -> Unit,
    onClose: () -> Unit,
    /** Draw the closed panel once, nearly transparent, so that its first opening is not its first draw (HomeScreen). */
    warm: Boolean = false,
    /** Home is on its top row: no tiles under the panel, only the wallpaper (see liquidGlass). */
    onShelf: Boolean = false,
) {
    BackHandler(enabled = visible) {
        when (page) {
            CcPage.Main -> onClose()
            CcPage.Inputs -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
            CcPage.InputSettings -> vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
            CcPage.Update, CcPage.Setup, CcPage.Backup, CcPage.Adb -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
        }
    }

    LaunchedEffect(visible) { if (visible) Updates.checkIfStale(maxAgeMs = Updates.CONTROL_CENTER_MAX_AGE_MS) }
    // Whether Aerial is the default home, read again each time the panel opens. Recreated after a
    // standby kill, Aerial kept the "not the default home screen" it read while the firmware was
    // still waking, and the activity's polling (MainActivity) never ran: Xiaomi MiTV-AZFP0,
    // Android 9, whose log showed onCreate but no onStart for that instance (issue #892).
    LaunchedEffect(visible) { if (visible) vm.refreshPanel() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)),
        exit = fadeOut(tween(180, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // A tap outside the panel closes Control Center.
        Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.28f)))
    }

    Box(Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp), contentAlignment = Alignment.TopEnd) {
        // The first opening composed the panel and then, frame after frame, gave the GPU its
        // pictures, glyphs and shaders for the first time: 55-95 ms a frame for the whole drop-in,
        // against about 26 ms on the second opening (Xiaomi MiTV-MFTR0, release build). Drawn once
        // at 1% while Home is idle, without focus, the first opening is like the second.
        if (warm && !visible) {
            Column(Modifier.graphicsLayer { alpha = 0.01f }.width(300.dp).liquidGlass(24.dp).padding(16.dp)) {
                MainPage(vm, now, false, onOpen)
            }
        }
        AnimatedVisibility(
            visible = visible,
            // Grows out of the corner where the status pill lives; leaves faster than it came.
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(280, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(300.dp)
                    .consumeTaps()
                    .liquidGlass(24.dp, seeThroughFilm = onShelf)
                    .padding(16.dp),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        // Fade only, and snap the panel size. Animating the panel's height re-measures
                        // both pages (and their scrollables) every frame, and sliding the new page on
                        // top of the cross-fade makes its first frame land late; on low-end TV SoCs
                        // that is felt as a stutter when opening and leaving pages.
                        (fadeIn(tween(130, easing = Motion.EaseOut)) togetherWith fadeOut(tween(110, easing = Motion.EaseOut)))
                            .using(SizeTransform { _, _ -> tween(0) })
                    },
                    label = "cc-page",
                ) { p ->
                    // Closing sets the page back to Main while the panel fades out; that page must
                    // not take focus from the tile Home has just put it back on.
                    when (p) {
                        CcPage.Main -> MainPage(vm, now, visible, onOpen)
                        CcPage.Inputs -> InputsPage(vm, visible, onOpen)
                        CcPage.InputSettings -> InputSettingsPage(vm, visible)
                        CcPage.Update -> UpdatePage(vm, visible)
                        CcPage.Setup -> SetupPage(vm, visible, onOpen)
                        CcPage.Backup -> BackupPage(vm, visible)
                        CcPage.Adb -> AdbPage(vm, visible)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: HomeViewModel, now: LocalDateTime, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val networkFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    // The buttons share one row above a panel-wide slider, so focus searching up from it has three
    // equally near choices and takes the middle one, Network, whichever button Down came from
    // (75A7D). Remember that button and send Up back to it.
    var cameFrom by remember { mutableStateOf(first) }
    // Firmwares that list their inputs late (see HomeViewModel.refreshSources): look again while
    // the page is open and still empty. One provider or TvInputManager query every 3 s.
    LaunchedEffect(Unit) {
        while (vm.sources.isEmpty()) {
            vm.refreshSources()
            delay(3000)
        }
    }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText(timeText(now), style = Type.SmallClock.copy(fontSize = Type.LargeTitle.fontSize * 1.05f))
                BasicText(dateText(now), style = Type.Callout)
            }
            val net = vm.network
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphIcon(
                    when (net.kind) { NetKind.Wifi -> Glyph.Wifi; NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline },
                    Palette.SecondaryLabel, 14.dp, level = net.level,
                )
                BasicText(
                    when (net.kind) {
                        NetKind.Wifi -> net.name ?: stringResource(R.string.net_wifi)
                        NetKind.Ethernet -> stringResource(R.string.net_ethernet)
                        NetKind.Offline -> stringResource(R.string.net_offline)
                    },
                    style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
                if (net.vpn) VpnBadge(Palette.SecondaryLabel, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(
                Glyph.Input, stringResource(R.string.cc_inputs),
                focusRequester = first,
                modifier = Modifier.onFocusChanged { if (it.isFocused) cameFrom = first },
            ) {
                // TCL lists no inputs to Android; its own input panel is the next best thing.
                vm.overlay = if (vm.sources.isEmpty() && SystemProbe.openInputPicker(context)) Overlay.None
                else Overlay.ControlCenter(CcPage.Inputs)
            }
            RoundButton(
                when (vm.network.kind) { NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline; else -> Glyph.Wifi },
                stringResource(R.string.cc_network),
                focusRequester = networkFocus,
                modifier = Modifier.onFocusChanged { if (it.isFocused) cameFrom = networkFocus },
            ) {
                vm.whenUnlocked {
                    val pages = SystemProbe.networkSettingsIntents(context, ethernet = vm.network.kind == NetKind.Ethernet)
                    LaunchFallback.arm(pages.drop(1))
                    onOpen(pages.first())
                }
            }
            RoundButton(
                Glyph.Gear, stringResource(R.string.cc_settings),
                focusRequester = settingsFocus,
                modifier = Modifier.onFocusChanged { if (it.isFocused) cameFrom = settingsFocus },
            ) {
                vm.whenUnlocked {
                    LaunchFallback.arm(SystemProbe.settingsFallbacks(context))
                    onOpen(SystemProbe.settingsIntent(context))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // 音量调节行已按用户要求隐藏（需求十九）：VolumeRow 定义保留，仅不再渲染。
        // 亮度调节行已按用户要求隐藏（需求十八）：BrightnessRow 定义保留，仅不再渲染。
        // How Home looks (wallpaper, screensaver, focus style, hidden apps, weather) lives in one place.
        MenuRow(
            Glyph.Photo,
            stringResource(R.string.cc_personalize),
            onClick = { vm.overlay = Overlay.Wallpaper },
        )
        // ADB management: local wireless debugging and the privilege pipeline.
        MenuRow(
            Glyph.Usb,
            stringResource(R.string.cc_adb),
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Adb) },
        )
        // The owner's setup in one file, saved to a shared folder that survives uninstalling Aerial.
        MenuRow(
            Glyph.Download,
            stringResource(R.string.cc_backup),
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Backup) },
        )
        // What the TV opens when switched on: easy to forget, and puzzling when it happens.
        vm.bootTargetLabel()?.let { name ->
            MenuRow(
                Glyph.Power,
                stringResource(R.string.cc_boot),
                onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) },
                trailing = "$name ›",
            )
        }

        // Android 9 to 11, Aerial running uncompiled: reinstalling itself has the system compile it,
        // which needs the owner's confirmation there (SelfCompile; 12+ does it by itself).
        LaunchedEffect(Unit) { SelfCompile.recheck(context) }
        if (SelfCompile.offered) {
            MenuRow(Glyph.Download, stringResource(R.string.cc_self_compile), onClick = { SelfCompile.run(context, auto = false) })
        }

        // Installed from the APK alone: how to finish, on the TV or with a computer.
        if (vm.homeStatus == HomeStatus.NotDefault) {
            MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_title), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Setup) }, trailing = "›")
        }

        Spacer(Modifier.height(8.dp))
        val (dot, text) = when (vm.homeStatus) {
            HomeStatus.Default -> Palette.Green to stringResource(R.string.cc_home_default)
            HomeStatus.Guarded -> Palette.Green to stringResource(R.string.cc_home_guarded)
            HomeStatus.NotDefault -> Palette.Orange to stringResource(R.string.cc_home_not_default)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            BasicText(text, style = Type.Caption, modifier = Modifier.weight(1f))
            BasicText("Aerial ${Updates.current}", style = Type.Caption.copy(color = Palette.TertiaryLabel), modifier = Modifier.padding(end = 14.dp))
        }
    }
}

/**
 * How to move to the new version: "Download and Install" fetches the APK and opens the system
 * installer (Updates.install). For TVs whose firmware lets no app install another (see
 * Updates.Failure), and for copies older than the button, a QR code opens the website's update
 * page on a phone, with the install command for this TV to copy: typing it off the screen was
 * too much. The TV's address rides in the fragment, which the browser does not send.
 */
@Composable
private fun UpdatePage(vm: HomeViewModel, takeFocus: Boolean) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { Updates.resetInstall() } }
    val canInstall = remember { Updates.canInstall(context) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val ip = PhoneServer.lanAddress(context)
        val url = "https://aerial.qust.me/update/#v=${Updates.current}&f=${BuildConfig.FLAVOR}" + (ip?.let { "&tv=$it" } ?: "")
        qr = withContext(Dispatchers.Default) { PhoneServer.qr(url).asImageBitmap() }
    }
    Column {
        MenuRow(
            Glyph.ChevronLeft, stringResource(R.string.cc_update_title, Updates.available ?: Updates.current),
            focusRequester = if (canInstall) null else first,
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) },
        )
        if (canInstall) {
            val state = Updates.install
            MenuRow(
                Glyph.Download,
                when (state) {
                    is Updates.Install.Downloading -> stringResource(R.string.cc_update_downloading, (state.fraction * 100).toInt())
                    else -> stringResource(R.string.cc_update_install)
                },
                focusRequester = first,
                onClick = { Updates.install(context) },
            )
        }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(10.dp))
            val status = when (val state = Updates.install) {
                Updates.Install.Opened -> stringResource(R.string.cc_update_opened)
                Updates.Install.NeedsPermission -> stringResource(R.string.cc_update_permission)
                Updates.Install.NeedsStoragePermission -> stringResource(R.string.cc_update_storage_permission)
                is Updates.Install.Failed -> stringResource(
                    when (state.reason) {
                        Updates.Failure.Download -> R.string.cc_update_failed_download
                        Updates.Failure.Blocked -> R.string.cc_update_failed_blocked
                        Updates.Failure.NotOfficial -> R.string.cc_update_failed_signature
                        Updates.Failure.NotNewer -> R.string.cc_update_failed_older
                    },
                )
                else -> stringResource(R.string.cc_update_current, Updates.current)
            }
            BasicText(status, style = Type.Caption)
        }
        // Read when the page opens too: the check that found the version may have missed them.
        LaunchedEffect(Unit) { if (Updates.notes.isEmpty()) Updates.loadNotes() }
        Updates.notes.takeIf { it.isNotEmpty() }?.let { notes ->
            Spacer(Modifier.height(10.dp))
            UpdateNotes(notes)
        }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(104.dp).smoothBackground(Color.White, 12.dp).padding(8.dp)) {
                    qr?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(stringResource(if (canInstall) R.string.cc_update_scan_else else R.string.cc_update_scan), style = Type.Body)
                    Spacer(Modifier.height(6.dp))
                    BasicText(stringResource(R.string.cc_update_keeps), style = Type.Caption)
                }
            }
        }
    }
}

/**
 * What the update brings, release by release. The lines are not controls: focused (Down from the
 * install button), Up and Down scroll it a few lines at a time, and past either end focus moves on
 * as from any row. The panel has room for about ten lines under the install button and above the
 * QR code; a bar on the right shows how much more there is.
 */
@Composable
private fun UpdateNotes(notes: List<ReleaseNotes>) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val step = with(LocalDensity.current) { 64.dp.toPx() }
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(140, easing = Motion.EaseOut), label = "notes-focus")
    val scrollable = scroll.maxValue > 0
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = glow }.smoothBackground(Palette.Fill, 12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 164.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    val down = when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> true
                        NativeKeyEvent.KEYCODE_DPAD_UP -> false
                        else -> return@onKeyEvent false
                    }
                    if (if (down) scroll.value >= scroll.maxValue else scroll.value <= 0) return@onKeyEvent false
                    if (e.type == KeyEventType.KeyDown) {
                        Sounds.step()
                        scope.launch { scroll.animateScrollBy(if (down) step else -step, spring(dampingRatio = 1f, stiffness = 500f)) }
                    }
                    true
                }
                .focusProperties { canFocus = scrollable }
                .focusable()
                .drawWithContent {
                    drawContent()
                    if (scroll.maxValue <= 0) return@drawWithContent
                    // The scroll bar, inside the right padding.
                    val track = size.height - 16.dp.toPx()
                    val thumb = (track * size.height / (size.height + scroll.maxValue)).coerceAtLeast(12.dp.toPx())
                    val top = 8.dp.toPx() + (track - thumb) * scroll.value / scroll.maxValue
                    drawRoundRect(
                        if (focused) Palette.SecondaryLabel else Palette.TertiaryLabel,
                        topLeft = Offset(size.width - 6.dp.toPx(), top),
                        size = Size(2.5.dp.toPx(), thumb),
                        cornerRadius = CornerRadius(1.25.dp.toPx()),
                    )
                }
                .verticalScroll(scroll)
                .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            notes.forEachIndexed { i, release ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                BasicText(stringResource(R.string.cc_update_notes, release.version), style = Type.Callout.copy(color = Palette.Label))
                release.changes.forEach { line ->
                    Row(Modifier.padding(top = 4.dp)) {
                        BasicText("•", style = Type.Caption, modifier = Modifier.width(10.dp))
                        BasicText(line, style = Type.Caption, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * For a TV where Aerial was installed from the APK alone (a USB stick, an app store): it opens, but
 * Home, power-on and wake still go to the stock launcher (issue #213). The settings pages that can
 * fix that on the TV itself, where the TV has them, and a QR code to the website's page with this
 * TV's install command, which does all of it.
 */
@Composable
private fun SetupPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val guard = remember { SystemProbe.guardSettingsIntent(context) }
    val home = remember { SystemProbe.homeSettingsIntent(context) }
    val revival = remember { SystemProbe.revivalSettingsIntent(context) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val ip = PhoneServer.lanAddress(context)
        val url = "https://aerial.qust.me/update/#setup=1&v=${Updates.current}&f=${BuildConfig.FLAVOR}" + (ip?.let { "&tv=$it" } ?: "")
        qr = withContext(Dispatchers.Default) { PhoneServer.qr(url).asImageBitmap() }
    }
    Column {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_setup_title), focusRequester = first, onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        BasicText(stringResource(R.string.cc_setup_body), style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        guard?.let { MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_guard), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_guard_where) + " ›") }
        home?.let { MenuRow(Glyph.Check, stringResource(R.string.cc_setup_home), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = "›") }
        revival?.let { MenuRow(Glyph.Moon, stringResource(R.string.cc_setup_revival), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_revival_where) + " ›") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
            Box(Modifier.size(104.dp).smoothBackground(Color.White, 12.dp).padding(8.dp)) {
                qr?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(stringResource(R.string.cc_setup_scan), style = Type.Body)
                Spacer(Modifier.height(6.dp))
                BasicText(stringResource(R.string.cc_setup_scan_hint), style = Type.Caption)
            }
        }
    }
}

/**
 * The owner's settings in one file on shared storage: export (backup) and restore, rows of the
 * files found in the fixed folder. See BackupStore for the storage rules (uninstall-safe).
 */
@Composable
private fun BackupPage(vm: HomeViewModel, takeFocus: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val msgExported = stringResource(R.string.cc_backup_exported)
    val msgExportFailed = stringResource(R.string.cc_backup_export_failed)
    val msgRestored = stringResource(R.string.cc_backup_restored)
    val msgRestoreFailed = stringResource(R.string.cc_backup_restore_failed)
    var backups by remember { mutableStateOf<List<BackupStore.Entry>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        backups = withContext(Dispatchers.IO) { BackupStore.list(context) }
    }
    // Android 5-9 write the shared folder through WRITE_EXTERNAL_STORAGE; ask for it once when a
    // backup or restore needs it. Android 11+ writes through the filesystem and needs All Files
    // Access (MANAGE_EXTERNAL_STORAGE), which only the user can grant in the system settings.
    val needAllFiles = Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()
    val needLegacyPermission = Build.VERSION.SDK_INT in 23..28 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    fun openAllFilesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        runCatching { allFiles.launch(intent) }.onFailure {
            runCatching { allFiles.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
    fun ensurePerm(f: () -> Unit) {
        when {
            needAllFiles -> openAllFilesSettings()
            needLegacyPermission -> permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> f()
        }
    }
    fun doExport() {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            val name = withContext(Dispatchers.IO) { BackupStore.export(context).getOrNull()?.name }
            busy = false
            if (name != null) {
                status = msgExported.replace("%1\$s", name)
                refresh++
            } else status = msgExportFailed
        }
    }
    fun doRestore(entry: BackupStore.Entry) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { BackupStore.restore(context, entry).isSuccess }
            busy = false
            status = if (ok) msgRestored.replace("%1\$s", entry.name) else msgRestoreFailed
        }
    }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_backup), focusRequester = first, onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        Spacer(Modifier.height(8.dp))
        if (needAllFiles) {
            MenuRow(Glyph.Shield, stringResource(R.string.cc_backup_perm), onClick = { openAllFilesSettings() })
            Spacer(Modifier.height(8.dp))
        }
        MenuRow(Glyph.Download, stringResource(R.string.cc_backup_export), onClick = { ensurePerm { doExport() } })
        status?.let { BasicText(it, style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }

        Spacer(Modifier.height(10.dp))
        BasicText(stringResource(R.string.cc_backup_restore_title), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        if (backups.isEmpty()) {
            BasicText(stringResource(R.string.cc_backup_none), style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp))
        } else {
            backups.forEach { entry ->
                MenuRow(
                    Glyph.Upload, entry.name,
                    onClick = { ensurePerm { doRestore(entry) } },
                    trailing = stringResource(R.string.cc_backup_restore) + " ›",
                )
            }
        }
        BasicText(stringResource(R.string.cc_backup_hint), style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** Which view the ADB page is showing. */
private enum class AdbPhase { Idle, List, AerialSource, Working, Done, Failed }

/** Which pipeline the ADB page is running or listing for. */
private enum class AdbMode { Privilege, Drama, AerialNas, AerialGithub, AerialGithubCustom }

/**
 * ADB management as a Control Center page, in the same pattern as Backup & Restore: the two
 * actions are rows, the upgrade's APK list appears as more rows, and the running log replaces the
 * dialog. Direct connect is classic mode: the TV shows the "Allow USB debugging?" dialog once, the
 * user taps Allow, then the backend pipeline runs over the session.
 */
@Composable
private fun AdbPage(vm: HomeViewModel, takeFocus: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    // The backend needs its context once; init is idempotent.
    AdbService.init(context)
    val local = remember(context) { AdbLocalConnect(context) }

    // Strings are resolved in composition and captured, because the pipeline appends them from a
    // plain coroutine where @Composable calls are not allowed.
    val sConnecting = stringResource(R.string.adb_connecting_progress)
    val sAuthorize = stringResource(R.string.adb_authorize_hint)
    val sPrivileging = stringResource(R.string.adb_privileging_progress)
    val sDone = stringResource(R.string.adb_privilege_done)
    val sFetching = stringResource(R.string.adb_upgrade_fetching)
    val sNoAssets = stringResource(R.string.adb_upgrade_no_assets)
    val sInstalling = stringResource(R.string.adb_upgrade_installing)
    val sUpgradeDone = stringResource(R.string.adb_upgrade_done)
    val sArm64 = stringResource(R.string.adb_upgrade_arm64)
    val sArmV7 = stringResource(R.string.adb_upgrade_armv7)
    val sHgUpgrade = stringResource(R.string.adb_hgtv_upgrade)
    val sAerialUpgrade = stringResource(R.string.adb_aerial_upgrade)
    val sNas = stringResource(R.string.adb_aerial_source_nas)
    val sGithub = stringResource(R.string.adb_aerial_source_github)
    val sGithubCustom = stringResource(R.string.adb_aerial_source_github_custom)
    val sAerialFetching = stringResource(R.string.adb_aerial_fetching)
    val sAerialNoAssets = stringResource(R.string.adb_aerial_no_assets)
    val sAerialDone = stringResource(R.string.adb_aerial_done)
    val sPushing = stringResource(R.string.adb_aerial_pushing)
    val sUninstalling = stringResource(R.string.adb_aerial_uninstalling)
    fun sConnectedFmt(endpoint: String) = context.getString(R.string.adb_connected_format, endpoint)

    var phase by remember { mutableStateOf(AdbPhase.Idle) }
    var mode by remember { mutableStateOf(AdbMode.Privilege) }
    var running by remember { mutableStateOf(false) }
    var assets by remember { mutableStateOf<List<ApkAsset>>(emptyList()) }
    var aerialFiles by remember { mutableStateOf<List<AerialFile>>(emptyList()) }
    // One persistent status line under the title: what the connection to this TV is doing.
    var connLine by remember { mutableStateOf<String?>(null) }
    // The official GitHub build cannot overwrite the custom one (signature mismatch); ask first.
    var confirmGithub by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    // Progress callbacks run on background threads; every log line hops to the main thread so the
    // order of lines is preserved no matter where the caller is running.
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun append(line: String) { mainHandler.post { log += line } }

    // Cleared through the same queue so leftover progress lines posted by a previous run are
    // flushed first, then dropped, then the new run's lines appear.
    fun clearLog() { mainHandler.post { log.clear() } }

    // One connection attempt is shared: page entry starts it, and actions await it instead of
    // opening a second connect (a second connect would re-show the "Allow USB debugging?" dialog
    // on the TV). Null when no attempt is in flight.
    var connectJob by remember { mutableStateOf<Job?>(null) }

    /**
     * A GitHub upgrade installs itself in the background (setsid, P4): report its pm install
     * output on the next visit to this page, then remove the result file. Nothing happens when
     * there is no session or no leftover result.
     */
    fun reportPendingGithubInstall() {
        if (AdbService.manager.currentEndpoint() == null) return
        scope.launch {
            val read = AdbService.manager.shell("cat /data/local/tmp/aerial-upgrade.result 2>/dev/null")
            val output = (read as? AdbOperationResult.Success)?.data?.output?.trim().orEmpty()
            if (output.isBlank()) return@launch
            append("last GitHub install: $output")
            AdbService.manager.shell("rm -f /data/local/tmp/aerial-upgrade.result")
        }
    }

    /** Starts the shared connect if none is in flight; reports on [onLog] and [connLine]. */
    fun connectOnce(onLog: (String) -> Unit = {}): Job {
        connectJob?.takeIf { it.isActive }?.let { return it }
        onLog(sConnecting)
        onLog(sAuthorize)
        val job = scope.launch {
            when (val connected = local.connectLocal()) {
                is AdbOperationResult.Failure -> {
                    connLine = failureLine(connected)
                    onLog(failureLine(connected))
                }
                is AdbOperationResult.Success -> {
                    connLine = sConnectedFmt(connected.data)
                    reportPendingGithubInstall()
                }
            }
        }
        connectJob = job
        return job
    }

    /**
     * True when a live session is already up. Connecting once happens on page entry; the actions
     * reuse it and only fall back to a fresh connect (which re-shows the allow dialog on the TV).
     */
    suspend fun ensureConnected(onLog: (String) -> Unit): Boolean {
        AdbService.manager.currentEndpoint()?.let { endpoint ->
            connLine = sConnectedFmt(endpoint)
            return true
        }
        // Page entry may already be connecting; wait for that attempt rather than opening a second
        // one (the TV's allow dialog must not appear twice in a row).
        connectJob?.let { job ->
            job.join()
            AdbService.manager.currentEndpoint()?.let { endpoint ->
                connLine = sConnectedFmt(endpoint)
                return true
            }
        }
        connectOnce(onLog).join()
        return AdbService.manager.currentEndpoint() != null
    }

    // The connect happens as soon as the page opens; the TV may show the "Allow USB debugging?"
    // dialog and the user taps Allow. If it fails the actions still retry on demand.
    LaunchedEffect(Unit) {
        val endpoint = AdbService.manager.currentEndpoint()
        if (endpoint != null) {
            connLine = sConnectedFmt(endpoint)
            reportPendingGithubInstall()
        } else {
            connectOnce()
        }
    }

    fun startPrivilege() {
        if (running) return
        running = true
        mode = AdbMode.Privilege
        clearLog()
        phase = AdbPhase.Working
        scope.launch {
            if (!ensureConnected(::append)) {
                phase = AdbPhase.Failed
                running = false
                return@launch
            }
            append(sPrivileging)
            when (val report = AdbPrivilege(AdbService.manager).run(keepAdbEnabled = true)) {
                is AdbOperationResult.Failure -> {
                    append(failureLine(report))
                    phase = AdbPhase.Failed
                    running = false
                }
                is AdbOperationResult.Success -> {
                    append(sDone)
                    append(AdbService.serializePrivilegeReport(report.data, keepAdb = true))
                    phase = AdbPhase.Done
                    running = false
                }
            }
        }
    }

    /** Connects to this TV, downloads [asset] and installs it over the ADB session. */
    fun runUpgrade(asset: ApkAsset) {
        if (running) return
        running = true
        mode = AdbMode.Drama
        clearLog()
        phase = AdbPhase.Working
        scope.launch {
            if (!ensureConnected(::append)) {
                phase = AdbPhase.Failed
                running = false
                return@launch
            }
            append(context.getString(R.string.adb_upgrade_downloading, asset.name))
            var lastDl = -1
            val downloaded = AdbDramaUpgrade.downloadApk(
                context.applicationContext,
                asset,
            ) { done, total ->
                val pct = if (total != null && total > 0L) (done * 100 / total).toInt() else (done / (256 * 1024)).toInt()
                if (pct != lastDl && (pct % 10 == 0 || pct >= 100)) {
                    lastDl = pct
                    append(context.getString(R.string.adb_upgrade_download_pct, pct))
                }
            }
            val file = when (downloaded) {
                is AdbOperationResult.Failure -> {
                    append(failureLine(downloaded))
                    phase = AdbPhase.Failed
                    running = false
                    return@launch
                }
                is AdbOperationResult.Success -> downloaded.data
            }
            append(sInstalling)
            var lastIn = -1
            val installed = AdbService.manager.install(file) { done, total ->
                val pct = if (total > 0L) (done * 100 / total).toInt() else -1
                if (pct >= 0 && pct != lastIn && (pct == 0 || pct % 10 == 0 || pct >= 100)) {
                    lastIn = pct
                    append(context.getString(R.string.adb_upgrade_install_pct, pct))
                }
            }
            when (installed) {
                is AdbOperationResult.Failure -> {
                    append(failureLine(installed))
                    phase = AdbPhase.Failed
                    running = false
                }
                is AdbOperationResult.Success -> {
                    append(sUpgradeDone)
                    phase = AdbPhase.Done
                    running = false
                }
            }
        }
    }

    /**
     * Fetches the latest release and installs the APK matching this TV's ABI automatically:
     * 64-bit devices get the arm64-v8a APK, 32-bit devices get the armeabi-v7a APK.
     */
    fun startUpgrade() {
        if (running) return
        running = true
        mode = AdbMode.Drama
        clearLog()
        phase = AdbPhase.Working
        scope.launch {
            append(sFetching)
            when (val fetched = AdbDramaUpgrade.fetchLatestAssets()) {
                is AdbOperationResult.Failure -> {
                    append(failureLine(fetched))
                    phase = AdbPhase.Failed
                    running = false
                }
                is AdbOperationResult.Success -> {
                    if (fetched.data.isEmpty()) {
                        append(sNoAssets)
                        phase = AdbPhase.Failed
                        running = false
                    } else {
                        // No manual file picker: select the APK by this TV's ABI.
                        val wantArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
                        val abiMarker = if (wantArm64) "arm64-v8a" else "armeabi-v7a"
                        val asset = fetched.data.firstOrNull {
                            if (wantArm64) it.isArm64 else it.isArmV7
                        }
                        if (asset == null) {
                            append(context.getString(R.string.adb_upgrade_no_abi_match, abiMarker))
                            phase = AdbPhase.Failed
                            running = false
                        } else {
                            // runUpgrade guards on running; release the lock so it can proceed.
                            running = false
                            runUpgrade(asset)
                        }
                    }
                }
            }
        }
    }

    /** Fetches the latest HGTV release: one APK installs directly, several show as rows. */
    fun startHgUpgrade() {
        if (running) return
        running = true
        mode = AdbMode.Drama
        clearLog()
        phase = AdbPhase.Working
        scope.launch {
            append(sFetching)
            when (val fetched = AdbDramaUpgrade.fetchHgAssets()) {
                is AdbOperationResult.Failure -> {
                    append(failureLine(fetched))
                    phase = AdbPhase.Failed
                    running = false
                }
                is AdbOperationResult.Success -> {
                    when {
                        fetched.data.isEmpty() -> {
                            append(sNoAssets)
                            phase = AdbPhase.Failed
                            running = false
                        }
                        fetched.data.size == 1 -> {
                            // A single APK: install it right away (runUpgrade guards on running).
                            running = false
                            runUpgrade(fetched.data.first())
                        }
                        else -> {
                            assets = fetched.data
                            // Picking a file is not "running": runUpgrade below must be allowed.
                            running = false
                            phase = AdbPhase.List
                        }
                    }
                }
            }
        }
    }

    /** Shows the two Aerial sources (NAS custom build, GitHub official). */
    fun startAerial() {
        if (running) return
        running = true
        mode = AdbMode.AerialNas
        clearLog()
        phase = AdbPhase.AerialSource
        // Choosing a source is not "running": fetchAerial below must be allowed.
        running = false
    }

    /** Fetches an Aerial APK list from [source]; on success the page shows it as rows. */
    fun fetchAerial(source: AerialSource) {
        if (running) return
        running = true
        mode =
            when (source) {
                AerialSource.Github -> AdbMode.AerialGithub
                AerialSource.GithubCustom -> AdbMode.AerialGithubCustom
                AerialSource.Nas -> AdbMode.AerialNas
            }
        clearLog()
        phase = AdbPhase.Working
        scope.launch {
            append(sAerialFetching)
            when (val fetched = AerialUpgrade.fetch(source)) {
                is AdbOperationResult.Failure -> {
                    append(failureLine(fetched))
                    phase = AdbPhase.Failed
                    running = false
                }
                is AdbOperationResult.Success -> {
                    if (fetched.data.isEmpty()) {
                        append(sAerialNoAssets)
                        phase = AdbPhase.Failed
                        running = false
                    } else {
                        aerialFiles = fetched.data
                        running = false
                        phase = AdbPhase.List
                    }
                }
            }
        }
    }

    /** Connects, downloads [file] and installs it over the ADB session. */
    fun runAerial(file: AerialFile) {
        if (running) return
        running = true
        clearLog()
        phase = AdbPhase.Working
        scope.launch {
            if (!ensureConnected(::append)) {
                phase = AdbPhase.Failed
                running = false
                return@launch
            }
            append(context.getString(R.string.adb_upgrade_downloading, file.name))
            var lastDl = -1
            val downloaded = AerialUpgrade.download(
                context.applicationContext,
                file,
            ) { done, total ->
                val pct = if (total != null && total > 0L) (done * 100 / total).toInt() else (done / (256 * 1024)).toInt()
                if (pct != lastDl && (pct % 10 == 0 || pct >= 100)) {
                    lastDl = pct
                    append(context.getString(R.string.adb_upgrade_download_pct, pct))
                }
            }
            val apk = when (downloaded) {
                is AdbOperationResult.Failure -> {
                    append(failureLine(downloaded))
                    phase = AdbPhase.Failed
                    running = false
                    return@launch
                }
                is AdbOperationResult.Success -> downloaded.data
            }
            if (mode == AdbMode.AerialGithub || mode == AdbMode.AerialGithubCustom) {
                // A GitHub-signed build cannot overwrite the locally-built custom one (different
                // signature), so the custom build must go first. Uninstalling this very package kills our process and
                // deletes our files dir, therefore: push the APK to /data/local/tmp (survives the
                // uninstall), then run uninstall + install as ONE shell command — adbd keeps
                // executing the install after our process is gone.
                append(sPushing)
                val remote = "/data/local/tmp/aerial-upgrade.apk"
                when (val pushed = AdbService.manager.push(apk, remote)) {
                    is AdbOperationResult.Failure -> {
                        append(failureLine(pushed))
                        phase = AdbPhase.Failed
                        running = false
                        return@launch
                    }
                    is AdbOperationResult.Success -> Unit
                }
                append(sUninstalling)
                // A plain "uninstall && install" chain dies with the adb session: uninstalling this
                // very package kills our process, the socket drops, and adbd terminates the shell
                // before pm install runs (observed on TV 35: uninstall succeeded, install never ran).
                // Instead, schedule the install FIRST as a detached setsid job that polls until the
                // package data dir is gone (uninstall finished), then runs pm install on its own;
                // the uninstall itself runs right after in this session. The detached install writes
                // its own output to /data/local/tmp/aerial-upgrade.result so the next time the ADB
                // page opens it can report what actually happened (P4).
                val scheduleThenUninstall =
                    "(setsid sh -c 'i=0; while [ \$i -lt 30 ] && [ -d /data/data/app.orchard.tv ]; do sleep 1; i=\$((i+1)); done; pm install -r $remote >/data/local/tmp/aerial-upgrade.result 2>&1' >/dev/null 2>&1 &) ; pm uninstall app.orchard.tv"
                val chain = AdbService.manager.shell(scheduleThenUninstall)
                // By now this process is usually gone; keep whatever the session reported.
                when (chain) {
                    is AdbOperationResult.Failure -> append(failureLine(chain))
                    is AdbOperationResult.Success -> append(chain.data.output.ifBlank { sAerialDone })
                }
                append("GitHub install is running in background; its result is shown when the ADB page reopens.")
                phase = AdbPhase.Done
                running = false
            } else {
                append(sInstalling)
                var lastIn = -1
                val installed = AdbService.manager.install(apk) { done, total ->
                    val pct = if (total > 0L) (done * 100 / total).toInt() else -1
                    if (pct >= 0 && pct != lastIn && (pct == 0 || pct % 10 == 0 || pct >= 100)) {
                        lastIn = pct
                        append(context.getString(R.string.adb_upgrade_install_pct, pct))
                    }
                }
                when (installed) {
                    is AdbOperationResult.Failure -> {
                        append(failureLine(installed))
                        phase = AdbPhase.Failed
                        running = false
                    }
                    is AdbOperationResult.Success -> {
                        append(sAerialDone)
                        phase = AdbPhase.Done
                        running = false
                    }
                }
            }
        }
    }

    Box {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.adb_panel_title), focusRequester = first, onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        Spacer(Modifier.height(8.dp))

        // The connection status started on page entry stays visible under the title.
        connLine?.let { line ->
            BasicText(
                line,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Spacer(Modifier.height(6.dp))
        }

        when (phase) {
            AdbPhase.AerialSource -> {
                MenuRow(Glyph.Folder, sNas, onClick = { fetchAerial(AerialSource.Nas) })
                Spacer(Modifier.height(8.dp))
                MenuRow(Glyph.Globe, sGithub, onClick = { confirmGithub = true })
                Spacer(Modifier.height(8.dp))
                MenuRow(Glyph.Globe, sGithubCustom, onClick = { fetchAerial(AerialSource.GithubCustom) })
                Spacer(Modifier.height(8.dp))
                MenuRow(Glyph.ChevronLeft, stringResource(R.string.adb_back), onClick = { phase = AdbPhase.Idle })
            }
            AdbPhase.List -> {
                if (mode == AdbMode.Drama) {
                    assets.forEach { asset ->
                        val label = when {
                            asset.isArm64 -> sArm64
                            asset.isArmV7 -> sArmV7
                            else -> asset.name
                        }
                        MenuRow(
                            Glyph.Download,
                            label,
                            onClick = { runUpgrade(asset) },
                        )
                    }
                } else {
                    aerialFiles.forEach { file ->
                        MenuRow(
                            Glyph.Download,
                            file.name,
                            onClick = { runAerial(file) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                MenuRow(Glyph.ChevronLeft, stringResource(R.string.adb_back), onClick = { phase = AdbPhase.Idle })
            }
            else -> {
                MenuRow(Glyph.Info, sAerialUpgrade, onClick = { startAerial() })
                Spacer(Modifier.height(8.dp))
                MenuRow(Glyph.Shield, stringResource(R.string.adb_privilege_boost), onClick = { startPrivilege() })
                Spacer(Modifier.height(8.dp))
                MenuRow(Glyph.Download, stringResource(R.string.adb_drama_upgrade), onClick = { startUpgrade() })
                Spacer(Modifier.height(8.dp))
                MenuRow(Glyph.Download, sHgUpgrade, onClick = { startHgUpgrade() })
            }
        }

        if (log.isNotEmpty() && phase != AdbPhase.List && phase != AdbPhase.AerialSource) {
            Spacer(Modifier.height(10.dp))
            val titleRes = when {
                phase == AdbPhase.Done ->
                    when (mode) {
                        AdbMode.Privilege -> R.string.adb_privilege_done
                        AdbMode.Drama -> R.string.adb_upgrade_done
                        AdbMode.AerialNas, AdbMode.AerialGithub, AdbMode.AerialGithubCustom -> R.string.adb_aerial_done
                    }
                phase == AdbPhase.Failed -> R.string.adb_failed
                else ->
                    when (mode) {
                        AdbMode.Privilege -> R.string.adb_privileging_progress
                        AdbMode.Drama -> R.string.adb_upgrade_progress
                        AdbMode.AerialNas, AdbMode.AerialGithub, AdbMode.AerialGithubCustom -> R.string.adb_aerial_fetching
                    }
            }
            BasicText(
                stringResource(titleRes),
                style = Type.Callout.copy(color = if (phase == AdbPhase.Failed) Palette.Red else Palette.Label),
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
            )
            log.forEach { line ->
                BasicText(line, style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
            }
        }
        }

        if (confirmGithub) {
            GithubConfirmSheet(
                onConfirm = {
                    confirmGithub = false
                    fetchAerial(AerialSource.Github)
                },
                onDismiss = { confirmGithub = false },
            )
        }
    }
}

/** A confirmation layer over the ADB page: the official GitHub build cannot overwrite the custom one. */
@Composable
private fun GithubConfirmSheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val okFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(20.dp)).liquidGlass(28.dp).padding(24.dp),
        ) {
            BasicText(
                stringResource(R.string.adb_aerial_confirm_body),
                style = Type.Callout.copy(color = Palette.Label),
                modifier = Modifier.padding(bottom = 18.dp),
            )
            ConfirmAction(stringResource(R.string.adb_aerial_confirm_ok), okFocus, onConfirm)
            Spacer(Modifier.height(8.dp))
            ConfirmAction(stringResource(R.string.adb_aerial_confirm_cancel), null, onDismiss)
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { okFocus.requestFocus() }
    }
}

/** A full-width text action inside the confirm sheet. */
@Composable
private fun ConfirmAction(
    text: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "confirm-bg")
        Box(
            Modifier.fillMaxWidth().height(44.dp).smoothBackground(bg, 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text,
                style = Type.Callout.copy(color = if (state.focused) Palette.OnFocus else Palette.Label),
            )
        }
    }
}

/** The error text of a failed backend call, one log line. */
private fun failureLine(failure: AdbOperationResult.Failure): String =
    listOf(failure.message, failure.suggestion, failure.cause?.message)
        .filter { !it.isNullOrBlank() }
        .joinToString(" | ")
        .take(300)

/** A round Control Center button: frosted at rest, white when focused. */
@Composable
private fun RoundButton(
    glyph: Glyph,
    label: String,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, modifier = modifier, focusRequester = focusRequester, focusedScale = 1.1f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "round-bg")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, if (state.focused) Palette.OnFocus else Palette.Label, 20.dp)
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * A Control Center slider: focus it, then Left and Right change the level, OK does [onClick].
 * Shared by volume and brightness so both feel identical. On a touch screen a tap does [onClick]
 * and a sideways drag steps the level, [steps] steps across the width of the row.
 */
@Composable
private fun SliderRow(
    glyph: Glyph,
    level: Float,
    value: String,
    steps: Int,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Each step ticks, as focus does; a step past either end bumps instead. Read through state:
    // the drag handler below is made once and outlives this composition's values.
    val atLevel by rememberUpdatedState(level)
    val doStep by rememberUpdatedState(onStep)
    val step = { by: Int ->
        if ((by > 0 && atLevel >= 0.999f) || (by < 0 && atLevel <= 0.001f)) Sounds.limit() else Sounds.step()
        doStep(by)
    }
    val stepCount by rememberUpdatedState(steps.coerceAtLeast(1))
    Pressable(
        onClick = onClick,
        focusedScale = 1.03f,
        modifier = modifier.fillMaxWidth(),
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
            if (e.type == KeyEventType.KeyDown) step(if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            true
        },
    ) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "slider-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val fraction by animateFloatAsState(level, spring(dampingRatio = 1f, stiffness = 700f), label = "slider-level")
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // The level only moves in the steps the system takes, so the drag is counted
                    // out in steps rather than set to an exact point.
                    var travelled = 0f
                    detectHorizontalDragGestures(onDragStart = { travelled = 0f }) { change, dx ->
                        change.consume()
                        travelled += dx
                        val pitch = size.width.toFloat() / stepCount
                        while (travelled >= pitch) { step(1); travelled -= pitch }
                        while (travelled <= -pitch) { step(-1); travelled += pitch }
                    }
                }
                .smoothBackground(bg, 14.dp).height(40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(glyph, fg, 18.dp, level = level)
            Canvas(Modifier.weight(1f).height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(fg.copy(alpha = 0.2f), cornerRadius = r)
                drawRoundRect(fg, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
            BasicText(
                value,
                style = Type.Headline.copy(color = fg, textAlign = TextAlign.End),
                modifier = Modifier.widthIn(min = 28.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(vm: HomeViewModel, returnTo: FocusRequester) {
    val volume = vm.volume
    SliderRow(
        glyph = if (volume.muted) Glyph.SpeakerMuted else Glyph.Speaker,
        level = if (volume.muted) 0f else volume.fraction,
        value = if (volume.muted) stringResource(R.string.cc_muted) else volume.level.toString(),
        steps = volume.max,
        onStep = vm::adjustVolume,
        modifier = Modifier.focusProperties { up = returnTo },
        onClick = vm::toggleMute,
    )
}

/**
 * Brightness where Aerial may change it; otherwise a row that opens the TV's picture settings, and
 * says so: a bare "Brightness" with no slider read as broken (Vidda 85V1F-S, FFalcon, issue #130).
 */
@Composable
private fun BrightnessRow(vm: HomeViewModel) {
    val context = LocalContext.current
    // XGIMI (GMUI): the real brightness is the DLP light-engine level. It reads through the HAL
    // class (SystemProbe.xgimiBrightnessLevel) and, on this firmware, writes through it too, so the
    // row is a slider of the light engine's own levels (1..10) rather than a percentage of the
    // system setting that moves nothing. Poll the HAL while the row is on screen: the level can be
    // changed from the TV's own overlay, which does not pause Aerial, so no onResume or
    // screen_brightness observer ever fires for it.
    val xgimi = remember { SystemProbe.xgimiBrightnessLevel() != null }
    LaunchedEffect(xgimi) {
        if (!xgimi) return@LaunchedEffect
        vm.refreshBrightness()
        while (isActive) {
            delay(700)
            vm.refreshBrightness()
        }
    }
    val level = vm.brightness
    if (level == null) {
        MenuRow(Glyph.Sun, stringResource(R.string.cc_brightness), onClick = {
            vm.overlay = Overlay.None
            SystemProbe.openPictureSettings(context)
        }, trailing = stringResource(R.string.cc_brightness_in_settings) + " ›")
    } else {
        SliderRow(
            glyph = Glyph.Sun,
            level = level,
            value = Math.round(level * 100).toString(),
            // Brightness.adjust moves 5% a step, and stops at 1%; XGIMI steps its own levels.
            steps = if (xgimi) SystemProbe.xgimiBrightnessMax else 20,
            onStep = vm::adjustBrightness,
            onClick = {},
        )
    }
}

@Composable
private fun InputsPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        Spacer(Modifier.height(8.dp))
        if (vm.sources.isEmpty()) {
            BasicText(stringResource(R.string.cc_no_inputs), style = Type.Callout, modifier = Modifier.padding(14.dp))
        }
        val connected = stringResource(R.string.input_connected)
        vm.sources.forEachIndexed { i, source ->
            MenuRow(
                glyph = when (source.kind) {
                    SourceKind.Hdmi, SourceKind.DisplayPort -> Glyph.Hdmi
                    SourceKind.Tuner -> Glyph.Antenna
                    SourceKind.Av -> Glyph.Av
                    SourceKind.Usb -> Glyph.Usb
                    SourceKind.Other -> Glyph.Input
                },
                text = source.label,
                // What the owner named the input on its tile, else what HDMI-CEC reports.
                trailing = SourceLooks.of(source.id)?.let { it.name ?: it.device?.let { d -> stringResource(d.label) } }
                    ?: source.deviceName ?: if (source.connected) connected else null,
                focusRequester = if (i == 0) first else null,
                onClick = { onOpen(source.intent) },
            )
        }
        // Switching is this page's one job; what Aerial does with the inputs is a page further in.
        if (vm.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MenuRow(Glyph.Gear, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) })
        }
    }
}

/**
 * What Aerial does with the inputs: which ones are tiles on Home (any number) and which one the
 * TV starts on (one, or none). Kept apart from the list that switches inputs.
 */
@Composable
private fun InputSettingsPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    fun name(source: app.orchard.tv.system.TvSource) = source.deviceName?.let { "${source.label} · $it" } ?: source.label
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Inputs) })

        Spacer(Modifier.height(10.dp))
        BasicText(stringResource(R.string.cc_inputs_pin), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        vm.sources.forEachIndexed { i, source ->
            val pinned = source.id in AerialSettings.pinnedSources
            ChoiceRow(
                text = name(source),
                selected = pinned,
                focusRequester = if (i == 0) first else null,
                onClick = { vm.setSourcePinned(source, !pinned) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_pin_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        BasicText(stringResource(R.string.cc_inputs_boot), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        ChoiceRow(
            text = stringResource(R.string.cc_inputs_boot_none),
            selected = !AerialSettings.hasBootTarget,
            focusRequester = null,
            onClick = { vm.chooseBootTarget() },
        )
        // An app is chosen from its tile's menu; here it only shows, and "nothing" clears it.
        AerialSettings.bootApp?.let { pkg ->
            ChoiceRow(
                text = vm.entry(pkg)?.label ?: pkg,
                selected = true,
                focusRequester = null,
                onClick = {},
            )
        }
        vm.sources.forEach { source ->
            ChoiceRow(
                text = name(source),
                selected = source.id == AerialSettings.bootSource,
                focusRequester = null,
                onClick = { vm.chooseBootTarget(sourceId = source.id) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_boot_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )
    }
}

/** A row in a list of choices: the chosen one carries a check, as in tvOS settings. */
@Composable
private fun ChoiceRow(text: String, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = Modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "choice-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.fillMaxWidth().smoothBackground(bg, 12.dp).height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
            if (selected) GlyphIcon(Glyph.Check, fg, 16.dp)
        }
    }
}
