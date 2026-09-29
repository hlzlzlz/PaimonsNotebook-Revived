package com.lianyi.paimonsnotebook

import com.lianyi.paimonsnotebook.ui.widgets.util.AppWidgetPageMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
* 桌面组件分页计算
*
* ## 这两个组件此前**零测试覆盖**,而两个缺陷都属于"编译期看不见"
*
* ① `Shortcut3X2RemoteViews` 把**未校正**的 intent 值写回 DataStore
*    (无 extra 时是 **-1**),且 clamp **只有下界**:
*    ```
*    val page = if (tempValue < 0) { ...clamp... } else { tempValue }
*    PreferenceKeys.AppWidgetShortcutCurrentPage.editValue(tempValue)  // ← 写错变量
*    ...
*    val start = page * count                                  // ← 无上界保护
*    val list = items.subList(start, if (end >= items.size) items.size else end)
*    ```
*    ⇒ **当存下的页号 ≥ 总页数时, fromIndex > toIndex, 抛 IndexOutOfBoundsException**,
*      而这条路径由系统定时回调驱动(异常会静默杀进程)。
*
* ② `DailyMaterial3X2RemoteViews` **读**的是「快捷启动」的键、
*    **写**的是自己的键 ⇒ 读写不对称, 自己存的页号读不回来, 两个组件串页。
*
* 现把逻辑收到 `AppWidgetPageMath` 纯函数里, 用本文件钉住。
* */
class AppWidgetPageMathTest {

    // ============ pageCount ============

    @Test
    fun `整除时页数正确`() {
        assertEquals(2, AppWidgetPageMath.pageCount(itemCount = 10, pageSize = 5))
        assertEquals(1, AppWidgetPageMath.pageCount(itemCount = 5, pageSize = 5))
    }

    @Test
    fun `有余数时向上取整`() {
        assertEquals(3, AppWidgetPageMath.pageCount(itemCount = 11, pageSize = 5))
        assertEquals(2, AppWidgetPageMath.pageCount(itemCount = 6, pageSize = 5))
    }

    /*
    * 空列表也必须**至少 1 页**。
    *
    * 原实现 `0 / 5 + if (0 % 5 == 0) 0 else 1` = 0 ⇒ 界面显示「1/0」,
    * 是个明显说不过去的页码。表现成「1/1(空)」更合理。
    * */
    @Test
    fun `空列表也返回至少一页`() {
        assertEquals(
            "空列表应显示 1 页而不是 0 页(否则界面出现 1/0)",
            1,
            AppWidgetPageMath.pageCount(itemCount = 0, pageSize = 5)
        )
    }

    @Test
    fun `每页条数为1时页数等于条目数`() {
        assertEquals(7, AppWidgetPageMath.pageCount(itemCount = 7, pageSize = 1))
    }

    // ============ resolvePage ============

    /*
    * 🔴 核心用例(对应缺陷①的越界成因)。
    *
    * 存下的页号 ≥ 总页数时**必须**回落到合法值。
    * 原实现只判 `tv < 0`, 于是 `tv = 5` / `maxPage = 2` 会被原样返回,
    * 调用方 `page * count` 直接越界。
    *
    * 反向验证: 把 resolvePage 的 `storedPage in 0 until pageCount` 改成
    * `storedPage >= 0` ⇒ 本用例必须 FAILED。
    * */
    @Test
    fun `存下的页号超出总页数时必须回落`() {
        val page = AppWidgetPageMath.resolvePage(
            intentPage = -1,      // 本次是系统定时刷新, 没有 intent
            storedPage = 5,       // 快捷项被删后残留的旧页号
            pageCount = 2         // 现在只剩 2 页
        )

        assertEquals("越界的存值必须回落为 0, 否则 subList 会崩", 0, page)
        assertTrue(
            "返回值必须落在 0 until pageCount 内",
            page in 0 until 2
        )
    }

    /*
    * 原实现会把 -1 持久化下来。这里钉住"负值一律不可信"。
    * */
    @Test
    fun `存下的负页号必须回落`() {
        assertEquals(
            0,
            AppWidgetPageMath.resolvePage(intentPage = -1, storedPage = -1, pageCount = 3)
        )
    }

    /*
    * intent 带来的页号(用户刚点的)优先 —— 这是原有行为, 必须保留。
    * */
    @Test
    fun `intent页号优先于存值`() {
        assertEquals(
            2,
            AppWidgetPageMath.resolvePage(intentPage = 2, storedPage = 0, pageCount = 3)
        )
    }

    /*
    * 但 intent 页号**也要**做上界检查: 用户点了「下一页」后
    * 快捷项恰好被删, 该 intent 就可能已越界。
    * */
    @Test
    fun `intent页号越界时回落到存值`() {
        assertEquals(
            "intent 越界时应退回存值而不是原样返回",
            1,
            AppWidgetPageMath.resolvePage(intentPage = 9, storedPage = 1, pageCount = 2)
        )
    }

    @Test
    fun `intent与存值都越界时回落到0`() {
        assertEquals(
            0,
            AppWidgetPageMath.resolvePage(intentPage = 9, storedPage = 8, pageCount = 2)
        )
    }

    @Test
    fun `只有一页时恒为0`() {
        assertEquals(
            0,
            AppWidgetPageMath.resolvePage(intentPage = 5, storedPage = 3, pageCount = 1)
        )
    }

    // ============ sliceForPage ============

    /*
    * 🔴 核心用例(对应缺陷①的直接后果)。
    *
    * 这些越界输入在原实现里**全部会抛 IndexOutOfBoundsException**
    * (fromIndex > toIndex)。这里要求"不抛且返回合理结果"。
    *
    * 反向验证: 把 sliceForPage 换回
    * `items.subList(page * pageSize, minOf((page+1)*pageSize, items.size))`
    * ⇒ 本用例必须 FAILED。
    * */
    @Test
    fun `越界页号不抛异常`() {
        val items = (1..7).toList()   // 7 个, 每页 5 ⇒ 共 2 页

        // 原实现在这三种输入下都会崩
        val cases = listOf(
            5 to "远大于总页数",
            Int.MAX_VALUE to "极大值",
            -1 to "负值"
        )

        cases.forEach { (page, why) ->
            val result = AppWidgetPageMath.sliceForPage(items, page, pageSize = 5)
            assertTrue(
                "$why(page=$page) 不应抛异常, 且结果须在合法范围。实际: $result",
                result.size <= 5
            )
        }
    }

    @Test
    fun `正常分页切分正确`() {
        val items = (1..12).toList()

        assertEquals(listOf(1, 2, 3, 4, 5), AppWidgetPageMath.sliceForPage(items, 0, 5))
        assertEquals(listOf(6, 7, 8, 9, 10), AppWidgetPageMath.sliceForPage(items, 1, 5))
        assertEquals(listOf(11, 12), AppWidgetPageMath.sliceForPage(items, 2, 5))
    }

    @Test
    fun `最后一页不足时返回剩余条目`() {
        val items = (1..11).toList()
        assertEquals(listOf(11), AppWidgetPageMath.sliceForPage(items, 2, 5))
    }

    @Test
    fun `超出末页返回空列表而不是崩溃`() {
        val items = (1..5).toList()
        assertEquals(
            "第 5 页(越界)应返回空列表",
            emptyList<Int>(),
            AppWidgetPageMath.sliceForPage(items, 5, 5)
        )
    }

    /*
    * 空列表: 每日材料组件在"本周没有紫色/橙色材料"时会走到这里。
    * */
    @Test
    fun `空列表返回空且不崩`() {
        val empty: List<Int> = emptyList()

        assertEquals(
            emptyList<Int>(),
            AppWidgetPageMath.sliceForPage(empty, 0, 15)
        )
        assertEquals(
            emptyList<Int>(),
            AppWidgetPageMath.sliceForPage(empty, 3, 15)
        )
    }

    /*
    * 🔴 与「快捷启动」场景完全一致的复现: 组件里是从 5 个快捷项里删掉几个,
    *    页号仍是旧的 1(第 2 页), 但列表只剩 3 个(共 1 页)。
    *
    * 这正是那个崩溃的真实触发路径 —— 不是构造出来的极端值。
    * */
    @Test
    fun `快捷项减少后旧页号不会导致崩溃`() {
        val count = 5
        val itemsAfterDeletion = (1..3).toList()        // 用户删到只剩 3 个
        val storedPageFromBefore = 1                     // 之前停在第 2 页

        val maxPage = AppWidgetPageMath.pageCount(itemsAfterDeletion.size, count)
        assertEquals("3 个条目、每页 5 个 ⇒ 1 页", 1, maxPage)

        val page = AppWidgetPageMath.resolvePage(-1, storedPageFromBefore, maxPage)
        assertEquals("旧页号必须被校正回 0", 0, page)

        // 关键: 校正后的页号拿去切片, 必须不崩
        val shown = AppWidgetPageMath.sliceForPage(itemsAfterDeletion, page, count)
        assertEquals(listOf(1, 2, 3), shown)
    }

    @Test
    fun `pageSize非正时快速失败`() {
        listOf(0, -1).forEach { bad ->
            try {
                AppWidgetPageMath.pageCount(10, bad)
                throw AssertionError("pageSize=$bad 应当抛异常")
            } catch (expected: IllegalArgumentException) {
                // 符合预期
            }
        }
    }
}
