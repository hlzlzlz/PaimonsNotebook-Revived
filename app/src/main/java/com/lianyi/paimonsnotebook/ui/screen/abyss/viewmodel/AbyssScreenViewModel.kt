package com.lianyi.paimonsnotebook.ui.screen.abyss.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianyi.paimonsnotebook.common.data.hoyolab.PlayerUid
import com.lianyi.paimonsnotebook.common.data.hoyolab.user.User
import com.lianyi.paimonsnotebook.common.data.hoyolab.user.UserAndUid
import com.lianyi.paimonsnotebook.common.database.PaimonsNotebookDatabase
import com.lianyi.paimonsnotebook.common.database.abyss.entity.AbyssSeasonSnapshot
import com.lianyi.paimonsnotebook.common.database.user.util.AccountHelper
import com.lianyi.paimonsnotebook.common.extension.scope.launchSafeIO
import com.lianyi.paimonsnotebook.common.util.abyss.AbyssNavigation
import com.lianyi.paimonsnotebook.common.util.metadata.genshin.abyss.AbyssSnapshotMapper
import com.lianyi.paimonsnotebook.common.extension.intent.setComponentName
import com.lianyi.paimonsnotebook.common.extension.string.errorNotify
import com.lianyi.paimonsnotebook.common.service.geetest.CardVerificationService
import com.lianyi.paimonsnotebook.common.util.enums.LoadingState
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.common.service.AvatarService
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.common.service.MonsterService
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.common.service.WeaponService
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoAvatarCollocationData
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoAvatarFloorRateData
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoHoldingRateData
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoHoldingRateEntry
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoOverviewData
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoStatisticsClient
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoTeamCombinationData
import com.lianyi.paimonsnotebook.common.web.hutao.statistics.HutaoWeaponCollocationData
import com.lianyi.paimonsnotebook.common.view.HoyolabWebActivity
import com.lianyi.paimonsnotebook.common.web.hoyolab.takumi.binding.UserGameRoleData
import com.lianyi.paimonsnotebook.common.web.hoyolab.takumi.game_record.GameRecordClient
import com.lianyi.paimonsnotebook.common.web.hoyolab.takumi.game_record.abyss.SpiralAbyssData
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.avatar.AvatarData
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.monster.MonsterData
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.weapon.WeaponData
import com.lianyi.paimonsnotebook.ui.screen.home.util.HomeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AbyssScreenViewModel : ViewModel() {

    companion object {
        /*
        * ── 一级板块 ──
        *
        * 1.8.29 把原先平铺的 10 个标签收成 4 个板块(用户要求"合并顶部的项目"):
        *   本期/上期            → 我的战绩(板块内切换期数)
        *   全服总览/出场率/使用率/配队/持有率 → 全服统计
        *   角色配装/武器装备      → 配装
        *   历史(保持独立 —— 它是本地快照,与网络统计不同源,合并易混淆)
        *
        * ⚠️ 常量与映射逻辑统一定义在 [AbyssNavigation],这里只是转发别名 ——
        *    避免两处各写一份编号而悄悄不一致。
        *    本文件原有注释就警告过:"tab index 与 load 分支硬绑定,
        *    插中间会让既有编号整体位移、极易漏改一处而静默串页"。
        * */
        const val SECTION_RECORD = AbyssNavigation.SECTION_RECORD
        const val SECTION_STATISTICS = AbyssNavigation.SECTION_STATISTICS
        const val SECTION_COLLOCATION = AbyssNavigation.SECTION_COLLOCATION
        const val SECTION_HISTORY = AbyssNavigation.SECTION_HISTORY

        // ── 全服统计的子页 ──
        const val STAT_OVERVIEW = AbyssNavigation.STAT_OVERVIEW
        const val STAT_APPEARANCE = AbyssNavigation.STAT_APPEARANCE
        const val STAT_USAGE = AbyssNavigation.STAT_USAGE
        const val STAT_TEAM = AbyssNavigation.STAT_TEAM
        const val STAT_HOLDING = AbyssNavigation.STAT_HOLDING

        // ── 配装的子页 ──
        const val COLLOCATION_AVATAR = AbyssNavigation.COLLOCATION_AVATAR
        const val COLLOCATION_WEAPON = AbyssNavigation.COLLOCATION_WEAPON
    }

    var currentPageIndex by mutableIntStateOf(SECTION_RECORD)
        private set

    val tabs = arrayOf("我的战绩", "全服统计", "配装", "历史")

    //二级子标签(仅"全服统计"与"配装"有)
    val statisticsTabs = arrayOf("全服总览", "出场率", "使用率", "配队", "持有率")
    val collocationTabs = arrayOf("角色配装", "武器装备")

    var statisticsSubIndex by mutableIntStateOf(STAT_OVERVIEW)
        private set
    var collocationSubIndex by mutableIntStateOf(COLLOCATION_AVATAR)
        private set

    /*
    * 「我的战绩」的本期/上期
    *
    * ⚠️ 与下面的 [lastPeriod] **刻意分开** —— 两者控制的是不同数据源:
    *    [recordIsPrevious] 选"我的哪一期深渊记录";
    *    [lastPeriod]       选"全服统计取哪一期"。
    * 原先 10 标签布局下"本期/上期"本身就是两个标签页,不存在混用问题;
    * 合并成"我的战绩"后若共用一个开关,点一下会连全服统计一起切走。
    * */
    var recordIsPrevious by mutableStateOf(false)
        private set

    /*
    * 历史成绩快照
    *
    * ⚠️ "历史"刻意**追加在末尾**(index 9)而不是插在"上期"后面:
    *    tab 的 index 与 load(page) 的分支、以及各处 when(pageIndex) 硬绑定,
    *    插在中间会让所有既有分支的编号整体位移,极易漏改一处而静默串页。
    * */
    var abyssHistory by mutableStateOf<List<AbyssSeasonSnapshot>>(listOf())
        private set
    var historyLoadingState by mutableStateOf(LoadingState.Loading)
        private set

    //本期与上期深渊记录
    var currentAbyssRecord by mutableStateOf<SpiralAbyssData?>(null)
    var previousAbyssRecord by mutableStateOf<SpiralAbyssData?>(null)

    //本期与上期深渊记录 加载状态
    var currentAbyssRecordLoadingState by mutableStateOf(LoadingState.Loading)
    var previousAbyssRecordLoadingState by mutableStateOf(LoadingState.Loading)

    //全服数据库 本期false/上期true
    var lastPeriod by mutableStateOf(false)
        private set

    var overview by mutableStateOf<HutaoOverviewData?>(null)
    var overviewLoadingState by mutableStateOf(LoadingState.Loading)

    var appearanceRate by mutableStateOf<List<HutaoAvatarFloorRateData>?>(null)
    var appearanceRateLoadingState by mutableStateOf(LoadingState.Loading)

    var usageRate by mutableStateOf<List<HutaoAvatarFloorRateData>?>(null)
    var usageRateLoadingState by mutableStateOf(LoadingState.Loading)

    var teamCombination by mutableStateOf<List<HutaoTeamCombinationData>?>(null)
    var teamCombinationLoadingState by mutableStateOf(LoadingState.Loading)

    //持有率不受本期/上期切换影响,始终显示本期与上期的环比
    var holdingRate by mutableStateOf<List<HutaoHoldingRateEntry>?>(null)
    var holdingRateLoadingState by mutableStateOf(LoadingState.Loading)

    //角色配装
    var avatarCollocation by mutableStateOf<List<HutaoAvatarCollocationData>?>(null)
    var avatarCollocationLoadingState by mutableStateOf(LoadingState.Loading)

    //武器配队
    var weaponCollocation by mutableStateOf<List<HutaoWeaponCollocationData>?>(null)
    var weaponCollocationLoadingState by mutableStateOf(LoadingState.Loading)

    private val gameRecordClient = GameRecordClient()
    private val statisticsClient = HutaoStatisticsClient()

    private var currentUser by mutableStateOf<User?>(null)
    var currentGameRole by mutableStateOf<UserGameRoleData.Role?>(null)
        private set

    // 1当期 2上期
    private val scheduleType = arrayOf(
        "1", "2"
    )

    private val avatarMap = mutableMapOf<Int, AvatarData>()

    //武器Id -> 武器,供"角色配装/武器配队"解析名称与图标
    private val weaponMap = mutableMapOf<Int, WeaponData>()

    private val monsterMap = mutableMapOf<String, MonsterData>()

    private var metadataLoaded = false

    init {
        //Compose状态的写入必须在主线程,仅文件/网络请求切换IO
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                avatarMap += AvatarService {
                    setErrorStates()
                }.avatarList.associateBy {
                    it.id
                }

                //武器元数据是本页新增依赖,取不到时只影响两个新标签页
                weaponMap += WeaponService {
                }.weaponMap

                monsterMap += MonsterService {
                    setErrorStates()
                }.monsterList.associateBy {
                    it.name
                }

                metadataLoaded = true
            }

            AccountHelper.selectedUserFlow.collect {
                currentUser = it
                currentGameRole = it?.getSelectedGameRole()

                loadCurrentSection()
            }
        }
    }

    private fun setErrorStates() {
        currentAbyssRecordLoadingState = LoadingState.Error
        previousAbyssRecordLoadingState = LoadingState.Error
        overviewLoadingState = LoadingState.Error
    }

    var showUserGameRoleDialog by mutableStateOf(false)

    var showConfirmDialog by mutableStateOf(false)

    fun showUserGameRoleDialog() {
        showUserGameRoleDialog = true
    }

    fun dismissUserGameRoleDialog() {
        showUserGameRoleDialog = false
    }

    fun showConfirmDialog() {
        showConfirmDialog = true
    }

    fun dismissConfirmDialog() {
        showConfirmDialog = false
    }

    fun onPageIndexChange(value: Int) {
        currentPageIndex = value
        loadCurrentSection()
    }

    //切换"全服统计"板块内的子页(全服总览/出场率/…)
    fun onStatisticsSubIndexChange(value: Int) {
        statisticsSubIndex = value
        loadStatistics(value)
    }

    //切换"配装"板块内的子页(角色配装/武器装备)
    fun onCollocationSubIndexChange(value: Int) {
        collocationSubIndex = value
        loadCollocation(value)
    }

    /*
    * 切换「我的战绩」的本期/上期
    *
    * 只影响自己的深渊记录,不碰全服统计的期数(两者是独立开关)。
    * */
    fun toggleRecordPeriod() {
        recordIsPrevious = !recordIsPrevious
        loadRecord()
    }

    /*
    * 重试当前板块。
    *
    * 供 ContentLoadingLayout 的错误占位按钮调用。各 load 函数内部都有
    * "已加载则跳过"的守卫(如 `if (overview != null) return`),
    * 而失败时对应字段仍为 null,故重试会真正重新发起请求。
    * */
    fun retryCurrentPage() {
        when (currentPageIndex) {
            SECTION_RECORD -> loadRecord()
            SECTION_STATISTICS -> loadStatistics(statisticsSubIndex)
            SECTION_COLLOCATION -> loadCollocation(collocationSubIndex)
            SECTION_HISTORY -> loadHistory()
        }
    }

    /*
    * 切换全服统计的本期/上期,清空已加载的数据重新加载
    *
    * ⚠️ 必须把 [overview] 也置空 —— 全服总览接口本身就带 `?Last=` 参数、
    *    数据随期数变化。1.8.28 及更早的实现漏了这一项,导致"停在全服总览页
    *    切换本期/上期时数字不变"(load 里 `if (overview != null) return`
    *    会直接跳过重新请求)。属本次一并修掉的既有缺陷。
    *
    * ⚠️ 配装两项([avatarCollocation]/[weaponCollocation])也要清 ——
    *    它们的接口同样带 `?Last=`,原先清掉是对的,别因为"配装已挪到另一个板块"
    *    就漏掉:期数开关对两个板块都生效。
    *
    * ⚠️ 不清 [holdingRate]:它固定是"本期 vs 上期环比",与期数开关无关。
    * */
    fun togglePeriod() {
        lastPeriod = !lastPeriod

        overview = null
        appearanceRate = null
        usageRate = null
        teamCombination = null
        avatarCollocation = null
        weaponCollocation = null

        //重新加载当前所在板块(两个板块都受期数影响)
        when (currentPageIndex) {
            SECTION_STATISTICS -> loadStatistics(statisticsSubIndex)
            SECTION_COLLOCATION -> loadCollocation(collocationSubIndex)
        }
    }

    /*
    * 按当前所在板块分发加载
    *
    * 供 init(账号流变化)与切换游戏角色后重新加载 ——
    * 这两个时机都要"把当前可见的板块刷新一遍"。
    * */
    private fun loadCurrentSection() {
        when (currentPageIndex) {
            SECTION_RECORD -> loadRecord()
            SECTION_STATISTICS -> loadStatistics(statisticsSubIndex)
            SECTION_COLLOCATION -> loadCollocation(collocationSubIndex)
            SECTION_HISTORY -> loadHistory()
        }
    }

    /*
    * 加载「我的战绩」(本期或上期)
    *
    * 由 [recordIsPrevious] 决定取哪一期,与全服统计的 [lastPeriod] 独立。
    * */
    private fun loadRecord() {
        val cached = if (recordIsPrevious) previousAbyssRecord else currentAbyssRecord

        if (cached == null) {
            setAbyssRecord(recordIsPrevious)
        }
    }

    /*
    * 加载「全服统计」板块的子页
    *
    * ⚠️ 必须先过 [metadataLoaded] 守卫:出场率/使用率/配队都要用角色元数据
    *    解析图标与名称,元数据未就绪时请求会白跑。
    *    (全服总览本身不需要元数据,但它的兄弟子页需要,且元数据是本地文件、
    *     加载很快,故整个板块统一等待,不做例外。)
    * */
    private fun loadStatistics(subIndex: Int) {
        if (!metadataLoaded) return

        //持有率固定拉"本期 vs 上期环比",与期数开关无关
        if (subIndex == STAT_HOLDING) {
            if (holdingRate != null) return

            holdingRateLoadingState = LoadingState.Loading

            viewModelScope.launch {
                //同时拉取本期与上期用于计算环比
                val responses = withContext(Dispatchers.IO) {
                    statisticsClient.getHoldingRate(false) to
                            statisticsClient.getHoldingRate(true)
                }

                val current = responses.first
                val previous = responses.second

                if (current?.retcode == 0) {
                    val joined = joinHoldingRate(
                        current = current.data.orEmpty(),
                        previous = previous?.data
                    )

                    holdingRate = joined
                    holdingRateLoadingState =
                        if (joined.isEmpty()) LoadingState.Empty else LoadingState.Success
                } else {
                    holdingRateLoadingState = LoadingState.Error
                    "获取持有率失败:${current?.message ?: "网络错误"}".errorNotify()
                }
            }
            return
        }

        //其余四项都随本期/上期变化
        val cached = when (subIndex) {
            STAT_OVERVIEW -> overview
            STAT_APPEARANCE -> appearanceRate
            STAT_USAGE -> usageRate
            STAT_TEAM -> teamCombination
            else -> null
        }
        if (cached != null) return

        setStatisticsLoadingState(subIndex, LoadingState.Loading)

        viewModelScope.launch {
            val last = lastPeriod

            when (subIndex) {
                STAT_OVERVIEW -> {
                    val response = withContext(Dispatchers.IO) {
                        statisticsClient.getOverview(last)
                    }

                    if (response?.retcode == 0) {
                        overview = response.data
                        overviewLoadingState =
                            if (response.data == null) LoadingState.Empty else LoadingState.Success
                    } else {
                        overviewLoadingState = LoadingState.Error
                        "获取深渊数据库失败:${response?.message ?: "网络错误"}".errorNotify()
                    }
                }

                STAT_APPEARANCE -> {
                    val response = withContext(Dispatchers.IO) {
                        statisticsClient.getAvatarAppearanceRate(last)
                    }

                    if (response?.retcode == 0) {
                        appearanceRate = response.data
                        appearanceRateLoadingState =
                            if (response.data.isNullOrEmpty()) LoadingState.Empty else LoadingState.Success
                    } else {
                        appearanceRateLoadingState = LoadingState.Error
                        "获取出场率失败:${response?.message ?: "网络错误"}".errorNotify()
                    }
                }

                STAT_USAGE -> {
                    val response = withContext(Dispatchers.IO) {
                        statisticsClient.getAvatarUsageRate(last)
                    }

                    if (response?.retcode == 0) {
                        usageRate = response.data
                        usageRateLoadingState =
                            if (response.data.isNullOrEmpty()) LoadingState.Empty else LoadingState.Success
                    } else {
                        usageRateLoadingState = LoadingState.Error
                        "获取使用率失败:${response?.message ?: "网络错误"}".errorNotify()
                    }
                }

                STAT_TEAM -> {
                    val response = withContext(Dispatchers.IO) {
                        statisticsClient.getTeamCombination(last)
                    }

                    if (response?.retcode == 0) {
                        teamCombination = response.data
                        teamCombinationLoadingState =
                            if (response.data.isNullOrEmpty()) LoadingState.Empty else LoadingState.Success
                    } else {
                        teamCombinationLoadingState = LoadingState.Error
                        "获取配队数据失败:${response?.message ?: "网络错误"}".errorNotify()
                    }
                }
            }
        }
    }

    /*
    * 加载「配装」板块的子页(角色配装/武器装备)
    *
    * 两者都随本期/上期变化,故也吃 [lastPeriod]。
    * */
    private fun loadCollocation(subIndex: Int) {
        if (!metadataLoaded) return

        val cached = when (subIndex) {
            COLLOCATION_AVATAR -> avatarCollocation
            COLLOCATION_WEAPON -> weaponCollocation
            else -> null
        }
        if (cached != null) return

        setCollocationLoadingState(subIndex, LoadingState.Loading)

        viewModelScope.launch {
            val last = lastPeriod

            when (subIndex) {
                COLLOCATION_AVATAR -> {
                    val response = withContext(Dispatchers.IO) {
                        statisticsClient.getAvatarCollocation(last)
                    }

                    if (response?.retcode == 0) {
                        avatarCollocation = response.data
                        avatarCollocationLoadingState =
                            if (response.data.isNullOrEmpty()) LoadingState.Empty else LoadingState.Success
                    } else {
                        avatarCollocationLoadingState = LoadingState.Error
                        "获取角色配装失败:${response?.message ?: "网络错误"}".errorNotify()
                    }
                }

                COLLOCATION_WEAPON -> {
                    val response = withContext(Dispatchers.IO) {
                        statisticsClient.getWeaponCollocation(last)
                    }

                    if (response?.retcode == 0) {
                        weaponCollocation = response.data
                        weaponCollocationLoadingState =
                            if (response.data.isNullOrEmpty()) LoadingState.Empty else LoadingState.Success
                    } else {
                        weaponCollocationLoadingState = LoadingState.Error
                        "获取武器配队失败:${response?.message ?: "网络错误"}".errorNotify()
                    }
                }
            }
        }
    }

    //把本期与上期的持有率按角色Id连接,计算总持有率与各命座持有率的环比差值
    private fun joinHoldingRate(
        current: List<HutaoHoldingRateData>,
        previous: List<HutaoHoldingRateData>?
    ): List<HutaoHoldingRateEntry> {
        val previousMap = previous?.associateBy { it.AvatarId }

        return current.map { entry ->
            val last = previousMap?.get(entry.AvatarId)

            HutaoHoldingRateEntry(
                AvatarId = entry.AvatarId,
                HoldingRate = entry.HoldingRate,
                HoldingDelta = last?.let { entry.HoldingRate - it.HoldingRate },
                Constellations = entry.Constellations,
                ConstellationDeltas = entry.Constellations.map { constellation ->
                    val lastConstellation =
                        last?.Constellations?.firstOrNull { it.Item == constellation.Item }

                    if (lastConstellation == null) null
                    else constellation.Rate - lastConstellation.Rate
                }
            )
        }.sortedByDescending { it.HoldingRate }
    }

    //「全服统计」子页的加载状态
    private fun setStatisticsLoadingState(subIndex: Int, state: LoadingState) {
        when (subIndex) {
            STAT_OVERVIEW -> overviewLoadingState = state
            STAT_APPEARANCE -> appearanceRateLoadingState = state
            STAT_USAGE -> usageRateLoadingState = state
            STAT_TEAM -> teamCombinationLoadingState = state
            STAT_HOLDING -> holdingRateLoadingState = state
        }
    }

    //「配装」子页的加载状态
    private fun setCollocationLoadingState(subIndex: Int, state: LoadingState) {
        when (subIndex) {
            COLLOCATION_AVATAR -> avatarCollocationLoadingState = state
            COLLOCATION_WEAPON -> weaponCollocationLoadingState = state
        }
    }

    //设置「我的战绩」的加载状态
    private fun setRecordLoadingState(isPrevious: Boolean, state: LoadingState) {
        if (isPrevious) {
            previousAbyssRecordLoadingState = state
        } else {
            currentAbyssRecordLoadingState = state
        }
    }

    /*
    * 拉取深渊记录
    *
    * ⚠️ 参数用 [isPrevious] 布尔而不是 0/1 索引:原先的 `pageIndex` 既要当
    *    schedule_type 的下标、又要当"写哪个 LoadingState"的判据,两处含义
    *    不同却共用同一个数字 —— 写反了编译期看不见,只表现为显示错期数。
    * */
    private fun setAbyssRecord(isPrevious: Boolean) {
        val state = if (currentUser == null || currentGameRole == null) {
            LoadingState.Error
        } else {
            LoadingState.Loading
        }

        setRecordLoadingState(isPrevious, state)

        if (state == LoadingState.Error) return

        viewModelScope.launch {
            /*
            * 整体try/catch:本方法内的CardVerificationService.verify带withTimeout(180s),
            * 用户触发1034风控后不完成滑块会抛TimeoutCancellationException,
            * 打断协程导致下面的LoadingState赋值永不执行 —— 界面永久停在Loading且无提示。
            * RoleCombatScreenViewModel已按同样理由加过兜底,此处补齐。
            * (CancellationException会被协程框架特殊处理,表现为静默挂起而非杀进程)
            * */
            try {
                val userAndUid =
                    UserAndUid(
                        userEntity = currentUser!!.userEntity,
                        playerUid = PlayerUid.fromGameRole(role = currentGameRole!!)
                    )

                val result = withContext(Dispatchers.IO) {
                    gameRecordClient.getSpiralAbyssData(
                        user = userAndUid, scheduleType = AbyssNavigation.scheduleTypeFor(isPrevious)
                    )
                }

                if (result.success) {
                    //data声明非空但服务端可能返回null,直接解引用会NPE
                    val resultData = result.data

                    if (resultData == null) {
                        setRecordLoadingState(isPrevious, LoadingState.Error)
                        "深渊数据为空".errorNotify()
                        return@launch
                    }

                    val data =
                        resultData.copy(floors = resultData.floors.sortedByDescending { it.index })

                    val resultState = if (data.floors.isEmpty()) {
                        LoadingState.Empty
                    } else {
                        LoadingState.Success
                    }

                    //落库本期成绩快照(服务端只提供本期/上期,过期即永久丢失)
                    archiveSnapshot(data)

                    if (isPrevious) {
                        previousAbyssRecord = data
                        previousAbyssRecordLoadingState = resultState
                    } else {
                        currentAbyssRecord = data
                        currentAbyssRecordLoadingState = resultState
                    }
                } else {
                    var finalState = LoadingState.Error

                    //1034风控:App内滑块验证后自动重试,失败时回退到网页验证确认框
                    if (result.validate) {
                        val challenge = CardVerificationService.verify(
                            currentUser!!.userEntity, CardVerificationService.PATH_SPIRAL_ABYSS
                        )

                        if (challenge != null) {
                            val retry = withContext(Dispatchers.IO) {
                                gameRecordClient.getSpiralAbyssData(
                                    user = userAndUid, scheduleType = AbyssNavigation.scheduleTypeFor(isPrevious), challenge = challenge
                                )
                            }

                            //同样先判空,再解引用
                            val retryData = retry.data

                            if (retry.success && retryData != null) {
                                val data =
                                    retryData.copy(floors = retryData.floors.sortedByDescending { it.index })

                                finalState = if (data.floors.isEmpty()) LoadingState.Empty else LoadingState.Success

                                //重试(风控验证)成功后同样存档
                                archiveSnapshot(data)

                                if (isPrevious) {
                                    previousAbyssRecord = data
                                } else {
                                    currentAbyssRecord = data
                                }
                            }
                        }
                    }

                    if (finalState != LoadingState.Success && finalState != LoadingState.Empty) {
                        showConfirmDialog = result.validate
                        if (!result.validate) {
                            "获取深渊数据失败:${result.message}[${result.retcode}]".errorNotify()
                        }
                        finalState = LoadingState.Error
                    }

                    setRecordLoadingState(isPrevious, finalState)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                //超时/取消:必须让界面脱离Loading,否则永久转圈
                setRecordLoadingState(isPrevious, LoadingState.Error)
                throw e
            } catch (e: Exception) {
                setRecordLoadingState(isPrevious, LoadingState.Error)
                "获取深渊数据时出现异常:${e.message ?: "未知错误"}".errorNotify()
            }
        }
    }

    /*
    * 加载历史快照
    * 纯本地读取,不依赖元数据/网络,故不走 1034 风控路径
    * */
    private fun loadHistory() {
        val uid = currentGameRole?.game_uid

        if (uid.isNullOrBlank()) {
            abyssHistory = emptyList()
            historyLoadingState = LoadingState.Empty
            return
        }

        viewModelScope.launch {
            historyLoadingState = LoadingState.Loading

            val snapshots = withContext(Dispatchers.IO) {
                runCatching {
                    PaimonsNotebookDatabase.database.abyssSeasonSnapshotDao
                        .getSnapshotsByUid(uid)
                        .first()
                }.getOrDefault(emptyList())
            }

            abyssHistory = snapshots
            historyLoadingState =
                if (snapshots.isEmpty()) LoadingState.Empty else LoadingState.Success
        }
    }

    fun getAvatarFromMetadata(avatarId: Int) = avatarMap[avatarId]
    /*
    * 存档本期成绩
    *
    * 服务端只提供本期(schedule_type=1)与上期(2),更早的期数取不回来 ——
    * 用户某期没打开深渊页,那期成绩就永久丢失。故每次成功拉取即落库。
    *
    * 失败静默:存档是附带价值,不能影响深渊页本身的展示。
    * */
    private fun archiveSnapshot(data: SpiralAbyssData) {
        val uid = currentGameRole?.game_uid ?: return

        launchSafeIO {
            runCatching {
                PaimonsNotebookDatabase.database.abyssSeasonSnapshotDao.upsert(
                    AbyssSnapshotMapper.toSnapshot(
                        data = data,
                        uid = uid,
                        savedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }    fun getWeaponFromMetadata(weaponId: Int) = weaponMap[weaponId]

    fun getMonsterFromMetadata(monsterName:String) = monsterMap[monsterName]

    fun onChangeGameRole(user: User, role: UserGameRoleData.Role) {
        dismissUserGameRoleDialog()

        currentUser = user
        currentGameRole = role

        /*
        * ⚠️ 切换游戏角色必须**清掉角色相关的缓存**再加载。
        *
        * 原先这里直接调 load(),而 load 里是
        * `0 -> if (currentAbyssRecord == null) setAbyssRecord(page)`
        * —— 已经加载过就跳过。于是**从角色 A 切到角色 B 时,
        * "我的战绩"仍显示 A 的深渊记录**(数据属于上一个角色却没有任何提示)。
        * 本次一并修掉。
        *
        * 清哪些:
        *   - 本期/上期深渊记录 + 历史(都是"这个角色的"数据,必须重取)
        * 不清哪些:
        *   - 全服统计与配装:那是**全服**数据,与当前角色无关,清掉纯属浪费请求。
        * */
        currentAbyssRecord = null
        previousAbyssRecord = null
        abyssHistory = emptyList()
        currentAbyssRecordLoadingState = LoadingState.Loading
        previousAbyssRecordLoadingState = LoadingState.Loading
        historyLoadingState = LoadingState.Loading

        loadCurrentSection()
    }

    fun goValidateScreen() {
        dismissConfirmDialog()

        if (currentUser == null) {
            "当前用户状态异常".errorNotify()
            return
        }

        HomeHelper.goActivityByIntentNewTask {
            setComponentName(HoyolabWebActivity::class.java)
            putExtra("mid", currentUser?.userEntity?.mid ?: "")
        }
    }
}
