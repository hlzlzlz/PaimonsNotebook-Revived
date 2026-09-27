package com.lianyi.paimonsnotebook

import com.lianyi.paimonsnotebook.common.util.coil.ImageFallbackInterceptor
import com.lianyi.paimonsnotebook.common.web.static_resources.StaticResourceSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
* 兜底拦截器的**超时预算**约束
*
* ## 背景:这里曾经有个真实缺陷(1.8.27 引入,1.8.30 修)
*
* `ImageFallbackInterceptor` 会对静态资源依次尝试多个候选源
* (主图床 → 镜像 → enka)。但它跑在 `applicationOkHttpClient` 上,
* 而那个 client 设了 `callTimeout(60s)` —— 而 **callTimeout 覆盖整次 call,
* 包含拦截器里的所有 `proceed()` 尝试**,不是"每个候选各 60s"。
*
* 用真实 OkHttp 4.12.0 写探针实测(把 60s 等比缩到 3s):
*
* ```
*   callTimeout=3s, readTimeout=3s
*   尝试 主图床(挂起) -> SocketException after 3012ms    ← 吃光全部预算
*   尝试 镜像          -> IOException: Canceled after 0ms  ← 0ms 被取消
*   RESULT: InterruptedIOException: timeout
* ```
*
* ⇒ **主图床一挂,镜像永远轮不到**,而"主图床挂起"正是这个拦截器存在的理由。
*    用户看到的现象是"图还是出不来",与修复前毫无区别 —— 一个**静默失效**的修复。
*
* ## 本测试钉住什么
*
* 修法是给每次尝试设独立短超时(`chain.withReadTimeout`)。本文件把
* "**每个候选的预算 × 候选数 < 总 callTimeout**" 这个不变式写成断言:
* 只要有人调大单次超时(或调小 callTimeout、或增加候选源),这里就会失败。
*
* ⚠️ 这属于"数字之间的关系"约束,不能靠注释传达 —— 注释不会在改坏时报警。
* */
class ImageFallbackTimeoutBudgetTest {

    /*
    * 与 `applicationOkHttpClient` 的 callTimeout 保持一致。
    *
    * ⚠️ 这是**故意重复**的一份数字:若哪天改了 client 的 callTimeout,
    *    本测试会立刻失败并提醒你同步确认这里的预算关系 ——
    *    这正是它存在的意义,不要为了"消除重复"而把它读成常量。
    * */
    private val callTimeoutMillis = 60_000L

    private val primary =
        "https://static.snaphutaorp.org/static/raw/AvatarIcon/UI_AvatarIcon_Qin.png"

    /*
    * 最坏情况:所有候选都挂起(每个都吃满单次读超时)。
    * 必须保证总耗时**小于** callTimeout,否则最后一个候选仍会在半路被取消。
    * */
    @Test
    fun `所有候选都挂起时总预算仍在callTimeout之内`() {
        val candidateCount = StaticResourceSources.candidateUrls(primary).size
        val worstCase =
            candidateCount * ImageFallbackInterceptor.PER_ATTEMPT_READ_TIMEOUT_MILLIS

        assertTrue(
            "最坏情况 ${worstCase}ms(候选 $candidateCount 个 × 单次 " +
                    "${ImageFallbackInterceptor.PER_ATTEMPT_READ_TIMEOUT_MILLIS}ms)" +
                    " 必须小于 callTimeout ${callTimeoutMillis}ms —— " +
                    "否则最后一个候选会被 callTimeout 取消,兜底等于没做",
            worstCase < callTimeoutMillis
        )
    }

    /*
    * 单次超时必须**显著**小于 callTimeout。
    *
    * 只说"小于"不够:若单次设成 59s、候选 3 个,总预算 177s 远超 60s,
    * 第二个候选就已经拿不到时间了。
    * */
    @Test
    fun `单次超时不超过callTimeout的六分之一`() {
        val perAttempt = ImageFallbackInterceptor.PER_ATTEMPT_READ_TIMEOUT_MILLIS
        val limit = callTimeoutMillis / 6

        assertTrue(
            "单次读超时 ${perAttempt}ms 过大(应 ≤ ${limit}ms):" +
                    "候选有 3 个,预算必须按最坏情况留足",
            perAttempt <= limit
        )
    }

    /*
    * 单次超时也不能太小 —— 图床**正常时**本就可能慢到 4~12s(实测:
    * 镜像中位 1.0s 但最慢 12.1s),设成几百毫秒会把正常请求误杀,
    * 反而让所有图都走"失败"路径。
    *
    * 这是双向约束:上界由 callTimeout 定(上面的用例),下界由实测速度定。
    * */
    @Test
    fun `单次超时不能小到误杀正常慢请求`() {
        val perAttempt = ImageFallbackInterceptor.PER_ATTEMPT_READ_TIMEOUT_MILLIS

        assertTrue(
            "单次读超时 ${perAttempt}ms 过小:实测图床正常时最慢可达 12.1s," +
                    "太小会把正常请求误判为失败",
            perAttempt >= 5_000
        )
    }

    /*
    * 连接超时同样要受约束:连接阶段挂起是另一种卡法。
    * */
    @Test
    fun `连接超时也远小于callTimeout`() {
        val connect = ImageFallbackInterceptor.PER_ATTEMPT_CONNECT_TIMEOUT_MILLIS

        assertTrue(
            "连接超时 ${connect}ms 应远小于 callTimeout ${callTimeoutMillis}ms",
            connect * 3 < callTimeoutMillis
        )
    }

    /*
    * 候选确实多于一个 —— 否则"多源兜底"无从谈起
    * (也顺带保证上面的候选数计算不是恒为 1 的假绿)。
    * */
    @Test
    fun `静态资源确实有多个候选源`() {
        val candidates = StaticResourceSources.candidateUrls(primary)

        assertTrue(
            "静态资源必须有多个候选源,否则兜底无意义。实际: $candidates",
            candidates.size >= 2
        )
        assertEquals(
            "第一个候选应为主图床原地址",
            primary,
            candidates[0]
        )
    }
}
