package org.watch2phone.wear

internal enum class PeripheralState {
    STOPPED, STARTING, ADVERTISING, CONNECTED;

    enum class Event { START, PUBLISHED, CONNECT, DISCONNECT, STOP }

    fun next(event: Event): PeripheralState = when (event) {
        Event.START -> if (this == STOPPED) STARTING else this
        Event.PUBLISHED -> if (this == STARTING) ADVERTISING else this
        Event.CONNECT -> if (this == ADVERTISING) CONNECTED else this
        Event.DISCONNECT -> if (this == CONNECTED) STARTING else this
        Event.STOP -> STOPPED
    }
}
