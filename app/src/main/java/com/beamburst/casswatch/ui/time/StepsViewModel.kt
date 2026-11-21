package com.beamburst.casswatch.ui.time

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.steps.StepHistory
import com.beamburst.casswatch.steps.StepHistoryMerge
import com.beamburst.casswatch.steps.StepHistoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What the Home steps card draws for one day. */
data class StepsChartModel(
    val todaySteps: Int?,
    val goal: Int?,
    /** 24 slots for [today], or empty when the latest reading is from another day. */
    val hourly: List<Int?>,
    /** The last 7 days, oldest first, ending with [today]. */
    val week: List<Pair<LocalDate, Int?>>,
    val lastReadAtMs: Long?,
)

internal fun stepsChartModel(history: StepHistory, today: LocalDate): StepsChartModel? {
    if (history.days.isEmpty()) return null
    return StepsChartModel(
        todaySteps = history.days[today]?.steps,
        goal = history.goal,
        hourly = if (history.hourlyDate == today) history.hourly.take(StepHistoryMerge.HOURS) else emptyList(),
        week = (6 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            date to history.days[date]?.steps
        },
        lastReadAtMs = history.fetchedAtMs,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StepsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    store: StepHistoryStore,
    watchSelector: CurrentWatchSelector,
) : ViewModel() {

    val history: StateFlow<StepHistory?> = watchSelector.selected
        .flatMapLatest { address -> if (address == null) flowOf(null) else store.observe(address) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _readingEnabled = MutableStateFlow(StepHistoryStore.isReadingEnabled(context))
    val readingEnabled: StateFlow<Boolean> = _readingEnabled.asStateFlow()

    fun setReadingEnabled(enabled: Boolean) {
        _readingEnabled.value = enabled
        viewModelScope.launch { StepHistoryStore.setReadingEnabled(context, enabled) }
    }
}
