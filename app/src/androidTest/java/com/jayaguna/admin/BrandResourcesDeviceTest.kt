package com.jayaguna.admin

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrandResourcesDeviceTest {
    @Test fun launcherAndSplashUseTheOriginalCompanyLogoWithinTheSafeArea() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        val foreground = (icon as AdaptiveIconDrawable).foreground
        assertTrue(foreground is InsetDrawable)
        val logoBitmap = ((foreground as InsetDrawable).drawable as BitmapDrawable).bitmap
        val original = BitmapFactory.decodeResource(context.resources, R.drawable.jayaguna_logo)
        assertTrue("The launcher must use the existing company artwork", original.sameAs(logoBitmap))
        val safeArea = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        foreground.setBounds(0, 0, 108, 108)
        foreground.draw(Canvas(safeArea))
        var red = 0; var navy = 0
        for (y in 0 until 108) for (x in 0 until 108) {
            val pixel = safeArea.getPixel(x, y)
            if (Color.alpha(pixel) > 160) {
                assertTrue("Brand artwork must fit inside the adaptive safe circle", Math.hypot(x - 53.5, y - 53.5) <= 34.0)
                if (Color.red(pixel) > 130 && Color.green(pixel) < 90) red++
                if (Color.blue(pixel) > 60 && Color.red(pixel) < 60) navy++
            }
        }
        assertTrue(red > 100); assertTrue(navy > 100)
        for (size in listOf(48, 96, 192)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size); icon.draw(Canvas(bitmap))
            assertEquals(Color.WHITE, bitmap.getPixel(size / 2, size / 8))
            bitmap.recycle()
        }
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(R.style.AppStartingTheme, info.theme)
        val theme = ContextThemeWrapper(context, R.style.AppStartingTheme).theme
        val value = TypedValue()
        assertTrue(theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon, value, true))
        assertEquals(R.drawable.ic_launcher_foreground, value.resourceId)
        assertTrue(theme.resolveAttribute(androidx.core.splashscreen.R.attr.postSplashScreenTheme, value, true))
        assertEquals(R.style.AppTheme, value.resourceId)
        safeArea.recycle(); original.recycle()
    }
}
