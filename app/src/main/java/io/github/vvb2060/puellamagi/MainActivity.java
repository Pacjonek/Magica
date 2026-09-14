package io.github.vvb2060.puellamagi;

import static io.github.vvb2060.puellamagi.App.TAG;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import io.github.vvb2060.puellamagi.databinding.ActivityMainBinding;

public final class MainActivity extends Activity {
    private boolean bound;
    private Shell shell;
    private ActivityMainBinding binding;
    private final List<String> console = new AppendCallbackList();
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            console.add(getString(R.string.service_connected));
            App.server = IRemoteService.Stub.asInterface(binder);
            Shell.enableVerboseLogging = BuildConfig.DEBUG;
            shell = Shell.Builder.create().setFlags(Shell.FLAG_NON_ROOT_SHELL).build();
            isRootAvailable();
            getRunningAppProcesses();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            App.server = null;
            console.add(getString(R.string.service_disconnected));
        }
    };

    private boolean bind() {
        try {
            bound = bindIsolatedService(
                    new Intent(this, MagicaService.class),
                    Context.BIND_AUTO_CREATE,
                    "magica",
                    getMainExecutor(),
                    connection
            );
            return bound;
        } catch (Exception e) {
            Log.e(TAG, "Can not bind service", e);
            bound = false;
            return false;
        }
    }

    void getRunningAppProcesses() {
        try {
            var processes = App.server.getRunningAppProcesses();
            console.add("[DEBUG] uid pid processName pkgList importance");
            for (var proc : processes) {
                var str = String.format(Locale.ROOT, "%d %d %s %s %d",
                        proc.uid, proc.pid, proc.processName,
                        Arrays.toString(proc.pkgList), proc.importance);
                console.add(str);
            }
        } catch (RemoteException | SecurityException e) {
            console.add(Log.getStackTraceString(e));
        }
    }

    void cmd(String... cmds) {
        shell.newJob().add(cmds).to(console).submit(out -> {
            if (!out.isSuccess()) {
                console.add(Arrays.toString(cmds) + getString(R.string.exec_failed));
            }
        });
    }

    void isRootAvailable() {
        cmd("id");
        if (shell.isRoot()) {
            console.add(getString(R.string.root_shell_opened));
        } else {
            console.add(getString(R.string.cannot_open_root_shell));
            return;
        }

        var cmd = "ps -A 2>/dev/null | grep magiskd | grep -qv grep";
        var magiskInstalled = ShellUtils.fastCmdResult(shell, cmd);
        if(magiskInstalled){
            console.add("Previous Magisk installation detected.");
        }
//        if (magiskd) {
//            console.add(getString(R.string.magiskd_running));
//            killMagiskd();
//        } else {
//            console.add(getString(R.string.magiskd_not_running));
            installMagisk();
//        }
    }


    /* @SuppressLint("SetTextI18n")
    void killMagiskd() {
        binding.install.setOnClickListener(v -> {
            var cmd = "kill -9 $(pidof magiskd)";
            if (ShellUtils.fastCmdResult(shell, cmd)) {
                console.add(getString(R.string.magiskd_killed));
            } else {
                console.add(getString(R.string.magiskd_failed_to_kill));
            }
            binding.install.setEnabled(false);
        });
        binding.install.setText("Kill magiskd");
        binding.install.setVisibility(View.VISIBLE);
    } */

    @SuppressLint("SetTextI18n")
    void installMagisk() {
        ApplicationInfo info;
        try {
            console.add("Querying for the installed Magisk app package (com.topjohnwu.magisk)...");
            info = getPackageManager().getApplicationInfo("com.topjohnwu.magisk", 0);
        } catch (PackageManager.NameNotFoundException e) {
            try {
                info = getPackageManager().getApplicationInfo("io.github.vvb2060.magisk", 0);
            } catch (PackageManager.NameNotFoundException ex) {
                console.add("[ERROR] "+getString(R.string.magisk_package_not_installed));
                console.add(getString(R.string.magisk_app_required));

                // binding.install.setText("Try again");
                // binding.install.setVisibility(View.VISIBLE);
                // binding.install.setEnabled(true);


                return;
            }
        }

        var cmd = "mkdir -p /dev/tmp/magica; unzip -o " + info.publicSourceDir +
                " META-INF/com/google/android/update-binary -d /dev/tmp/magica;" +
                "sh /dev/tmp/magica/META-INF/com/google/android/update-binary dummy 1 " + info.publicSourceDir;

        try (var apk = new ZipFile(info.publicSourceDir)) {
            console.add("Attempting to unpack the update-binary extra from installed Magisk APK resources...");
            var update = apk.getEntry("META-INF/com/google/android/update-binary");
            if (update != null) {
                console.add(getString(R.string.tap_to_install_magisk));
                console.add("[PROMPT] Ready for installation! At your command, sir!");
                binding.install.setOnClickListener(v -> {
                    shell.newJob().add(cmd).to(console).submit(out -> {
                        if (out.isSuccess()) {
                            console.add(getString(R.string.tap_to_reboot));
                            binding.install.setOnClickListener(a -> cmd("reboot"));
                            console.add("[OK] Installation passed. After reboot, you should have a fully functional Magisk installation. You can uninstall this installer app");
                            binding.install.setText("Reboot device");
                            binding.install.setEnabled(true);
                        } else {
                            console.add("[ERROR] " + getString(R.string.failed_to_install));
                        }
                    });
                    binding.install.setEnabled(false);
                });
                binding.install.setText("Install Magisk");
                binding.install.setVisibility(View.VISIBLE);
            } else {
                console.add("[ERROR] Installed Magisk APK has been found but not update-binary. Are you sure you have the latest version?");
                return;
            }
        } catch (IOException e) {
            Log.e(TAG, "MagicaMagiskInstaller", e);
            console.add("[ERROR] Couldn't get update-binary extra from installed APK Magisk file. See logcat for more info. ");
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        console.add(getString(R.string.start_service, Boolean.toString(bind())));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
    }

    class AppendCallbackList extends CallbackList<String> {
        @Override
        public void onAddElement(String s) {
            Log.d(TAG, s);
            binding.console.append(s);
            binding.console.append("\n");
            binding.sv.postDelayed(() -> binding.sv.fullScroll(ScrollView.FOCUS_DOWN), 10);
        }
    }
}
