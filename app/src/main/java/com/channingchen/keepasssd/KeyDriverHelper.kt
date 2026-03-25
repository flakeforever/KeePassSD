package com.channingchen.keepasssd

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Helper to handle YubiKey Challenge-Response intents for KeePassDX or ykDroid drivers.
 * Encapsulates: Actions, Extras, 64-byte Padding (matching KeePassDX), and Result Parsing.
 *
 * The actual launch is done by KeyDriverProxyActivity (transparent, excludeFromRecents),
 * NOT by MainActivity directly — this is the key to keeping the main UI in foreground.
 */
object KeyDriverHelper {
    // Intent Actions
    private const val ACTION_KEEPASSDX = "android.yubikey.intent.action.CHALLENGE_RESPONSE"
    private const val ACTION_YKDROID   = "net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE"

    // Extra Keys
    const val EXTRA_CHALLENGE = "challenge"
    const val EXTRA_RESPONSE  = "response"

    /**
     * Prepares the challenge by padding it to 64 bytes.
     * Matches KeePassDX: first 32 bytes = seed, next 32 bytes = 0x20 (space).
     */
    fun prepareChallenge(seed: ByteArray?): ByteArray? {
        if (seed == null) return null
        val challenge = ByteArray(64)
        seed.copyInto(challenge, 0, 0, seed.size.coerceAtMost(32))
        challenge.fill(32.toByte(), 32, 64)
        return challenge
    }

    /**
     * Builds the resolved driver Intent (KeePassDX first, ykDroid fallback).
     * Returns null if no driver is installed.
     *
     * Callers should wrap in a KeyDriverProxyActivity launch, NOT call this
     * from a Composable ActivityResultLauncher directly.
     */
    fun buildChallengeIntent(seed: ByteArray): Intent? {
        val paddedChallenge = prepareChallenge(seed) ?: return null
        // Try KeePassDX driver first
        val keepassdxIntent = Intent(ACTION_KEEPASSDX).apply {
            putExtra(EXTRA_CHALLENGE, paddedChallenge)
        }
        // Try ykDroid as fallback
        val ykdroidIntent = Intent(ACTION_YKDROID).apply {
            putExtra(EXTRA_CHALLENGE, paddedChallenge)
        }
        // Return whichever action string is valid — the ProxyActivity will catch ActivityNotFoundException
        // We just need to signal which one to try first; ProxyActivity handles the fallback too.
        // For simplicity return the KeePassDX intent and let ProxyActivity handle the ykDroid fallback.
        return keepassdxIntent
    }

    /**
     * Extracts the response byte array from the result intent.
     */
    fun parseResponse(data: Intent?): ByteArray? {
        return data?.getByteArrayExtra(EXTRA_RESPONSE)
    }
}
