package jd.plugins.hoster;

import java.net.URL;
//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.StringUtils;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.parser.UrlQuery;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;
import jd.plugins.components.PluginJSonUtils;

//IMPORTANT: this class must stay in jd.plugins.hoster because it extends another plugin (UseNet) which is only available through PluginClassLoader
abstract public class ZeveraCore extends UseNet {
    /* Connection limits */
    private static final boolean  ACCOUNT_PREMIUM_RESUME                      = true;
    private static final int      ACCOUNT_PREMIUM_MAXCHUNKS                   = 0;
    private static final String   PROPERTY_ACCOUNT_successfully_loggedin_once = "successfully_loggedin_once";
    private MultiHosterManagement mhm                                         = null;

    @Override
    @Deprecated
    public void correctDownloadLink(final DownloadLink link) {
        if (isDirecturl(link.getDownloadLink())) {
            /* TODO: Remove this after 2020-10-01 */
            final String new_url = link.getPluginPatternMatcher().replaceAll("[a-z0-9]+decrypted://", "https://");
            link.setPluginPatternMatcher(new_url);
        }
    }

    public ZeveraCore(PluginWrapper wrapper) {
        super(wrapper);
        this.setAccountwithoutUsername(true);
        // this.enablePremium("https://www." + this.getHost() + "/premium");
    }

    @Override
    public String getAGBLink() {
        return "https://www." + this.getHost() + "/legal#tos";
    }

    @Override
    public void init() {
        mhm = new MultiHosterManagement(this.getHost());
    }

    /** Must override!! */
    abstract String getClientID();

    /**
     * Returns whether resume is supported or not for current download mode based on account availability and account type. <br />
     * Override this function to set resume settings!
     */
    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        /* Resume is always possible */
        return true;
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        if (this.isSelfhostedContent(link)) {
            try {
                return UrlQuery.parse(link.getPluginPatternMatcher()).get("id");
            } catch (final Throwable e) {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * Returns how many max. chunks per file are allowed for current download mode based on account availability and account type. <br />
     * Override this function to set chunks settings!
     */
    public int getDownloadModeMaxChunks(final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return 0;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return 0;
        } else {
            /* Free(anonymous) and unknown account type */
            return 0;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        /* Direct URLs can be downloaded without account! */
        return -1;
    }

    public int getMaxSimultaneousFreeAccountDownloads() {
        /* No free downloads at all possible by default */
        return 0;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    protected Browser prepBR(final Browser br) {
        br.setCookiesExclusive(true);
        prepBrowser(br, getHost());
        br.getHeaders().put("User-Agent", "JDownloader");
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public boolean isSpeedLimited(DownloadLink link, Account account) {
        return false;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        if (isUsenetLink(link)) {
            return super.requestFileInformation(link);
        } else if (this.isSelfhostedContent(link)) {
            return requestFileInformationSelfhosted(link, null);
        } else {
            return requestFileInformationDirectURL(link);
        }
    }

    protected AvailableStatus requestFileInformationSelfhosted(final DownloadLink link, Account account) throws Exception {
        /* 2020-07-16: New handling */
        final String fileID = getFID(link);
        if (fileID == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (!link.isNameSet()) {
            /* Set meaningful names in case content is offline */
            link.setName(fileID);
        }
        if (account == null) {
            /* Account required to perform the API request below. */
            account = AccountController.getInstance().getValidAccount(this.getHost());
        }
        if (account == null) {
            /* Cannot check without account */
            return AvailableStatus.UNCHECKABLE;
        }
        /* See: https://app.swaggerhub.com/apis-docs/premiumize.me/api/1.6.7#/ */
        callAPI(this.br, account, "/api/item/details?id=" + fileID);
        this.handleAPIErrors(br, link, account);
        final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final String filename = (String) entries.get("name");
        final long filesize = ((Number) entries.get("size")).longValue();
        if (!StringUtils.isEmpty(filename)) {
            link.setFinalFileName(filename);
        }
        if (filesize > 0) {
            link.setDownloadSize(filesize);
        }
        return AvailableStatus.TRUE;
    }

    @Deprecated
    protected AvailableStatus requestFileInformationDirectURL(final DownloadLink link) throws Exception {
        /* OLD handling: TODO: Remove this after 2020-10-01 */
        URLConnectionAdapter con = null;
        try {
            final Browser brc = br.cloneBrowser();
            brc.setFollowRedirects(true);
            con = openAntiDDoSRequestConnection(brc, brc.createHeadRequest(link.getPluginPatternMatcher()));
            if (!StringUtils.containsIgnoreCase(con.getContentType(), "text") && con.getResponseCode() == 200) {
                if (link.getFinalFileName() == null) {
                    link.setFinalFileName(Encoding.urlDecode(Plugin.getFileNameFromHeader(con), false));
                }
                final long completeContentLength = con.getCompleteContentLength();
                final long verifiedFileSize = link.getVerifiedFileSize();
                if (completeContentLength != verifiedFileSize) {
                    logger.info("Update Filesize: old=" + verifiedFileSize + "|new=" + completeContentLength);
                    link.setVerifiedFileSize(completeContentLength);
                }
                return AvailableStatus.TRUE;
            } else if (con.getResponseCode() == 404) {
                /* Usually 404 == offline */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                /*
                 * E.g. 403 because of bad fair use status (or offline, 2019-05-08: This is confusing, support was told to change it to a
                 * 404 if a cloud file has been deleted by the user and is definitely offline!)
                 */
                return AvailableStatus.UNCHECKABLE;
            }
        } finally {
            try {
                if (con != null) {
                    /* make sure we close connection */
                    con.disconnect();
                }
            } catch (final Throwable e) {
            }
        }
    }

    @Override
    public boolean canHandle(DownloadLink link, Account account) throws Exception {
        if (link != null && this.isDirecturl(link)) {
            /* Such URLs can even be downloaded without account */
            return true;
        } else if (account != null) {
            /*
             * Either only premium accounts are allowed or, if configured by users, free accounts are allowed to be used for downloading too
             * in some cases.
             */
            return account.getType() == AccountType.PREMIUM || this.supportsFreeAccountDownloadMode(account);
        } else {
            /* Download without account is not possible */
            return false;
        }
    }

    @Deprecated
    private boolean isDirecturl(final DownloadLink link) {
        return StringUtils.equals(getHost(), link.getHost()) && !isSelfhostedContent(link);
    }

    private boolean isSelfhostedContent(final DownloadLink link) {
        if (link == null) {
            return false;
        }
        return link.getPluginPatternMatcher() != null && link.getPluginPatternMatcher().matches("https?://[^/]+/file\\?id=.+");
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception, PluginException {
        if (isDirecturl(link)) {
            /* DirectURLs can be downloaded without logging in */
            handleDL_DIRECT(null, link);
        } else {
            throw new AccountRequiredException();
        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        if (this.isSelfhostedContent(link)) {
            handleDLSelfhosted(link, account);
        } else if (isDirecturl(link)) {
            handleDL_DIRECT(account, link);
        } else {
            /* This should never happen */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.MULTIHOST };
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        if (isUsenetLink(link)) {
            super.handleMultiHost(link, account);
            return;
        } else if (isDirecturl(link)) {
            /* TODO: Remove this */
            handleDL_DIRECT(account, link);
        } else {
            this.br = prepBR(this.br);
            mhm.runCheck(account, link);
            login(this.br, account, false, getClientID());
            final String dllink = getDllink(this.br, account, link, getClientID(), this);
            handleDL_MOCH(account, link, dllink);
        }
    }

    private void handleDL_MOCH(final Account account, final DownloadLink link, final String dllink) throws Exception {
        if (dllink == null) {
            handleAPIErrors(this.br, link, account);
            mhm.handleErrorGeneric(account, link, "dllinknull", 2, 5 * 60 * 1000l);
        }
        link.setProperty(account.getHoster() + "directlink", dllink);
        try {
            antiCloudflare(br, dllink);
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
            final String contentType = dl.getConnection().getContentType();
            final long completeContentLength = dl.getConnection().getCompleteContentLength();
            if (StringUtils.containsIgnoreCase(contentType, "text") || !dl.getConnection().isOK() || completeContentLength <= 0) {
                br.followConnection();
                handleAPIErrors(this.br, link, account);
                mhm.handleErrorGeneric(account, link, "unknowndlerror", 2, 5 * 60 * 1000l);
            }
            final long verifiedFileSize = link.getVerifiedFileSize();
            if (completeContentLength != verifiedFileSize) {
                logger.info("Update Filesize: old=" + verifiedFileSize + "|new=" + completeContentLength);
                link.setVerifiedFileSize(completeContentLength);
            }
            this.dl.startDownload();
        } catch (final Exception e) {
            link.setProperty(account.getHoster() + "directlink", Property.NULL);
            throw e;
        }
    }

    @Deprecated
    protected void antiCloudflare(Browser br, final String url) throws Exception {
        /* 2019-12-18: TODO: Check if we still need this */
        final Request request = br.createHeadRequest(url);
        prepBrowser(br, request.getURL().getHost());
        final URLConnectionAdapter con = openAntiDDoSRequestConnection(br, request);
        con.disconnect();
    }

    /** Account is not required for such URLs. */
    @Deprecated
    private void handleDL_DIRECT(final Account account, final DownloadLink link) throws Exception {
        antiCloudflare(br, link.getPluginPatternMatcher());
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, link.getPluginPatternMatcher(), ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
        if (dl.getConnection().getResponseCode() == 403) {
            /*
             * 2019-05-08: This most likely only happens for offline cloud files. They've been notified to update their API to return a
             * clear 404 for offline files.
             */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Most likely you have reached your bandwidth limit, or you don't have this file in your cloud anymore!", 3 * 60 * 1000l);
        }
        final String contenttype = dl.getConnection().getContentType();
        if (contenttype.contains("html")) {
            // br.followConnection();
            // handleAPIErrors(this.br);
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 3 * 60 * 1000l);
        }
        this.dl.startDownload();
    }

    private void handleDLSelfhosted(final DownloadLink link, final Account account) throws Exception {
        this.requestFileInformationSelfhosted(link, account);
        final String dllink = PluginJSonUtils.getJson(br, "link");
        if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
        final String contenttype = dl.getConnection().getContentType();
        if (contenttype.contains("html")) {
            // br.followConnection();
            // handleAPIErrors(this.br);
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 3 * 60 * 1000l);
        }
        this.dl.startDownload();
    }

    public String getDllink(final Browser br, final Account account, final DownloadLink link, final String client_id, final PluginForHost hostPlugin) throws Exception {
        String dllink = checkDirectLink(br, link, account.getHoster() + "directlink");
        if (dllink == null) {
            /* TODO: Check if the cache function is useful for us */
            // br.getPage("https://www." + account.getHoster() + "/api/cache/check?client_id=" + client_id + "&pin=" +
            // Encoding.urlEncode(account.getPass()) + "&items%5B%5D=" +
            // Encoding.urlEncode(link.getDefaultPlugin().buildExternalDownloadURL(link, hostPlugin)));
            final String hash_md5 = link.getMD5Hash();
            final String hash_sha1 = link.getSha1Hash();
            final String hash_sha256 = link.getSha256Hash();
            String getdata = "?src=" + Encoding.urlEncode(link.getDefaultPlugin().buildExternalDownloadURL(link, hostPlugin));
            if (hash_md5 != null) {
                getdata += "&hash_md5=" + hash_md5;
            }
            if (hash_sha1 != null) {
                getdata += "&hash_sha1=" + hash_sha1;
            }
            if (hash_sha256 != null) {
                getdata += "&hash_sha256=" + hash_sha256;
            }
            callAPI(br, account, "/api/transfer/directdl" + getdata);
            dllink = PluginJSonUtils.getJsonValue(br, "location");
            if (!StringUtils.isEmpty(dllink)) {
                /*
                 * 2019-11-29: TODO: This is a workaround! They're caching data. This means that it may also happen that a slightly
                 * different file will get delivered (= new hash). This is a bad workaround to "disable" the hash check of our original file
                 * thus prevent JD to display CRC errors when there are none. Premiumize is advised to at least return the correct MD5 hash
                 * so that we can set it accordingly but for now, we only have this workaround. See also:
                 * https://svn.jdownloader.org/issues/87604
                 */
                final boolean forceDisableCRCCheck = true;
                final long originalSourceFilesize = link.getView().getBytesTotal();
                long thisFilesize = 0;
                final String thisFilesizeStr = PluginJSonUtils.getJson(br, "filesize");
                if (thisFilesizeStr != null && thisFilesizeStr.matches("\\d+")) {
                    thisFilesize = Long.parseLong(thisFilesizeStr);
                }
                if (forceDisableCRCCheck || originalSourceFilesize > 0 && thisFilesize > 0 && thisFilesize != originalSourceFilesize) {
                    logger.info("Dumping existing hashes to prevent errors because of cache download");
                    link.setMD5Hash(null);
                    link.setSha1Hash(null);
                    link.setSha256Hash(null);
                }
            }
        }
        return dllink;
    }

    public String checkDirectLink(final Browser br, final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = openAntiDDoSRequestConnection(br2, br2.createHeadRequest(dllink));
                if (StringUtils.containsIgnoreCase(con.getContentType(), "text") || con.getResponseCode() != 200 || con.getCompleteContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
            } catch (final Exception e) {
                logger.log(e);
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            } finally {
                try {
                    if (con != null) {
                        con.disconnect();
                    }
                } catch (final Throwable e) {
                }
            }
        }
        return dllink;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        this.br = prepBR(this.br);
        final AccountInfo ai = fetchAccountInfoAPI(this.br, getClientID(), account);
        return ai;
    }

    public AccountInfo fetchAccountInfoAPI(final Browser br, final String client_id, final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(br, account, true, client_id);
        Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        // final String fair_use_used_str = PluginJSonUtils.getJson(br, "limit_used");
        final Object fair_use_usedO = entries.get("limit_used");
        final Object space_usedO = entries.get("space_used");
        final Object premium_untilO = entries.get("premium_until");
        if (space_usedO != null && space_usedO instanceof Long) {
            ai.setUsedSpace(((Number) space_usedO).longValue());
        } else if (space_usedO != null && space_usedO instanceof Double) {
            /* 2019-06-26: New */
            ai.setUsedSpace((long) ((Double) space_usedO).doubleValue());
        }
        /* E.g. free account: "premium_until":false */
        final long currentTime = br.getCurrentServerTime(System.currentTimeMillis());
        final long premium_until = (premium_untilO != null && premium_untilO instanceof Number) ? ((Number) premium_untilO).longValue() * 1000 : 0;
        if (premium_until > currentTime) {
            account.setType(AccountType.PREMIUM);
            account.setMaxSimultanDownloads(getMaxSimultanPremiumDownloadNum());
            if (isBoosterPointsUnlimitedTrafficWorkaroundActive(account)) {
                ai.setStatus("Premium | Unlimited Traffic Booster workaround enabled");
                ai.setUnlimitedTraffic();
            } else {
                if (fair_use_usedO != null && fair_use_usedO instanceof Double) {
                    final double d = ((Number) fair_use_usedO).doubleValue();
                    final int fairUsagePercentUsed = (int) (d * 100.0);
                    final int fairUsagePercentLeft = 100 - fairUsagePercentUsed;
                    if (fairUsagePercentUsed >= 100) {
                        /* Fair use limit reached --> No traffic left, no downloads possible at the moment */
                        ai.setTrafficLeft(0);
                        ai.setStatus("Premium | Fair-Use Status: 100% used [0% left - limit reached]");
                    } else {
                        ai.setUnlimitedTraffic();
                        ai.setStatus(String.format("Premium | Fair-Use Status: %d%% used [%d%% left]", fairUsagePercentUsed, fairUsagePercentLeft));
                    }
                } else {
                    /* This should never happen */
                    ai.setStatus("Premium | Fair-Use Status: Unknown");
                    ai.setUnlimitedTraffic();
                }
            }
            ai.setValidUntil(premium_until, br);
        } else {
            /* Expired == FREE */
            account.setType(AccountType.FREE);
            account.setMaxSimultanDownloads(getMaxSimultaneousFreeAccountDownloads());
            setFreeAccountTraffic(account, ai);
        }
        callAPI(br, account, "/api/services/list");
        entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        // final ArrayList<String> supportedHosts = new ArrayList<String>();
        final ArrayList<String> directdl = (ArrayList<String>) entries.get("directdl");
        final HashSet<String> list = new HashSet<String>();
        /* 2019-08-05: usenet is not supported when pairing login is used because then we do not have the internal Usenet-Logindata! */
        if (supportsUsenet(account)) {
            list.add("usenet");
        }
        if (account.getType() == AccountType.FREE && supportsFreeAccountDownloadMode(account)) {
            /* Display info-dialog regarding free account usage */
            handleFreeModeLoginDialog(account, "https://www." + account.getHoster() + "/free");
        }
        if (directdl != null) {
            list.addAll(directdl);
        }
        /* Debug function to find entries which are on the "cache" list but not on "directdl". */
        final ArrayList<String> cachehosts = (ArrayList<String>) entries.get("cache");
        for (final String cachehost : cachehosts) {
            if (!list.contains(cachehost)) {
                logger.info("Host which is only in cache list but not in directdl list: " + cachehost);
            }
        }
        ai.setMultiHostSupport(this, new ArrayList<String>(list));
        return ai;
    }

    @SuppressWarnings("deprecation")
    private void handleFreeModeLoginDialog(final Account account, final String url) {
        final boolean showAlways = true;
        SubConfiguration config = null;
        try {
            config = getPluginConfig();
            if (showAlways || config.getBooleanProperty("featuredialog_login_Shown_2019_07_01", Boolean.FALSE) == false) {
                if (showAlways || config.getProperty("featuredialog_login_Shown_2019_07_02") == null) {
                    showFreeModeLoginInformation(account, url);
                } else {
                    config = null;
                }
            } else {
                config = null;
            }
        } catch (final Throwable e) {
        } finally {
            if (config != null) {
                config.setProperty("featuredialog_login_Shown_2019_07_01", Boolean.TRUE);
                config.setProperty("featuredialog_login_Shown_2019_07_02", "shown");
                config.save();
            }
        }
    }

    private Thread showFreeModeLoginInformation(final Account account, final String url) throws Exception {
        if (!displayFreeAccountDownloadDialogs(account)) {
            return null;
        }
        final Thread thread = new Thread() {
            public void run() {
                try {
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = br.getHost() + " ermöglicht ab sofort auch kostenlose Downloads";
                        message += "Hallo liebe(r) " + br.getHost() + " NutzerIn\r\n";
                        message += "Ab sofort kannst du diesen Anbieter auch mit einem kostenlosen Account verwenden!\r\n";
                        message += "Mehr infos dazu findest du unter:\r\n" + new URL(url) + "\r\n";
                        message += "Beim ersten Downloadversuch wird ein Info-Dialog mit weiteren Informationen erscheinen.\r\n";
                        message += "Du kannst diese Info-Dialoge in den Plugin-Einstellungen deaktivieren\r\n";
                    } else {
                        title = br.getHost() + " allows free downloads from now on";
                        message += "Hello dear " + br.getHost() + " user\r\n";
                        message += "From now on this service allows downloads via free account.\r\n";
                        message += "More information:\r\n" + new URL(url) + "\r\n";
                        message += "On the first download attempt, a window with more detailed information will be displayed.\r\n";
                        message += "You can turn off these dialogs via Plugin Settings\r\n";
                    }
                    final ConfirmDialog dialog = new ConfirmDialog(UIOManager.LOGIC_COUNTDOWN, title, message);
                    dialog.setTimeout(1 * 60 * 1000);
                    if (CrossSystem.isOpenBrowserSupported() && !Application.isHeadless()) {
                        CrossSystem.openURL(url);
                    }
                    final ConfirmDialogInterface ret = UIOManager.I().show(ConfirmDialogInterface.class, dialog);
                    ret.throwCloseExceptions();
                } catch (final Throwable e) {
                    getLogger().log(e);
                }
            };
        };
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @SuppressWarnings("deprecation")
    private void handleFreeModeDownloadDialog(final Account account, final String url) {
        if (displayFreeAccountDownloadDialogs(account)) {
            final boolean showAlways = false;
            SubConfiguration config = null;
            try {
                config = getPluginConfig();
                if (showAlways || config.getBooleanProperty("featuredialog_download_Shown_2019_07_1", Boolean.FALSE) == false) {
                    if (showAlways || config.getProperty("featuredialog_download_Shown_2019_07_2") == null) {
                        showFreeModeDownloadInformation(url);
                    } else {
                        config = null;
                    }
                } else {
                    config = null;
                }
            } catch (final Throwable e) {
            } finally {
                if (config != null) {
                    config.setProperty("featuredialog_download_Shown_2019_07_1", Boolean.TRUE);
                    config.setProperty("featuredialog_download_Shown_2019_07_2", "shown");
                    config.save();
                }
            }
        }
    }

    private Thread showFreeModeDownloadInformation(final String url) throws Exception {
        final Thread thread = new Thread() {
            public void run() {
                try {
                    final boolean xSystem = CrossSystem.isOpenBrowserSupported();
                    String message = "";
                    final String title;
                    final String host = Browser.getHost(url);
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = br.getHost() + " möchte einen kostenlosen Download starten";
                        message += "Hallo liebe(r) " + host + " NutzerIn\r\n";
                        if (xSystem) {
                            message += "Um mit deinem kostenlosen Account von " + host + " herunterladen zu können musst du den 'free mode' im Fenster das sich gleich öffnet aktivieren.\r\n";
                        } else {
                            message += "Um kostenlos von diesem Anbieter herunterladen zu können musst du den 'free mode' unter dieser Adresse aktivieren:\r\n" + new URL(url) + "\r\n";
                        }
                        message += "Starte die Downloads danach erneut um zu sehen, ob deine Downloadlinks die Bedingungen eines kostenlosen Downloads erfüllen.\r\n";
                        message += "Sobald du das Limit erreicht hast, musst du den Free Mode wieder über die oben gezeigte URL aktivieren.\r\n";
                        message += "Du kannst diese Info-Dialoge in den Plugin-Einstellungen deaktivieren\r\n";
                    } else {
                        title = br.getHost() + " wants to start a free download";
                        message += "Hello dear " + host + " user\r\n";
                        if (xSystem) {
                            message += "To be able to use the free mode of this service, you will have to enable it in the browser-window which will open soon.\r\n";
                        } else {
                            message += "To be able to use the free mode of this service, you will have to enable it here:\r\n" + new URL(url) + "\r\n";
                        }
                        message += "Restart your downloads afterwards to see whether your downloadlinks meet the requirements to be downloadable via free account.\r\n";
                        message += "Once you've reached the free account downloadlimit, you will have to re-activate free mode via the previously mentioned URL.\r\n";
                        message += "You can turn off these dialogs via Plugin Settings\r\n";
                    }
                    final ConfirmDialog dialog = new ConfirmDialog(UIOManager.LOGIC_COUNTDOWN, title, message);
                    dialog.setTimeout(2 * 60 * 1000);
                    if (CrossSystem.isOpenBrowserSupported() && !Application.isHeadless()) {
                        CrossSystem.openURL(url);
                    }
                    final ConfirmDialogInterface ret = UIOManager.I().show(ConfirmDialogInterface.class, dialog);
                    ret.throwCloseExceptions();
                } catch (final Throwable e) {
                    getLogger().log(e);
                }
            };
        };
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    protected final void setFreeAccountTraffic(final Account account, final AccountInfo ai) {
        if (this.supportsFreeAccountDownloadMode(account)) {
            /** 2019-07-27: TODO: Remove this hardcoded trafficlimit and obtain this value via API (not yet given at the moment)! */
            ai.setTrafficLeft(5000000000l);
        } else {
            /** Default = Free accounts do not have any traffic. */
            ai.setTrafficLeft(0);
        }
    }

    public void login(Browser br, final Account account, final boolean force, final String clientID) throws Exception {
        synchronized (account) {
            br.setCookiesExclusive(true);
            br = prepBR(br);
            loginAPI(br, clientID, account, force);
        }
    }

    public void loginAPI(final Browser br, final String clientID, final Account account, final boolean force) throws Exception {
        if (usePairingLogin(account)) {
            /* 2019-06-26: New: TODO: We need a way to get the usenet logindata without exposing the original account logindata/apikey! */
            try {
                boolean has_tried_old_token = false;
                final long token_valid_until = account.getLongProperty("token_valid_until", 0);
                if (System.currentTimeMillis() > token_valid_until) {
                    logger.info("Token has expired");
                } else if (setAuthHeader(br, account)) {
                    has_tried_old_token = true;
                    callAPI(br, account, "/api/account/info");
                    if (isLoggedIn(br)) {
                        return;
                    }
                    logger.info("Token expired or user has revoked access --> Full login required");
                }
                this.postPage("https://www." + account.getHoster() + "/token", "response_type=device_code&client_id=" + clientID);
                final int interval_seconds = Integer.parseInt(PluginJSonUtils.getJson(br, "interval"));
                final int expires_in_seconds = Integer.parseInt(PluginJSonUtils.getJson(br, "expires_in")) - interval_seconds;
                final long expires_in_timestamp = System.currentTimeMillis() + expires_in_seconds * 1000l;
                final String verification_uri = PluginJSonUtils.getJson(br, "verification_uri");
                final String device_code = PluginJSonUtils.getJson(br, "device_code");
                final String user_code = PluginJSonUtils.getJson(br, "user_code");
                if (StringUtils.isEmpty(device_code) || StringUtils.isEmpty(user_code) || StringUtils.isEmpty(verification_uri)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                boolean success = false;
                int loop = 0;
                int internal_max_loops_limit = 120;
                final Thread dialog = showPairingLoginInformation(verification_uri, user_code);
                String access_token = null;
                try {
                    do {
                        logger.info("Waiting for user to authorize application: " + loop);
                        Thread.sleep(interval_seconds * 1001l);
                        this.postPage("https://www." + account.getHoster() + "/token", "grant_type=device_code&client_id=" + clientID + "&code=" + device_code);
                        access_token = PluginJSonUtils.getJson(br, "access_token");
                        if (!StringUtils.isEmpty(access_token)) {
                            success = true;
                            break;
                        } else if (!dialog.isAlive()) {
                            logger.info("Dialog closed!");
                            break;
                        }
                        loop++;
                    } while (!success && System.currentTimeMillis() < expires_in_timestamp && loop < internal_max_loops_limit);
                } finally {
                    dialog.interrupt();
                }
                final String token_expires_in = PluginJSonUtils.getJson(br, "expires_in");
                final String token_type = PluginJSonUtils.getJson(br, "token_type");
                if (!success) {
                    final String errormsg = "User did not confirm pairing code!\r\nDo not close the pairing dialog until you've confirmed the code via browser!";
                    if (has_tried_old_token) {
                        /*
                         * Don't display permanent error if we still have an old token. Maybe something else has failed and the old token
                         * will work fine again on the next try.
                         */
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, errormsg, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, errormsg, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else if (!"bearer".equals(token_type)) {
                    /* This should never happen! */
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "Unsupported token_type", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.setProperty("access_token", access_token);
                if (!StringUtils.isEmpty(token_expires_in) && token_expires_in.matches("\\d+")) {
                    account.setProperty("token_valid_until", System.currentTimeMillis() + Long.parseLong(token_expires_in));
                }
                setAuthHeader(br, account);
                callAPI(br, account, "/api/account/info");
                if (!isLoggedIn(br)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                } else {
                    account.setProperty(PROPERTY_ACCOUNT_successfully_loggedin_once, true);
                }
            } finally {
                /*
                 * Users may enter logindata through webinterface which means they may even enter their real password which we do not want
                 * to store in our account database. Also we do not want this field to be empty (null) as this would look strange in the
                 * account manager.
                 */
                account.setPass("JD_DUMMY_PASSWORD");
            }
        } else {
            callAPI(br, account, "/api/account/info");
            if (!isLoggedIn(br)) {
                /* E.g. {"status":"error","message":"customer_id and pin parameter missing or not logged in "} */
                loginInvalid(account);
            } else {
                account.setProperty(PROPERTY_ACCOUNT_successfully_loggedin_once, true);
            }
        }
        final String customer_id = PluginJSonUtils.getJson(br, "customer_id");
        if (customer_id != null && customer_id.length() > 2) {
            /* don't store the complete customer id as a security purpose */
            final String shortcustomer_id = customer_id.substring(0, customer_id.length() / 2) + "****";
            account.setUser(shortcustomer_id);
        }
    }

    /* 2020-04-1: Temp. workaround for possible API issue */
    private void loginInvalid(final Account account) throws PluginException {
        final boolean successfully_loggedin_once = account.getBooleanProperty(PROPERTY_ACCOUNT_successfully_loggedin_once, false);
        if (successfully_loggedin_once) {
            /* Display permanent error on next failed login attempt! */
            account.setProperty(PROPERTY_ACCOUNT_successfully_loggedin_once, false);
            throw new AccountUnavailableException("Maybe API key invalid! Check API key here: " + account.getHoster() + "/account", 15 * 60 * 1000l);
        } else {
            throw new AccountInvalidException("API key invalid! Check API key here: " + account.getHoster() + "/account");
        }
    }

    /**
     * For API calls AFTER logging-in, NOT for initial 'pairing' API calls (oauth login)!
     *
     * @throws Exception
     */
    private void callAPI(final Browser br, final Account account, String url) throws Exception {
        url = "https://www." + account.getHoster() + url;
        if (!url.contains("?")) {
            url += "?";
        } else {
            url += "&";
        }
        url += "client_id=" + this.getClientID();
        if (!this.usePairingLogin(account)) {
            /*
             * Without pairing login we need an additional parameter. It will also work with pairing mode when that parameter is given with
             * a wrong value but that may change in the future so this is to avoid issues!
             */
            url += "&pin=" + Encoding.urlEncode(getAPIKey(account));
        }
        getPage(br, url);
    }

    @Override
    protected String getUseNetUsername(Account account) {
        if (usePairingLogin(account)) {
            /* Login via access_token:access_token */
            return account.getStringProperty("access_token", null);
        } else {
            /* Login via APIKEY:APIKEY */
            return account.getPass();
        }
    }

    @Override
    protected String getUseNetPassword(Account account) {
        if (usePairingLogin(account)) {
            /* Login via access_token:access_token */
            return account.getStringProperty("access_token", null);
        } else {
            /* Login via APIKEY:APIKEY */
            return account.getPass();
        }
    }

    private boolean isLoggedIn(final Browser br) {
        final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final Object statusO = entries.get("status");
        if ("success".equalsIgnoreCase((String) statusO)) {
            return true;
        } else {
            return false;
        }
    }

    /** true = Account has 'access_token' property, false = Account does not have 'access_token' property. */
    public static boolean setAuthHeader(final Browser br, final Account account) {
        final String access_token = account.getStringProperty("access_token", null);
        if (access_token != null) {
            br.getHeaders().put("Authorization", "Bearer " + access_token);
            return true;
        } else {
            return false;
        }
    }

    private Thread showPairingLoginInformation(final String verification_url, final String user_code) {
        final Thread thread = new Thread() {
            public void run() {
                try {
                    final String host = Browser.getHost(verification_url);
                    final String host_without_tld = host.split("\\.")[0];
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = host + " - neue Login-Methode";
                        message += "Hallo liebe(r) " + host + " NutzerIn\r\n";
                        message += "Um deinen Account in JDownloader verwenden zu können, musst du folgende Schritte beachten:\r\n";
                        message += "1. Gehe sicher, dass du im Browser in deinem " + host_without_tld + " Account eingeloggt bist.\r\n";
                        message += "2. Öffne diesen Link im Browser falls das nicht automatisch passiert:\r\n\t'" + verification_url + "'\t\r\n";
                        message += "3. Gib im Browser folgenden Code ein: " + user_code + "\r\n";
                        message += "Dein Account sollte nach einigen Sekunden von JDownloader akzeptiert werden.\r\n";
                    } else {
                        title = host + " - New login method";
                        message += "Hello dear " + host + " user\r\n";
                        message += "In order to use this service in JDownloader you need to follow these steps:\r\n";
                        message += "1. Make sure that you're logged in your " + host_without_tld + " account with your browser.\r\n";
                        message += "2. Open this URL in your browser it that did not already happen automatically:\r\n\t'" + verification_url + "'\t\r\n";
                        message += "3. Enter the following code in the browser window: " + user_code + "\r\n";
                        message += "Your account should be accepted in JDownloader within a few seconds.\r\n";
                    }
                    final ConfirmDialog dialog = new ConfirmDialog(UIOManager.LOGIC_COUNTDOWN, title, message);
                    dialog.setTimeout(2 * 60 * 1000);
                    if (CrossSystem.isOpenBrowserSupported() && !Application.isHeadless()) {
                        CrossSystem.openURL(verification_url);
                    }
                    final ConfirmDialogInterface ret = UIOManager.I().show(ConfirmDialogInterface.class, dialog);
                    ret.throwCloseExceptions();
                } catch (final Throwable e) {
                    getLogger().log(e);
                }
            };
        };
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public boolean supportsUsenet(final Account account) {
        return false;
    }

    /** Indicates whether downloads via free accounts are possible or not. */
    public boolean supportsFreeAccountDownloadMode(final Account account) {
        return false;
    }

    /**
     * Indicates whether or not the new 'pairing' login is supported & enabled: https://alexbilbie.com/2016/04/oauth-2-device-flow-grant/
     */
    public boolean usePairingLogin(final Account account) {
        return false;
    }

    /**
     * Indicates whether or not to display free account download dialogs which tell the user to activate free mode via website. </br>
     * Some users find this annoying and will deactivate it. </br>
     * default = true
     */
    public boolean displayFreeAccountDownloadDialogs(final Account account) {
        return false;
    }

    /**
     * 2019-08-21: Premiumize.me has so called 'booster points' which basically means that users with booster points can download more than
     * normal users can with their fair use limit: https://www.premiumize.me/booster </br>
     * Premiumize has not yet integrated this in their API which means accounts with booster points will run into the fair-use-limit in
     * JDownloader and will not be able to download any more files then. </br>
     * This workaround can set accounts to unlimited traffic so that users will still be able to download.</br>
     * Remove this workaround once Premiumize has integrated their booster points into their API.
     */
    public boolean isBoosterPointsUnlimitedTrafficWorkaroundActive(final Account account) {
        return false;
    }

    public static String getAPIKey(final Account account) {
        return account.getPass().trim();
    }

    /**
     * Keep this for possible future API implementation
     *
     * @throws InterruptedException
     */
    private void handleAPIErrors(final Browser br, final DownloadLink link, final Account account) throws PluginException, InterruptedException {
        /* E.g. {"status":"error","error":"topup_required","message":"Please purchase premium membership or activate free mode."} */
        final String status = PluginJSonUtils.getJson(br, "status");
        if ("error".equalsIgnoreCase(status)) {
            String errortype = PluginJSonUtils.getJson(br, "error");
            if (errortype == null) {
                errortype = "unknowndlerror";
            }
            String message = PluginJSonUtils.getJson(br, "message");
            if (message == null) {
                /* Fallback */
                message = errortype;
            }
            if ("topup_required".equalsIgnoreCase(errortype)) {
                /**
                 * 2019-07-27: TODO: Premiumize should add an API call which returns whether free account downloads are currently activated
                 * or not (see premiumize.me/free). Currently if a user tries to download files via free account and gets this errormessage,
                 * it is unclear whether: 1. Premium is required to download, 2. User needs to activate free mode first to download this
                 * file. 3. User has activated free mode but this file is not allowed to be downloaded via free account.
                 */
                /* {"status":"error","error":"topup_required","message":"Please purchase premium membership or activate free mode."} */
                if (account != null && account.getType() == AccountType.FREE) {
                    /* Free */
                    /* 2019-07-27: Original errormessage may cause confusion so we'll slightly modify that. */
                    message = "Premium required or activate free mode via premiumize.me/free";
                    if (this.supportsFreeAccountDownloadMode(account)) {
                        /* Ask user to unlock free account downloads via website */
                        handleFreeModeDownloadDialog(account, "https://www." + this.br.getHost() + "/free");
                    } else {
                        /*
                         * User has not enabled free account downloads in plugin settings (this is a rare case which may happen in the
                         * moment when a premium account expires and becomes a free account!)
                         */
                        message += " AND via Settings->Plugins->Premiumize.me";
                    }
                    mhm.putError(account, link, 5 * 60 * 1000l, message);
                } else {
                    /* Premium account - probably no traffic left */
                    message = "Traffic empty or fair use limit reached?";
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, message, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                }
            } else if ("content not in cache".equalsIgnoreCase(message)) {
                /* 2019-02-19: Not all errors have an errortype given */
                /* E.g. {"status":"error","message":"content not in cache"} */
                if (account != null && account.getType() == AccountType.FREE && this.supportsFreeAccountDownloadMode(account)) {
                    /* Case: User tries to download non-cached-file via free account. */
                    message = "Not downloadable via free account because: " + message;
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, message);
                } else {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, message);
                }
            } else if ("item not found".equalsIgnoreCase(message)) {
                /*
                 * 2020-07-16: This should only happen for selfhosted cloud items --> Offline: {"status":"error","message":"item not found"}
                 */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, message);
            } else {
                /* Unknown error */
                mhm.handleErrorGeneric(account, link, errortype, 2, 5 * 60 * 1000l);
            }
        }
    }

    public static String getCloudID(final String url) {
        if (url.contains("folder_id")) {
            return new Regex(url, "folder_id=([a-zA-Z0-9\\-_]+)").getMatch(0);
        } else {
            return new Regex(url, "id=([a-zA-Z0-9\\-_]+)").getMatch(0);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}