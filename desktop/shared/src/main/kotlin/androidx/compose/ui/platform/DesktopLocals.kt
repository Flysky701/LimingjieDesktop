package androidx.compose.ui.platform
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf
val LocalContext = staticCompositionLocalOf<Context> { error("DesktopContext 未提供") }
val LocalConfiguration = staticCompositionLocalOf<Configuration> { error("内容区尺寸未提供") }
