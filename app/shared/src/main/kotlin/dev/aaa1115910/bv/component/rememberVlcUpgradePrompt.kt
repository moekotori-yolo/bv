package dev.aaa1115910.bv.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.aaa1115910.bv.player.impl.vlc.VlcNativeLibs
import dev.aaa1115910.bv.util.VlcLibsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Check once on entering the home screen; dismissing survives activity recreation. */
@Composable
fun rememberVlcUpgradePrompt(usingVlc: Boolean, selectedVersion: String): MutableState<Boolean> {
    val context = LocalContext.current
    val visible = rememberSaveable { mutableStateOf(false) }
    var checked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!checked) {
            visible.value = withContext(Dispatchers.IO) {
                usingVlc && VlcLibsInstaller.isVlcLibsInstalled(context) &&
                    VlcNativeLibs.shouldOfferStableUpgrade(
                        VlcLibsInstaller.getInstalledVersion(context),
                        selectedVersion,
                        usingVlc,
                    )
            }
            checked = true
        }
    }
    return visible
}
