//jDownloader - Downloadmanager
//Copyright (C) 2017  JD-Team support@jdownloader.org
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.parser.UrlQuery;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class RecurbateCom extends antiDDoSForHost {
    public RecurbateCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://recurbate.com/signup");
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.XXX };
    }

    /* DEV NOTES */
    // Tags: Porn plugin
    // other:
    /* Connection stuff */
    /* Global limits */
    private static final boolean resume               = true;
    private static final int     free_maxchunks       = -2;
    /* Free (+ free account) and premium specific limits */
    private static final int     free_maxdownloads    = 1;
    private static final int     premium_maxdownloads = 10;
    private static final String  PROPERTY_USER        = "username";

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "recurbate.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/play\\.php\\?video=(\\d+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public String getAGBLink() {
        return "https://recurbate.com/terms";
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        if (!link.isNameSet()) {
            link.setName(this.getFID(link) + ".mp4");
        }
        br.setFollowRedirects(true);
        getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String performer = br.getRegex("/performer/([^/\"<>]+)").getMatch(0);
        if (performer != null) {
            performer = Encoding.htmlDecode(performer).trim();
            link.setProperty(PROPERTY_USER, performer);
            link.setFinalFileName(performer + "_" + this.getFID(link) + ".mp4");
        } else {
            /* Fallback */
            link.setFinalFileName(this.getFID(link) + ".mp4");
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        handleDownload(link, null);
    }

    public void handleDownload(final DownloadLink link, final Account account) throws Exception {
        final String directurlproperty;
        if (account != null) {
            directurlproperty = "directlink_account";
        } else {
            directurlproperty = "directlink";
        }
        if (!this.attemptStoredDownloadurlDownload(link, directurlproperty, resume, free_maxchunks)) {
            requestFileInformation(link);
            checkErrors(br, account);
            if (account != null && !this.isLoggedin(br)) {
                throw new AccountUnavailableException("Session expired?", 30 * 1000l);
            }
            final String token = br.getRegex("data-token=\"([a-f0-9]{64})\"").getMatch(0);
            if (token == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.setCookie(br.getHost(), "im18", "true");
            br.setCookie(br.getHost(), "im18_ets", Long.toString(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000));
            br.setCookie(br.getHost(), "im18_its", Long.toString(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000));
            final Browser brc = br.cloneBrowser();
            brc.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            brc.getHeaders().put("Accept", "*/*");
            final UrlQuery query = new UrlQuery();
            query.add("video", this.getFID(link));
            query.add("token", token);
            brc.getPage("/api/get.php?" + query.toString());
            if (brc.toString().equalsIgnoreCase("shall_signin")) {
                /**
                 * Free users can watch one video per IP per X time. </br>
                 * This error should only happen in logged-out state.
                 */
                errorDailyDownloadlimitReached(account);
            } else if (brc.toString().equalsIgnoreCase("shall_subscribe")) {
                errorDailyDownloadlimitReached(account);
            }
            final String dllink = brc.getRegex("<source src=\"(https?://[^\"]+)\"[^>]*type=\"video/mp4\" />").getMatch(0);
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, free_maxchunks);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                if (dl.getConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 429) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 429 too many requests", 60 * 60 * 1000l);
                }
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error");
            }
            /*
             * 2021-09-01: Save to re-use later. This URL is valid for some minutes only but allows resume + chunkload (up to 3 connections
             * - we allow max. 2.).
             */
            link.setProperty(directurlproperty, dl.getConnection().getURL().toString());
        }
        dl.startDownload();
    }

    private void errorDailyDownloadlimitReached(final Account account) throws PluginException {
        if (account != null) {
            throw new AccountUnavailableException("Daily downloadlimit reached", 60 * 60 * 1000l);
        } else {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Daily downloadlimit reached", 10 * 60 * 1000l);
        }
    }

    private boolean attemptStoredDownloadurlDownload(final DownloadLink link, final String directlinkproperty, final boolean resumable, final int maxchunks) throws Exception {
        final String url = link.getStringProperty(directlinkproperty);
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        try {
            final Browser brc = br.cloneBrowser();
            dl = new jd.plugins.BrowserAdapter().openDownload(brc, link, url, resumable, maxchunks);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                return true;
            } else {
                brc.followConnection(true);
                throw new IOException();
            }
        } catch (final Throwable e) {
            link.removeProperty(directlinkproperty);
            logger.log(e);
            try {
                dl.getConnection().disconnect();
            } catch (Throwable ignore) {
            }
            return false;
        }
    }

    private boolean login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setFollowRedirects(true);
                br.setCookiesExclusive(true);
                final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass(), getLogger());
                if (userCookies == null) {
                    /**
                     * 2021-09-28: They're using Cloudflare on their login page thus we only accept cookie login at this moment.</br>
                     * Login page: https://recurbate.com/signin
                     */
                    /* Only display cookie login instructions on first login attempt */
                    if (!account.hasEverBeenValid()) {
                        showCookieLoginInformation();
                    }
                    throw new AccountInvalidException("Cookie login required");
                }
                logger.info("Attempting user cookie login");
                this.br.setCookies(this.getHost(), userCookies);
                if (!force) {
                    /* Do not validate cookies */
                    return false;
                }
                br.getPage("https://" + this.getHost() + "/account.php");
                if (this.isLoggedin(br)) {
                    logger.info("User cookie login successful");
                    return true;
                } else {
                    logger.info("Cookie login failed");
                    if (account.hasEverBeenValid()) {
                        throw new AccountInvalidException("Login cookies expired");
                    } else {
                        throw new AccountInvalidException("Login cookies invalid");
                    }
                }
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedin(final Browser br) {
        if (br.containsHTML("/signout\"")) {
            return true;
        } else {
            return false;
        }
    }

    private Thread showCookieLoginInformation() {
        final String host = this.getHost();
        final Thread thread = new Thread() {
            public void run() {
                try {
                    final String help_article_url = "https://support.jdownloader.org/Knowledgebase/Article/View/account-cookie-login-instructions";
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = host + " - Login";
                        message += "Hallo liebe(r) " + host + " NutzerIn\r\n";
                        message += "Um deinen " + host + " Account in JDownloader verwenden zu können, musst du folgende Schritte beachten:\r\n";
                        message += "Folge der Anleitung im Hilfe-Artikel:\r\n";
                        message += help_article_url;
                    } else {
                        title = host + " - Login";
                        message += "Hello dear " + host + " user\r\n";
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

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        ai.setUnlimitedTraffic();
        final String nickname = br.getRegex("(?i)Nickname\\s*</div>\\s*<div class=\"col-sm-8\">\\s*([^<>\"]+)").getMatch(0);
        if (nickname != null) {
            /* User can theoretically enter whatever he wants in username field when doing cookie login --> We prefer unique usernames. */
            account.setUser(nickname);
        } else {
            logger.warning("Failed to find nickname in HTML");
        }
        final String plan = br.getRegex("<span class=\"plan-name\"[^>]*>([^<>\"<>]*)</span>").getMatch(0);
        if (StringUtils.equalsIgnoreCase(plan, "Premium")) {
            final String expire = br.getRegex("(?i)Expire on\\s*</div>\\s*<div class=\"col-sm-8\">\\s*([A-Za-z]+ \\d{2}, \\d{4})").getMatch(0);
            if (expire != null) {
                /* Allow premium accounts without expire-date although all premium accounts should have an expire-date. */
                ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "MMM dd, yyyy", Locale.ENGLISH));
            } else {
                logger.warning("Failed to find expire-date for premium account!");
            }
            account.setType(AccountType.PREMIUM);
            account.setConcurrentUsePossible(true);
            account.setMaxSimultanDownloads(premium_maxdownloads);
            ai.setStatus(plan);
        } else {
            account.setType(AccountType.FREE);
            account.setMaxSimultanDownloads(free_maxdownloads);
        }
        checkErrors(br, account);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        login(account, false);
        handleDownload(link, account);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        /* 2021-09-28: Tested with up to 18 items but got error 429 quite frequently then --> 10 should be fine. */
        return premium_maxdownloads;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return free_maxdownloads;
    }

    private void checkErrors(final Browser br, final Account account) throws AccountUnavailableException {
        /* 2021-10-11: Very interesting: While this is happening, users will still get 1 free view without account. */
        if ((account == null || account.getType() == AccountType.FREE) && br.containsHTML("(?i)Sorry guys, but due to the high load.*Basic \\(Free\\).*accounts are temporary limited to")) {
            throw new AccountUnavailableException("Free accounts are temporarily limited to 0 video views", 5 * 60 * 1000);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}