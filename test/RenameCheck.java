import regrind.core.*;
import java.nio.file.*;

/** Renames a class and its nested classes in a jar, then exports it. Run the result to check it still loads. */
public class RenameCheck {
    public static void main(String[] a) throws Exception {
        Workspace ws = Workspace.open(Path.of(a[0]));
        int n = Renamer.renameClass(ws, a[1], a[2]);
        System.out.println("rewrote " + n + " classes");
        ws.export(Path.of(a[3]));
    }
}
