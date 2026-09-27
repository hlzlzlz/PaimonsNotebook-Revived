package com.lianyi.paimonsnotebook.common.util.abyss

/*
* 深境螺旋的两级导航映射(纯函数,无 Android 依赖)
*
* ## 为什么单独抽出来
*
* 1.8.29 把原先平铺的 10 个标签收成「4 板块 + 板块内子页」。这个改动会
* **重新编号所有索引**,而本项目的索引与内容分发是硬绑定的 ——
* AGENTS.md 里已三次记录同类事故:
*   "tab index 与 load 分支硬绑定,插中间会让既有编号整体位移、
*    极易漏改一处而静默串页"
*
* 这类 bug 的特征是**编译期完全看不见、页面照常渲染、只是内容不对**,
* 所以必须抽成纯函数 + 单测钉住。放在 Compose 文件里(原先是 private fun)
* 就只能靠真机肉眼发现。
*
* ## 结构
*
*   板块(section)        子页(subIndex)
*   ─────────────────────────────────────
*   我的战绩              (无 —— 用"期内切换"代替)
*   全服统计              全服总览/出场率/使用率/配队/持有率
*   配装                  角色配装/武器装备
*   历史                  (无 —— 本地快照)
* */
object AbyssNavigation {

    // ── 板块(与 AbyssScreenViewModel.tabs 下标一一对应) ──
    const val SECTION_RECORD = 0
    const val SECTION_STATISTICS = 1
    const val SECTION_COLLOCATION = 2
    const val SECTION_HISTORY = 3

    // ── 全服统计的子页 ──
    const val STAT_OVERVIEW = 0
    const val STAT_APPEARANCE = 1
    const val STAT_USAGE = 2
    const val STAT_TEAM = 3
    const val STAT_HOLDING = 4

    // ── 配装的子页 ──
    const val COLLOCATION_AVATAR = 0
    const val COLLOCATION_WEAPON = 1

    /*
    * 内容区标识
    *
    * 用 enum 而非字符串:字符串拼错是编译期看不见的(与索引写错同一类问题),
    * enum 让"某个 key 无人产出"或"两个状态产出同一 key"都能被穷尽性检查发现。
    * */
    enum class ContentKey {
        RECORD_CURRENT,
        RECORD_PREVIOUS,
        STAT_OVERVIEW,
        STAT_APPEARANCE,
        STAT_USAGE,
        STAT_TEAM,
        STAT_HOLDING,
        COLLOCATION_AVATAR,
        COLLOCATION_WEAPON,
        HISTORY
    }

    /*
    * 由当前导航状态推出内容区标识
    *
    * ⚠️ 必须**完全由参数决定**,不读任何外部状态 —— 这样才能单测。
    *
    * ⚠️ 同一个板块内,凡是"内容不同"的状态必须产出**不同的 key**。
    *    "我的战绩"的本期/上期就是典型:两者板块下标相同(都是 SECTION_RECORD),
    *    若 key 只取板块,切期数时 Crossfade 不会触发、内容不刷新。
    *    这正是把本函数抽出来的直接原因。
    *
    * @param section 当前板块
    * @param recordIsPrevious 「我的战绩」是否显示上期
    * @param statisticsSubIndex 「全服统计」的子页下标(板块不符时忽略)
    * @param collocationSubIndex 「配装」的子页下标(板块不符时忽略)
    * */
    fun contentKeyFor(
        section: Int,
        recordIsPrevious: Boolean,
        statisticsSubIndex: Int,
        collocationSubIndex: Int
    ): ContentKey = when (section) {
        SECTION_RECORD ->
            if (recordIsPrevious) ContentKey.RECORD_PREVIOUS
            else ContentKey.RECORD_CURRENT

        SECTION_STATISTICS -> when (statisticsSubIndex) {
            STAT_OVERVIEW -> ContentKey.STAT_OVERVIEW
            STAT_APPEARANCE -> ContentKey.STAT_APPEARANCE
            STAT_USAGE -> ContentKey.STAT_USAGE
            STAT_TEAM -> ContentKey.STAT_TEAM
            STAT_HOLDING -> ContentKey.STAT_HOLDING
            //越界兜底:宁可显示总览,也不要因为下标异常而整块空白
            else -> ContentKey.STAT_OVERVIEW
        }

        SECTION_COLLOCATION -> when (collocationSubIndex) {
            COLLOCATION_WEAPON -> ContentKey.COLLOCATION_WEAPON
            else -> ContentKey.COLLOCATION_AVATAR
        }

        //历史(含未知板块兜底)
        else -> ContentKey.HISTORY
    }

    /*
    * 「我的战绩」的期数 -> 深渊接口的 schedule_type
    *
    * 服务端约定:1=本期 2=上期。
    * 抽成函数而不是让调用方写 `if (recordIsPrevious) "2" else "1"` ——
    * 这个映射写反了同样编译期看不见(只是显示错期数)。
    * */
    fun scheduleTypeFor(recordIsPrevious: Boolean): String =
        if (recordIsPrevious) "2" else "1"

    /*
    * 当前板块是否有子页标签
    * */
    fun hasSubTabs(section: Int): Boolean =
        section == SECTION_STATISTICS || section == SECTION_COLLOCATION

    /*
    * 「持有率」的期数切换是否应当隐藏
    *
    * 持有率固定是"本期 vs 上期环比",与期数开关无关 ——
    * 显示一个点了没反应的开关比不显示更糟。
    * */
    fun shouldShowPeriodSwitch(section: Int, statisticsSubIndex: Int): Boolean =
        when (section) {
            SECTION_RECORD -> true
            SECTION_STATISTICS -> statisticsSubIndex != STAT_HOLDING
            SECTION_COLLOCATION -> true
            else -> false
        }
}
