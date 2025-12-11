package com.example.spywatch

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

class PreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple layout with a button
        val button = Button(this).apply {
            text = "Set SpyWatch Wallpaper"
            setOnClickListener {
                setLiveWallpaper()
            }
        }

        setContentView(button)
    }

    private fun setLiveWallpaper() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@PreviewActivity, RadarWallpaperService::class.java)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Cannot set live wallpaper", Toast.LENGTH_SHORT).show()
        }
    }
}
