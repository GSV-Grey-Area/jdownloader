//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.gui.swing.dialog;

import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.lang.management.MemoryPoolMXBean;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;

import jd.SecondLevelLaunch;
import jd.controlling.ClipboardMonitoring;
import jd.gui.swing.Factory;
import jd.gui.swing.components.linkbutton.JLink;
import jd.gui.swing.jdgui.JDGui;
import jd.nutils.io.JDIO;
import net.miginfocom.swing.MigLayout;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.swing.MigPanel;
import org.appwork.swing.components.ExtButton;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.IO;
import org.appwork.utils.ReflectionUtils;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.Time;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.os.Docker;
import org.appwork.utils.os.Snap;
import org.appwork.utils.os.hardware.HardwareType;
import org.appwork.utils.swing.dialog.AbstractDialog;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.appwork.utils.swing.dialog.Dialog;
import org.appwork.utils.swing.dialog.DialogNoAnswerException;
import org.jdownloader.actions.AppAction;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.notify.BasicNotify;
import org.jdownloader.gui.notify.BubbleNotify;
import org.jdownloader.gui.notify.BubbleNotify.AbstractNotifyWindowFactory;
import org.jdownloader.gui.notify.gui.AbstractNotifyWindow;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.AbstractIcon;

public class AboutDialog extends AbstractDialog<Integer> {
    private int labelHeight;

    public AboutDialog() {
        super(UIOManager.BUTTONS_HIDE_CANCEL | UIOManager.BUTTONS_HIDE_OK | Dialog.STYLE_HIDE_ICON, _GUI.T.jd_gui_swing_components_AboutDialog_title(), null, null, null);
    }

    @Override
    protected Integer createReturnValue() {
        return null;
    }

    @Override
    protected boolean isResizable() {
        return false;
    }

    public static void showNonBlocking() {
        final AboutDialog aboutDialog = new AboutDialog();
        aboutDialog.setModalityType(ModalityType.MODELESS);
        new Thread("AboutDialog") {
            {
                setDaemon(true);
            }

            @Override
            public void run() {
                try {
                    Dialog.getInstance().showDialog(aboutDialog);
                } catch (DialogNoAnswerException e1) {
                }
            }
        }.start();
    }

    @Override
    public JComponent layoutDialogContent() {
        this.labelHeight = new JLabel("HeightTester").getPreferredSize().height;
        final JPanel contentpane = new JPanel();
        JLabel lbl = new JLabel("JDownloader® 2", JLabel.CENTER);
        lbl.setFont(lbl.getFont().deriveFont(lbl.getFont().getSize() * 2.0f));
        final JPanel links = new JPanel(new MigLayout("ins 0", "[]push[]push[]push[]"));
        try {
            final File file = Application.getResource("licenses/jdownloader.license");
            if (file.isFile()) {
                JButton btn = Factory.createButton(_GUI.T.jd_gui_swing_components_AboutDialog_license(), new AbstractIcon(IconKey.ICON_PREMIUM, 16), new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        String license = JDIO.readFileToString(file);
                        try {
                            ConfirmDialog d = new ConfirmDialog(Dialog.STYLE_LARGE | Dialog.STYLE_HIDE_ICON | UIOManager.BUTTONS_HIDE_CANCEL, _GUI.T.jd_gui_swing_components_AboutDialog_license_title(), license, null, null, null) {
                                @Override
                                protected boolean isResizable() {
                                    return true;
                                }
                            };
                            d.setPreferredSize(JDGui.getInstance().getMainFrame().getSize());
                            Dialog.getInstance().showDialog(d);
                        } catch (DialogNoAnswerException ignore) {
                        }
                    }
                });
                btn.setBorder(null);
                links.add(btn);
            }
            links.add(new JLink(_GUI.T.jd_gui_swing_components_AboutDialog_homepage(), new AbstractIcon(IconKey.ICON_URL, 16), new URL("https://jdownloader.org/home")));
            links.add(new JLink(_GUI.T.jd_gui_swing_components_AboutDialog_forum(), new AbstractIcon(IconKey.ICON_BOARD, 16), new URL("https://board.jdownloader.org/")));
            links.add(new JLink(_GUI.T.jd_gui_swing_components_AboutDialog_ticket(), new AbstractIcon(IconKey.ICON_BOARD, 16), new URL("https://support.jdownloader.org/")));
            links.add(new JLink(_GUI.T.jd_gui_swing_components_AboutDialog_contributers(), new AbstractIcon(IconKey.ICON_CONTRIBUTER, 16), new URL("https://svn.jdownloader.org/projects/jd")));
        } catch (MalformedURLException e1) {
            e1.printStackTrace();
        }
        contentpane.setLayout(new MigLayout("ins 10, wrap 1", "[grow,fill]"));
        contentpane.add(new JLabel(new AbstractIcon(IconKey.ICON_LOGO_JD_LOGO_64_64, -1)), "aligny center, spany 6");
        contentpane.add(lbl, "");
        MigPanel stats = new MigPanel("ins 0,wrap 2", "[][grow,align right]", "[]");
        contentpane.add(stats, "pushx,growx,spanx");
        Map<String, Object> map = null;
        try {
            stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_trademark()), "spanx,alignx center");
            try {
                final File buildJson = Application.getResource("build.json");
                if (buildJson.isFile()) {
                    map = JSonStorage.restoreFromString(IO.readFileToString(buildJson), TypeRef.HASHMAP);
                }
            } catch (Exception e) {
                org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().log(e);
            }
            if (map != null) {
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_builddate()));
                stats.add(disable(map.get("buildDate")));
            }
            stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_runtime()));
            stats.add(disable(TimeFormatter.formatMilliSeconds(Time.systemIndependentCurrentJVMTimeMillis() - SecondLevelLaunch.startup, 0)));
            try {
                stats.add(new JLabel("Java:"), "");
                java.lang.management.MemoryUsage memory = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                ExtButton comp;
                stats.add(comp = disable(System.getProperty("java.vendor") + " - " + System.getProperty("java.runtime.name") + " - " + System.getProperty("java.version") + (Application.is64BitJvm() ? "(64bit)" : "(32bit)")));
                comp.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        CrossSystem.showInExplorer(new File(CrossSystem.getJavaBinary()));
                        try {
                            final java.lang.management.RuntimeMXBean runtimeMxBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
                            final List<String> arguments = runtimeMxBean.getInputArguments();
                            final StringBuilder sb = new StringBuilder();
                            for (String s : arguments) {
                                if (sb.length() > 0) {
                                    sb.append(" ");
                                }
                                sb.append(s);
                            }
                            final StringSelection selection = new StringSelection(sb.toString());
                            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                            clipboard.setContents(selection, selection);
                        } catch (final Throwable e1) {
                        }
                    }
                });
                try {
                    final java.lang.management.RuntimeMXBean runtimeMxBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
                    final List<String> arguments = runtimeMxBean.getInputArguments();
                    final StringBuilder sb = new StringBuilder();
                    for (String s : arguments) {
                        if (sb.length() > 0) {
                            sb.append("\r\n");
                        }
                        sb.append(s);
                    }
                    comp.setToolTipText(sb.toString());
                } catch (final Throwable e1) {
                    org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().log(e1);
                }
                stats.add(new JLabel("OS:"), "");
                stats.add(disable(CrossSystem.getOSFamily() + "(" + CrossSystem.getOS() + ")"));
                stats.add(new JLabel("Memory:"), "");
                stats.add(comp = disable("Usage: " + SizeFormatter.formatBytes(memory.getUsed()) + " - Allocated: " + SizeFormatter.formatBytes(memory.getCommitted()) + " - Max: " + SizeFormatter.formatBytes(memory.getMax())));
                try {
                    final List<MemoryPoolMXBean> memoryPoolMXBeans = java.lang.management.ManagementFactory.getMemoryPoolMXBeans();
                    final StringBuilder sb = new StringBuilder();
                    for (final MemoryPoolMXBean memoryPoolMXBean : memoryPoolMXBeans) {
                        if (sb.length() > 0) {
                            sb.append("\r\n");
                        }
                        sb.append("Pool:").append(memoryPoolMXBean.getName()).append("\r\n");
                        sb.append("Type:").append(memoryPoolMXBean.getType()).append("\r\n");
                        sb.append("Managed by:").append(Arrays.toString(memoryPoolMXBean.getMemoryManagerNames())).append("\r\n");
                        memory = memoryPoolMXBean.getCollectionUsage();
                        if (memory != null) {
                            sb.append("Usage: " + SizeFormatter.formatBytes(memory.getUsed()) + " - Allocated: " + SizeFormatter.formatBytes(memory.getCommitted()) + " - Max: " + SizeFormatter.formatBytes(memory.getMax()));
                        }
                        sb.append("\r\n");
                    }
                    comp.setToolTipText(sb.toString());
                } catch (final Throwable e1) {
                    org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().log(e1);
                }
            } catch (final Throwable e) {
                org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().log(e);
            }
            stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_installdir()), "");
            ExtButton bt;
            final File directory = Application.getResource(".");
            final String fullPath = "<html><u>" + directory + "</u></html>";
            final String anonPath = "<html><u>" + _GUI.T.jd_gui_swing_components_AboutDialog_installdir_anon() + "</u></html>";
            stats.add(bt = disable(anonPath));
            final ExtButton directoryButton = bt;
            bt.addMouseListener(new MouseListener() {
                @Override
                public void mouseReleased(MouseEvent e) {
                }

                @Override
                public void mousePressed(MouseEvent e) {
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    directoryButton.setText(anonPath);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    directoryButton.setText(fullPath);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                }
            });
            bt.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    CrossSystem.openFile(directory);
                }
            });
            if (Snap.isInsideSnap() || Docker.isInsideDocker() || HardwareType.getHardware() != null) {
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_environment()), "spanx");
                if (HardwareType.getHardware() != null) {
                    stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_hardware()), "gapleft 10");
                    stats.add(disable(HardwareType.getHardware().toString()));
                }
                if (Snap.isInsideSnap()) {
                    stats.add(new JLabel("Snap:"), "gapleft 10");
                    stats.add(disable(Snap.getSnapInstanceName()));
                }
                if (Docker.isInsideDocker()) {
                    stats.add(new JLabel("Docker:"), "gapleft 10");
                    stats.add(disable(Docker.getDockerContainerID()));
                }
            }
            if (map != null) {
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_sourcerevisions()), "spanx");
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_core()), "gapleft 10");
                stats.add(disable("#" + map.get("JDownloaderRevision"), "https://svn.jdownloader.org/build.php?check=" + map.get("JDownloaderRevision")));
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_launcher()), "gapleft 10");
                stats.add(disable("#" + map.get("JDownloaderUpdaterRevision")));
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_appworkutilities()), "gapleft 10");
                stats.add(disable("#" + map.get("AppWorkUtilsRevision")));
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_browser()), "gapleft 10");
                stats.add(disable("#" + map.get("JDBrowserRevision")));
                stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_updater()), "gapleft 10");
                stats.add(disable("#" + map.get("UpdateClientV2Revision")));
            }
        } catch (Throwable t) {
            org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().log(t);
        }
        contentpane.add(lbl = new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_mopdules()), "gaptop 10, spanx");
        stats = new MigPanel("ins 0 10 0 0,wrap 2", "[][grow,align right]", "[]");
        contentpane.add(stats, "pushx,growx,spanx");
        stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_core()), "");
        stats.add(disable("Copyright \u00A9 2009-2021 AppWork GmbH"));
        stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_plugins()), "");
        stats.add(disable("Copyright \u00A9 2009-2021 JDownloader Community"));
        stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_translations()), "");
        stats.add(disable("Copyright \u00A9 2009-2021 JDownloader Community"));
        stats.add(new JLabel("JSON Support:"), "");
        stats.add(disable("Jackson JSON Processor 2.7.9", "https://github.com/FasterXML/jackson/"));
        stats.add(new JLabel("Java Native Access:"), "");
        stats.add(disable("JNA 5.8.0", "https://github.com/java-native-access/jna"));
        stats.add(new JLabel("RTMP Support:"), "");
        stats.add(disable("RtmpDump", "https://rtmpdump.mplayerhq.hu"));
        stats.add(new JLabel("UPNP:"), "");
        stats.add(disable("Cling", "https://github.com/4thline/cling"));
        stats.add(new JLabel("Extraction:"));
        stats.add(disable("7ZipJBindings (" + get7ZipJBindingDetails() + ")", "https://github.com/borisbrodski/sevenzipjbinding"));
        stats.add(disable("Zip4J", "https://github.com/srikanth-lingala/zip4j"), "skip");
        final LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf != null) {
            stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_laf()), "");
            if (StringUtils.containsIgnoreCase(laf.getID(), "FlatLaf")) {
                stats.add(disable(laf.getName(), "https://www.formdev.com/flatlaf/"));
            } else if (StringUtils.containsIgnoreCase(laf.getID(), "Synthetica")) {
                stats.add(disable(laf.getName(), "http://www.jyloo.com/synthetica/"));
                try {
                    final Object info = UIManager.get("Synthetica.license.info");
                    if (info instanceof String[]) {
                        final String license = StringUtils.join(Arrays.asList((String[]) info), "\r");
                        final String Licensee = new Regex(license, "Licensee\\s*=\\s*([^\r\n]+)").getMatch(0);
                        final String LicenseRegistrationNumber = new Regex(license, "LicenseRegistrationNumber\\s*=\\s*([^\r\n]+)").getMatch(0);
                        stats.add(disable(_GUI.T.jd_gui_swing_components_AboutDialog_synthetica2(Licensee + "(#" + LicenseRegistrationNumber + ")")), "skip");
                    }
                } catch (Throwable ignore) {
                }
            } else if (StringUtils.containsIgnoreCase(laf.getID(), "Substance")) {
                stats.add(disable(laf.getName(), "https://github.com/kirill-grouchnikov/radiance"));
            } else {
                stats.add(disable(laf.getName(), ""));
            }
        }
        stats.add(new JLabel(_GUI.T.jd_gui_swing_components_AboutDialog_icons()), "");
        stats.add(disable("See /themes/* folder for Icon Licenses"), "");
        stats.add(disable("Icons8", "https://icons8.com"), "skip");
        stats.add(disable("Tango Icons", "https://en.wikipedia.org/wiki/Tango_Desktop_Project"), "skip");
        stats.add(disable("FatCow-Farm Fresh Icons", "https://www.fatcow.com/free-icons"), "skip");
        stats.add(disable("Mimi Glyphs Set", "http://salleedesign.com/blog/mimi-glyphs/"), "skip");
        stats.add(disable("Bright Mix Set", "http://brightmix.com/blog/brightmix-icon-set-free-for-all/"), "skip");
        stats.add(disable("Picol Icon Set", "http://www.picol.org/"), "skip");
        stats.add(disable("Aha Soft Icon Set", "http://www.aha-soft.com"), "skip");
        stats.add(disable("Oxygen Team", "https://techbase.kde.org/Projects/Oxygen/Licensing"), "skip");
        stats.add(disable("further icons by AppWork GmbH"), "skip");
        stats.add(disable("& the JDownloader Community"), "skip");
        contentpane.add(links, "gaptop 15, growx, pushx, spanx");
        this.registerEscape(contentpane);
        return contentpane;
    }

    private String get7ZipJBindingDetails() {
        String version = "4.65";
        try {
            version = ReflectionUtils.invoke("net.sf.sevenzipjbinding.SevenZip", "getSevenZipJBindingVersion", null, String.class, new Object[0]);
            final String usedPlatform = ReflectionUtils.invoke("net.sf.sevenzipjbinding.SevenZip", "getUsedPlatform", null, String.class, new Object[0]);
            if (usedPlatform != null) {
                return version + "/" + usedPlatform;
            }
        } catch (final Throwable ignore) {
        }
        return version;
    }

    private ExtButton disable(final Object object, final String url) {
        ExtButton ret = new ExtButton(new AppAction() {
            /**
             *
             */
            private static final long serialVersionUID = 1L;
            {
                if (StringUtils.startsWithCaseInsensitive(url, "http") && CrossSystem.isOpenBrowserSupported()) {
                    setName("<html><u>" + object + "</u></html>");
                } else {
                    setName(String.valueOf(object));
                }
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                ClipboardMonitoring.getINSTANCE().setCurrentContent(getName());
                if (StringUtils.startsWithCaseInsensitive(url, "http") && CrossSystem.isOpenBrowserSupported()) {
                    CrossSystem.openURL(url);
                } else {
                    BubbleNotify.getInstance().show(new AbstractNotifyWindowFactory() {
                        @Override
                        public AbstractNotifyWindow<?> buildAbstractNotifyWindow() {
                            return new BasicNotify(_GUI.T.lit_clipboard(), _GUI.T.AboutDialog_actionPerformed_clipboard_(getName()), new AbstractIcon(IconKey.ICON_CLIPBOARD, 20));
                        }
                    });
                }
            }
        });
        ret.setBorderPainted(false);
        ret.setContentAreaFilled(false);
        ret.setEnabled(true);
        ret.setMaximumSize(new Dimension(1000, labelHeight));
        return ret;
    }

    private ExtButton disable(final Object object) {
        return disable(object, null);
    }
}