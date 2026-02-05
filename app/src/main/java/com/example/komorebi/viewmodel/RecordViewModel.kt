package com.example.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val repository: KonomiRepository // リポジトリを注入
) : ViewModel() {

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings

    private val _isRecordingLoading = MutableStateFlow(true)
    val isRecordingLoading: StateFlow<Boolean> = _isRecordingLoading

    init{
        fetchRecentRecordings()
    }

    fun fetchRecentRecordings() {
        viewModelScope.launch {
            _isRecordingLoading.value = true
            try {
                val response = repository.getRecordedPrograms(page = 1)
                _recentRecordings.value = response.recordedPrograms
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRecordingLoading.value = false
            }
        }
    }
}