package com.trayucmd;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Slf4j
public class TryUCmd {
    private static final List<ScriptTask> scriptTasks = new ArrayList<>();
    private static final String CONFIG_PATH = "config.properties";
    private static TrayIcon trayIcon;
    private static boolean autostartEnabled;

    public static void main(String[] args) {
        if (!SystemTray.isSupported()) {
            log.error("SystemTray not supported.");
            return;
        }
        log.info("Application started.");
        loadConfig();
        createTrayIcon();
        if (autostartEnabled) {
            runAllScripts();
        }
    }

    private static void loadConfig() {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(CONFIG_PATH))) {
            props.load(in);
            autostartEnabled = Boolean.parseBoolean(props.getProperty("autostart", "false"));
            String scripts = props.getProperty("scripts", "");
            for (String scriptPath : scripts.split(";")) {
                if (!scriptPath.isBlank()) {
                    File f = new File(scriptPath.trim());
                    if (f.exists() && f.isFile() && f.getName().toLowerCase().endsWith(".cmd")) {
                        scriptTasks.add(new ScriptTask(f));
                        log.info("Script restored: {}", f.getAbsolutePath());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("No existing config found or error reading config: {}", e.getMessage());
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("autostart", String.valueOf(autostartEnabled));
        StringBuilder sb = new StringBuilder();
        for (ScriptTask st : scriptTasks) {
            sb.append(st.getFile().getAbsolutePath()).append(";");
        }
        props.setProperty("scripts", sb.toString());
        try (OutputStream out = Files.newOutputStream(Path.of(CONFIG_PATH))) {
            props.store(out, "Tray U CMD Config");
        } catch (IOException e) {
            log.error("Error saving config: {}", e.getMessage(), e);
        }
    }

    private static void createTrayIcon() {
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = buildBaseMenu();
            trayIcon = new TrayIcon(Toolkit.getDefaultToolkit()
                .createImage(TryUCmd.class.getResource("/icon.png")), "TUC", menu);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
            log.info("Tray icon added.");
        } catch (Exception e) {
            log.error("Tray icon error: {}", e.getMessage(), e);
        }
    }

    private static PopupMenu buildBaseMenu() {
        PopupMenu menu = new PopupMenu();
        menu.add(createMenuItem("Add .cmd", e -> {
            log.info("Add .cmd clicked");
            addCmdFile(menu);
        }));
        menu.add(createMenuItem("Run All", e -> {
            log.info("'Run All' clicked");
            runAllScripts();
        }));
        menu.add(createMenuItem("Stop All", e -> {
            log.info("'Stop All' clicked");
            stopAllScripts();
        }));
        menu.addSeparator();
        updateMenuWithScripts(menu);
        menu.addSeparator();
        CheckboxMenuItem autostartItem = new CheckboxMenuItem("Enable Autostart", autostartEnabled);
        autostartItem.addItemListener(e -> {
            autostartEnabled = !autostartEnabled;
            if (autostartEnabled) {
                enableAutostart();
            } else {
                disableAutostart();
            }
            saveConfig();
        });
        menu.add(autostartItem);
        menu.add(createMenuItem("Exit", e -> {
            log.info("Exit clicked");
            stopAllScripts();
            System.exit(0);
        }));
        return menu;
    }

    private static void updateMenuWithScripts(PopupMenu menu) {
        int sepIndex = findSeparatorIndex(menu);
        while (menu.getItemCount() > sepIndex + 1 && !(menu.getItem(sepIndex + 1) instanceof MenuItem mi && ("Exit".equals(mi.getLabel()) || mi instanceof CheckboxMenuItem))) {
            menu.remove(sepIndex + 1);
        }
        for (ScriptTask st : scriptTasks) {
            Menu scriptMenu = new Menu(st.getFile().getName());
            scriptMenu.add(createMenuItem("Run", e -> {
                log.info("'Run' clicked for script: {}", st.getFile().getName());
                runScript(st);
            }));
            scriptMenu.add(createMenuItem("Stop", e -> {
                log.info("'Stop' clicked for script: {}", st.getFile().getName());
                stopScript(st);
            }));
            menu.insert(scriptMenu, sepIndex);
            sepIndex++;
        }
    }

    private static int findSeparatorIndex(PopupMenu menu) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            if (menu.getItem(i) instanceof MenuItem mi && "-".equals(mi.getLabel())) return i;
        }
        return -1;
    }

    private static void runAllScripts() {
        log.info("Running all scripts...");
        scriptTasks.forEach(TryUCmd::runScript);
    }

    private static void stopAllScripts() {
        log.info("Stopping all scripts...");
        for (ScriptTask st : scriptTasks) {
            if (st.isRunning()) stopScript(st);
        }
    }

    private static void runScript(ScriptTask st) {
        if (st.isRunning()) {
            log.info("Script {} is already running.", st.getFile().getName());
            showNotification("Already Running", st.getFile().getName());
            return;
        }
        log.info("Attempting to run script {}", st.getFile().getAbsolutePath());
        String taskName = "TUC_" + st.getFile().getName().replace(" ", "_");
        LocalTime futureTime = LocalTime.now().plusMinutes(1);
        String timeStr = futureTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        try {
            int createRes = runCommand("schtasks", "/create",
                "/tn", taskName,
                "/tr", st.getFile().getAbsolutePath(),
                "/sc", "once",
                "/st", timeStr,
                "/ru", "SYSTEM",
                "/rl", "HIGHEST",
                "/f");
            log.info("Create task result code: {}", createRes);
            if (createRes == 0) {
                int runRes = runCommand("schtasks", "/run", "/tn", taskName);
                log.info("Run task result code: {}", runRes);
                if (runRes == 0) {
                    st.setRunning(true);
                    st.setTaskName(taskName);
                    log.info("Script {} started successfully.", st.getFile().getName());
                    showNotification("Success", st.getFile().getName());
                } else {
                    log.error("Failed to run task: {} for script {}", taskName, st.getFile().getName());
                }
            } else {
                log.error("Failed to create task for script {}", st.getFile().getName());
            }
        } catch (Exception e) {
            log.error("Run script error for {}: {}", st.getFile().getName(), e.getMessage(), e);
        }
    }

    private static void stopScript(ScriptTask st) {
        if (!st.isRunning()) {
            log.info("Script {} is not running.", st.getFile().getName());
            showNotification("Not Running", st.getFile().getName());
            return;
        }
        if (st.getTaskName() == null || st.getTaskName().isBlank()) {
            log.warn("Script {} has no task name.", st.getFile().getName());
            showNotification("No Task", st.getFile().getName());
            return;
        }
        log.info("Attempting to stop script {}", st.getFile().getName());
        try {
            int res = runCommand("schtasks", "/end", "/tn", st.getTaskName());
            log.info("Stop task result code: {}", res);
            if (res == 0) {
                runCommand("schtasks", "/delete", "/tn", st.getTaskName(), "/f");
                st.setRunning(false);
                st.setTaskName(null);
                log.info("Script {} stopped successfully.", st.getFile().getName());
                showNotification("Stopped", st.getFile().getName());
            } else {
                log.error("Failed to stop task {} for script {}", st.getTaskName(), st.getFile().getName());
                showNotification("Stop Failed", st.getFile().getName());
            }
        } catch (Exception e) {
            log.error("Stop script error for {}: {}", st.getFile().getName(), e.getMessage(), e);
        }
    }

    private static int runCommand(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).start();
        return p.waitFor();
    }

    private static void addCmdFile(PopupMenu menu) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CMD files", "cmd"));
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (f.exists() && f.isFile() && f.getName().toLowerCase().endsWith(".cmd")) {
                scriptTasks.add(new ScriptTask(f));
                log.info("Script added manually: {}", f.getAbsolutePath());
                showNotification("Script Added", f.getName());
                saveConfig();
                updateMenuWithScripts(menu);
            } else {
                showNotification("Invalid File", "Not a valid .cmd");
            }
        }
    }

    private static void enableAutostart() {
        try {
            String exePath = new File(TryUCmd.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
            runCommand("reg", "add", "HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "TryUCmd", "/t", "REG_SZ", "/d", exePath, "/f");
            log.info("Autostart enabled");
        } catch (Exception e) {
            log.error("Error enabling autostart: {}", e.getMessage(), e);
        }
    }

    private static void disableAutostart() {
        try {
            runCommand("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "TryUCmd", "/f");
            log.info("Autostart disabled");
        } catch (Exception e) {
            log.error("Error disabling autostart: {}", e.getMessage(), e);
        }
    }

    private static MenuItem createMenuItem(String label, ActionListener action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(action);
        return item;
    }

    private static void showNotification(String title, String msg) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, msg, TrayIcon.MessageType.INFO);
        }
    }

    @Getter
    static class ScriptTask {
        private final File file;
        @lombok.Setter
        private String taskName;
        @lombok.Setter
        private boolean running;

        ScriptTask(File file) {
            this.file = file;
        }
    }
}
