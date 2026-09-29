package com.lianyi.paimonsnotebook.ui.widgets.remoteviews.util

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.lianyi.paimonsnotebook.R
import com.lianyi.paimonsnotebook.common.database.app_widget_binding.entity.AppWidgetBinding
import com.lianyi.paimonsnotebook.common.extension.data_store.editValue
import com.lianyi.paimonsnotebook.common.util.data_store.PreferenceKeys
import com.lianyi.paimonsnotebook.common.util.data_store.dataStoreValuesFirst
import com.lianyi.paimonsnotebook.ui.screen.home.util.HomeHelper
import com.lianyi.paimonsnotebook.ui.widgets.core.BaseRemoteViews
import com.lianyi.paimonsnotebook.ui.widgets.util.AppWidgetHelper
import com.lianyi.paimonsnotebook.ui.widgets.util.AppWidgetPageMath
import com.lianyi.paimonsnotebook.ui.widgets.widget.AppWidgetCommon3X2

/*
* 快捷启动3*2远端视图
* */
class Shortcut3X2RemoteViews(
    private val appWidgetBinding: AppWidgetBinding
) : BaseRemoteViews(
    appWidgetBinding.appWidgetId,
    AppWidgetCommon3X2::class.java,
    R.layout.widget_layout_shortcut_3_1
) {
    private val count = 5

    override suspend fun onUpdateContent(intent: Intent?): RemoteViews? {
        dataStoreValuesFirst {
            val intentPage =
                intent?.getIntExtra(AppWidgetHelper.PARAM_SHORTCUT_CURRENT_PAGE, -1) ?: -1
            val storedPage = it[PreferenceKeys.AppWidgetShortcutCurrentPage] ?: 0

            //此处默认不启用元数据
            val enableMetadata = it[PreferenceKeys.EnableMetadata] ?: false
            val items = HomeHelper.getShowModalItemData(enableMetadata)

            val maxPage = AppWidgetPageMath.pageCount(items.size, count)
            val page = AppWidgetPageMath.resolvePage(intentPage, storedPage, maxPage)

            /*
            * ⚠️ 必须写**校正后**的 page。
            *
            * 原实现写的是 `tempValue`(intent 未携带该 extra 时为 **-1**),
            * 于是页号被持久化成 -1;下次 intentPage 也是 -1,读回来仍是 -1,
            * 经 resolvePage 会兜底成 0 —— 表面看"还能用",但一旦快捷项减少
            * 使存下的页号超过总页数,就会走到下面 subList 的越界(现已修复)。
            * 姊妹实现 DailyMaterial3X2 写的就是 page,这里对齐。
            * */
            PreferenceKeys.AppWidgetShortcutCurrentPage.editValue(page)

            setOnClickPendingIntent(
                R.id.next,
                changePage(if (page + 1 >= maxPage) page else page + 1)
            )
            setOnClickPendingIntent(R.id.pre, changePage(if (page - 1 < 0) 0 else page - 1))

            setTextViewText(R.id.page_text, "${page + 1}/${maxPage}")

            removeAllViews(R.id.list)

            //越界安全:原实现 `page * count` 在 page 越界时会让 subList 抛
            //IndexOutOfBoundsException(详见 AppWidgetPageMath 注释)
            val list = AppWidgetPageMath.sliceForPage(items, page, count)

            val textIds = mutableListOf(
                R.id.text, R.id.page_text
            )

            val imageIds = mutableListOf(
                R.id.pre_image, R.id.next_image
            )

            list.forEach { itemData ->
                val itemViews =
                    RemoteViews(context.packageName, R.layout.widget_item_layout_shortcut).apply {
                        setImageViewResource(R.id.image, itemData.icon)

                        setTextViewText(R.id.text, itemData.name)

                        val activityIntent = Intent(context, itemData.target)

                        val pendingIntent = PendingIntent.getActivity(
                            context,
                            AppWidgetHelper.APPWIDGET_ACTIVITY_REQUEST_CODE,
                            activityIntent,
                            PendingIntent.FLAG_IMMUTABLE
                        )

                        imageIds += R.id.image
                        textIds += R.id.text

                        setOnClickPendingIntent(R.id.container, pendingIntent)
                    }

                addView(R.id.list, itemViews)
            }

            setCommonStyle(
                appWidgetBinding.configuration,
                textIds.toIntArray(),
                imageIds.toIntArray()
            )
        }

        return super.onUpdateContent(intent)
    }

    private suspend fun changePage(page: Int): PendingIntent {
        PreferenceKeys.AppWidgetShortcutCurrentPage.editValue(page)

        val bundle = Bundle().apply {
            putInt(AppWidgetHelper.PARAM_SHORTCUT_CURRENT_PAGE, page)
        }

        return basePendingIntent(AppWidgetHelper.ACTION_UPDATE_WIDGET, "$page", bundle)
    }
}