package com.earbook.app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.earbook.app.store.PlaybackStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 smoke：CI 冒烟测试（GHA 模拟器上跑）。
 * 只验证启动路径与主界面骨架——不碰 TTS/文件导入深水区（Phase 2 再做）。
 * 注意：共享真机上书架可能有历史数据——空提示断言按实际书架状态不变式判定。
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun activityLaunches_emptyHintVisible() {
        // 空状态提示与书架实际状态一致：无书→显示；有书→隐藏（共享真机场景）
        val bookCount = PlaybackStore(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).listBooks().size
        if (bookCount == 0) {
            onView(withId(R.id.empty_hint)).check(matches(isDisplayed()))
        } else {
            onView(withId(R.id.empty_hint))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test
    fun activityLaunches_fabImportVisible() {
        // 导入入口可见（主交互骨架在）
        onView(withId(R.id.fab_import)).check(matches(isDisplayed()))
    }

    @Test
    fun activityLaunches_recyclerVisible() {
        // 书架容器可见
        onView(withId(R.id.recycler_books)).check(matches(isDisplayed()))
    }
}

