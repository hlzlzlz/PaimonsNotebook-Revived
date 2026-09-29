package com.lianyi.paimonsnotebook.ui.widgets.util

/*
* 桌面组件「分页」的纯计算
*
* ## 为什么单独抽出来(这里曾经有两个真实缺陷)
*
* 「快捷启动3*2」与「每日材料3*2」两个组件各自手写了一遍分页逻辑,
* 两份实现**都错**、而且错法不同:
*
*   ① `Shortcut3X2RemoteViews` 把**未校正**的 intent 值写回 DataStore
*      (intent 没有该 extra 时就是 **-1**),且它的 clamp **只有下界没有上界**
*      ⇒ 当快捷项变少、存下的页号 ≥ 总页数时,
*        `items.subList(page * count, min(...))` 的 fromIndex > toIndex,
*        **抛 IndexOutOfBoundsException**(发生在系统驱动的小组件更新路径上)。
*
*   ② `DailyMaterial3X2RemoteViews` **读**的是「快捷启动」的偏好键、
*      **写**的是自己的键 ⇒ 读写不对称,读到的是另一个组件的页号(串页)。
*
* 两处都属于「编译期完全看不见、页面照常渲染、只是内容不对/偶发崩溃」,
* 故把这段逻辑收成一个纯函数并加单测 —— 放在组件类里就只能靠真机肉眼发现。
*
* ⚠️ 不依赖任何 Android API,可直接在 JVM 单测里跑。
* */
object AppWidgetPageMath {

    /*
    * 总页数
    *
    * ⚠️ **至少返回 1**,即使 itemCount 为 0。
    *    原实现是 `size / count + if (size % count == 0) 0 else 1`,
    *    在列表为空时得 0,界面会显示「1/0」这种明显错误的页码。
    *    空列表表现成「1/1(空)」比「1/0」合理。
    *
    * @param itemCount 条目总数
    * @param pageSize  每页条数(必须为正)
    * */
    fun pageCount(itemCount: Int, pageSize: Int): Int {
        require(pageSize > 0) { "pageSize 必须为正,实际 $pageSize" }

        if (itemCount <= 0) return 1

        return (itemCount + pageSize - 1) / pageSize
    }

    /*
    * 决定当前该显示第几页(0 基)
    *
    * 取值优先级:
    *   1. intent 带来的页号(用户刚点了上一页/下一页)——**且必须在合法范围内**
    *   2. DataStore 里存的页号(上次点的结果)——同样要合法
    *   3. 兜底 0
    *
    * ⚠️ 三条都要做**上下界**检查。原实现只判 `page < 0`,
    *    于是「存下的页号 ≥ 总页数」(快捷项被删/开关变化使列表变短)会直接越界。
    *
    * ⚠️ 返回值**必定**落在 `0 until pageCount`,调用方可直接拿去乘 pageSize。
    *
    * @param intentPage  Intent 里的页号;没有该 extra 时传负数
    * @param storedPage  DataStore 里存的页号
    * @param pageCount   总页数,来自 [pageCount]
    * */
    fun resolvePage(intentPage: Int, storedPage: Int, pageCount: Int): Int {
        if (pageCount <= 1) return 0

        if (intentPage in 0 until pageCount) return intentPage

        return if (storedPage in 0 until pageCount) storedPage else 0
    }

    /*
    * 取当前页应显示的条目
    *
    * ⚠️ 无论 page 传什么都不会抛异常 —— 这是本函数存在的**主要理由**:
    *    原实现是 `items.subList(page * count, if (end >= size) size else end)`,
    *    page 越界时 fromIndex > toIndex 直接崩。
    *    这里先把 from 夹到 `0..size`,再令 to = min(from + pageSize, size),
    *    因此恒有 from <= to。
    *
    * @param items    全部条目
    * @param page     页号(越界也安全)
    * @param pageSize 每页条数
    * */
    fun <T> sliceForPage(items: List<T>, page: Int, pageSize: Int): List<T> {
        require(pageSize > 0) { "pageSize 必须为正,实际 $pageSize" }

        if (items.isEmpty()) return emptyList()

        val from = (page.toLong() * pageSize).coerceIn(0L, items.size.toLong()).toInt()
        val to = (from + pageSize).coerceAtMost(items.size)

        return items.subList(from, to)
    }
}
