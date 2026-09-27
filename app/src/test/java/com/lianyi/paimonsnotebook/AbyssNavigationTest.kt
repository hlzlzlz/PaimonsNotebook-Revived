package com.lianyi.paimonsnotebook

import com.lianyi.paimonsnotebook.common.util.abyss.AbyssNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
* 深境螺旋两级导航的映射契约
*
* 1.8.29 把平铺的 10 个标签收成「4 板块 + 板块内子页」,这会**重新编号**
* 所有索引。本项目已三次因索引/映射错位出现"编译通过、页面正常、内容不对"
* 的静默串页事故(见 AGENTS.md「新增 tab 一律追加在末尾」),故这里把映射
* 钉死。
*
* 这些用例全部针对**纯函数**,不依赖 Android,可在 JVM 直接跑。
* */
class AbyssNavigationTest {

    private fun key(
        section: Int,
        recordIsPrevious: Boolean = false,
        statisticsSubIndex: Int = 0,
        collocationSubIndex: Int = 0
    ) = AbyssNavigation.contentKeyFor(
        section = section,
        recordIsPrevious = recordIsPrevious,
        statisticsSubIndex = statisticsSubIndex,
        collocationSubIndex = collocationSubIndex
    )

    /*
    * 本测试是抽出 AbyssNavigation 的**直接原因**。
    *
    * 「我的战绩」的本期与上期属于**同一个板块**(SECTION_RECORD),
    * 若内容标识只取板块,Crossfade 的 targetState 不变 ⇒
    * 用户点"上期"时**内容不刷新**(还停在本期)。
    * 这个 bug 编译期看不见,页面也不报错。
    *
    * 反向验证:把 contentKeyFor 的 SECTION_RECORD 分支改成
    * 恒定返回 RECORD_CURRENT ⇒ 本用例必须 FAILED。
    * */
    @Test
    fun `同一板块下本期与上期必须产出不同的内容标识`() {
        val current = key(AbyssNavigation.SECTION_RECORD, recordIsPrevious = false)
        val previous = key(AbyssNavigation.SECTION_RECORD, recordIsPrevious = true)

        assertNotEquals(
            "本期与上期必须有不同的内容标识,否则切期数时 Crossfade 不触发、内容不刷新",
            current,
            previous
        )
    }

    @Test
    fun `我的战绩的期数映射到正确的内容标识`() {
        assertEquals(
            AbyssNavigation.ContentKey.RECORD_CURRENT,
            key(AbyssNavigation.SECTION_RECORD, recordIsPrevious = false)
        )
        assertEquals(
            AbyssNavigation.ContentKey.RECORD_PREVIOUS,
            key(AbyssNavigation.SECTION_RECORD, recordIsPrevious = true)
        )
    }

    /*
    * 期数 -> schedule_type 的映射写反了同样编译期看不见,只是显示错期数。
    * 服务端约定:1=本期 2=上期。
    * */
    @Test
    fun `期数映射到接口的 schedule_type`() {
        assertEquals("1", AbyssNavigation.scheduleTypeFor(recordIsPrevious = false))
        assertEquals("2", AbyssNavigation.scheduleTypeFor(recordIsPrevious = true))
    }

    /*
    * 5 个统计子页必须各自映射到**唯一**的内容标识。
    * 若两个子页映射到同一个 key,表现为"点 A 显示 B"(或点了没反应)。
    * */
    @Test
    fun `全服统计的五个子页各自映射到唯一内容标识`() {
        val expected = listOf(
            AbyssNavigation.STAT_OVERVIEW to AbyssNavigation.ContentKey.STAT_OVERVIEW,
            AbyssNavigation.STAT_APPEARANCE to AbyssNavigation.ContentKey.STAT_APPEARANCE,
            AbyssNavigation.STAT_USAGE to AbyssNavigation.ContentKey.STAT_USAGE,
            AbyssNavigation.STAT_TEAM to AbyssNavigation.ContentKey.STAT_TEAM,
            AbyssNavigation.STAT_HOLDING to AbyssNavigation.ContentKey.STAT_HOLDING,
        )

        expected.forEach { (subIndex, expectedKey) ->
            assertEquals(
                "全服统计子页 $subIndex 映射错误",
                expectedKey,
                key(AbyssNavigation.SECTION_STATISTICS, statisticsSubIndex = subIndex)
            )
        }

        //唯一性:5 个子页不能有两个映射到同一个 key
        val keys = expected.map { key(AbyssNavigation.SECTION_STATISTICS, statisticsSubIndex = it.first) }
        assertEquals("5 个统计子页必须映射到 5 个互不相同的 key", 5, keys.toSet().size)
    }

    @Test
    fun `配装的两个子页各自映射到唯一内容标识`() {
        assertEquals(
            AbyssNavigation.ContentKey.COLLOCATION_AVATAR,
            key(AbyssNavigation.SECTION_COLLOCATION, collocationSubIndex = AbyssNavigation.COLLOCATION_AVATAR)
        )
        assertEquals(
            AbyssNavigation.ContentKey.COLLOCATION_WEAPON,
            key(AbyssNavigation.SECTION_COLLOCATION, collocationSubIndex = AbyssNavigation.COLLOCATION_WEAPON)
        )
    }

    /*
    * 跨板块的 key 必须互不相同。
    *
    * 若不同板块产出了同名 key(例如"全服总览"与"角色配装"共用),
    * 从 A 切到 B 时 Crossfade 会认为内容没变而不刷新。
    * */
    @Test
    fun `所有板块与子页组合产出的内容标识两两不同`() {
        val all = buildList {
            add(key(AbyssNavigation.SECTION_RECORD, recordIsPrevious = false))
            add(key(AbyssNavigation.SECTION_RECORD, recordIsPrevious = true))
            for (i in 0..4) {
                add(key(AbyssNavigation.SECTION_STATISTICS, statisticsSubIndex = i))
            }
            for (i in 0..1) {
                add(key(AbyssNavigation.SECTION_COLLOCATION, collocationSubIndex = i))
            }
            add(key(AbyssNavigation.SECTION_HISTORY))
        }

        assertEquals(
            "所有内容标识必须两两不同,否则切换时不刷新。实际: $all",
            all.size,
            all.toSet().size
        )
    }

    /*
    * 板块下标必须与 tabs 数组严格对齐 —— 这是最容易在合并/插入标签时错位的地方。
    * 断言「4 个板块互不相同」+ 具体编号,改动时必须同步改这里。
    * */
    @Test
    fun `四个板块的编号连续且唯一`() {
        val sections = listOf(
            AbyssNavigation.SECTION_RECORD,
            AbyssNavigation.SECTION_STATISTICS,
            AbyssNavigation.SECTION_COLLOCATION,
            AbyssNavigation.SECTION_HISTORY,
        )

        assertEquals("板块编号必须从 0 连续", listOf(0, 1, 2, 3), sections)
        assertEquals(4, sections.toSet().size)
    }

    /*
    * 有子页标签的板块必须与 UI 实际渲染的一致。
    * 「我的战绩」与「历史」没有子页 —— 若这里判错,
    * UI 会渲染一个空标签行(虽有占位但语义不对)。
    * */
    @Test
    fun `只有全服统计与配装有子页标签`() {
        assertFalse(
            "我的战绩没有子页(期数用切换按钮,不是标签)",
            AbyssNavigation.hasSubTabs(AbyssNavigation.SECTION_RECORD)
        )
        assertTrue(AbyssNavigation.hasSubTabs(AbyssNavigation.SECTION_STATISTICS))
        assertTrue(AbyssNavigation.hasSubTabs(AbyssNavigation.SECTION_COLLOCATION))
        assertFalse(
            "历史没有子页",
            AbyssNavigation.hasSubTabs(AbyssNavigation.SECTION_HISTORY)
        )
    }

    /*
    * 持有率固定是"本期 vs 上期环比",与期数开关无关。
    * 若在这里显示了开关,用户点了没反应 —— 比不显示更糟。
    *
    * 反向验证:去掉 `statisticsSubIndex != STAT_HOLDING` 判断 ⇒ 本用例 FAILED。
    * */
    @Test
    fun `持有率子页不显示期数切换`() {
        assertFalse(
            "持有率固定为本期与上期环比,不该有期数开关",
            AbyssNavigation.shouldShowPeriodSwitch(
                section = AbyssNavigation.SECTION_STATISTICS,
                statisticsSubIndex = AbyssNavigation.STAT_HOLDING
            )
        )

        //其余统计子页都应显示
        for (sub in 0..3) {
            assertTrue(
                "统计子页 $sub 应显示期数切换",
                AbyssNavigation.shouldShowPeriodSwitch(
                    section = AbyssNavigation.SECTION_STATISTICS,
                    statisticsSubIndex = sub
                )
            )
        }
    }

    @Test
    fun `我的战绩与配装都显示期数切换`() {
        assertTrue(
            AbyssNavigation.shouldShowPeriodSwitch(
                section = AbyssNavigation.SECTION_RECORD,
                statisticsSubIndex = 0
            )
        )
        assertTrue(
            AbyssNavigation.shouldShowPeriodSwitch(
                section = AbyssNavigation.SECTION_COLLOCATION,
                statisticsSubIndex = 0
            )
        )
        assertFalse(
            "历史页无需期数切换",
            AbyssNavigation.shouldShowPeriodSwitch(
                section = AbyssNavigation.SECTION_HISTORY,
                statisticsSubIndex = 0
            )
        )
    }

    /*
    * 越界兜底:下标异常时不能崩、也不该整块空白。
    * 宁可显示一个能看的默认页。
    * */
    @Test
    fun `越界的子页下标回退到默认内容而不是崩溃`() {
        assertEquals(
            AbyssNavigation.ContentKey.STAT_OVERVIEW,
            key(AbyssNavigation.SECTION_STATISTICS, statisticsSubIndex = 99)
        )
        assertEquals(
            AbyssNavigation.ContentKey.COLLOCATION_AVATAR,
            key(AbyssNavigation.SECTION_COLLOCATION, collocationSubIndex = 99)
        )
        assertEquals(
            AbyssNavigation.ContentKey.HISTORY,
            key(section = 99)
        )
    }

    /*
    * ⚠️ 关键隔离性:统计子页下标**不应**影响"我的战绩"的内容标识。
    * 两个板块各有各的下标状态,若实现里误用了对方的参数,
    * 会出现"在战绩页切统计子页,战绩内容变了"这类诡异串扰。
    * */
    @Test
    fun `各板块的子页下标互不干扰`() {
        //战绩页:统计/配装下标怎么变,内容标识都不该变
        val base = key(AbyssNavigation.SECTION_RECORD, recordIsPrevious = false)

        for (sub in 0..4) {
            assertEquals(
                "统计子页下标不应影响战绩页内容",
                base,
                key(
                    AbyssNavigation.SECTION_RECORD,
                    recordIsPrevious = false,
                    statisticsSubIndex = sub
                )
            )
        }

        for (sub in 0..1) {
            assertEquals(
                "配装子页下标不应影响战绩页内容",
                base,
                key(
                    AbyssNavigation.SECTION_RECORD,
                    recordIsPrevious = false,
                    collocationSubIndex = sub
                )
            )
        }

        //期数不应影响统计页内容
        val statBase = key(AbyssNavigation.SECTION_STATISTICS, statisticsSubIndex = 2)
        assertEquals(
            "期数开关不应改变统计页的内容标识(期数由数据决定,不由 key 决定)",
            statBase,
            key(
                AbyssNavigation.SECTION_STATISTICS,
                recordIsPrevious = true,
                statisticsSubIndex = 2
            )
        )
    }
}
