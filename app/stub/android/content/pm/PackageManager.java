package android.content.pm;
import java.util.List;
public abstract class PackageManager {
    public static class NameNotFoundException extends Exception {
        public NameNotFoundException() {}
        public NameNotFoundException(String n) { super(n); }
    }
    public abstract List<ApplicationInfo> getInstalledApplications(int flags);
    public abstract ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException;
}
