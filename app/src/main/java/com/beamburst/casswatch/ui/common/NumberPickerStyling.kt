package com.beamburst.casswatch.ui.common

import android.annotation.SuppressLint
import android.graphics.Paint
import android.widget.EditText
import android.widget.NumberPicker

@SuppressLint("SoonBlockedPrivateApi")
fun NumberPicker.applyWheelTextStyle(
    selectedColorArgb: Int,
    inactiveColorArgb: Int,
    textSizePx: Float,
) {
    runCatching {
        val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
        paintField.isAccessible = true
        (paintField.get(this) as? Paint)?.apply {
            color = inactiveColorArgb
            textSize = textSizePx
        }
    }
    runCatching {
        val inputField = NumberPicker::class.java.getDeclaredField("mInputText")
        inputField.isAccessible = true
        (inputField.get(this) as? EditText)?.apply {
            setTextColor(selectedColorArgb)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
        }
    }
    invalidate()
}
