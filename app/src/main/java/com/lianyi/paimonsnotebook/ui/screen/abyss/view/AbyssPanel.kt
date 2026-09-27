package com.lianyi.paimonsnotebook.ui.screen.abyss.view

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyi.paimonsnotebook.R
import com.lianyi.paimonsnotebook.common.components.dialog.ConfirmDialog
import com.lianyi.paimonsnotebook.common.components.layout.column.TabBarColumnLayout
import com.lianyi.paimonsnotebook.common.components.loading.ContentLoadingLayout
import com.lianyi.paimonsnotebook.common.extension.modifier.radius.radius
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.AbyssHistoryPage
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.AbyssRecordPage
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.HutaoAvatarCollocationPage
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.HutaoAvatarRatePage
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.HutaoHoldingRatePage
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.HutaoOverviewPage
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.HutaoTeamPage
import com.lianyi.paimonsnotebook.ui.screen.abyss.components.page.HutaoWeaponCollocationPage
import com.lianyi.paimonsnotebook.ui.screen.abyss.viewmodel.AbyssScreenViewModel
import com.lianyi.paimonsnotebook.ui.screen.account.components.dialog.UserGameRolesDialog
import com.lianyi.paimonsnotebook.ui.theme.Black
import com.lianyi.paimonsnotebook.ui.theme.CardBackGroundColor
import com.lianyi.paimonsnotebook.ui.theme.Primary
import com.lianyi.paimonsnotebook.ui.theme.White

/*
* 深境螺旋面板
*
* 从 AbyssScreen 抽出,使其可被「战斗记录」合并页与独立页(AbyssScreen)共用。
*
* statusBarEnabled:合并页里外层已经有状态栏占位,必须传 false 否则会重复留白。
*
* ## 两级标签(1.8.29 按用户要求合并顶部项目)
*
* 原先平铺 10 个标签,横向太长且语义混杂:
*   本期 / 上期 / 全服总览 / 出场率 / 使用率 / 配队 / 持有率 /
*   角色配装 / 武器装备 / 历史
* 现收成 4 个板块 + 板块内子页:
*
*   我的战绩   ← 本期 + 上期(用"期内切换"代替两个标签)
*   全服统计   ← 全服总览 / 出场率 / 使用率 / 配队 / 持有率
*   配装       ← 角色配装 / 武器装备
*   历史       ← 保持独立(本地快照,与网络统计不同源,合并易混淆)
*
* ## 层级说明(重要,改之前先读)
*
* 合并页「战斗记录」里已有**一级**切换(深境螺旋 / 战斗记录,由外层渲染)。
* 因此本面板的 4 个板块是**二级**,与另一侧 RoleCombatPanel 的
* 三标签(剧诗 / 危战 / 全服统计)同层 —— 故这里用 **TabBar** 渲染,
* 两边样式一致才是同一个层级。
*
* 板块内的子页(全服总览 / 出场率 / …)是**三级**,用
* [SubTabChips](浅色小圆角块)渲染,**刻意与 TabBar 长得不一样** ——
* 两行都是"纯文字+选中放大"会让人点错行。
* */
@Composable
internal fun AbyssPanel(
    viewModel: AbyssScreenViewModel,
    statusBarEnabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabBarColumnLayout(
            tabs = viewModel.tabs,
            onTabBarSelect = viewModel::onPageIndexChange,
            tabBarPaddingHorizontal = 12.dp,
            tabBarWeighted = true,
            statusBarEnabled = statusBarEnabled
        ) {
            //子页 chips(仅"全服统计"与"配装"有;其余板块不渲染此行)
            val subTabs = subTabsOf(viewModel)
            if (subTabs.isNotEmpty()) {
                SubTabChips(
                    tabs = subTabs,
                    currentIndex = subTabIndexOf(viewModel),
                    onSelect = { viewModel.onSubTabSelect(it) }
                )
            }

            //角色选择 + 期数切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp, 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .radius(2.dp)
                        .clickable {
                            viewModel.showUserGameRoleDialog()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = viewModel.currentGameRole?.game_uid ?: "",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_down),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                PeriodSwitchIfNeeded(viewModel)
            }

            Crossfade(targetState = contentKeyOf(viewModel), label = "") { key ->
                AbyssSectionContent(viewModel = viewModel, key = key)
            }
        }
    }

    if (viewModel.showUserGameRoleDialog) {
        UserGameRolesDialog(
            onButtonClick = {
                viewModel.dismissUserGameRoleDialog()
            },
            onDismissRequest = viewModel::dismissUserGameRoleDialog,
            onSelectRole = viewModel::onChangeGameRole
        )
    }

    if (viewModel.showConfirmDialog) {
        ConfirmDialog(
            content = "进行验证才能继续进行查询,点击确认前往验证界面",
            onConfirm = viewModel::goValidateScreen,
            onCancel = viewModel::dismissConfirmDialog
        )
    }
}

/*
* 板块内子页的 chips(三级导航)
*
* 为什么不用 TabBar:上一行已经是 TabBar,两行长得一样会点错行。
* 这里用"浅底小圆角块 + 选中反白"的分段样式,与 TabBar 的
* "纯文字 + 选中放大"明显不同。
*
* ⚠️ 必须横向可滚动:5 个子页在 360dp 下勉强放得下,但文案一旦变长
*    (如以后加"角色配装(武器)"这类)就会被裁掉。
*    **不能用 LazyRow** —— 外层 TabBarColumnLayout 用了
*    `Modifier.height(IntrinsicSize.Min)`,Lazy 布局不支持固有尺寸测量会崩。
* */
@Composable
private fun SubTabChips(
    tabs: Array<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, name ->
            val selected = index == currentIndex

            Text(
                text = name,
                fontSize = 13.sp,
                color = if (selected) White else Black,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .radius(4.dp)
                    .background(if (selected) Primary else CardBackGroundColor)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

/*
* 期数切换按钮
*
* ⚠️ 两个板块各用**自己的**开关,不能共用:
*    「我的战绩」切的是"我的哪一期深渊记录",
*    「全服统计 / 配装」切的是"全服统计取哪一期"。
*    共用一个开关的话,在战绩页点一下会把全服统计也一起切走。
*
* "持有率"固定为本期与上期环比,不显示切换。
* */
@Composable
private fun PeriodSwitchIfNeeded(viewModel: AbyssScreenViewModel) {
    when (viewModel.currentPageIndex) {
        AbyssScreenViewModel.SECTION_RECORD -> {
            PeriodSwitch(
                isPrevious = viewModel.recordIsPrevious,
                onClick = viewModel::toggleRecordPeriod
            )
        }

        AbyssScreenViewModel.SECTION_STATISTICS -> {
            if (viewModel.statisticsSubIndex != AbyssScreenViewModel.STAT_HOLDING) {
                PeriodSwitch(
                    isPrevious = viewModel.lastPeriod,
                    onClick = viewModel::togglePeriod
                )
            }
        }

        AbyssScreenViewModel.SECTION_COLLOCATION -> {
            PeriodSwitch(
                isPrevious = viewModel.lastPeriod,
                onClick = viewModel::togglePeriod
            )
        }
    }
}

@Composable
private fun PeriodSwitch(
    isPrevious: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = if (isPrevious) "上期" else "本期",
        fontSize = 14.sp,
        modifier = Modifier
            .radius(2.dp)
            .clickable { onClick() }
            .padding(10.dp, 4.dp)
    )
}

/*
* 内容区
*
* 用 Crossfade 的 key 分发,而不是直接读 currentPageIndex ——
* 合并后"我的战绩"的本期/上期是两个不同内容但板块下标相同,
* 只比较板块会导致切期数时内容不刷新。
* */
@Composable
private fun AbyssSectionContent(
    viewModel: AbyssScreenViewModel,
    key: String
) {
    when (key) {
        CONTENT_RECORD_CURRENT -> AbyssRecordPage(
            abyssData = viewModel.currentAbyssRecord,
            loadingState = viewModel.currentAbyssRecordLoadingState,
            getAvatarFromMetadata = viewModel::getAvatarFromMetadata,
            getMonsterFromMetadata = viewModel::getMonsterFromMetadata
        )

        CONTENT_RECORD_PREVIOUS -> AbyssRecordPage(
            abyssData = viewModel.previousAbyssRecord,
            loadingState = viewModel.previousAbyssRecordLoadingState,
            getAvatarFromMetadata = viewModel::getAvatarFromMetadata,
            getMonsterFromMetadata = viewModel::getMonsterFromMetadata
        )

        CONTENT_STAT_OVERVIEW -> ContentLoadingLayout(
            loadingState = viewModel.overviewLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                HutaoOverviewPage(overview = viewModel.overview)
            }
        )

        CONTENT_STAT_APPEARANCE -> ContentLoadingLayout(
            loadingState = viewModel.appearanceRateLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                HutaoAvatarRatePage(
                    rates = viewModel.appearanceRate,
                    title = "出场率",
                    getAvatar = viewModel::getAvatarFromMetadata
                )
            }
        )

        CONTENT_STAT_USAGE -> ContentLoadingLayout(
            loadingState = viewModel.usageRateLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                HutaoAvatarRatePage(
                    rates = viewModel.usageRate,
                    title = "使用率",
                    getAvatar = viewModel::getAvatarFromMetadata
                )
            }
        )

        CONTENT_STAT_TEAM -> ContentLoadingLayout(
            loadingState = viewModel.teamCombinationLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                HutaoTeamPage(
                    teams = viewModel.teamCombination,
                    getAvatar = viewModel::getAvatarFromMetadata
                )
            }
        )

        CONTENT_STAT_HOLDING -> ContentLoadingLayout(
            loadingState = viewModel.holdingRateLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                HutaoHoldingRatePage(
                    entries = viewModel.holdingRate,
                    getAvatar = viewModel::getAvatarFromMetadata
                )
            }
        )

        CONTENT_COLLOCATION_AVATAR -> ContentLoadingLayout(
            loadingState = viewModel.avatarCollocationLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                HutaoAvatarCollocationPage(
                    collocations = viewModel.avatarCollocation,
                    getAvatar = viewModel::getAvatarFromMetadata,
                    getWeapon = viewModel::getWeaponFromMetadata
                )
            }
        )

        CONTENT_COLLOCATION_WEAPON -> ContentLoadingLayout(
            loadingState = viewModel.weaponCollocationLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                HutaoWeaponCollocationPage(
                    collocations = viewModel.weaponCollocation,
                    getAvatar = viewModel::getAvatarFromMetadata,
                    getWeapon = viewModel::getWeaponFromMetadata
                )
            }
        )

        else -> ContentLoadingLayout(
            loadingState = viewModel.historyLoadingState,
            onRetry = viewModel::retryCurrentPage,
            successContent = {
                AbyssHistoryPage(snapshots = viewModel.abyssHistory)
            }
        )
    }
}

/*
* 当前板块的子页标签(「我的战绩」与「历史」没有子页,返回空数组)
* */
private fun subTabsOf(viewModel: AbyssScreenViewModel): Array<String> =
    when (viewModel.currentPageIndex) {
        AbyssScreenViewModel.SECTION_STATISTICS -> viewModel.statisticsTabs
        AbyssScreenViewModel.SECTION_COLLOCATION -> viewModel.collocationTabs
        else -> emptyArray()
    }

/*
* 当前选中的子页下标
* */
private fun subTabIndexOf(viewModel: AbyssScreenViewModel): Int =
    when (viewModel.currentPageIndex) {
        AbyssScreenViewModel.SECTION_STATISTICS -> viewModel.statisticsSubIndex
        AbyssScreenViewModel.SECTION_COLLOCATION -> viewModel.collocationSubIndex
        else -> 0
    }

/*
* 子页选择分发 —— 各板块的子页有各自的下标状态
* */
private fun AbyssScreenViewModel.onSubTabSelect(index: Int) {
    when (currentPageIndex) {
        AbyssScreenViewModel.SECTION_STATISTICS -> onStatisticsSubIndexChange(index)
        AbyssScreenViewModel.SECTION_COLLOCATION -> onCollocationSubIndexChange(index)
    }
}

//内容区 key(见 AbyssSectionContent 注释)
private const val CONTENT_RECORD_CURRENT = "record_current"
private const val CONTENT_RECORD_PREVIOUS = "record_previous"
private const val CONTENT_STAT_OVERVIEW = "stat_overview"
private const val CONTENT_STAT_APPEARANCE = "stat_appearance"
private const val CONTENT_STAT_USAGE = "stat_usage"
private const val CONTENT_STAT_TEAM = "stat_team"
private const val CONTENT_STAT_HOLDING = "stat_holding"
private const val CONTENT_COLLOCATION_AVATAR = "collocation_avatar"
private const val CONTENT_COLLOCATION_WEAPON = "collocation_weapon"

private fun contentKeyOf(viewModel: AbyssScreenViewModel): String =
    when (viewModel.currentPageIndex) {
        AbyssScreenViewModel.SECTION_RECORD ->
            if (viewModel.recordIsPrevious) CONTENT_RECORD_PREVIOUS
            else CONTENT_RECORD_CURRENT

        AbyssScreenViewModel.SECTION_STATISTICS -> when (viewModel.statisticsSubIndex) {
            AbyssScreenViewModel.STAT_OVERVIEW -> CONTENT_STAT_OVERVIEW
            AbyssScreenViewModel.STAT_APPEARANCE -> CONTENT_STAT_APPEARANCE
            AbyssScreenViewModel.STAT_USAGE -> CONTENT_STAT_USAGE
            AbyssScreenViewModel.STAT_TEAM -> CONTENT_STAT_TEAM
            else -> CONTENT_STAT_HOLDING
        }

        AbyssScreenViewModel.SECTION_COLLOCATION ->
            if (viewModel.collocationSubIndex == AbyssScreenViewModel.COLLOCATION_WEAPON) {
                CONTENT_COLLOCATION_WEAPON
            } else {
                CONTENT_COLLOCATION_AVATAR
            }

        else -> CONTENT_HISTORY
    }

private const val CONTENT_HISTORY = "history"
