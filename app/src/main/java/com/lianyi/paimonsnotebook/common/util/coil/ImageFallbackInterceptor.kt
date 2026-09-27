package com.lianyi.paimonsnotebook.common.util.coil

import com.lianyi.paimonsnotebook.common.web.static_resources.StaticResourceSources
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
* 静态资源图片加载的**多源重试**拦截器
*
* 背景与实测数据见 `StaticResourceSources` 的注释 —— 主图床
* `static.snaphutaorp.org` 实测单张 72KB 图标耗时 21.8s~137.5s、甚至 60s 超时,
* 而本机带宽正常(同期 npmmirror 1862 KB/s)。
*
* ## 修过的缺陷
*
* ### ① 超时不触发兜底(原实现)
* 原实现是:
* ```
* val response = chain.proceed(request)          // 超时时这里直接抛异常
* if (request.url.host != StaticHost || response.isSuccessful) return response
* ```
* ⇒ **`chain.proceed` 超时是抛 `IOException`,根本走不到 `if`**,
*    而当前图床的主要症状恰恰是超时 ⇒ 兜底从未真正生效过。
*    现在把每次尝试都包在 try/catch 里,**超时与连接失败都会继续试下一个源**。
*
* ### ② 只有 enka 一个兜底,且 enka 自己也很慢
* enka 实测 7.9s / 23.6s / 12.7s。现在先试**路径结构完全一致**的镜像
* (`static.hutaorp.org`,实测 4.1s),enka 退为最后一档,并按分类过滤。
*
* ### ③ ⚠️ 兜底仍拿不到时间(1.8.29 实测发现)
*
* **尽管 ① 修好了"能走到兜底",兜底依然会失败** —— 原因是
* `applicationOkHttpClient` 上设了 `callTimeout(60s)`,而 **callTimeout 覆盖
* 整次 call(含拦截器里的所有 `proceed()` 尝试)**,不是"每次尝试各 60s"。
*
* 用真实 OkHttp(4.12.0)写探针复现(把 60s 等比缩小到 3s):
*
* ```
*   callTimeout=3s, readTimeout=3s
*   尝试 /primary.png(挂起不响应) -> SocketException after 3012ms   ← 吃光预算
*   尝试 /mirror.png              -> IOException: Canceled after 0ms  ← 0ms 被取消
*   RESULT: InterruptedIOException: timeout
* ```
*
* ⇒ **主图床一挂,镜像就永远轮不到**,而"主图床挂起"正是本拦截器存在的理由。
*    这个缺陷对用户表现为:**图还是加载不出来**,与修 ① 之前没有区别。
*
* **修法**:给每次尝试设**独立的、远小于 callTimeout 的 readTimeout**
* (`chain.withReadTimeout(...)`),让挂起的那次快速失败、把预算留给后续候选。
* 同一探针验证(perAttempt=4s):
*
* ```
*   尝试 /primary.png -> SocketTimeoutException after 4031ms   ← 快速失败
*   尝试 /mirror.png  -> HTTP 200 in 0ms                       ← 兜底成功
*   RESULT: HTTP 200  (total 4046ms)
* ```
*
* 4s 这个数字的依据:镜像 `static.hutaorp.org` 实测最慢约 12.1s、但中位数 1.0s,
* 主图床正常时也在 1~5s;取 8s 既容得下正常波动,又能保证 3 个候选
* (8×3=24s)在 60s callTimeout 内跑完。
*
* ## 设计约束
*
*   - **只对静态资源生效**:host 不是主图床的请求(用户帖子里外链的图、
*     B 站图等)原样透传,不介入、不重试 —— 否则会改变非本应用资源的加载行为。
*   - **每个候选只试一次**:重试次数由候选数量决定(主 + 镜像 + enka ≤ 3)。
*     不做指数退避 —— 图片加载是用户可感知的等待,宁可快速失败让 Coil 显示占位图。
*   - **失败要抛出**:所有候选都失败时抛 IOException,让 Coil 走它自己的 onError
*     与占位图逻辑(不能静默返回一个空响应,那会变成"加载成功但空白")。
*   - **失败原因逐个记录**在抛出的异常里,便于定位是哪个源的问题
*     (真机日志是本项目排查图床问题的唯一手段)。
* */
object ImageFallbackInterceptor : Interceptor {

    /*
    * 单次尝试的读超时(毫秒)
    *
    * ⚠️ 必须**远小于** `applicationOkHttpClient` 的 callTimeout(60s),
    *    否则第一个候选会把预算吃光(见类注释 ③ 的探针数据)。
    *
    * 用 internal 暴露给测试:这个数字与 callTimeout 的**大小关系**是
    * 兜底能否生效的前提,写成断言比靠注释可靠。
    * */
    internal const val PER_ATTEMPT_READ_TIMEOUT_MILLIS = 8_000

    /*
    * 单次尝试的连接超时(毫秒)
    *
    * 连接阶段挂起与"连上但不发数据"是两种不同的卡法,都要各自设短超时。
    * */
    internal const val PER_ATTEMPT_CONNECT_TIMEOUT_MILLIS = 5_000

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalUrl = request.url.toString()

        //非静态资源:原样透传,不介入
        if (!StaticResourceSources.isStaticResource(originalUrl)) {
            return chain.proceed(request)
        }

        val candidates = StaticResourceSources.candidateUrls(originalUrl)

        /*
        * 第一个候选就是原 URL,先直接试它(最常见的情况:主图床正常,一次成功)。
        * 失败后依次尝试其余候选。
        * */
        val failures = mutableListOf<String>()

        candidates.forEachIndexed { index, candidateUrl ->
            try {
                val attempt = if (index == 0) {
                    request
                } else {
                    request.newBuilder().url(candidateUrl).build()
                }

                /*
                * ⚠️ 每次尝试都设**独立的短超时**。
                *
                * 不能依赖 client 上的 readTimeout(60s) + callTimeout(60s):
                * 那个预算被整次 call 共享,第一个挂起的候选会把它全吃掉,
                * 后续候选在 0ms 被取消(已用 OkHttp 4.12.0 探针实证)。
                * */
                val attemptChain = chain
                    .withReadTimeout(PER_ATTEMPT_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .withConnectTimeout(PER_ATTEMPT_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

                val response = attemptChain.proceed(attempt)

                if (response.isSuccessful) {
                    return response
                }

                /*
                * HTTP 错误码:关掉响应体再试下一个源。
                * ⚠️ 必须 close —— 否则连接无法复用,最终耗尽连接池。
                * */
                val code = response.code
                response.close()
                failures += "HTTP $code @ $candidateUrl"
            } catch (e: IOException) {
                /*
                * ⚠️ 这里就是原实现的缺陷所在:超时/连接失败走的是这条分支,
                *    原代码没有捕获它,于是"兜底"永远不会被触发。
                * */
                failures += "${e.javaClass.simpleName} @ $candidateUrl: ${e.message}"
            }
        }

        //全部候选都失败:抛出,交给 Coil 的 onError 与占位图
        throw IOException("static resource failed, tried ${candidates.size} source(s): $failures")
    }
}
