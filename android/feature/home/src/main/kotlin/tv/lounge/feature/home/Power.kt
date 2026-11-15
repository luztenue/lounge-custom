package tv.lounge.feature.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.lounge.design.LoungeIcons
import tv.lounge.design.LoungeTheme
import tv.lounge.power.CecDevice
import tv.lounge.power.PowerAction
import tv.lounge.power.PowerSetup
import tv.lounge.power.PowerStatus
import tv.lounge.power.PrivilegedException

/** How one action went, under its row; `output` is what its command printed. */
private sealed interface RunState {
    data object Running : RunState
    data class Done(val output: String) : RunState
    data class Failed(val reason: String, val output: String = "") : RunState
    /** It ran without an error and the TV still says otherwise; [next] is what to do about it. */
    data class DidntTake(val next: String, val output: String = "") : RunState
}

/** What staying on means, said before anyone turns USB debugging on for Power Setup. */
internal const val DEBUGGING_STAYS_ON = "USB debugging stays on afterwards. Other devices on your home network can then ask to connect, " +
    "and the TV asks you before any can. TV controls and the phone's keys over other apps need it, and turning it off stops them. Updates still come, though the TV may ask you to confirm one."

/** How to turn USB debugging on, step by step, for Power Setup and the setup steps. */
@Composable
internal fun DebuggingHowTo() {
    val colors = LoungeTheme.colors
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        Text("Para activar la depuración ADB:", style = LoungeTheme.typography.caption, color = colors.limelight)
        Text("1. En Ajustes del TV, entra en Sistema > Acerca de y pulsa OK 7 veces en Compilación.", style = LoungeTheme.typography.caption, color = colors.dust)
        Text("2. Vuelve a Sistema, entra en Opciones de desarrollador y activa Depuración USB.", style = LoungeTheme.typography.caption, color = colors.dust)
        Text("3. Regresa aquí y elige Conectar.", style = LoungeTheme.typography.caption, color = colors.dust)
    }
}

/** One glyph for each thing a Power Setup action sets up. */
private fun glyphFor(action: PowerAction): ImageVector = when {
    action.id.startsWith("home") -> SettingsGlyphs.Home
    action.id == "tv_listings" -> LoungeIcons.Tv
    action.id == "notifications" -> LoungeIcons.Equaliser
    action.id == "usage" -> SettingsGlyphs.Clock
    action.id.startsWith("animations") -> LoungeIcons.Move
    action.id == "optimise" -> SettingsGlyphs.Gauge
    else -> LoungeIcons.Power
}

/**
 * Power Setup: connect the launcher to the TV's own USB debugging or to Shizuku, and run the
 * handful of shell actions an app cannot do on its own. Nothing runs unasked, and each action
 * shows what is already so and how it last went.
 */
@Composable
fun PowerSetupSheet(power: PowerSetup, onHomeRoleChanged: () -> Unit, say: (String) -> Unit, firstFocus: FocusRequester) {
    val colors = LoungeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by power.status.collectAsStateWithLifecycle()
    val results by power.results.collectAsStateWithLifecycle()
    // Power Setup runs the actions and keeps how each went, so both outlive this sheet.
    val running by power.running.collectAsStateWithLifecycle()
    val applied by power.applied.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    // Forgetting the key makes a new one, so the fingerprint is read again on every status change.
    val fingerprint by produceState<String?>(null, status) { value = power.fingerprint() }
    // What is already so on the TV is read again on return to Lounge; an action reads it as it finishes.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    LaunchedEffect(checks) { power.recheck() }
    val homeActions by produceState(emptyList<PowerAction>(), checks, running) {
        value = withContext(Dispatchers.IO) { power.takeHome().filter { it.id.startsWith("home_off_") } + power.giveBackHome() }
    }
    val isHome = applied[power.homeRole.id]
    LaunchedEffect(isHome) { onHomeRoleChanged() }
    // The undo for faster animations stays on offer once it has been shown, so focus never loses its row.
    var offerUndo by remember { mutableStateOf(false) }
    if (applied[power.normalAnimations.id] == false) offerUndo = true

    WhileStarted {
        power.refresh()
        while (true) {
            delay(if (power.status.value is PowerStatus.Ready) 15_000 else 6_000)
            if (!pairing) power.refresh()
        }
    }

    fun stateOf(action: PowerAction): RunState? {
        val last = results[action.id]
        return when {
            action.id in running -> RunState.Running
            applied[action.id] == true -> RunState.Done(last?.takeIf { it.ok }?.output.orEmpty())
            last != null && !last.ok -> RunState.Failed(last.said ?: PowerSetup.failureSentence(last.output), last.output)
            last != null && PowerSetup.didntTake(applied[action.id], last) -> RunState.DidntTake(notTaken(action, power, homeActions.any { it.id.startsWith("home_off_") }), last.output)
            applied[action.id] == false -> null
            last != null -> RunState.Done(last.output)
            else -> null
        }
    }

    val ready = status is PowerStatus.Ready
    SheetHeading(
        "Power Setup",
        when (val s = status) {
            is PowerStatus.Ready -> if (s.backend == "shizuku") "Connected through Shizuku." else "Connected to this TV's USB debugging."
            PowerStatus.WaitingForApproval -> "Allow it on the prompt the TV is showing. Tick “Always allow” so you only do this once."
            PowerStatus.NotPaired -> "Lets Lounge do the few things an app can't: become the home screen, allow its own accesses, and turn the TV on and switch its input."
            is PowerStatus.Unavailable -> s.reason
            is PowerStatus.Failed -> s.reason
        },
    )

    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        val s = status
        val unavailable = s is PowerStatus.Unavailable
        SheetRow(
            icon = if (unavailable) LoungeIcons.Settings else LoungeIcons.Power,
            title = when (s) {
                is PowerStatus.Ready -> "Connected"
                PowerStatus.WaitingForApproval -> "Waiting for you to allow it…"
                is PowerStatus.Unavailable -> "Open Developer options"
                else -> "Connect"
            },
            value = when (s) {
                is PowerStatus.Ready -> if (s.backend == "shizuku") "Shizuku" else "USB debugging"
                PowerStatus.WaitingForApproval -> fingerprint?.let { "Key ${it.take(11)}…" } ?: "Look at the prompt"
                is PowerStatus.Unavailable -> "Turn on USB debugging"
                else -> if (pairing) "Connecting…" else "The TV asks you once"
            },
            accent = when (s) {
                is PowerStatus.Ready -> colors.signal
                is PowerStatus.Unavailable -> colors.dust
                else -> colors.houselight
            },
            onClick = {
                when (s) {
                    is PowerStatus.Ready, PowerStatus.WaitingForApproval -> Unit
                    is PowerStatus.Unavailable -> runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    else -> if (!pairing) {
                        pairing = true
                        scope.launch { power.pair(); pairing = false }
                    }
                }
            },
            focusRequester = firstFocus,
        )
        if (!ready && power.shizukuInstalled) {
            SheetRow(icon = LoungeIcons.Power, title = "Usar Shizuku en su lugar", value = "Solicitar", onClick = { power.requestShizuku(7001); scope.launch { delay(1500); power.refresh() } })
        }
        if (!ready) {
            Column(Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                if (s is PowerStatus.Unavailable && s.reason == PowerSetup.DEBUGGING_OFF) DebuggingHowTo()
                Text(DEBUGGING_STAYS_ON, style = LoungeTheme.typography.caption, color = colors.dust)
            }
        }
    }

    Spacer(Modifier.height(28.dp))
    Text("Qué puede hacer", style = LoungeTheme.typography.meta, color = colors.dust)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        power.setupActions.flatMap {
            when (it) {
                power.homeRole -> listOf(it) + homeActions
                power.quickAnimations -> if (offerUndo) listOf(it, power.normalAnimations) else listOf(it)
                else -> listOf(it)
            }
        }.forEach { action -> ActionRow(action, stateOf(action), enabled = ready) { power.start(action) } }
    }

    if (ready) {
        Spacer(Modifier.height(20.dp))
        // Forgetting asks once more first, like removing a plugin; moving away takes the question back.
        SheetRow(
            icon = LoungeIcons.Trash,
            title = if (forgetting) "Sí, olvidar esta conexión" else "Olvidar esta conexión",
            titleColor = if (forgetting) colors.ember else colors.limelight,
            subtitle = "El TV volverá a pedir permiso la próxima vez que conectes",
            modifier = Modifier.onFocusChanged { if (!it.isFocused) forgetting = false },
            onClick = {
                if (!forgetting) {
                    forgetting = true
                } else {
                    forgetting = false
                    power.forget()
                    say("Connection forgotten")
                    scope.launch { firstFocus.requestWhenReady() }
                }
            },
        )
    }
    SheetFootnote(
        "Nothing runs until you press it, and every command is shown first.",
        fingerprint?.let { "The key stays on this TV: $it" } ?: "The key stays on this TV.",
    )
}

@Composable
private fun ActionRow(action: PowerAction, state: RunState?, enabled: Boolean, onRun: () -> Unit) {
    val colors = LoungeTheme.colors
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        SheetRow(
            icon = glyphFor(action),
            title = action.title,
            value = when (state) {
                RunState.Running -> "Running…"
                is RunState.Done -> "Done"
                is RunState.Failed -> "Failed"
                is RunState.DidntTake -> "Didn't take"
                null -> if (enabled) "Run" else "Connect first"
            },
            accent = when (state) {
                is RunState.Done -> colors.signal
                is RunState.Failed, is RunState.DidntTake -> colors.ember
                RunState.Running -> colors.houselight
                null -> colors.dust
            },
            onClick = { if (enabled) onRun() },
        )
        Column(Modifier.padding(start = 20.dp, end = 4.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Text(action.detail, style = LoungeTheme.typography.caption, color = colors.dust)
            val printed = when (state) {
                is RunState.Done -> state.output
                is RunState.Failed -> state.output
                is RunState.DidntTake -> state.output
                else -> ""
            }.trim()
            val mono = LoungeTheme.typography.caption.copy(fontFamily = FontFamily.Monospace)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.velvet).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(action.command, style = mono, color = colors.houselight, maxLines = 3)
                // What the command printed stays in its box; the row itself speaks in sentences.
                if (printed.isNotEmpty()) Text(printed, style = mono, color = colors.dust, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (state is RunState.Failed) Text(state.reason, style = LoungeTheme.typography.caption, color = colors.ember)
            if (state is RunState.DidntTake) Text(state.next, style = LoungeTheme.typography.caption, color = colors.ember)
        }
    }
}

/** What to do about [action] when it ran without an error and changed nothing; [homesFirst] when a home to turn off is listed below. */
private fun notTaken(action: PowerAction, power: PowerSetup, homesFirst: Boolean): String = when {
    action.id != power.homeRole.id -> "The TV ran this, but nothing changed. Try again."
    homesFirst -> "The TV still opens its own home first. Turn it off below."
    else -> HOME_NOT_TAKEN_SETUP
}

/** A TV control that did not work, as a sentence with what to try. */
private fun controlFailure(action: PowerAction, power: PowerSetup): String = when (action) {
    power.everythingOff -> "It didn't go to sleep. Try again."
    else -> "The TV didn't answer. Check that HDMI-CEC is on in the TV's settings, then try again."
}

/**
 * The TV controls card's sheet: power, input and sound through Power Setup, with volume and
 * playback working even without it.
 */
@Composable
fun TvControlsSheet(power: PowerSetup, deviceName: String, onKey: (String) -> Unit, onPowerSetup: () -> Unit, firstFocus: FocusRequester) {
    val colors = LoungeTheme.colors
    val scope = rememberCoroutineScope()
    val status by power.status.collectAsStateWithLifecycle()
    val runs = remember { mutableStateMapOf<String, RunState>() }
    var devices by remember { mutableStateOf<List<CecDevice>>(emptyList()) }
    val ready = status is PowerStatus.Ready

    LaunchedEffect(Unit) { power.refresh() }
    LaunchedEffect(ready) { if (ready) devices = power.cecDevices() }

    fun run(action: PowerAction) {
        if (runs[action.id] == RunState.Running) return
        runs[action.id] = RunState.Running
        scope.launch {
            val result = try {
                val r = power.run(action)
                if (r.ok) RunState.Done(r.output) else RunState.Failed(controlFailure(action, power))
            } catch (e: PrivilegedException) {
                RunState.Failed(e.message ?: controlFailure(action, power))
            }
            runs[action.id] = result
            // A failure stays with its row until it is pressed again, so there is time to read it.
            if (result is RunState.Done) {
                delay(2500)
                if (runs[action.id] == result) runs.remove(action.id)
            }
        }
    }

    fun valueFor(action: PowerAction, idle: String? = null) = when (runs[action.id]) {
        RunState.Running -> "Sending…"
        is RunState.Done -> "Sent"
        is RunState.Failed, is RunState.DidntTake -> "Didn't work"
        null -> idle
    }
    fun reasonFor(action: PowerAction) = (runs[action.id] as? RunState.Failed)?.reason
    fun accentFor(action: PowerAction) = when (runs[action.id]) {
        is RunState.Done -> colors.signal
        is RunState.Failed, is RunState.DidntTake -> colors.ember
        RunState.Running -> colors.houselight
        null -> colors.dust
    }

    SheetHeading("Controles de TV", if (ready) "Enciende el TV, cambia la entrada HDMI y elige la salida de audio." else "Volumen y reproducción funcionan ya. Encendido y entradas requieren Configuración Avanzada.")

    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        val tv = devices.firstOrNull { it.isTv }
        SheetRow(
            icon = if (ready) LoungeIcons.Tv else LoungeIcons.Power,
            title = if (ready) "Encender TV y cambiar a esta entrada" else "Configurar encendido y entradas",
            subtitle = if (ready) reasonFor(power.oneTouchPlay) else null,
            subtitleLines = WHOLE,
            value = if (ready) valueFor(power.oneTouchPlay, tv?.let { if (it.isOn) "TV is on" else "TV is off" }) else if (status is PowerStatus.Unavailable) "Needs USB debugging" else "Power Setup",
            accent = if (ready) accentFor(power.oneTouchPlay) else colors.houselight,
            onClick = { if (ready) run(power.oneTouchPlay) else onPowerSetup() },
            focusRequester = firstFocus,
        )
        if (ready) {
            SheetRow(icon = LoungeIcons.Power, title = "Encendido de TV", subtitle = reasonFor(power.tvPower), subtitleLines = WHOLE, value = valueFor(power.tvPower, "Encender o apagar"), accent = accentFor(power.tvPower), onClick = { run(power.tvPower) })
            SheetRow(icon = LoungeIcons.Power, title = "Apagar todo", subtitle = reasonFor(power.everythingOff), subtitleLines = WHOLE, value = valueFor(power.everythingOff, "Suspender todo"), accent = if (runs[power.everythingOff.id] == null) colors.ember else accentFor(power.everythingOff), onClick = { run(power.everythingOff) })
            if (power.isTelevision) {
                SheetRow(icon = LoungeIcons.Soundbar, title = "Audio por barra de sonido", subtitle = reasonFor(power.systemAudioOn), subtitleLines = WHOLE, value = valueFor(power.systemAudioOn), accent = accentFor(power.systemAudioOn), onClick = { run(power.systemAudioOn) })
                SheetRow(icon = LoungeIcons.Soundbar, title = "Audio por altavoces del TV", subtitle = reasonFor(power.systemAudioOff), subtitleLines = WHOLE, value = valueFor(power.systemAudioOff), accent = accentFor(power.systemAudioOff), onClick = { run(power.systemAudioOff) })
            }
            val others = devices.filter { !it.isTv && it.physicalAddress != 0 }
            if (others.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text("Cambiar entrada del TV a", style = LoungeTheme.typography.meta, color = colors.dust)
                others.forEach { d ->
                    val action = power.selectDevice(d)
                    SheetRow(icon = LoungeIcons.Hdmi, title = d.name, subtitle = reasonFor(action), subtitleLines = WHOLE, value = valueFor(action, if (d.isOn) "Encendido" else "En espera"), accent = accentFor(action), onClick = { run(action) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Sonido y reproducción", style = LoungeTheme.typography.meta, color = colors.dust)
        SheetRow(icon = LoungeIcons.Volume, title = "Subir volumen", onClick = { onKey("volume_up") })
        SheetRow(icon = LoungeIcons.Volume, title = "Bajar volumen", onClick = { onKey("volume_down") })
        SheetRow(icon = LoungeIcons.Mute, title = "Silenciar", onClick = { onKey("mute") })
        SheetRow(icon = LoungeIcons.Play, title = "Reproducir o pausar", onClick = { onKey("play_pause") })
    }
    SheetFootnote(if (ready) "Lounge asks the TV through its HDMI cable; the TV decides what it does." else "Volume reaches the TV or soundbar the way the remote's own buttons do.")
}
