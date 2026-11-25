import com.landosol.toolbox.desktop.shared.DesktopCharacterIconPack;
import com.landosol.toolbox.desktop.shared.DesktopContext;
import java.nio.file.Files;
import java.nio.file.Path;
import kotlin.Unit;

/** Build-only entry point. The classifier's original image path compiles the source assets. */
final class CompileCharacterIcons {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).resolve(DesktopCharacterIconPack.RESOURCE);
        Files.createDirectories(output.getParent());
        var context = new DesktopContext(Path.of(args[1]).toFile(), recovered -> Unit.INSTANCE);
        try (var stream = Files.newOutputStream(output)) {
            DesktopCharacterIconPack.INSTANCE.compile(context, stream);
        }
    }
}
