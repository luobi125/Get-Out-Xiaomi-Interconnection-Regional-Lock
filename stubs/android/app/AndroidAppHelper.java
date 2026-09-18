package android.app;

/** 编译桩：Xposed 提供的当前 Application 访问器。 */
public final class AndroidAppHelper {

    private AndroidAppHelper() {}

    public static Application currentApplication() {
        return null;
    }
}
