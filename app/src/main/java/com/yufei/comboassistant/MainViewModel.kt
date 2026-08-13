package com.yufei.comboassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yufei.comboassistant.data.ComboRepository
import com.yufei.comboassistant.data.GlobalSettings
import com.yufei.comboassistant.data.GlobalSettingsRepository
import com.yufei.comboassistant.domain.Combo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val combos: List<Combo> = emptyList(),
    val settings: GlobalSettings = GlobalSettings(),
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val comboRepository: ComboRepository,
    private val settingsRepository: GlobalSettingsRepository,
) : ViewModel() {
    val state: StateFlow<MainUiState> = combine(
        comboRepository.observeAll(),
        settingsRepository.settings,
        ::MainUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MainUiState())

    fun setDisclosureAccepted(value: Boolean) = viewModelScope.launch {
        settingsRepository.setDisclosureAccepted(value)
    }

    fun setFloatingBallEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setFloatingBallEnabled(value)
    }

    fun setButtonsHidden(value: Boolean) = viewModelScope.launch {
        settingsRepository.setButtonsHidden(value)
    }

    fun setEnhancedForegroundDetection(value: Boolean) = viewModelScope.launch {
        settingsRepository.setEnhancedForegroundDetection(value)
    }

    fun save(combo: Combo) = viewModelScope.launch {
        comboRepository.save(combo.copy(updatedAt = System.currentTimeMillis()))
    }

    fun delete(id: String) = viewModelScope.launch { comboRepository.delete(id) }
}
