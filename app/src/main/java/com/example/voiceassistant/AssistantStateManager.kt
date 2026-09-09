package com.example.voiceassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AssistantStateManager {
    enum class State(val displayLabel: String) {
        UNINITIALISED("Initialising…"),
        LOADING_STT("Loading speech engine…"),
        LOADING_LLM("Loading language model…"),
        IDLE("Ready — tap mic to speak"),
        LISTENING("Listening…"),
        PROCESSING("Thinking…"),
        EXECUTING("Executing…"),
        ERROR("Error")
    }

    private val _state = MutableStateFlow(State.UNINITIALISED)
    val state: StateFlow<State> = _state
    val current: State get() = _state.value
    var errorMessage: String? = null
        private set

    fun transitionTo(newState: State, error: String? = null) {
        errorMessage = error
        _state.value = newState
    }

    fun forceReset() {
        errorMessage = null
        _state.value = State.UNINITIALISED
    }
}
