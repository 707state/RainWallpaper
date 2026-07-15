package com.jask.rainwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jask.rainwallpaper.ui.theme.RainWallpaperTheme
import com.jask.rainwallpaper.effect.Effects
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RainWallpaperTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WallpaperPicker(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}


@Composable
fun WallpaperPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedKey by remember { mutableStateOf("none") }
    var saved by remember { mutableStateOf(false) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, it)
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            }
            bitmap = bmp
            saved = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Gravity Bubble Wallpaper",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        val currentBitmap = bitmap
        if (currentBitmap != null) {
            // Preview
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Selected wallpaper preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Effect selector
            Text(
                text = "Effect",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.selectableGroup()) {
                Effects.uiChoices().forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedKey == choice.key,
                                onClick = { selectedKey = choice.key }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedKey == choice.key,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = choice.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Button(onClick = { pickImageLauncher.launch("image/*") }) {
                Text("Change Image")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = {
                saveWallpaperConfiguration(context, currentBitmap, selectedKey)
                saved = true
                openWallpaperPicker(context)
            }) {
                Text("Set as Wallpaper")
            }

            if (saved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Image saved! Confirm RainWallpaper in the wallpaper preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Button(onClick = { pickImageLauncher.launch("image/*") }) {
                Text("Pick an Image")
            }
        }
    }
}

private fun saveWallpaperConfiguration(context: Context, bitmap: Bitmap, effect: String) {
    // Write to a temporary file first so the active wallpaper never observes a
    // partially written JPEG when the preference change triggers its reload.
    val targetFile = File(context.filesDir, RainWallpaperService.WALLPAPER_FILE_NAME)
    val temporaryFile = File(context.filesDir, "${RainWallpaperService.WALLPAPER_FILE_NAME}.tmp")
    FileOutputStream(temporaryFile).use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
            "Unable to encode wallpaper image"
        }
        out.fd.sync()
    }
    check(temporaryFile.renameTo(targetFile)) {
        "Unable to replace wallpaper image"
    }

    val preferences = context.getSharedPreferences(
        RainWallpaperService.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    val nextRevision = preferences.getLong(RainWallpaperService.CONFIG_REVISION_KEY, 0L) + 1L
    preferences.edit()
        .putString(RainWallpaperService.EFFECT_MODE_KEY, effect)
        .putLong(RainWallpaperService.CONFIG_REVISION_KEY, nextRevision)
        .apply()
}

private fun openWallpaperPicker(context: Context) {
    // Keep the chooser action used by the original working implementation.
    // Some system wallpaper apps do not reopen ACTION_CHANGE_LIVE_WALLPAPER
    // when the requested component is already the active wallpaper.
    val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
        putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, RainWallpaperService::class.java)
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
