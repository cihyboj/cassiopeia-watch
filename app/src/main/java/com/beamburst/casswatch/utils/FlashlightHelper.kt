package com.beamburst.casswatch.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.beamburst.casswatch.R
import com.beamburst.casswatch.ui.common.AppSnackbar
import timber.log.Timber

object FlashlightHelper {
    private var torchOn = false

    fun toggle(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        runCatching {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                AppSnackbar(context.getString(R.string.flashlight_not_available))
                return
            }
            torchOn = !torchOn
            manager.setTorchMode(cameraId, torchOn)
        }.onFailure { e ->
            Timber.e(e, "Flashlight toggle failed")
            AppSnackbar(context.getString(R.string.flashlight_not_available))
        }
    }
}
