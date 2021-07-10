package scooper.viewmodels

import kotlinx.coroutines.GlobalScope
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import scooper.data.Setting


data class SettingState(val setting: Setting = Setting())

class SettingsViewModel : ContainerHost<SettingState, Nothing> {
    override val container: Container<SettingState, Nothing> = GlobalScope.container(SettingState())

    
}