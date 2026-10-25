package android.content
import android.content.res.AssetManager
import java.io.File

abstract class Context {
    abstract val assets: AssetManager
    abstract val applicationContext: Context
    abstract val filesDir: File
    abstract val cacheDir: File
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    companion object { const val MODE_PRIVATE = 0 }
}
