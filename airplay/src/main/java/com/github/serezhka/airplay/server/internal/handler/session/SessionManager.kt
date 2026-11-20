package com.github.serezhka.airplay.server.internal.handler.session

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity

class SessionManager @JvmOverloads constructor(
    private val identity: PairingIdentity = PairingIdentity.generate(),
) {

    private val sessions = HashMap<String, Session>()

    fun pairingPublicKey(): ByteArray = identity.publicKey()

    fun getSession(sessionId: String): Session = synchronized(sessions) {
        sessions.getOrPut(sessionId) { Session(sessionId, identity) }
    }

    fun allSessions(): List<Session> = synchronized(sessions) { ArrayList(sessions.values) }
}
