package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import scooper.data.Setting


data class SettingState(val setting: Setting = Setting())

class SettingsViewModel : ContainerHost<SettingState, Nothing> {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    override val container: Container<SettingState, Nothing> = coroutineScope.container(SettingState())

}