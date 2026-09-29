package com.lianyi.paimonsnotebook.ui.screen.items.viewmodel.screen

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.lianyi.paimonsnotebook.common.database.cultivate.data.CultivateItemType
import com.lianyi.paimonsnotebook.common.extension.data_store.editValue
import com.lianyi.paimonsnotebook.common.extension.scope.launchIO
import com.lianyi.paimonsnotebook.common.util.data_store.PreferenceKeys
import com.lianyi.paimonsnotebook.common.util.enums.LoadingState
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.common.service.MaterialService
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.common.service.WeaponService
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.intrinsic.format.FightPropertyFormat
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.intrinsic.format.WeaponAffixFormat
import com.lianyi.paimonsnotebook.common.web.hutao.genshin.weapon.WeaponData
import com.lianyi.paimonsnotebook.ui.screen.items.data.cultivate.CultivateConfigData
import com.lianyi.paimonsnotebook.ui.screen.items.util.ItemContentFilterHelper
import com.lianyi.paimonsnotebook.ui.screen.items.util.ItemFilterType
import com.lianyi.paimonsnotebook.ui.screen.items.util.ItemHelper
import com.lianyi.paimonsnotebook.ui.screen.items.util.ItemScreenStateResolver
import com.lianyi.paimonsnotebook.ui.screen.items.util.ItemSearchOptionHelper
import com.lianyi.paimonsnotebook.ui.screen.items.viewmodel.base.ItemBaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WeaponScreenViewModel : ItemBaseViewModel<WeaponData>() {

    private val weaponService by lazy {
        WeaponService {
            onMissingFile()
        }
    }

    private val materialService by lazy {
        MaterialService {
            onMissingFile()
        }
    }

    //属性列表
    var propertyList by mutableStateOf(listOf<FightPropertyFormat>())
        private set

    var compareItemPropertyList by mutableStateOf(listOf<FightPropertyFormat>())
        private set

    //技能列表
    var weaponAffixFormat by mutableStateOf<WeaponAffixFormat?>(null)
        private set

    val itemFilterViewModel by lazy {
        ItemSearchOptionHelper.getWeaponFilterItemViewModel(weaponService = weaponService)
    }

    override fun init(intent: Intent) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = weaponService.weaponList

            if (list.isNotEmpty()) {
                val weapon =
                    ItemHelper.getItemFromIntent(intent, weaponService.weaponList) { it.id }

                onClickItem(weapon)
            }

            /*
            * ⚠️ 必须判 currentItem,不能只判 loadingState。
            *
            * 原实现无条件置 Success,但武器列表为空时 onClickItem 从未被调用,
            * currentItem 仍是 null ⇒ 成功分支被渲染 ⇒ 页面里 23 处
            * `currentItem!!` 直接 NPE 崩溃。
            * (该状态可复现:元数据存在但武器列表为空/条目被过滤掉时。)
            * */
            loadingState = ItemScreenStateResolver.resolve(
                current = loadingState,
                hasItem = currentItem != null
            )
        }
    }

    override val tabs = arrayOf(
        "属性", "精炼", "资料", "材料"
    )

    //更新面板
    private fun updateProperty(promoted: Boolean = false) {
        if (currentItem == null) return

        propertyList = weaponService.getFightPropertyFormatList(
            currentItem!!, currentItemLevel, promoted
        )
    }

    private fun updateCompareAvatarProperty(promoted: Boolean = false) {
        if (compareItem == null) {
            if (compareItemPropertyList.isNotEmpty()) {
                compareItemPropertyList = listOf()
            }

            return
        }
        compareItemPropertyList = weaponService.getFightPropertyFormatList(
            compareItem!!, currentItemLevel, promoted
        )
    }

    //更新技能
    private fun updateSkill() {
        if (currentItem == null) return

        val max = currentItem!!.affix?.Descriptions?.size ?: 1

        weaponAffixFormat = if (max != 1) {
            WeaponAffixFormat(currentItem?.affix)
        } else {
            null
        }
    }

    //更新材料
    override fun updateMaterial() {
        if (currentItem == null) return
        materialList.clear()

        materialList += materialService.getMaterialListByIds(currentItem!!.cultivationItems)
    }

    //重置比对角色
    private fun resetCompareItem() {
        compareItem = null
        updateCompareAvatarProperty()
        selectCompareItem = false
    }

    override fun onClickItem(item: WeaponData) {
        if (item.id == currentItem?.id) return

        if (selectCompareItem) {
            compareItem = item
            updateCompareAvatarProperty()
            selectCompareItem = false

            itemFilterViewModel.showResultList()
        } else {
            currentItem = item
            resetCompareItem()

            updateProperty()
            updateSkill()

            updateMaterial()
            viewModelScope.launchIO {
                PreferenceKeys.LastViewWeaponId.editValue(item.id)
            }
        }

        itemFilterViewModel.dismissFilterContent()


    }

    override fun onClickCompareItem() {
        selectCompareItem = compareItem == null

        if (selectCompareItem) {
            toggleFilterContent()
        } else {
            resetCompareItem()
        }
    }

    //当更改等级时
    override fun onChangeItemLevel(value: Int, promoted: Boolean) {
        currentItemLevel = value

        updateProperty(promoted)
        updateCompareAvatarProperty(promoted)
    }

    override fun onPromotedChange(promoted: Boolean) {
        updateProperty(promoted)
        updateCompareAvatarProperty(promoted)
    }

    override fun toggleFilterContent() {
        itemFilterViewModel.toggleFilterContent()

        if (!itemFilterViewModel.showFilterContent) {
            resetCompareItem()
        }
    }

    override fun getItemDataContent(
        item: WeaponData, type: ItemFilterType, isList: Boolean
    ): String {
        val dataContent = ItemContentFilterHelper.getWeaponShowContentByType(
            type = type,
            weapon = item,
            fightPropertyValueCalculateService = weaponService.fightPropertyValueCalculateService
        )

        return if (isList) {
            when (type) {
                ItemFilterType.Default, ItemFilterType.Name -> ""

                else -> "${ItemContentFilterHelper.getSortTypeNameByType(type)}:${dataContent}"
            }
        } else {
            dataContent
        }
    }

    override fun getCurrentItemId(): Int = currentItem?.id ?: 0

    override fun onShowItemConfigDialog() {
        super.onShowItemConfigDialog()

        val weapon = currentItem ?: return

        /*
        * 这里原先挂着一个 `//TODO 适配武器等级为100`,已删除 —— 它基于**错误前提**。
        *
        * 依据(详细推导见 `LevelLimit.kt` 的注释):
        *   - 角色上限确实已提到 **100**(需新材料「无主的命星」做 95/100 阶突破)
        *   - 但武器上限仍是 **90 / 70**,取值与维护活跃的同源实现胡桃
        *     `GetMaxLevelByQuality()` 一致 ⇒ **两者刻意不对称,不要一起改**
        *
        * ⚠️ 容易被误判的一点:`WeaponCurve.json` 里**确实有 Level 1..100 的曲线**,
        *    看着像"武器也能到 100"。但 `AvatarCurve.json` 同样有 100 档,
        *    而 `WeaponPromote.json` 与 `AvatarPromote.json` 的突破档位都是 0..6
        *    —— 即**曲线文件是按 100 档统一下发的,不代表游戏内上限是 100**。
        *
        * 故这里取 `weapon.maxLevel`(委托 `LevelLimit.weaponMaxLevel(rankLevel)`)是对的。
        * 若日后官方确实放开武器 100 级,应**同时**改 `LevelLimit.weaponMaxLevel`
        * 与武器计算路径,而不是只改这一个滑块。
        * */
        val weaponMaxLevel = weapon.maxLevel

        cultivateConfigList += CultivateConfigData(
            name = "武器等级",
            iconUrl = weapon.iconUrl,
            id = weapon.id,
            type = CultivateItemType.Weapon,
            maxLevel = weaponMaxLevel,
            tintIcon = false
        )
    }
}