package android.content;
import android.content.pm.PackageManager;
public abstract class Context {
    public static final String VIBRATOR_MANAGER_SERVICE = "vibrator_manager";
    public abstract Object getSystemService(String name);
    public abstract PackageManager getPackageManager();
    public abstract Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException;
}
