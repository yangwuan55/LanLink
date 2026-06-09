package com.ymr.lanlink.presentation

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ymr.lanlink.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun `MainActivity launches with correct UI elements`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Verify all UI components exist
                assertNotNull(activity.findViewById(R.id.tab_layout))
                assertNotNull(activity.findViewById(R.id.start_stop_button))
                assertNotNull(activity.findViewById(R.id.status_text))
                assertNotNull(activity.findViewById(R.id.message_input))
                assertNotNull(activity.findViewById(R.id.send_button))
            }
        }
    }

    @Test
    fun `Start Server button is displayed`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.start_stop_button))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun `Status text is displayed`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.status_text))
                .check(matches(isDisplayed()))
        }
    }
}