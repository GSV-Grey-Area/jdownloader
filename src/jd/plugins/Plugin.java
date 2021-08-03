//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
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
package jd.plugins;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Icon;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.SubConfiguration;
import jd.controlling.accountchecker.AccountChecker.AccountCheckJob;
import jd.controlling.accountchecker.AccountCheckerThread;
import jd.controlling.downloadcontroller.SingleDownloadController;
import jd.controlling.linkchecker.LinkCheckerThread;
import jd.controlling.linkcrawler.CrawledLink;
import jd.controlling.linkcrawler.LinkCrawler;
import jd.controlling.linkcrawler.LinkCrawler.LinkCrawlerGeneration;
import jd.controlling.linkcrawler.LinkCrawlerDeepInspector;
import jd.controlling.linkcrawler.LinkCrawlerThread;
import jd.controlling.reconnect.ipcheck.BalancedWebIPCheck;
import jd.controlling.reconnect.ipcheck.IPCheckException;
import jd.controlling.reconnect.ipcheck.OfflineException;
import jd.http.Browser;
import jd.http.Browser.BrowserException;
import jd.http.BrowserSettingsThread;
import jd.http.ProxySelectorInterface;
import jd.http.StaticProxySelector;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.utils.JDUtilities;

import org.appwork.exceptions.WTFException;
import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.uio.CloseReason;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Exceptions;
import org.appwork.utils.Hash;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.logging2.extmanager.LoggerFactory;
import org.appwork.utils.net.httpconnection.HTTPConnectionUtils;
import org.appwork.utils.net.httpconnection.HTTPConnectionUtils.DispositionHeader;
import org.appwork.utils.net.httpconnection.HTTPProxy;
import org.jdownloader.auth.AuthenticationInfo.Type;
import org.jdownloader.auth.Login;
import org.jdownloader.captcha.v2.Challenge;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.RecaptchaV2Challenge;
import org.jdownloader.captcha.v2.solverjob.SolverJob;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.dialog.AskForUserAndPasswordDialog;
import org.jdownloader.gui.dialog.AskUsernameAndPasswordDialogInterface;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.logging.LogController;
import org.jdownloader.plugins.UserIOProgress;
import org.jdownloader.plugins.config.AccountConfigInterface;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginHost;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.plugins.controller.PluginClassLoader;
import org.jdownloader.plugins.controller.UpdateRequiredClassNotFoundException;
import org.jdownloader.plugins.controller.crawler.CrawlerPluginController;
import org.jdownloader.plugins.controller.crawler.LazyCrawlerPlugin;
import org.jdownloader.plugins.controller.host.HostPluginController;
import org.jdownloader.plugins.controller.host.LazyHostPlugin;
import org.jdownloader.settings.staticreferences.CFG_CAPTCHA;
import org.jdownloader.translate._JDT;

/**
 * Diese abstrakte Klasse steuert den Zugriff auf weitere Plugins. Alle Plugins müssen von dieser Klasse abgeleitet werden.
 *
 * Alle Plugins verfügen über einen Event Mechanismus
 */
public abstract class Plugin implements ActionListener {
    public static final String                 HTTP_LINKS_HOST     = "http links";
    public static final String                 DIRECT_HTTP_HOST    = "DirectHTTP";
    public static final String                 FTP_HOST            = "ftp";
    /* to keep 0.95xx comp */
    /* switch this on every stable update */
    // protected static Logger logger = jd.controlling.JDLogger.getLogger();
    /* after 0.95xx */
    protected LogInterface                     logger              = LogController.TRASH;
    protected final CopyOnWriteArrayList<File> cleanUpCaptchaFiles = new CopyOnWriteArrayList<File>();
    private CrawledLink                        currentLink         = null;

    public void setLogger(LogInterface logger) {
        if (logger == null) {
            logger = LogController.TRASH;
        }
        //
        this.logger = logger;
    }

    /** Returns '(?:domain1|domain2)' */
    public static String buildHostsPatternPart(String[] domains) {
        final StringBuilder pattern = new StringBuilder();
        pattern.append("(?:");
        for (int i = 0; i < domains.length; i++) {
            final String domain = domains[i];
            if (i > 0) {
                pattern.append("|");
            }
            pattern.append(Pattern.quote(domain));
        }
        pattern.append(")");
        return pattern.toString();
    }

    protected String getMappedHost(List<String[]> pluginDomains, String host) {
        for (final String[] domains : pluginDomains) {
            for (final String domain : domains) {
                if (StringUtils.equalsIgnoreCase(host, domain)) {
                    return domains[0];// return first domain = plugin domain = getHost();
                }
            }
        }
        return null;
    }

    protected boolean looksLikeDownloadableContent(final URLConnectionAdapter urlConnection) {
        return new LinkCrawlerDeepInspector() {
            @Override
            public List<CrawledLink> deepInspect(LinkCrawler lc, LinkCrawlerGeneration generation, Browser br, URLConnectionAdapter urlConnection, CrawledLink link) throws Exception {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }.looksLikeDownloadableContent(urlConnection);
    }

    protected static String[] buildAnnotationNames(List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>(pluginDomains.size());
        for (final String[] domains : pluginDomains) {
            ret.add(domains[0]);
        }
        return ret.toArray(new String[0]);
    }

    public String getExtensionFromMimeType(final String contentType) {
        final String mimeType = new Regex(contentType, "(\\w+/[\\w\\-]+)").getMatch(0);
        if (StringUtils.isEmpty(mimeType)) {
            return null;
        } else {
            // developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types
            final HashMap<String, String> map = new HashMap<String, String>();
            map.put("application/x-7z-compressed", "7z");
            map.put("image/gif", "gif");
            map.put("image/jpeg", "jpg");
            map.put("image/png", "png");
            map.put("image/apng", "apng");
            map.put("image/tiff", "tiff");
            map.put("image/webp", "webp");
            map.put("video/mp4", "mp4");
            map.put("video/ogg", "ogg");
            map.put("video/webm", "webm");
            map.put("audio/mp3", "mp3");
            map.put("audio/mp4", "mp4");
            map.put("audio/x-flac", "flac");
            map.put("audio/wav", "wav");
            map.put("text/css", "css");
            map.put("text/javascript", "js");
            map.put("text/xml", "xml");
            map.put("text/html", "html");
            map.put("text/plain", "txt");
            map.put("application/x-xz", "xz");
            map.put("application/gzip", "gz");
            map.put("application/json", "json");
            map.put("application/xml", "xml");
            map.put("application/pdf", "pdf");
            return map.get(mimeType.toLowerCase(Locale.ENGLISH));
        }
    }

    protected String[] buildSupportedNames(List<String[]> pluginDomains) {
        for (final String[] domains : pluginDomains) {
            if (StringUtils.equalsIgnoreCase(getHost(), domains[0])) {
                return domains;
            }
        }
        throw new WTFException();
    }

    public abstract String getCrawlerLoggerID(CrawledLink link);

    public abstract void runCaptchaDDosProtection(String id) throws InterruptedException;

    protected String getBrowserReferrer() {
        final LinkCrawler crawler = getCrawler();
        if (crawler != null) {
            return crawler.getReferrerUrl(getCurrentLink());
        }
        return null;
    }

    public CrawledLink getCurrentLink() {
        return currentLink;
    }

    public void setCurrentLink(CrawledLink currentLink) {
        this.currentLink = currentLink;
    }

    public LogInterface getLogger() {
        return logger;
    }

    protected LinkCrawler getCrawler() {
        if (Thread.currentThread() instanceof LinkCrawlerThread) {
            /* not sure why we have this here? */
            final LinkCrawler ret = ((LinkCrawlerThread) Thread.currentThread()).getCurrentLinkCrawler();
            if (ret != null) {
                return ret;
            }
        }
        return null;
    }

    public boolean isHandlingMultipleHosts() {
        if (this instanceof PluginForHost) {
            try {
                final Method method = this.getClass().getMethod("getHost", new Class[] { DownloadLink.class, Account.class });
                final boolean ret = method.getDeclaringClass() != PluginForHost.class;
                return ret;
            } catch (Throwable e) {
            }
        }
        return false;
    }

    public boolean isProxyRotationEnabled(boolean premiumDownload) {
        return !premiumDownload;
    }

    /**
     * Gibt nur den Dateinamen aus der URL extrahiert zurück. Um auf den dateinamen zuzugreifen sollte bis auf Ausnamen immer
     * DownloadLink.getName() verwendet werden
     *
     * @return Datename des Downloads.
     */
    public static String extractFileNameFromURL(final String iFilename) {
        String filename = iFilename;
        if (StringUtils.isEmpty(filename)) {
            return null;
        }
        int index = filename.indexOf("?");
        /*
         * cut off get url parameters
         */
        if (index > 0) {
            filename = filename.substring(0, index);
        }
        index = filename.indexOf("#");
        /* cut off anchor */
        if (index > 0) {
            filename = filename.substring(0, index);
        }
        index = Math.max(filename.lastIndexOf("/"), filename.lastIndexOf("\\"));
        /*
         * use filename
         */
        filename = filename.substring(index + 1);
        return Encoding.htmlDecode(filename);
    }

    public static String getFileNameFromDispositionHeader(final URLConnectionAdapter urlConnection) {
        final DispositionHeader dispositionHeader = parseDispositionHeader(urlConnection);
        if (dispositionHeader != null) {
            return dispositionHeader.getFilename();
        } else {
            return null;
        }
    }

    public static DispositionHeader parseDispositionHeader(final URLConnectionAdapter urlConnection) {
        final String contentDisposition = urlConnection.getHeaderField(HTTPConstants.HEADER_RESPONSE_CONTENT_DISPOSITION);
        return HTTPConnectionUtils.parseDispositionHeader(contentDisposition);
    }

    /**
     * Determines file extension, from provided String. <br />
     * Must be a valid URL otherwise failover will be returned <br />
     * This should be more fail proof than getFileNameExtensionFromString
     *
     * @since JD2
     * @author raztoki
     * @throws MalformedURLException
     */
    public static String getFileNameExtensionFromURL(final String url, final String failover) {
        if (url == null) {
            return null;
        }
        try {
            final String output = Plugin.getFileNameFromURL(new URL(url));
            if (output != null && output.contains(".")) {
                return output.substring(output.lastIndexOf("."));
            }
        } catch (final MalformedURLException e) {
        }
        return failover;
    }

    /**
     * Wrapper
     *
     * @since JD2
     * @author raztoki
     * @param url
     * @return
     * @throws MalformedURLException
     */
    public static String getFileNameExtensionFromURL(final String url) {
        return getFileNameExtensionFromURL(url, null);
    }

    /**
     * Determines file extension, from a provided String. <br />
     * Can be used without a VALID URL, or part url or just filename. <br />
     * Will fix file.exe+junk etc.
     *
     * @since JD2
     * @author raztoki
     */
    public static String getFileNameExtensionFromString(final String filename, final String failover) {
        if (filename == null) {
            return null;
        }
        final String output = extractFileNameFromURL(filename);
        if (output != null && output.contains(".")) {
            return output.substring(output.lastIndexOf("."));
        }
        return failover;
    }

    /**
     * Wrapper
     *
     * @since JD2
     * @author raztoki
     * @param filename
     * @return
     */
    public static String getFileNameExtensionFromString(final String filename) {
        return getFileNameExtensionFromString(filename, null);
    }

    public String getPluginVersionHash() {
        Class<?> clazz = getClass();
        final StringBuilder sb = new StringBuilder();
        sb.append(getHost());
        sb.append(getVersion());
        while (clazz != null && Plugin.class.isAssignableFrom(clazz)) {
            final HostPlugin hostPlugin;
            final DecrypterPlugin decryptPlugin;
            if ((hostPlugin = clazz.getAnnotation(HostPlugin.class)) != null) {
                sb.append("\r\n");
                sb.append(clazz.getName());
                sb.append(hostPlugin.revision());
            } else if ((decryptPlugin = clazz.getAnnotation(DecrypterPlugin.class)) != null) {
                sb.append("\r\n");
                sb.append(clazz.getName());
                sb.append(decryptPlugin.revision());
            }
            clazz = clazz.getSuperclass();
        }
        if (sb.length() > 0) {
            return Hash.getSHA256(sb.toString());
        } else {
            return null;
        }
    }

    /**
     * Holt den Dateinamen aus einem Content-Disposition header. wird dieser nicht gefunden, wird der dateiname aus der url ermittelt
     *
     * @param urlConnection
     * @return Filename aus dem header (content disposition) extrahiert
     */
    public static String getFileNameFromHeader(final URLConnectionAdapter urlConnection) {
        final String fileName = getFileNameFromDispositionHeader(urlConnection);
        if (StringUtils.isEmpty(fileName)) {
            return Plugin.getFileNameFromURL(urlConnection.getURL());
        } else {
            return fileName;
        }
    }

    public static String getFileNameFromURL(final URL url) {
        return Plugin.extractFileNameFromURL(url.getPath());
    }

    /**
     * Corrects extension of given filename. Adds extension if it is missing. Returns null if given filename is null. </br> Pass
     * fileExtension with dot(s) to this! </br> Only replaces extensions with one dot e.g. ".mp4", NOT e.g. ".tar.gz".
     *
     * @param filenameOrg
     *            Original filename
     * @param newExtension
     *            New extension for filename
     *
     *
     * @return Filename with new extension
     */
    protected String correctOrApplyFileNameExtension(final String filenameOrg, final String newExtension) {
        if (StringUtils.isEmpty(filenameOrg) || StringUtils.isEmpty(newExtension)) {
            return filenameOrg;
        } else if (!filenameOrg.contains(".")) {
            /* Filename doesn't contain an extension at all -> Add extension to filename. */
            return filenameOrg + newExtension;
        } else {
            /* Replace existing extension with new extension. */
            final String filenameWithoutExtension = filenameOrg.substring(0, filenameOrg.lastIndexOf("."));
            return filenameWithoutExtension + newExtension;
        }
    }

    /** Adds extension to given filename (it it's not already in this filename). */
    public static String applyFilenameExtension(final String filenameOrg, final String fileExtension) {
        if (filenameOrg == null) {
            return null;
        } else if (fileExtension == null) {
            return filenameOrg;
        }
        if (filenameOrg.toLowerCase(Locale.ENGLISH).endsWith(fileExtension)) {
            /* Filename already contains target-extension. */
            return filenameOrg;
        } else {
            return filenameOrg + fileExtension;
        }
    }

    protected boolean isConnectionOffline(Throwable e) {
        HTTPProxy proxy = null;
        final BrowserException browserException = Exceptions.getInstanceof(e, BrowserException.class);
        if (browserException != null && browserException.getRequest() != null) {
            proxy = browserException.getRequest().getProxy();
        }
        if (proxy == null) {
            final Plugin plugin = getCurrentActivePlugin();
            if (plugin != null) {
                final Browser br;
                if (plugin instanceof PluginForHost) {
                    br = ((PluginForHost) plugin).getBrowser();
                } else if (plugin instanceof PluginForDecrypt) {
                    br = ((PluginForDecrypt) plugin).getBrowser();
                } else {
                    br = null;
                }
                if (br != null && br.getRequest() != null) {
                    proxy = br.getRequest().getProxy();
                }
            }
        }
        final ProxySelectorInterface proxySelector;
        if (proxy != null) {
            proxySelector = new StaticProxySelector(proxy);
        } else {
            proxySelector = BrowserSettingsThread.getThreadProxySelector();
        }
        final BalancedWebIPCheck onlineCheck = new BalancedWebIPCheck(proxySelector);
        try {
            onlineCheck.getExternalIP();
        } catch (final OfflineException e2) {
            return true;
        } catch (final IPCheckException e2) {
        }
        return false;
    }

    protected boolean isAbort() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread instanceof SingleDownloadController) {
            final SingleDownloadController sdc = (SingleDownloadController) currentThread;
            return sdc.isAborting() || currentThread.isInterrupted();
        }
        return currentThread.isInterrupted();
    }

    /**
     * Show a USername + password dialog
     *
     * @param link
     * @return
     * @throws PluginException
     */
    protected Login requestLogins(String message, String realm, DownloadLink link) throws PluginException {
        if (message == null) {
            message = _JDT.T.Plugin_requestLogins_message();
        }
        final UserIOProgress prg = new UserIOProgress(message);
        prg.setProgressSource(this);
        prg.setDisplayInProgressColumnEnabled(false);
        try {
            link.addPluginProgress(prg);
            final AskUsernameAndPasswordDialogInterface handle = UIOManager.I().show(AskUsernameAndPasswordDialogInterface.class, new AskForUserAndPasswordDialog(message, link));
            if (handle.getCloseReason() == CloseReason.OK) {
                final String password = handle.getPassword();
                if (StringUtils.isEmpty(password)) {
                    throw new PluginException(LinkStatus.ERROR_FATAL, _JDT.T.plugins_errors_wrongpassword());
                }
                final String username = handle.getUsername();
                if (StringUtils.isEmpty(username)) {
                    throw new PluginException(LinkStatus.ERROR_FATAL, _JDT.T.plugins_errors_wrongusername());
                }
                final Type type;
                if (StringUtils.startsWithCaseInsensitive(link.getPluginPatternMatcher(), "ftp")) {
                    type = Type.FTP;
                } else {
                    type = Type.HTTP;
                }
                return new Login(type, realm, link.getHost(), username, password, false) {
                    @Override
                    public boolean isRememberSelected() {
                        return handle.isRememberSelected();
                    }
                };
            } else {
                throw new PluginException(LinkStatus.ERROR_FATAL, _JDT.T.plugins_errors_wrongpassword());
            }
        } finally {
            link.removePluginProgress(prg);
        }
    }

    private volatile ConfigContainer config;
    protected Browser                br = null;

    public Plugin() {
    }

    @Deprecated
    public Plugin(final PluginWrapper wrapper) {
    }

    public void actionPerformed(final ActionEvent e) {
        return;
    }

    public static boolean implementsCanHandleString(Plugin plugin) {
        try {
            if (plugin != null) {
                final Method method = plugin.getClass().getMethod("canHandle", String.class);
                final boolean impl = method.getDeclaringClass() != Plugin.class;
                return impl;
            }
        } catch (NoSuchMethodException e) {
        } catch (Throwable e) {
            LogController.CL().log(e);
        }
        return false;
    }

    /**
     * Hier wird geprüft, ob das Plugin diesen Text oder einen Teil davon handhaben kann. Dazu wird einfach geprüft, ob ein Treffer des
     * Patterns vorhanden ist.
     *
     * @param data
     *            der zu prüfende Text
     * @return wahr, falls ein Treffer gefunden wurde.
     */
    public boolean canHandle(final String data) {
        if (data != null) {
            final Matcher matcher = getMatcher();
            synchronized (matcher) {
                try {
                    return matcher.reset(data).find();
                } finally {
                    matcher.reset("");
                }
            }
        }
        return false;
    }

    public abstract Matcher getMatcher();

    public void clean() {
        for (final Plugin plugin : pluginInstances) {
            try {
                plugin.clean();
            } catch (final Throwable e) {
                logger.log(e);
            }
        }
        cleanupLastChallengeResponse();
        br = null;
        for (final File clean : cleanUpCaptchaFiles) {
            if (clean != null && !clean.delete()) {
                clean.deleteOnExit();
            }
        }
    }

    public CrawledLink convert(final DownloadLink link) {
        return new CrawledLink(link);
    }

    /**
     * Gibt das Konfigurationsobjekt der Instanz zurück. Die Gui kann daraus Dialogelement zaubern
     *
     * @return gibt die aktuelle Configuration Instanz zurück
     */
    public ConfigContainer getConfig() {
        if (this.config != null) {
            return config;
        }
        synchronized (this) {
            if (this.config != null) {
                return config;
            }
            this.config = new ConfigContainer(null) {
                private static final long serialVersionUID = -30947319320765343L;

                /**
                 * we dont have to catch icon until it is really needed
                 */
                @Override
                public Icon getIcon() {
                    return new AbstractIcon(IconKey.ICON_WARNING, 16);
                }

                @Override
                public String getTitle() {
                    return getHost();
                }
            };
        }
        return config;
    }

    public boolean hasConfig() {
        final ConfigContainer lconfig = config;
        if (lconfig != null && lconfig.getEntries() != null && lconfig.getEntries().size() > 0) {
            return true;
        }
        return getConfigInterface() != null;
    }

    @Deprecated
    protected boolean hasOldConfigContainer() {
        final ConfigContainer lconfig = config;
        return lconfig != null && lconfig.getEntries() != null && lconfig.getEntries().size() > 0;
    }

    /**
     * Liefert den Anbieter zurück, für den dieses Plugin geschrieben wurde
     *
     * @return Der unterstützte Anbieter
     */
    public abstract String getHost();

    public File getLocalCaptchaFile() {
        return this.getLocalCaptchaFile(".jpg");
    }

    /**
     * Returns the time in ms until a captcha request times out. this can be different for every plugin.
     *
     *
     * @return
     */
    public int getChallengeTimeout(Challenge<?> challenge) {
        return CFG_CAPTCHA.CFG.getDefaultChallengeTimeout();
    }

    public boolean keepAlive(Challenge<?> challenge) {
        if (challenge != null && challenge instanceof RecaptchaV2Challenge) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Gibt die Datei zurück in die der aktuelle captcha geladen werden soll.
     *
     * @param plugin
     * @return Gibt einen Pfad zurück der für die nächste Captchadatei reserviert ist
     */
    public File getLocalCaptchaFile(String extension) {
        if (extension == null) {
            extension = ".jpg";
        }
        final Calendar calendar = Calendar.getInstance();
        final String date = String.format("%1$td.%1$tm.%1$tY_%1$tH.%1$tM.%1$tS.", calendar) + new Random().nextInt(999);
        final File dest = JDUtilities.getResourceFile("captchas/" + this.getHost() + "_" + date + extension, true);
        cleanUpCaptchaFiles.add(dest);
        return dest;
    }

    public String encodeUnicode(final String input) {
        if (input != null) {
            String output = input;
            output = output.replace(":", ";");
            output = output.replace("|", "¦");
            output = output.replace("<", "[");
            output = output.replace(">", "]");
            output = output.replace("/", "⁄");
            output = output.replace("\\", "∖");
            output = output.replace("*", "#");
            output = output.replace("?", "¿");
            // not illegal
            // output = output.replace("!", "¡");
            output = output.replace("!", "¡");
            output = output.replace("\"", "'");
            return output;
        }
        return null;
    }

    /**
     * p gibt das interne properties objekt zurück indem die Plugineinstellungen gespeichert werden
     *
     * @return internes property objekt
     */
    public abstract SubConfiguration getPluginConfig();

    /**
     * Ein regulärer Ausdruck, der anzeigt, welche Links von diesem Plugin unterstützt werden
     *
     * @return Ein regulärer Ausdruck
     * @see Pattern
     */
    public abstract Pattern getSupportedLinks();

    /**
     * Liefert die Versionsbezeichnung dieses Plugins zurück
     *
     * @return Versionsbezeichnung
     */
    public abstract long getVersion();

    /**
     * Initialisiert das Plugin vor dem ersten Gebrauch
     */
    public void init() {
    }

    /**
     * Can be overridden, to return a descriptio of the hoster, or a short help forit's settings
     *
     * @return
     */
    public String getDescription() {
        return null;
    }

    public boolean pluginAPI(String function, Object input, Object output) throws Exception {
        return false;
    }

    private WeakReference<PluginConfigPanelNG> configPanel = null;

    public PluginConfigPanelNG getConfigPanel() {
        PluginConfigPanelNG panel = configPanel == null ? null : configPanel.get();
        if (panel == null) {
            panel = createConfigPanel();
            configPanel = new WeakReference<PluginConfigPanelNG>(panel);
        }
        return panel;
    }

    protected PluginConfigPanelNG createConfigPanel() {
        if (getConfigInterface() != null) {
            PluginConfigPanelNG ret = new PluginConfigPanelNG() {
                @Override
                public void updateContents() {
                }

                @Override
                public void save() {
                }
            };
            return ret;
        }
        return null;
    }

    public Class<? extends PluginConfigInterface> getConfigInterface() {
        for (Class<?> cls : getClass().getClasses()) {
            if (PluginConfigInterface.class.isAssignableFrom(cls) && !AccountConfigInterface.class.isAssignableFrom(cls)) {
                PluginHost anno = cls.getAnnotation(PluginHost.class);
                if (anno != null) {
                    if (StringUtils.equals(anno.host(), getHost())) {
                        return (Class<? extends PluginConfigInterface>) cls;
                    }
                } else {
                    return (Class<? extends PluginConfigInterface>) cls;
                }
            }
        }
        return null;
    }

    public boolean isProxyRotationEnabledForLinkCrawler() {
        // if (AccountController.getInstance().hasAccounts(plg.getHost())) {
        // rly? are there many crawler that require an account?
        return true;
    }

    /**
     * used to disable site testing, for instance names reference is invalid domain (just a place holder). dummyplugins etc.
     *
     * @since JD2
     * @author raztoki
     * @return
     */
    public Boolean siteTesterDisabled() {
        return null;
    }

    /**
     * So plugins can override when one partular site changes away from template defaults. one needs to override.
     *
     * @since JD2
     * @author raztoki
     * @param siteTemplate
     * @param plugin
     * @param br
     * @return
     */
    public Boolean siteTester(final SiteTemplate siteTemplate, final Plugin plugin, final Browser br) {
        return SiteTester.siteTester(siteTemplate, plugin, br);
    }

    /**
     * sets the SiteTemplate defination.
     *
     * @since JD2
     * @author raztoki
     * @return
     */
    public SiteTemplate siteTemplateType() {
        return null;
    }

    /**
     * Used when names url entry contains multiple (sub\.)?domains within URLs. This allows us to return all supported domains.
     *
     * @since JD2
     * @author raztoki
     * @return
     */
    public String[] siteSupportedNames() {
        return null;
    }

    /**
     * extends siteSuportedNames, but allows you to set path, so that siteTemplateConfirmation test is done on the correct URL. Useful when
     * site runs many services on a single domain, but have different base paths for each.
     *
     * @since JD2
     * @author raztoki
     * @return
     */
    public String siteSupportedPath() {
        return null;
    }

    protected List<Challenge<?>> challenges = null;

    /**
     * returns a unmodifiiable List of all challenges done so far in this plugin
     *
     * @return
     */
    public List<Challenge<?>> getChallenges() {
        final List<Challenge<?>> challenges = this.challenges;
        if (challenges == null) {
            return Collections.unmodifiableList(new ArrayList<Challenge<?>>());
        } else {
            return Collections.unmodifiableList(challenges);
        }
    }

    /**
     * returns the current challenge round. if there has been 1 captcha so far, this will return 1
     */
    public int getChallengeRound() {
        final List<Challenge<?>> challenges = this.challenges;
        return challenges == null ? 0 : (challenges.size() - 1);
    }

    /**
     * adds a challenge that has been used in this plugin
     *
     * @param challenge
     */
    public synchronized int addChallenge(Challenge<?> challenge) {
        final List<Challenge<?>> nchallenges = new ArrayList<Challenge<?>>();
        final List<Challenge<?>> old = this.challenges;
        if (old != null) {
            nchallenges.addAll(old);
        }
        nchallenges.add(challenge);
        this.challenges = nchallenges;
        return nchallenges.size() - 1;
    }

    public void invalidateLastChallengeResponse() {
        final List<Challenge<?>> ch = challenges;
        if (ch != null) {
            for (final Challenge<?> c : ch) {
                final SolverJob<?> job = c.getJob();
                if (job != null) {
                    job.invalidate();
                }
            }
        }
    }

    protected PluginForHost getNewPluginForHostInstance(final String host) throws PluginException {
        final LazyHostPlugin lazyHostPlugin = HostPluginController.getInstance().get(host);
        if (lazyHostPlugin != null) {
            return getNewPluginInstance(lazyHostPlugin);
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Could not find PluginForHost:" + host);
        }
    }

    protected PluginForDecrypt getNewPluginForDecryptInstance(final String host) throws PluginException {
        final LazyCrawlerPlugin lazyCrawlerPlugin = CrawlerPluginController.getInstance().get(host);
        if (lazyCrawlerPlugin != null) {
            return getNewPluginInstance(lazyCrawlerPlugin);
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Could not find PluginForDecrypt:" + host);
        }
    }

    protected final CopyOnWriteArrayList<Plugin> pluginInstances = new CopyOnWriteArrayList<Plugin>();

    public void setBrowser(Browser br) {
        this.br = br;
    }

    public Browser getBrowser() {
        return br;
    }

    protected <T> T getNewPluginInstance(final LazyPlugin<?> lazyPlugin) throws PluginException {
        if (lazyPlugin != null) {
            try {
                final Plugin plugin = lazyPlugin.newInstance(PluginClassLoader.getThreadPluginClassLoaderChild());
                pluginInstances.add(plugin);
                plugin.setLogger(getLogger());
                plugin.setBrowser(getBrowser());
                plugin.init();
                return (T) plugin;
            } catch (UpdateRequiredClassNotFoundException e) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Failed to create new instanceof:" + lazyPlugin, e);
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static Plugin getCurrentActivePlugin() {
        final Thread thread = Thread.currentThread();
        if (thread instanceof SingleDownloadController) {
            final Plugin plugin = ((SingleDownloadController) thread).getProcessingPlugin();
            if (plugin != null) {
                return plugin;
            } else {
                return ((SingleDownloadController) thread).getDownloadLinkCandidate().getCachedAccount().getPlugin();
            }
        } else if (thread instanceof AccountCheckerThread) {
            final AccountCheckJob job = ((AccountCheckerThread) thread).getJob();
            if (job != null) {
                return job.getAccount().getPlugin();
            } else {
                return null;
            }
        } else if (thread instanceof LinkCheckerThread) {
            return ((LinkCheckerThread) thread).getPlugin();
        } else if (thread instanceof LinkCrawlerThread) {
            final Object owner = ((LinkCrawlerThread) thread).getCurrentOwner();
            if (owner instanceof Plugin) {
                return (Plugin) owner;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    public synchronized void cleanupLastChallengeResponse() {
        final List<Challenge<?>> ch = challenges;
        challenges = null;
        if (ch != null) {
            for (final Challenge<?> c : ch) {
                if (c != null) {
                    try {
                        c.cleanup();
                    } catch (Throwable e) {
                        final LogInterface logger = getLogger();
                        if (logger != null) {
                            logger.log(e);
                        } else {
                            LoggerFactory.getDefaultLogger().log(e);
                        }
                    }
                }
            }
        }
    }

    public void validateLastChallengeResponse() {
        final List<Challenge<?>> ch = challenges;
        if (ch != null && ch.size() > 0) {
            final Challenge<?> latest = ch.get(ch.size() - 1);
            final SolverJob<?> job = latest.getJob();
            if (job != null) {
                job.validate();
            }
        }
    }

    public boolean hasChallengeResponse() {
        final List<Challenge<?>> challenges = this.challenges;
        return challenges != null && challenges.size() > 0;
    }
}