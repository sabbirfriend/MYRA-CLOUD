package com.myra.assistant.auth

import com.myra.assistant.voice.VoiceModuleBridge
import java.util.concurrent.CopyOnWriteArraySet

/** Single process auth event bridge so login state reaches voice/AI/module consumers immediately. */
object AuthStateBridge {
    data class State(val signedIn: Boolean, val userId: String = "", val email: String = "", val displayName: String = "", val isAdmin: Boolean = false)
    fun interface Listener { fun onAuthChanged(state: State) }
    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile var current: State = State(false)
        private set
    fun register(listener: Listener) { listeners.add(listener); listener.onAuthChanged(current) }
    fun unregister(listener: Listener) { listeners.remove(listener) }
    fun publish(state: State) {
        current = state
        listeners.forEach { runCatching { it.onAuthChanged(state) } }
        VoiceModuleBridge.publish(
            text = if (state.signedIn) "Google account connected: ${state.displayName.ifBlank { state.email }}" else "MYRA account signed out",
            success = state.signedIn, module = "auth", source = "auth_state",
            priority = VoiceModuleBridge.Priority.HIGH, speak = false
        )
    }
}
