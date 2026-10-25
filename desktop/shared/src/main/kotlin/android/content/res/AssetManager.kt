package android.content.res
import java.io.FileNotFoundException
import java.io.InputStream

class AssetManager(private val loader: ClassLoader = AssetManager::class.java.classLoader) {
    fun open(path: String): InputStream = loader.getResourceAsStream("assets/$path") ?: throw FileNotFoundException(path)
}
class Configuration(var screenWidthDp: Int = 0, var screenHeightDp: Int = 0)
