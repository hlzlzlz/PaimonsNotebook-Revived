package com.lianyi.paimonsnotebook.common.util.image

import com.lianyi.paimonsnotebook.common.application.PaimonsNotebookApplication
import com.lianyi.paimonsnotebook.common.extension.scope.launchSafeIO
import com.lianyi.paimonsnotebook.common.extension.string.errorNotify
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
* 网络图片加载失败记录器
*
* 将失败原因写入 files/image_error.log,并在当次运行首次失败时弹出通知。
* 用途:在无法连接调试器的情况下定位图片加载失败的真实原因。
*
* ## ⚠️ 为什么写盘必须异步 + 节流(1.8.30 修)
*
* 本方法由 Coil 的 `onError` 回调触发。`AsyncImagePainter` 的 `rememberScope`
* 是 `CoroutineScope(Dispatchers.Main)`(已从 coil-compose-base-2.6.0.aar
* 反编译确认:字节码里 `Dispatchers.getMain()` → `CoroutineScope(...)` →
* `putfield rememberScope`,随后 `launch` 进该 scope 并 `setState`),
* 而 `onError` 是经 painter 的 `onState` 分发的。
* ⇒ **该回调可能运行在主线程上**,在其中做同步磁盘 I/O 会直接掉帧。
*
* 图床不稳时一次列表滚动可能触发几十次失败 ⇒ 几十次主线程写盘。
* 这属于"越卡越写、越写越卡"的放大器:图床越差,失败越多,主线程被占得越久。
*
* 现在改为:① 写盘投递到 IO(不阻塞调用方线程);② 同一 URL 在窗口期内只记一次
* (失败往往成批重复出现,日志留一条足够定位,不必刷屏)。
*
* 注意首次失败的通知仍只发一次,且 `errorNotify` 内部已自行切到后台
* (`PaimonsNotebookNotification.add` 用 launchSafeIO),无需额外处理。
* */
object ImageErrorLogger {

    private val context by lazy { PaimonsNotebookApplication.context }

    private val format by lazy { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US) }

    @Volatile
    private var firstErrorNotified = false

    /*
    * 同一 URL 的写盘节流窗口
    *
    * 这份日志纯粹用于排查,重复条目没有额外价值。
    * */
    private const val LOG_THROTTLE_MS = 60_000L

    private val lastLogTimeMap = mutableMapOf<String, Long>()

    fun log(url: String, throwable: Throwable?) {
        //① 通知只发一次
        synchronized(this) {
            if (!firstErrorNotified) {
                firstErrorNotified = true
                "图片加载失败:${throwable?.javaClass?.simpleName}:${throwable?.message?.take(120)}\n完整日志:files/image_error.log".errorNotify()
            }
        }

        //② 节流:同一 URL 窗口期内不重复写盘
        val now = System.currentTimeMillis()
        synchronized(lastLogTimeMap) {
            val last = lastLogTimeMap[url] ?: 0L
            if (now - last < LOG_THROTTLE_MS) return

            lastLogTimeMap[url] = now

            //避免长期运行后 map 无限增长
            if (lastLogTimeMap.size > 512) {
                val threshold = now - LOG_THROTTLE_MS
                lastLogTimeMap.entries.removeAll { it.value < threshold }
            }
        }

        val message = buildString {
            append(format.format(Date(now)))
            append(" ")
            append(url)
            append(" -> ")
            append(throwable?.javaClass?.simpleName)
            append(": ")
            append(throwable?.message ?: "unknown")
        }

        //③ 写盘投递到 IO —— 绝不在调用方线程(可能正是主线程)上做磁盘 I/O
        launchSafeIO {
            appendToFile(message)
        }
    }

    private fun appendToFile(message: String) {
        try {
            val file = File(context.filesDir, "image_error.log")
            file.appendText(message + "\n")
        } catch (_: Exception) {
        }
    }
}
