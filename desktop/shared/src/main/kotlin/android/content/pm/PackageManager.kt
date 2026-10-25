package android.content.pm
/** Compile-only contract for upstream installedPackageNames; desktop resolves packages through ADB. */
abstract class PackageManager {
    abstract fun getApplicationInfo(packageName: String, flags: Int): Any
    class NameNotFoundException(message: String? = null) : Exception(message)
}
