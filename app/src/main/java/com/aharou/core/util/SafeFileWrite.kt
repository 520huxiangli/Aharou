package com.aharou.core.util

import java.io.File

/**
 * 把文本写进宿主 app 目录里的文件，失败时自愈，绝不抛给调用方。
 *
 * 这个目录（`filesDir/aharou`）及其子目录被绑定进容器（proot `-b`），容器内的 root 环境
 * 可能改掉文件的权限位——比如 `umask 077` 下建出来的文件是 600，或者脚本里一句 `chmod`
 * 把配置文件的属主与权限改成了 App 读写不了的样子。此后 App 每次写这个文件都是 EACCES，
 * 抛到协程里就会把整个 App 带崩。
 *
 * 自愈的依据：删除文件只看**父目录**的写权限，不需要文件本身的权限，而父目录始终在 App
 * 手里。所以写失败后删掉重建即可恢复。
 *
 * @return 写入是否成功；失败只记日志，不抛。
 */
fun File.writeTextSafely(text: String, tag: String): Boolean {
    runCatching {
        parentFile?.mkdirs()
        writeText(text)
    }.onSuccess { return true }

    return runCatching {
        delete()
        parentFile?.mkdirs()
        writeText(text)
    }.onSuccess {
        FileLogger.i(tag, "重建 $name 成功（原文件权限被容器改坏）")
    }.onFailure {
        FileLogger.w(tag, "写入 $name 失败: ${it.message}")
    }.isSuccess
}
