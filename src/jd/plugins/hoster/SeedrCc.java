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
import java.util.Locale;

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
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "seedr.cc" }, urls = { "https?://(?:[A-Za-z0-9\\-]+)?\\.seedr\\.cc/(?:downloads|zip)/\\d+.+|http://seedrdecrypted\\.cc/\\d+" })
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
    private final boolean       FREE_RESUME                  = true;
    private final int           FREE_MAXCHUNKS               = -2;
    private final int           FREE_MAXDOWNLOADS            = 20;
    private final boolean       ACCOUNT_FREE_RESUME          = true;
    private final int           ACCOUNT_FREE_MAXCHUNKS       = -2;
    // private final int ACCOUNT_FREE_MAXDOWNLOADS = 20;
    private final boolean       ACCOUNT_PREMIUM_RESUME       = true;
    private final int           ACCOUNT_PREMIUM_MAXCHUNKS    = -2;
    private final int           ACCOUNT_PREMIUM_MAXDOWNLOADS = 20;
    private static final String TYPE_DIRECTLINK              = "https?://(?:[A-Za-z0-9\\-]+)?\\.seedr\\.cc/(?:downloads|zip)/(\\d+).+";
    private static final String TYPE_ZIP                     = "https?://[^/]+/zip/(\\d+).+";
    private static final String TYPE_NORMAL                  = "http://seedrdecrypted\\.cc/(\\d+)";
    private boolean             server_issues                = false;
    private String              dllink                       = null;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, null, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, Account account, final boolean isDownload) throws Exception {
        this.setBrowserExclusive();
        server_issues = false;
        String filename = null;
        if (account == null) {
            /* Pick random valid account if none is given via parameter. */
            account = AccountController.getInstance().getValidAccount(this);
        }
        // if (link.getPluginPatternMatcher().matches(TYPE_ZIP)) {
        // if (account == null) {
        // return AvailableStatus.UNCHECKABLE;
        // }
        // this.login(this.br, account, false);
        // prepAjaxBr(this.br);
        // final String fid = new Regex(link.getPluginPatternMatcher(), TYPE_ZIP).getMatch(0);
        // if (fid == null) {
        // /* This should never happen */
        // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        // }
        // this.br.postPage("https://www." + this.getHost() + "/content.php?action=fetch_archive",
        // "%5B%7B%22type%22%3A%22folder%22%2C%22id%22%3A%22" + fid + "%22%7D%5D");
        // if (br.getHttpConnection().getResponseCode() == 404) {
        // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        // } else if (br.getHttpConnection().getResponseCode() == 401) {
        // /*
        // * 2020-07-15: E.g. deleted files --> They do not provide a meaningful errormessage for this case --> First check if we
        // * really are loggedin --> If no Exception happens we're logged in which means the file is offline --> This is a rare case!
        // * Most of all URLs the users add will be online and downloadable!
        // */
        // this.login(this.br, account, true);
        // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        // }
        // this.dllink = PluginJSonUtils.getJsonValue(this.br, "archive_url");
        // }
        if (link.getPluginPatternMatcher().matches(TYPE_NORMAL)) {
            if (account == null) {
                return AvailableStatus.UNCHECKABLE;
            }
            this.login(this.br, account, false);
            prepAjaxBr(this.br);
            final String fid = new Regex(link.getPluginPatternMatcher(), TYPE_NORMAL).getMatch(0);
            if (fid == null) {
                /* This should never happen */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            this.br.postPage("https://www." + this.getHost() + "/content.php?action=fetch_file", "folder_file_id=" + fid);
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.getHttpConnection().getResponseCode() == 401) {
                /*
                 * 2020-07-15: E.g. deleted files --> They do not provide a meaningful errormessage for this case --> First check if we
                 * really are loggedin --> If no Exception happens we're logged in which means the file is offline --> This is a rare case!
                 * Most of all URLs the users add will be online and downloadable!
                 */
                this.login(this.br, account, true);
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            this.dllink = PluginJSonUtils.getJsonValue(this.br, "url");
            filename = PluginJSonUtils.getJsonValue(this.br, "name");
        } else {
            dllink = link.getPluginPatternMatcher();
        }
        if (filename != null && !link.isNameSet()) {
            link.setFinalFileName(filename);
        }
        if (dllink != null && !isDownload) {
            URLConnectionAdapter con = null;
            try {
                br.setFollowRedirects(true);
                con = br.openHeadConnection(dllink);
                if (con.isContentDisposition()) {
                    link.setDownloadSize(con.getLongContentLength());
                    if (!link.isNameSet()) {
                        link.setFinalFileName(getFileNameFromHeader(con));
                    }
                } else {
                    server_issues = true;
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
        /* Without account, only directurls can be downloaded! */
        if (!link.getPluginPatternMatcher().matches(TYPE_DIRECTLINK)) {
            throw new AccountRequiredException();
        }
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink link, final boolean resumable, int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (link.getPluginPatternMatcher().matches(TYPE_ZIP)) {
            /* 2020-03-09: Such URLs are not resumable */
            maxchunks = 1;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
        }
        link.setProperty(directlinkproperty, dllink);
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    public void login(Browser br, final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setFollowRedirects(true);
                br.setCookiesExclusive(true);
                final boolean cookieLoginOnly = true;
                final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass(), getLogger());
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    br.setCookies(account.getHoster(), cookies);
                    prepAjaxBr(br);
                    br.postPage("https://www." + this.getHost() + "/content.php?action=get_settings", "");
                    if (isLoggedIn(br)) {
                        /* Save new cookie timestamp */
                        logger.info("Cookie login successful");
                        account.saveCookies(br.getCookies(account.getHoster()), "");
                        return;
                    }
                    br.clearAll();
                }
                if (userCookies != null) {
                    logger.info("Attempting user cookie login");
                    br.setCookies(userCookies);
                    br.postPage("https://www." + this.getHost() + "/content.php?action=get_settings", "");
                    if (isLoggedIn(br)) {
                        /* Save new cookie timestamp */
                        logger.info("User Cookie login successful");
                        account.saveCookies(br.getCookies(account.getHoster()), "");
                        return;
                    } else {
                        logger.info("User Cookie login failed");
                        showCookieLoginInformation();
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                logger.info("Performing full login");
                if (cookieLoginOnly) {
                    showCookieLoginInformation();
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "Cookie login required", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                final DownloadLink dlinkbefore = this.getDownloadLink();
                if (dlinkbefore == null) {
                    this.setDownloadLink(new DownloadLink(this, "Account", this.getHost(), "http://" + account.getHoster(), true));
                }
                br.getPage("https://www." + this.getHost());
                if (br.containsHTML("hcaptcha\\.com/") || br.containsHTML("class=\"h-captcha\"")) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "Unsupported captcha type 'hcaptcha', use cookie login or read: board.jdownloader.org/showthread.php?t=83712", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                String reCaptchaKey;
                /*
                 * 2019-06-06: Use static key because website contains two keys - one for registration and one for login! Using the wrong
                 * key will result in login failure!
                 */
                final boolean useStaticReCaptchaKey = true;
                if (useStaticReCaptchaKey) {
                    /* 2018-10-30 */
                    reCaptchaKey = "6LdNI3MUAAAAAKcY5lKxRTMxg4xFWHEJWzSNJGdE";
                } else {
                    reCaptchaKey = br.getRegex("data\\-sitekey=\"([^<>\"]+)\"").getMatch(0);
                    if (reCaptchaKey == null) {
                        /* 2018-10-30 */
                        reCaptchaKey = "6LdNI3MUAAAAAKcY5lKxRTMxg4xFWHEJWzSNJGdE";
                    }
                }
                final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br, reCaptchaKey).getToken();
                if (dlinkbefore != null) {
                    this.setDownloadLink(dlinkbefore);
                }
                String postData = "username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()) + "&rememberme=on";
                postData += "&g-recaptcha-response=" + Encoding.urlEncode(recaptchaV2Response);
                postData += "&recaptcha-version=3";
                prepAjaxBr(br);
                br.postPageRaw("https://www.seedr.cc/actions.php?action=login", postData);
                final String error = PluginJSonUtils.getJson(br, "error");
                if (!StringUtils.isEmpty(error)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.postPage("https://www." + br.getHost() + "/content.php?action=get_devices", "");
                if (!isLoggedIn(br)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(br.getCookies(account.getHoster()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
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
        return br.getCookie(br.getHost(), "remember", Cookies.NOTDELETEDPATTERN) != null;
    }

    public static void prepAjaxBr(final Browser br) {
        br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(this.br, account, true);
        /*
         * Correct username - e.g. when logging in via cookies, users can enter whatever they want into the username field but we want the
         * account to be unique!
         */
        final String email = PluginJSonUtils.getJson(br, "username");
        if (!StringUtils.isEmpty(email)) {
            account.setUser(email);
        }
        ai.setUnlimitedTraffic();
        prepAjaxBr(this.br);
        this.br.postPage("https://www." + this.getHost() + "/content.php?action=get_memory_bandwidth", "");
        final String is_premium = PluginJSonUtils.getJsonValue(this.br, "is_premium");
        final String bandwidth_used = PluginJSonUtils.getJsonValue(this.br, "bandwidth_used");
        final String bandwidth_max = PluginJSonUtils.getJsonValue(this.br, "bandwidth_max");
        final String space_used = PluginJSonUtils.getJsonValue(this.br, "space_used");
        if (space_used != null && space_used.matches("\\d+")) {
            ai.setUsedSpace(Long.parseLong(space_used));
        }
        if (is_premium == null || !is_premium.equals("1")) {
            account.setType(AccountType.FREE);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            ai.setStatus("Registered (free) user");
        } else {
            final String expire = br.getRegex("").getMatch(0);
            if (expire == null) {
                if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername/Passwort oder nicht unterstützter Account Typ!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password or unsupported account type!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
            } else {
                ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "dd MMMM yyyy", Locale.ENGLISH));
            }
            if (bandwidth_used != null && bandwidth_max != null && bandwidth_used.matches("\\d+") && bandwidth_max.matches("\\d+")) {
                ai.setTrafficMax(Long.parseLong(bandwidth_max));
                ai.setTrafficLeft(ai.getTrafficMax() - Long.parseLong(bandwidth_used));
            }
            account.setType(AccountType.PREMIUM);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            ai.setStatus("Premium account");
        }
        account.setConcurrentUsePossible(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        /* Login is done inside linkcheck */
        // login(this.br, account, false);
        requestFileInformation(link, account, true);
        if (account.getType() == AccountType.FREE) {
            doFree(link, ACCOUNT_FREE_RESUME, ACCOUNT_FREE_MAXCHUNKS, "account_free_directlink");
        } else {
            // String dllink = this.checkDirectLink(link, "premium_directlink");
            // if (dllink == null) {
            // dllink = br.getRegex("").getMatch(0);
            // if (dllink == null) {
            // logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
            // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            // }
            // }
            doFree(link, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS, "premium_directlink");
        }
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_PREMIUM_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}