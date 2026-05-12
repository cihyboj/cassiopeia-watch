import com.beamburst.casswatch.ui.common.AppTextVeryLarge
import com.beamburst.casswatch.ui.time.ClockDisplaySource
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RealTimeClock(
    source: ClockDisplaySource,
    modifier: Modifier = Modifier,
) {
    // State to hold the current time
    var currentTime by remember {
        mutableStateOf(currentTimeMillisForDisplay(source))
    }

    // LaunchedEffect to update time every second
    LaunchedEffect(source) {
        while (true) {
            delay(1000L) // Delay for 1 second
            currentTime = currentTimeMillisForDisplay(source)
        }
    }

    // Display the current time
    AppTextVeryLarge(
        text = getCurrentTime(currentTime, getSystemTimeFormat()),
        modifier = modifier
    )
}

enum class TimeFormat(val pattern: String) {
    TwelveHour("h:mm:ss a"), // 12-hour format without AM/PM
    TwentyFourHour("H:mm:ss") // 24-hour format
}

fun getCurrentTime(format: TimeFormat): String {
    val sdf = SimpleDateFormat(format.pattern, Locale.getDefault())
    return sdf.format(Date())
}

fun getCurrentTime(currentTimeMs: Long, format: TimeFormat): String {
    val sdf = SimpleDateFormat(format.pattern, Locale.getDefault())
    return sdf.format(Date(currentTimeMs))
}

private fun currentTimeMillisForDisplay(source: ClockDisplaySource): Long {
    if (!source.ntpTimeEnabled) return System.currentTimeMillis()
    val cache = source.ntpOffsetCache ?: return System.currentTimeMillis()
    val elapsedNow = SystemClock.elapsedRealtime()
    val ageMs = elapsedNow - cache.capturedElapsedRealtimeMs
    if (ageMs < 0L || ageMs > NTP_DISPLAY_CACHE_VALID_MS) return System.currentTimeMillis()
    return cache.ntpTimeAtCaptureMs + ageMs
}

fun getSystemTimeFormat(): TimeFormat {
    // Get the short time format pattern from the system's DateFormat
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT) as SimpleDateFormat).toPattern()

    // Return the appropriate TimeFormat enum based on the presence of "a" in the pattern
    return if (pattern.contains("a")) {
        TimeFormat.TwelveHour // 12-hour format
    } else {
        TimeFormat.TwentyFourHour // 24-hour format
    }
}

private const val NTP_DISPLAY_CACHE_VALID_MS = 30L * 60L * 1_000L
