package com.docscanner.ui.screens.pairing

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.drive.sync.DeviceInfo
import com.docscanner.drive.sync.LocalDriveIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevicePairingUiState(
    val localDeviceId: String = "",
    val localDeviceName: String = "",
    val pairedDevices: Map<String, DeviceInfo> = emptyMap(),
    val qrCodeData: String = "",
    val qrCodeSize: Int = 512,
    val scannedData: String = "",
    val pairingResult: String? = null,
)

@HiltViewModel
class DevicePairingViewModel @Inject constructor(
    application: Application,
    private val localDriveIndex: LocalDriveIndex,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DevicePairingUiState())
    val uiState: StateFlow<DevicePairingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val deviceId = localDriveIndex.getLocalDeviceId()
        val deviceName = Build.MODEL
        val qrData = if (deviceId.isNotBlank()) {
            "docscanner-pair:$deviceId:$deviceName:${System.currentTimeMillis()}"
        } else {
            ""
        }
        _uiState.update {
            it.copy(
                localDeviceId = deviceId,
                localDeviceName = deviceName,
                pairedDevices = localDriveIndex.getDevices().filterKeys { key -> key != deviceId },
                qrCodeData = qrData,
                pairingResult = null,
            )
        }
    }

    fun onQrScanned(data: String) {
        if (!data.startsWith("docscanner-pair:")) {
            _uiState.update { it.copy(pairingResult = "Invalid pairing code") }
            return
        }
        val parts = data.removePrefix("docscanner-pair:").split(":", limit = 3)
        if (parts.size < 2) {
            _uiState.update { it.copy(pairingResult = "Invalid pairing data") }
            return
        }
        val deviceId = parts[0]
        val deviceName = parts[1]
        if (deviceId == localDriveIndex.getLocalDeviceId()) {
            _uiState.update { it.copy(pairingResult = "Cannot pair with yourself") }
            return
        }
        localDriveIndex.setDevice(
            deviceId,
            DeviceInfo(
                name = deviceName,
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
            ),
        )
        _uiState.update { it.copy(pairingResult = "Paired with $deviceName") }
        refresh()
    }

    fun unpairDevice(deviceId: String) {
        viewModelScope.launch {
            localDriveIndex.removeDevice(deviceId)
            refresh()
        }
    }

    fun clearPairingResult() {
        _uiState.update { it.copy(pairingResult = null) }
    }
}
