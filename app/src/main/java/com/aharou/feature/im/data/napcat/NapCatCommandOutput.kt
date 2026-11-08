package com.aharou.feature.im.data.napcat

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal suspend fun readNapCatCommandOutput(
    stream: InputStream,
    isAlive: () -> Boolean,
    onLine: (String) -> Unit
) {
    val buffer = ByteArray(4096)
    val line = ByteArrayOutputStream()
    fun emit() {
        if (line.size() > 0) {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
                .decode(ByteBuffer.wrap(line.toByteArray())).toString()
            onLine(text)
        }
        line.reset()
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        val available = stream.available()
        if (available == 0) {
            // 派生进程可能仍持管道；主命令退出后只排空已就绪输出，不再等 EOF。
            if (!isAlive()) {
                if (stream.available() == 0) break
                continue
            }
            delay(50)
            continue
        }
        val read = stream.read(buffer, 0, minOf(buffer.size, available))
        if (read < 0) break
        if (read == 0) {
            delay(50)
            continue
        }
        for (index in 0 until read) {
            val byte = buffer[index].toInt() and 0xff
            if (byte == 10 || byte == 13) emit()
            else if (line.size() < buffer.size) line.write(byte)
        }
    }
    emit()
}
