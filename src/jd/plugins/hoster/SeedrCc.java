//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
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
package jd.plugins.hoster;

import java.io.IOException;
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
import org.jdownloader.captcha.v2.challenge.hcaptcha.CaptchaHelperHostPluginHCaptcha;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "seedr.cc" }, urls = { "https://seedr\\.cc/download/file/\\d+" })
public class SeedrCc extends PluginForHost {
    public SeedrCc(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.seedr.cc/premium");
    }

    @Override
    public String getAGBLink() {
        return "https://www.seedr.cc/dynamic/terms";
    }

    /* Connection stuff */
    private final int           FREE_MAXDOWNLOADS    = -1;
    private final int           ACCOUNT_MAXDOWNLOADS = -1;
    private final boolean       ACCOUNT_RESUME       = true;
    private final int           ACCOUNT_MAXCHUNKS    = -2;
    private String              dllink               = null;
    private static final String PROPERTY_DIRECTURL   = "directurl";

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        return requestFileInformation(link, account, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        this.setBrowserExclusive();
        final boolean lookForFilesize = false; // filesize gets set set in crawler already
        if (account == null) {
            return AvailableStatus.UNCHECKABLE;
        }
        this.login(account, false);
        prepAjaxBr(this.br);
        final String fid = new Regex(link.getPluginPatternMatcher(), "(\\d+)$").getMatch(0);
        if (fid == null) {
            /* This should never happen */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.getPage("https://www." + this.getHost() + "/download/file/" + fid + "/url");
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        this.dllink = (String) entries.get("url");
        final String filename = (String) entries.get("name");
        if (!StringUtils.isEmpty(filename)) {
            link.setFinalFileName(filename);
        }
        if (dllink != null && !isDownload && lookForFilesize) {
            URLConnectionAdapter con = null;
            try {
                br.setFollowRedirects(true);
                con = br.openHeadConnection(dllink);
                if (!this.looksLikeDownloadableContent(con)) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Broken file?");
                }
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                final String filenameFromHeader = getFileNameFromHeader(con);
                if (filenameFromHeader != null) {
                    link.setFinalFileName(filenameFromHeader);
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link, null, true);
        throw new AccountRequiredException();
    }

    private void handleDownload(final DownloadLink link, final Account account) throws Exception, PluginException {
        if (this.attemptStoredDownloadurlDownload(link)) {
            logger.info("Downloading previously stored directurl");
        } else {
            requestFileInformation(link, account, true);
            if (StringUtils.isEmpty(dllink)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_RESUME, ACCOUNT_MAXCHUNKS);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                if (dl.getConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 5 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
                }
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
            }
            link.setProperty(PROPERTY_DIRECTURL, dllink);
        }
        dl.startDownload();
    }

    private boolean attemptStoredDownloadurlDownload(final DownloadLink link) throws Exception {
        final String url = link.getStringProperty(PROPERTY_DIRECTURL);
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        boolean valid = false;
        try {
            final Browser brc = br.cloneBrowser();
            dl = new jd.plugins.BrowserAdapter().openDownload(brc, link, url, ACCOUNT_RESUME, ACCOUNT_MAXCHUNKS);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                valid = true;
                return true;
            } else {
                link.removeProperty(PROPERTY_DIRECTURL);
                brc.followConnection(true);
                throw new IOException();
            }
        } catch (final Throwable e) {
            logger.log(e);
            return false;
        } finally {
            if (!valid) {
                try {
                    dl.getConnection().disconnect();
                } catch (Throwable ignore) {
                }
                this.dl = null;
            }
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    public void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setFollowRedirects(true);
                br.setCookiesExclusive(true);
                final boolean cookieLoginOnly = false;
                final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass(), getLogger());
                final Cookies cookies = account.loadCookies("");
                if (userCookies != null) {
                    logger.info("Attempting user cookie login");
                    br.setCookies(userCookies);
                    if (!force) {
                        /* Do not verify cookies */
                        return;
                    }
                    if (this.checkLogin(br, account)) {
                        /* Save new cookie timestamp */
                        logger.info("User Cookie login successful");
                        return;
                    } else {
                        logger.info("User Cookie login failed");
                        if (account.hasEverBeenValid()) {
                            throw new AccountInvalidException("Cookies expired");
                        } else {
                            showCookieLoginInformation();
                            throw new AccountInvalidException("Invalid user name or password");
                        }
                    }
                }
                if (cookieLoginOnly) {
                    showCookieLoginInformation();
                    throw new AccountInvalidException("Cookie login required");
                }
                if (cookies != null) {
                    br.setCookies(account.getHoster(), cookies);
                    if (!force) {
                        /* Do not verify cookies */
                        return;
                    }
                    if (this.checkLogin(br, account)) {
                        /* Save new cookie timestamp */
                        logger.info("Cookie login successful");
                        account.saveCookies(br.getCookies(br.getHost()), "");
                        return;
                    } else {
                        logger.info("Cookie login failed");
                        br.clearCookies(br.getHost());
                        account.clearCookies("");
                    }
                }
                logger.info("Performing full login");
                final DownloadLink dlinkbefore = this.getDownloadLink();
                if (dlinkbefore == null) {
                    this.setDownloadLink(new DownloadLink(this, "Account", this.getHost(), "http://" + account.getHoster(), true));
                }
                br.getPage("https://www." + this.getHost() + "/auth/pages/signup");
                final UrlQuery query = new UrlQuery();
                query.add("username", Encoding.urlEncode(account.getUser()));
                query.add("password", Encoding.urlEncode(account.getPass()));
                query.add("rememberme", "on");
                query.add("g-recaptcha-response", "");
                if (CaptchaHelperHostPluginHCaptcha.containsHCaptcha(br)) {
                    final String hcaptchaResponse = new CaptchaHelperHostPluginHCaptcha(this, br).getToken();
                    query.add("h-captcha-response", Encoding.urlEncode(hcaptchaResponse));
                }
                prepAjaxBr(br);
                br.postPage("/auth/login", query);
                if (!isLoggedIn(br)) {
                    throw new AccountInvalidException();
                }
                account.saveCookies(br.getCookies(br.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    private boolean checkLogin(final Browser br, final Account account) throws IOException {
        prepAjaxBr(br);
        br.setAllowedResponseCodes(401);
        br.getPage("https://www." + this.getHost() + "/account/settings");
        if (isLoggedIn(br)) {
            return true;
        } else {
            return false;
        }
    }

    private Thread showCookieLoginInformation() {
        final Thread thread = new Thread() {
            public void run() {
                try {
                    final String help_article_url = "https://support.jdownloader.org/Knowledgebase/Article/View/account-cookie-login-instructions";
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = "Seedr.cc - Login";
                        message += "Hallo liebe(r) Seedr.cc NutzerIn\r\n";
                        message += "Um deinen seedr.cc Account in JDownloader verwenden zu können, musst du folgende Schritte beachten:\r\n";
                        message += "Folge der Anleitung im Hilfe-Artikel:\r\n";
                        message += help_article_url;
                    } else {
                        title = "Seedr.cc - Login";
                        message += "Hello dear seedr.cc user\r\n";
                        message += "In order to use an account of this service in JDownloader, you need to follow these instructions:\r\n";
                        message += help_article_url;
                    }
                    final ConfirmDialog dialog = new ConfirmDialog(UIOManager.LOGIC_COUNTDOWN, title, message);
                    dialog.setTimeout(3 * 60 * 1000);
                    if (CrossSystem.isOpenBrowserSupported() && !Application.isHeadless()) {
                        CrossSystem.openURL(help_article_url);
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

    private static boolean isLoggedIn(final Browser br) {
        if (br.getHttpConnection().getResponseCode() == 401) {
            /* E.g. {"status_code":401,"reason_phrase":"Unauthorized"} */
            return false;
        } else {
            return true;
        }
    }

    public static void prepAjaxBr(final Browser br) {
        br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        /*
         * Correct username - e.g. when logging in via cookies, users can enter whatever they want into the username field but we want the
         * account to be unique!
         */
        final Map<String, Object> user = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final Map<String, Object> userAccount = (Map<String, Object>) user.get("account");
        // final Map<String, Object> userSettings =(Map<String, Object>)user.get("settings");
        final String email = (String) userAccount.get("email");
        final String userAccountPackageName = (String) userAccount.get("package_name");
        final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass(), getLogger());
        /*
         * Users can enter anything into the "username" field when cookie login is used --> Correct that so we got an unique 'username'
         * value. Otherwise users could easily add one account multiple times -> Could cause issues.
         */
        if (!StringUtils.isEmpty(email) && userCookies != null) {
            account.setUser(email);
        }
        ai.setUnlimitedTraffic();
        prepAjaxBr(this.br);
        br.getPage("/account/quota/used");
        final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final long bandwidth_used = ((Number) entries.get("bandwidth_used")).longValue();
        final long bandwidth_max = ((Number) entries.get("bandwidth_max")).longValue();
        ai.setUsedSpace(((Number) entries.get("space_used")).longValue());
        if ((Boolean) entries.get("is_premium")) {
            /* TODO: Set expire-date if present */
            account.setType(AccountType.PREMIUM);
        } else {
            account.setType(AccountType.FREE);
        }
        if (bandwidth_max > 0) {
            ai.setTrafficMax(bandwidth_max);
            ai.setTrafficLeft(ai.getTrafficMax() - bandwidth_used);
        }
        account.setMaxSimultanDownloads(ACCOUNT_MAXDOWNLOADS);
        if (!StringUtils.isEmpty(userAccountPackageName)) {
            ai.setStatus("Package: " + userAccountPackageName);
        }
        account.setConcurrentUsePossible(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        handleDownload(link, account);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}