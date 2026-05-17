package com.beamburst.casswatch.ui.common

import android.annotation.SuppressLint
import android.graphics.Paint
import android.util.TypedValue
import android.view.ViewGroup
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
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        }
    }
    applyInputTextStyleToChildren(selectedColorArgb, textSizePx)
    invalidate()
}

private fun ViewGroup.applyInputTextStyleToChildren(
    selectedColorArgb: Int,
    textSizePx: Float,
) {
    for (index in 0 until childCount) {
        when (val child = getChildAt(index)) {
            is EditText -> {
                child.setTextColor(selectedColorArgb)
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            }
            is ViewGroup -> child.applyInputTextStyleToChildren(selectedColorArgb, textSizePx)
        }
    }
}
