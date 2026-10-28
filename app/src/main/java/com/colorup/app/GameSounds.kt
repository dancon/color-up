package com.colorup.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Original synthesized effects; volume follows the device's game/media settings. */
internal class GameSounds(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val ready = mutableSetOf<Int>()
    private val ids: Map<String, Int>

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready += id }
        ids = listOf("crayon", "pop", "select", "erase", "complete").associateWith { name ->
            context.assets.openFd("audio/$name.wav").use { pool.load(it, 1) }
        }
    }

    fun play(name: String) {
        val id = ids[name] ?: return
        if (id in ready) pool.play(id, .45f, .45f, 1, 0, 1f)
    }

    fun release() { pool.release() }
}
