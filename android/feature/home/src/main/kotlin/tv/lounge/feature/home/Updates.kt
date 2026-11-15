package tv.lounge.feature.home

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import tv.lounge.design.Hairline
import tv.lounge.design.LoungeIcons
import tv.lounge.design.LoungeTheme
import tv.lounge.design.Words
import tv.lounge.update.InstallPath
import tv.lounge.update.UpdateManager
import tv.lounge.update.UpdateState

/** The one word the Settings row shows for the updater. */
fun updateStatusWord(state: UpdateState, auto: Boolean): String = when (state) {
    UpdateState.Checking -> "Checking…"
    is UpdateState.Available -> "${state.release.version} available"
    is UpdateState.Downloading -> "Downloading…"
    is UpdateState.Ready, is UpdateState.NeedsConfirmation -> "${state.release?.version} ready"
    is UpdateState.Installing -> "Installing…"
    is UpdateState.Failed -> "Error"
    is UpdateState.UpToDate -> "Al día"
    is UpdateState.Idle -> if (auto) "On" else "Manual"
}

private fun ago(ms: Long): String = if (ms <= 0) "never" else Words.ago(System.currentTimeMillis() - ms)

/** What OK on a row that opens Android's list of apps allowed to install says first. */
private const val INSTALL_HINT = "OK abre la lista de Android: activa Lounge allí y pulsa Atrás"

/** Updates, from Settings: where Lounge stands against its releases and what, if anything, needs a press. */
@Composable
fun UpdatesSheet(updates: UpdateManager, firstFocus: FocusRequester) {
    val colors = LoungeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by updates.state.collectAsStateWithLifecycle()
    val auto by updates.autoCheck.collectAsStateWithLifecycle()
    val activity = context as? Activity
    fun act() { activity?.let { updates.interact(it, scope) } }

    SheetHeading("Actualizaciones")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetRow(
            icon = LoungeIcons.Info,
            title = "Lounge ${updates.currentVersion}",
            value = if (state is UpdateState.Idle) "Sin comprobar aún" else updateStatusWord(state, auto),
            accent = when (state) {
                is UpdateState.UpToDate -> colors.signal
                is UpdateState.Failed -> colors.ember
                else -> colors.dust
            },
            subtitle = when (val s = state) {
                is UpdateState.UpToDate -> "Checked ${ago(s.checkedAtMs)}"
                is UpdateState.Idle -> "Pulsa para comprobar ahora"
                is UpdateState.Failed -> s.message
                else -> null
            },
            subtitleLines = WHOLE,
            onClick = { scope.launch { updates.check() } },
            focusRequester = firstFocus,
        )
        SheetRow(
            icon = SettingsGlyphs.Clock,
            title = "Buscar dos veces al día",
            value = if (auto) "On" else "Off",
            accent = if (auto) colors.signal else colors.dust,
            onClick = { updates.setAutoCheck(!auto) },
        )
        when (val s = state) {
            is UpdateState.Available -> SheetRow(icon = SettingsGlyphs.Download, title = "Descargar Lounge ${s.release.version}", value = "Descargar", accent = colors.houselight, onClick = ::act)
            is UpdateState.Downloading -> SheetRow(icon = SettingsGlyphs.Download, title = "Descargando Lounge ${s.release.version}", value = "${(s.progress * 100).roundToInt()} %", onClick = {})
            is UpdateState.Ready -> SheetRow(
                icon = if (s.path == InstallPath.NeedsPermission) LoungeIcons.Settings else SettingsGlyphs.Download,
                title = if (s.path == InstallPath.NeedsPermission) "Permitir a Lounge instalar actualizaciones" else "Instalar Lounge ${s.release.version}",
                subtitle = if (s.path == InstallPath.NeedsPermission) INSTALL_HINT else null,
                accent = colors.houselight,
                value = when (s.path) {
                    InstallPath.NeedsPermission -> "Open"
                    InstallPath.Confirm -> "Confirm"
                    else -> "Now"
                },
                onClick = ::act,
            )
            is UpdateState.NeedsConfirmation -> SheetRow(icon = SettingsGlyphs.Download, title = "Instalar Lounge ${s.release.version}", value = "Confirmar", accent = colors.houselight, onClick = ::act)
            is UpdateState.Installing -> SheetRow(icon = SettingsGlyphs.Download, title = "Instalando Lounge ${s.release.version}…", onClick = {})
            is UpdateState.Failed -> SheetRow(icon = LoungeIcons.Refresh, title = "Reintentar", value = s.release?.let { "Lounge ${it.version}" }, onClick = ::act)
            else -> Unit
        }
        state.release?.let { r ->
            val summary = r.summary()
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Novedades en ${r.version}", style = LoungeTheme.typography.meta, color = colors.limelight, modifier = Modifier.padding(horizontal = 16.dp))
                Text(summary, style = LoungeTheme.typography.caption, color = colors.dust, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val path = updates.currentPath()
        val how = when (path) {
            InstallPath.Silent -> "Las actualizaciones se instalan automáticamente mientras usas otra app."
            InstallPath.PowerSetup -> "Configuración Avanzada instala actualizaciones en segundo plano sin preguntar."
            InstallPath.Confirm -> "Android te pedirá confirmar la actualización; elige Más detalles > Instalar de todas formas."
            InstallPath.NeedsPermission -> "Android aún no permite a Lounge instalar apps. Actívalo en Ajustes > Apps > Acceso especial > Instalar apps desconocidas."
        }
        Text("Lounge busca versiones en GitHub dos veces al día. $how", style = LoungeTheme.typography.caption, color = colors.dust, modifier = Modifier.padding(horizontal = 16.dp))
        if (path == InstallPath.NeedsPermission) {
            Spacer(Modifier.height(8.dp))
            SheetRow(icon = LoungeIcons.Settings, title = "Permitir a Lounge instalar actualizaciones", subtitle = INSTALL_HINT, onClick = { activity?.let { updates.askForInstallPermission(it, scope) } })
        }
    }
}
